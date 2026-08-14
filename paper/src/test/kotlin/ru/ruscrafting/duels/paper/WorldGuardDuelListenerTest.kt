package ru.ruscrafting.duels.paper

import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
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
