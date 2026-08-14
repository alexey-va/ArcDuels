package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import java.util.UUID

class PlayerDataSyncGateTest : StringSpec({
    "players are immediately ready when no external synchronization is required" {
        val playerId = UUID.randomUUID()
        val player = mockk<Player> { every { uniqueId } returns playerId }
        val gate = PlayerDataSyncGate(explicitSyncRequired = false)

        gate.joined(playerId)

        gate.isReady(player) shouldBe true
    }

    "required synchronization blocks every new connection until completion" {
        val playerId = UUID.randomUUID()
        val player = mockk<Player> { every { uniqueId } returns playerId }
        val gate = PlayerDataSyncGate(explicitSyncRequired = true)

        gate.joined(playerId)
        gate.isReady(player) shouldBe false

        gate.synchronized(playerId)
        gate.isReady(player) shouldBe true

        gate.left(playerId)
        gate.isReady(player) shouldBe false

        gate.synchronized(playerId)
        gate.joined(playerId)
        gate.isReady(player) shouldBe false
    }
})
