package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerMoveEvent
import net.kyori.adventure.text.Component
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
    "countdown movement is anchored to the arena spawn instead of a stale client position" {
        val player = mockk<Player>(relaxed = true)
        val playerId = PlayerId(UUID.randomUUID())
        every { player.uniqueId } returns playerId.value
        val world = mockk<World>(relaxed = true)
        val anchor = Location(world, 10.5, 70.0, -4.5)
        val from = Location(world, 120.0, 70.0, 120.0)
        val attempted = Location(world, 121.0, 70.0, 120.0, 90f, 10f)
        val match =
            DuelMatch.reserve(
                playerId,
                PlayerId(UUID.randomUUID()),
                ArenaId("stabilized"),
                ServerId("test"),
                DuelRules(DuelMode.OWN_INVENTORY),
                Instant.EPOCH,
            ).beginCountdown()
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.matchFor(player) } returns match
        every { sessions.countdownAnchor(player) } returns anchor
        val event = mockk<PlayerMoveEvent>(relaxed = true)
        every { event.player } returns player
        every { event.from } returns from
        every { event.to } returns attempted

        DuelGameplayListener(sessions, mockk(relaxed = true)).onMove(event)

        verify(exactly = 1) {
            event.to = match { it.x == anchor.x && it.y == anchor.y && it.z == anchor.z && it.yaw == attempted.yaw }
        }
    }

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

    "accepted duel damage clears external combat tags for both participants" {
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
                ArenaId("classic"),
                ServerId("test"),
                DuelRules(DuelMode.OWN_INVENTORY),
                Instant.EPOCH,
            ).beginCountdown().activate(Instant.EPOCH)
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.matchFor(victim) } returns match
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { event.entity } returns victim
        every { event.damager } returns attacker
        val listener = DuelGameplayListener(sessions, mockk(relaxed = true))

        listener.onAcceptedDamageTrace(event)

        verify(exactly = 1) { sessions.clearExternalCombatTags(match, "damage-accepted") }
    }

    "post-match waiting players cannot damage each other and have CMI tags cleared" {
        val attacker = mockk<Player>(relaxed = true)
        val victim = mockk<Player>(relaxed = true)
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.isPostMatchWaiting(victim) } returns true
        every { sessions.isPostMatchWaiting(attacker) } returns true
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
        every { event.entity } returns victim
        every { event.damager } returns attacker
        val listener = DuelGameplayListener(sessions, mockk(relaxed = true))

        listener.onPlayerDamage(event)

        verify(exactly = 1) { event.isCancelled = true }
        verify(exactly = 1) { sessions.clearPostMatchCombatTag(victim, "post-match-damage-blocked") }
        verify(exactly = 1) { sessions.clearPostMatchCombatTag(attacker, "post-match-damage-blocked") }
    }

    "post-match waiting players are protected from environmental damage" {
        val player = mockk<Player>(relaxed = true)
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.isPostMatchWaiting(player) } returns true
        val event = mockk<EntityDamageEvent>(relaxed = true)
        every { event.entity } returns player
        val listener = DuelGameplayListener(sessions, mockk(relaxed = true))

        listener.onLethalDamage(event)

        verify(exactly = 1) { event.isCancelled = true }
        verify(exactly = 1) { sessions.clearPostMatchCombatTag(player, "post-match-damage-blocked") }
    }

    "command bypass permission skips duel command restrictions while state is locked" {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arcduels.bypass") } returns true
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.isStateLocked(player) } returns true
        val event = mockk<PlayerCommandPreprocessEvent>(relaxed = true)
        every { event.player } returns player
        every { event.message } returns "/spawn"
        val listener = DuelGameplayListener(sessions, mockk(relaxed = true))

        listener.onCommandEarly(event)
        listener.onCommandFinal(event)

        verify(exactly = 0) { event.isCancelled = true }
    }

    "ordinary commands stay blocked without the bypass permission while state is locked" {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arcduels.bypass") } returns false
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.isStateLocked(player) } returns true
        val locales = mockk<LocaleService>()
        every { locales.notice(player, "session.command-blocked") } returns Component.empty()
        val event = mockk<PlayerCommandPreprocessEvent>(relaxed = true)
        every { event.player } returns player
        every { event.message } returns "/spawn"
        val listener = DuelGameplayListener(sessions, locales)

        listener.onCommandEarly(event)

        verify(exactly = 1) { event.isCancelled = true }
    }

    "final command guard cancels a blocked command again after another plugin uncancels it" {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arcduels.bypass") } returns false
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.isStateLocked(player) } returns true
        val event = mockk<PlayerCommandPreprocessEvent>(relaxed = true)
        every { event.player } returns player
        every { event.message } returns "/spawn"
        val listener = DuelGameplayListener(sessions, mockk(relaxed = true))

        listener.onCommandFinal(event)

        verify(exactly = 1) { event.isCancelled = true }
    }

    "duel leave remains available without command bypass while state is locked" {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arcduels.bypass") } returns false
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.isStateLocked(player) } returns true
        val event = mockk<PlayerCommandPreprocessEvent>(relaxed = true)
        every { event.player } returns player
        every { event.message } returns "/duel leave"
        val listener = DuelGameplayListener(sessions, mockk(relaxed = true))

        listener.onCommandEarly(event)
        listener.onCommandFinal(event)

        verify(exactly = 0) { event.isCancelled = true }
    }
})
