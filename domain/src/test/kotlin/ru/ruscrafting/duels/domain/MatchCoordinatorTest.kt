package ru.ruscrafting.duels.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

class MatchCoordinatorTest : StringSpec({
    val first = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val second = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    val events = CopyOnWriteArrayList<DuelEvent>()
    val releases = AtomicInteger()
    val statistics = InMemoryStatisticsRepository()
    val coordinator =
        MatchCoordinator(
            serverId = ServerId("duels-1"),
            arenaAllocator = ArenaAllocator {
                CompletableFuture.completedFuture(ArenaReservation(ArenaId("arena-1"), releases::incrementAndGet))
            },
            statistics = statistics,
            eventPublisher = DuelEventPublisher { event ->
                events += event
                CompletableFuture.completedFuture(Unit)
            },
            clock = Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC),
        )

    "completion persists once, releases arena and publishes network events" {
        val match =
            coordinator.reserve(first, second, DuelRules(DuelMode.KIT, KitId("classic"), ranked = true)).get()
        coordinator.beginCountdown(match.id)
        coordinator.activate(match.id)
        val completed = coordinator.recordRoundWinner(match.id, first).get()

        completed.state shouldBe MatchState.COMPLETED
        releases.get() shouldBe 1
        statistics.find(first).get().wins shouldBe 1
        statistics.find(first).get().rating shouldBe 1_016
        statistics.find(second).get().losses shouldBe 1
        events shouldHaveSize 2
        coordinator.findByPlayer(first) shouldBe null
    }

    "duplicate result recording is idempotent" {
        val outcome =
            MatchOutcome(
                MatchId.random(),
                first,
                second,
                DuelMode.KIT,
                KitId("classic"),
                ranked = true,
                ServerId("duels-1"),
                Instant.parse("2026-08-13T10:00:00Z"),
            )

        statistics.record(outcome).get().newlyRecorded shouldBe true
        statistics.record(outcome).get().newlyRecorded shouldBe false
    }

    "failed durable write keeps the match completing and arena reserved for retry" {
        val releasesOnFailure = AtomicInteger()
        val failingRepository =
            object : StatisticsRepository {
                override fun find(playerId: PlayerId) = CompletableFuture.completedFuture(PlayerStatistics(playerId))

                override fun record(outcome: MatchOutcome) =
                    CompletableFuture.failedFuture<PersistedMatchResult>(IllegalStateException("database unavailable"))

                override fun leaderboard(limit: Int) = CompletableFuture.completedFuture(emptyList<LeaderboardEntry>())
            }
        val failClosed =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator {
                    CompletableFuture.completedFuture(
                        ArenaReservation(ArenaId("arena-2"), releasesOnFailure::incrementAndGet),
                    )
                },
                failingRepository,
                clock = Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC),
            )
        val match = failClosed.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY)).get()
        failClosed.beginCountdown(match.id)
        failClosed.activate(match.id)

        shouldThrow<ExecutionException> { failClosed.recordRoundWinner(match.id, first).get() }

        failClosed.find(match.id)?.state shouldBe MatchState.COMPLETING
        releasesOnFailure.get() shouldBe 0
    }
})
