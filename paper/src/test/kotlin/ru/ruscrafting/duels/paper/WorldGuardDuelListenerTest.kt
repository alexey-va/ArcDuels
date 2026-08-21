package ru.ruscrafting.duels.paper

import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

class WorldGuardDuelListenerTest : StringSpec({
    "WorldGuard bucket denial is overridden only for a tracked active-arena fluid source" {
        val player = mockk<Player>()
        val block = mockk<Block>()
        val sessions = mockk<DuelSessionManager>()
        every { sessions.allowsFluidPlacement(player, block, Material.LAVA_BUCKET) } returns true
        every { sessions.trackFluidPlacement(player, block) } returns true
        val event = mockk<PlayerBucketEmptyEvent>(relaxed = true)
        every { event.player } returns player
        every { event.block } returns block
        every { event.bucket } returns Material.LAVA_BUCKET
        every { event.isCancelled } returns true

        DuelFluidListener(sessions, overrideWorldGuard = true).onBucketEmpty(event)

        verify(exactly = 1) { event.isCancelled = false }
        verify(exactly = 1) { sessions.trackFluidPlacement(player, block) }
    }

    "tracked duel fluid cannot flow beyond its arena bounds" {
        val source = mockk<Block>()
        val destination = mockk<Block>()
        val sessions = mockk<DuelSessionManager>()
        every { sessions.trackFluidFlow(source, destination) } returns false
        val event = mockk<BlockFromToEvent>(relaxed = true)
        every { event.block } returns source
        every { event.toBlock } returns destination

        DuelFluidListener(sessions, overrideWorldGuard = true).onFluidFlow(event)

        verify(exactly = 1) { event.isCancelled = true }
    }

    "WorldGuard denial is overridden only for participants of the same active arena match" {
        val attacker = mockk<Player>()
        val defender = mockk<Player>()
        val attackerId = PlayerId(UUID.randomUUID())
        val defenderId = PlayerId(UUID.randomUUID())
        val attackerLocation = mockk<Location>()
        val defenderLocation = mockk<Location>()
        every { attacker.uniqueId } returns attackerId.value
        every { defender.uniqueId } returns defenderId.value
        every { attacker.location } returns attackerLocation
        every { defender.location } returns defenderLocation
        val match =
            DuelMatch.reserve(
                attackerId,
                defenderId,
                ArenaId("arena"),
                ServerId("test"),
                DuelRules(DuelMode.OWN_INVENTORY),
                Instant.EPOCH,
            ).beginCountdown().activate(Instant.EPOCH)
        val sessions = mockk<DuelSessionManager>()
        every { sessions.matchFor(attacker) } returns match
        every { sessions.matchFor(defender) } returns match
        every { sessions.isInsideArena(attacker, attackerLocation) } returns true
        every { sessions.isInsideArena(defender, defenderLocation) } returns true
        val event = DisallowedPVPEvent(attacker, defender, mockk<Event>())

        WorldGuardDuelListener(sessions, Logger.getAnonymousLogger()).onDisallowedPvp(event)

        event.isCancelled shouldBe true
    }

    "WorldGuard denial remains in force outside the configured arena bounds" {
        val attacker = mockk<Player>()
        val defender = mockk<Player>()
        val attackerId = PlayerId(UUID.randomUUID())
        val defenderId = PlayerId(UUID.randomUUID())
        val attackerLocation = mockk<Location>()
        val defenderLocation = mockk<Location>()
        every { attacker.uniqueId } returns attackerId.value
        every { defender.uniqueId } returns defenderId.value
        every { attacker.location } returns attackerLocation
        every { defender.location } returns defenderLocation
        val match =
            DuelMatch.reserve(
                attackerId,
                defenderId,
                ArenaId("arena"),
                ServerId("test"),
                DuelRules(DuelMode.OWN_INVENTORY),
                Instant.EPOCH,
            ).beginCountdown().activate(Instant.EPOCH)
        val sessions = mockk<DuelSessionManager>()
        every { sessions.matchFor(attacker) } returns match
        every { sessions.matchFor(defender) } returns match
        every { sessions.isInsideArena(attacker, attackerLocation) } returns true
        every { sessions.isInsideArena(defender, defenderLocation) } returns false
        val event = DisallowedPVPEvent(attacker, defender, mockk<Event>())

        WorldGuardDuelListener(sessions, Logger.getAnonymousLogger()).onDisallowedPvp(event)

        event.isCancelled shouldBe false
    }
})
