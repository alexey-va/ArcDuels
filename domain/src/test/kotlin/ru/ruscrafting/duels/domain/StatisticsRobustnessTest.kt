package ru.ruscrafting.duels.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class StatisticsRobustnessTest : StringSpec({
    val first = PlayerId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
    val second = PlayerId(UUID.fromString("20000000-0000-0000-0000-000000000002"))

    fun outcome(
        matchId: UUID = UUID.randomUUID(),
        winner: PlayerId = first,
        loser: PlayerId = second,
        ranked: Boolean = false,
        completedAt: Instant = Instant.parse("2026-08-13T10:00:00Z"),
    ) = MatchOutcome(
        matchId = MatchId(matchId),
        winner = winner,
        loser = loser,
        mode = if (ranked) DuelMode.KIT else DuelMode.OWN_INVENTORY,
        kitId = if (ranked) KitId("classic") else null,
        ranked = ranked,
        serverId = ServerId("duels-test"),
        completedAt = completedAt,
    )

    "match outcomes reject corrupt participant and mode combinations" {
        shouldThrow<IllegalArgumentException> { outcome(winner = first, loser = first) }
        shouldThrow<IllegalArgumentException> {
            MatchOutcome(
                MatchId.random(),
                first,
                second,
                DuelMode.KIT,
                null,
                ranked = false,
                ServerId("duels-test"),
                Instant.parse("2026-08-13T10:00:00Z"),
            )
        }
        shouldThrow<IllegalArgumentException> {
            MatchOutcome(
                MatchId.random(),
                first,
                second,
                DuelMode.OWN_INVENTORY,
                null,
                ranked = true,
                ServerId("duels-test"),
                Instant.parse("2026-08-13T10:00:00Z"),
            )
        }
    }

    "sub-millisecond timestamps have one canonical idempotency identity" {
        val repository = InMemoryStatisticsRepository()
        val source = outcome(completedAt = Instant.parse("2026-08-13T10:00:00.123456789Z"))

        val firstWrite = repository.record(source).get()
        val duplicate = repository.record(source.canonicalized()).get()

        firstWrite.outcome.completedAt shouldBe Instant.parse("2026-08-13T10:00:00.123Z")
        duplicate shouldBe firstWrite.copy(newlyRecorded = false)
        repository.find(first).get().wins shouldBeExactly 1L
    }

    "many concurrent duplicate writes count exactly one match" {
        val repository = InMemoryStatisticsRepository()
        val source = outcome(ranked = true)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val writes =
                List(64) {
                    CompletableFuture.supplyAsync({ repository.record(source).join() }, executor)
                }.map(CompletableFuture<PersistedMatchResult>::join)

            writes.count(PersistedMatchResult::newlyRecorded) shouldBe 1
            writes.map(PersistedMatchResult::leaderboardRevision).distinct() shouldContainExactly listOf(1L)
            repository.find(first).get().wins shouldBeExactly 1L
            repository.find(second).get().losses shouldBeExactly 1L
        } finally {
            executor.shutdownNow()
        }
    }

    "five hundred results preserve counts streaks revisions and stable ranking" {
        val repository = InMemoryStatisticsRepository()
        repository.rememberPlayerName(first, "First").get()
        repository.rememberPlayerName(second, "Second").get()

        repeat(500) { index ->
            val firstWins = index % 2 == 0
            repository.record(
                outcome(
                    winner = if (firstWins) first else second,
                    loser = if (firstWins) second else first,
                    completedAt = Instant.parse("2026-08-13T10:00:00Z").plusMillis(index.toLong()),
                ),
            ).get()
        }

        val firstStats = repository.find(first).get()
        val secondStats = repository.find(second).get()
        firstStats shouldBe PlayerStatistics(first, wins = 250, losses = 250, bestWinStreak = 1, revision = 500)
        secondStats shouldBe PlayerStatistics(second, wins = 250, losses = 250, currentWinStreak = 1, bestWinStreak = 1, revision = 500)
        repository.leaderboard(2).get().map(LeaderboardEntry::playerName) shouldContainExactly listOf("First", "Second")
    }

    "rating calculation stays bounded and monotonic across a wide input matrix" {
        val ratings = listOf(0, 1, 100, 1_000, 50_000, RatingCalculator.MAX_RATING)
        for (winnerBefore in ratings) {
            for (loserBefore in ratings) {
                val (winnerAfter, loserAfter) = RatingCalculator.afterWin(winnerBefore, loserBefore)
                winnerAfter shouldBeGreaterThanOrEqual winnerBefore
                winnerAfter shouldBeLessThanOrEqual RatingCalculator.MAX_RATING
                loserAfter shouldBeLessThanOrEqual loserBefore
                loserAfter shouldBeGreaterThanOrEqual 0
            }
        }
    }

    "statistics and network events reject impossible values at construction" {
        shouldThrow<IllegalArgumentException> { PlayerStatistics(first, wins = -1) }
        shouldThrow<IllegalArgumentException> { PlayerStatistics(first, currentWinStreak = 2, bestWinStreak = 1) }
        shouldThrow<IllegalArgumentException> {
            LeaderboardInvalidatedEvent("bad event id!", Instant.EPOCH, ServerId("duels-test"), 1)
        }
        shouldThrow<IllegalArgumentException> {
            LeaderboardInvalidatedEvent("valid:event", Instant.EPOCH, ServerId("duels-test"), -1)
        }
    }
})
