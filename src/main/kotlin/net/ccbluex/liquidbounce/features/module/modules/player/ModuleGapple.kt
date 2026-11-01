/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.entity.useItem
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items

/**
 * Gapple module
 *
 * Eats a golden apple while attacking. The outgoing movement packets are buffered while the apple is
 * being eaten, so the server keeps seeing the player as moving, and the release packet of the item
 * use is dropped, so the server side eating timer keeps running.
 *
 * Ported from MoonLight's AutoGap/AutoGap2, which did the same thing with the 1.8 packets.
 */
object ModuleGapple : ClientModule("Gapple", ModuleCategories.PLAYER, aliases = listOf("AutoGapple", "GapApple")) {

    private val health by float("Health", 15f, 0f..20f, "hp")
    private val whileAttacking by boolean("WhileAttacking", true)
    private val delay by int("Delay", 75, 0..300, "ticks")
    private val releasePerTick by int("ReleasePerTick", 4, 1..32, "packets")
    private val instantEat by boolean("InstantEat", true)
    private val cancelRelease by boolean("CancelRelease", true)
    private val enchanted by boolean("Enchanted", true)
    private val stopMovement by boolean("StopMovement", true)

    private enum class Phase {
        IDLE,
        EATING
    }

    /**
     * Ticks the server needs to consume the apple.
     */
    private const val EAT_DURATION = 32

    private var phase = Phase.IDLE
    private var eatTicks = 0
    private var lastEatTick = 0
    private var previousSlot = 0
    private var slotChanged = false
    private var usedOffhand = false
    private val queuedMoves = ArrayDeque<Packet<*>>()

    override fun onDisabled() {
        phase = Phase.IDLE
        eatTicks = 0
        queuedMoves.clear()
        restoreSlot()
    }

    @Suppress("unused")
    private val tickTask = tickHandler {
        when (phase) {
            Phase.IDLE -> handleIdle()
            Phase.EATING -> handleEating()
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin != TransferOrigin.OUTGOING || phase != Phase.EATING) {
            return@handler
        }

        val packet = event.packet

        // The server side eating timer must not be interrupted, so the release packet is dropped.
        // The class is matched by name, because its exact name differs between game versions.
        if (cancelRelease && packet.javaClass.simpleName.contains("ReleaseUseItem")) {
            event.cancelEvent()
            return@handler
        }

        // Movement is buffered, so the server keeps seeing the player as running while eating.
        // Rotation packets are not buffered, so the aim of the KillAura keeps working.
        if (packet is ServerboundMovePlayerPacket && packet.hasPosition()) {
            queuedMoves.addLast(packet)
            event.cancelEvent()
        }
    }

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent> { event ->
        if (stopMovement && phase == Phase.EATING) {
            event.directionalInput = DirectionalInput.NONE
        }
    }

    private fun handleIdle() {
        if (!shouldEat()) {
            return
        }

        val slot = findApple() ?: return
        val hand = if (slot == HotbarItemSlot.OFFHAND) InteractionHand.OFF_HAND else InteractionHand.MAIN_HAND

        previousSlot = player.inventory.realSelectedSlot
        usedOffhand = hand == InteractionHand.OFF_HAND
        slotChanged = false

        if (!usedOffhand) {
            val index = slot.hotbarIndex ?: return
            network.send(ServerboundSetCarriedItemPacket(index))
            slotChanged = true
        }

        if (useItem(hand) !is InteractionResult.Success) {
            restoreSlot()
            return
        }

        phase = Phase.EATING
        eatTicks = 0
        lastEatTick = player.tickCount

        if (instantEat) {
            // The client stops using the item right away, the server keeps counting on its own.
            player.releaseUsingItem()
        }
    }

    private fun handleEating() {
        eatTicks++

        if (!instantEat && !player.isUsingItem && eatTicks < EAT_DURATION) {
            // The eat was interrupted, for example by taking damage.
            finishEating()
            return
        }

        releaseQueuedMoves()

        if (eatTicks >= EAT_DURATION) {
            finishEating()
        }
    }

    private fun finishEating() {
        phase = Phase.IDLE
        eatTicks = 0
        restoreSlot()
        flushQueuedMoves()
    }

    private fun shouldEat(): Boolean {
        if (player.tickCount - lastEatTick < delay) {
            return false
        }

        if (player.health > health && !(whileAttacking && inCombat())) {
            return false
        }

        return true
    }

    private fun inCombat(): Boolean =
        CombatManager.isInCombat || ModuleKillAura.targetTracker.target != null

    private fun findApple(): HotbarItemSlot? = Slots.OffhandWithHotbar.firstOrNull { slot ->
        val item = slot.itemStack.item
        item == Items.GOLDEN_APPLE || (enchanted && item == Items.ENCHANTED_GOLDEN_APPLE)
    }

    private fun restoreSlot() {
        if (!slotChanged) {
            return
        }

        slotChanged = false
        network.send(ServerboundSetCarriedItemPacket(previousSlot))
    }

    private fun releaseQueuedMoves() {
        repeat(releasePerTick) {
            val packet = queuedMoves.removeFirstOrNull() ?: return@repeat
            network.send(packet)
        }
    }

    private fun flushQueuedMoves() {
        while (true) {
            val packet = queuedMoves.removeFirstOrNull() ?: break
            network.send(packet)
        }
    }
}
