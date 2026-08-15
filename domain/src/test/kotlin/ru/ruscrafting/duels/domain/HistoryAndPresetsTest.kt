package ru.ruscrafting.duels.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class HistoryAndPresetsTest : StringSpec({
    "history keeps complete replayable rules, perspective and head to head" {
        val repository = InMemoryStatisticsRepository()
        val first = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000101"))
        val second = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000102"))
        repository.rememberPlayerName(first, "First").get()
        repository.rememberPlayerName(second, "Second").get()
        val older =
            MatchOutcome(
                MatchId(UUID.fromString("00000000-0000-0000-0000-000000000103")),
                first,
                second,
                DuelMode.KIT,
                KitId("boxing"),
                ranked = true,
                ServerId("spawn"),
                Instant.parse("2026-08-15T10:00:00Z"),
                DuelObjectiveType.BOXING,
                ArenaId("kit-test"),
                3,
                CombatModifiers(false, false, false, false, boxingHitsToWin = 50),
                2,
                1,
                MatchEndReason.OBJECTIVE,
            )
        val newer =
            MatchOutcome(
                MatchId(UUID.fromString("00000000-0000-0000-0000-000000000104")),
                second,
                first,
                DuelMode.OWN_INVENTORY,
                null,
                ranked = false,
                ServerId("spawn"),
                Instant.parse("2026-08-15T11:00:00Z"),
                arenaId = ArenaId("snow"),
                endReason = MatchEndReason.FORFEIT,
            )
        repository.record(older).get()
        repository.record(newer).get()

        val history = repository.recentMatches(first, 10).get()
        history.map { it.outcome.matchId } shouldContainExactly listOf(newer.matchId, older.matchId)
        history[0].wonBy(first) shouldBe false
        history[0].scoreFor(first) shouldBe (0 to 1)
        history[1].opponentNameOf(first) shouldBe "Second"
        repository.findMatch(older.matchId).get()?.outcome shouldBe older
        repository.headToHead(first, second).get().let {
            it.firstWins shouldBe 1
            it.secondWins shouldBe 1
            it.matches shouldBe 2
            it.lastCompletedAt shouldBe newer.completedAt
        }
    }

    "five preset slots overwrite and delete without changing the normal setup flow" {
        val repository = InMemoryStatisticsRepository()
        val player = PlayerId(UUID.randomUUID())
        val initial = DuelPreset(player, 1, DuelRules(DuelMode.OWN_INVENTORY), null, Instant.parse("2026-08-15T12:00:00.123456Z"))
        repository.savePreset(initial).get()
        repository.presets(player).get().single().updatedAt shouldBe Instant.parse("2026-08-15T12:00:00.123Z")

        val replacement =
            DuelPreset(
                player,
                1,
                DuelRules(DuelMode.KIT, KitId("classic"), ranked = true, bestOf = 3),
                ArenaSelection(ServerId("spawn"), ArenaId("kit-test")),
                Instant.parse("2026-08-15T12:01:00Z"),
            )
        repository.savePreset(replacement).get()
        repository.presets(player).get() shouldContainExactly listOf(replacement)
        repository.deletePreset(player, 1).get() shouldBe true
        repository.deletePreset(player, 1).get() shouldBe false
        shouldThrow<IllegalArgumentException> {
            DuelPreset(player, MAX_DUEL_PRESETS + 1, DuelRules(DuelMode.OWN_INVENTORY), null, Instant.EPOCH)
        }
    }
})
