package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.CombatModifiers
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.util.UUID

class DuelGameplayListenerTest : StringSpec({
    "sumo suppresses lethal and environmental damage independently of damage-listener ordering" {
        val player = mockk<Player>(relaxed = true)
        val playerId = PlayerId(UUID.randomUUID())
        every { player.uniqueId } returns playerId.value
        val match =
            DuelMatch.reserve(
                playerId,
                PlayerId(UUID.randomUUID()),
                ArenaId("sumo"),
                ServerId("test"),
                DuelRules(DuelMode.KIT, KitId("sumo"), objective = DuelObjectiveType.SUMO),
                Instant.EPOCH,
            ).beginCountdown().activate(Instant.EPOCH)
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.matchFor(player) } returns match
        every { sessions.isSumo(player) } returns true
        val event = mockk<EntityDamageEvent>(relaxed = true)
        every { event.entity } returns player
        val listener = DuelGameplayListener(sessions, mockk(relaxed = true))

        listener.onLethalDamage(event)

        verify(exactly = 1) { event.damage = 0.0 }
        verify(exactly = 0) { sessions.handleElimination(any()) }
    }

    "sumo sudden death wither can end a stalled round" {
        val player = mockk<Player>(relaxed = true)
        val playerId = PlayerId(UUID.randomUUID())
        every { player.uniqueId } returns playerId.value
        every { player.health } returns 5.0
        val match =
            DuelMatch.reserve(
                playerId,
                PlayerId(UUID.randomUUID()),
                ArenaId("sumo"),
                ServerId("test"),
                DuelRules(DuelMode.KIT, KitId("sumo"), objective = DuelObjectiveType.SUMO),
                Instant.EPOCH,
            ).beginCountdown().activate(Instant.EPOCH)
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.matchFor(player) } returns match
        every { sessions.isSumo(player) } returns true
        val event = mockk<EntityDamageEvent>(relaxed = true)
        every { event.entity } returns player
        every { event.cause } returns EntityDamageEvent.DamageCause.WITHER
        every { event.finalDamage } returns 6.0
        val listener = DuelGameplayListener(sessions, mockk(relaxed = true))

        listener.onLethalDamage(event)

        verify(exactly = 1) { event.isCancelled = true }
        verify(exactly = 1) { sessions.handleElimination(player) }
        verify(exactly = 0) { event.damage = 0.0 }
    }

    "boxing accepts only a direct participant melee hit and suppresses health damage" {
        val attacker = mockk<Player>(relaxed = true)
        val victim = mockk<Player>(relaxed = true)
        val attackerId = PlayerId(UUID.randomUUID())
        val victimId = PlayerId(UUID.randomUUID())
        every { attacker.uniqueId } returns attackerId.value
        every { victim.uniqueId } returns victimId.value
        val match =
            DuelMatch.reserve(
                attackerId,
                victimId,
                ArenaId("boxing"),
                ServerId("test"),
                DuelRules(
                    DuelMode.KIT,
                    KitId("boxing"),
                    objective = DuelObjectiveType.BOXING,
                    modifiers = CombatModifiers(false, false, false, false),
                ),
                Instant.EPOCH,
            ).beginCountdown().activate(Instant.EPOCH)
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.matchFor(attacker) } returns match
        every { sessions.matchFor(victim) } returns match
        every { sessions.isHitRace(attacker) } returns true
        every { sessions.isHitRace(victim) } returns true
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { event.entity } returns victim
        every { event.damager } returns attacker
        val listener = DuelGameplayListener(sessions, mockk(relaxed = true))

        listener.onPlayerDamage(event)
        listener.onAcceptedMeleeHit(event)

        verify(exactly = 1) { event.damage = 0.0 }
        verify(exactly = 1) { sessions.recordMeleeHit(attacker, victim) }
    }
})
