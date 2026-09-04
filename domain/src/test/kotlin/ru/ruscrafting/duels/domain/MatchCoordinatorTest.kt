package ru.ruscrafting.duels.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
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

    "completion persists once and holds gameplay resources until platform cleanup" {
        val match =
            coordinator.reserve(first, second, DuelRules(DuelMode.KIT, KitId("classic"), ranked = true)).get()
        coordinator.beginCountdown(match.id)
        coordinator.activate(match.id)
        val completed = coordinator.recordRoundWinner(match.id, first).get()

        completed.state shouldBe MatchState.COMPLETED
        releases.get() shouldBe 0
        statistics.find(first).get().wins shouldBe 1
        statistics.find(first).get().rating shouldBe 1_016
        statistics.find(second).get().losses shouldBe 1
        events shouldHaveSize 2
        coordinator.findByPlayer(first)?.id shouldBe match.id
        coordinator.releaseCompleted(match.id) shouldBe true
        coordinator.releaseCompleted(match.id) shouldBe false
        releases.get() shouldBe 1
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
                override fun rememberPlayerName(
                    playerId: PlayerId,
                    playerName: String,
                ) = CompletableFuture.completedFuture(Unit)

                override fun find(playerId: PlayerId) = CompletableFuture.completedFuture(PlayerStatistics(playerId))

                override fun findPlayerName(playerId: PlayerId) = CompletableFuture.completedFuture<String?>(null)

                override fun record(outcome: MatchOutcome) =
                    CompletableFuture.failedFuture<PersistedMatchResult>(IllegalStateException("database unavailable"))

                override fun leaderboard(limit: Int) = CompletableFuture.completedFuture(emptyList<LeaderboardEntry>())

                override fun findMatch(matchId: MatchId) = CompletableFuture.completedFuture<RecordedMatch?>(null)

                override fun recentMatches(playerId: PlayerId, limit: Int) =
                    CompletableFuture.completedFuture(emptyList<RecordedMatch>())

                override fun headToHead(firstPlayer: PlayerId, secondPlayer: PlayerId) =
                    CompletableFuture.completedFuture(HeadToHeadRecord(firstPlayer, secondPlayer, 0, 0, null))
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

    "synchronous presentation failure cannot strand a persisted match" {
        val releasesOnPublicationFailure = AtomicInteger()
        val failSoftPublisher =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator {
                    CompletableFuture.completedFuture(
                        ArenaReservation(ArenaId("arena-3"), releasesOnPublicationFailure::incrementAndGet),
                    )
                },
                InMemoryStatisticsRepository(),
                eventPublisher = DuelEventPublisher { error("redis unavailable") },
                clock = Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC),
            )
        val match = failSoftPublisher.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY)).get()
        failSoftPublisher.beginCountdown(match.id)
        failSoftPublisher.activate(match.id)

        val completed = failSoftPublisher.recordRoundWinner(match.id, first).get()

        completed.state shouldBe MatchState.COMPLETED
        failSoftPublisher.releaseCompleted(match.id) shouldBe true
        releasesOnPublicationFailure.get() shouldBe 1
    }

    "a future hill objective can continue and then complete through the coordinator" {
        val objectiveCoordinator =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator {
                    CompletableFuture.completedFuture(ArenaReservation(ArenaId("hill-1")) {})
                },
                InMemoryStatisticsRepository(),
                clock = Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC),
            )
        val match = objectiveCoordinator.reserve(first, second, DuelRules(DuelMode.KIT, KitId("classic"))).get()
        objectiveCoordinator.beginCountdown(match.id)
        objectiveCoordinator.activate(match.id)
        val hillObjective =
            object : MatchObjective {
                override val type = "king_of_the_hill"

                override fun evaluate(
                    match: DuelMatch,
                    frame: ObjectiveFrame,
                ): ObjectiveDecision =
                    if (frame.elapsedTicks >= 200 && frame.contenders.size == 1) {
                        ObjectiveDecision.Complete(frame.contenders.single())
                    } else {
                        ObjectiveDecision.Continue
                    }
            }

        val active =
            objectiveCoordinator.evaluateObjective(
                match.id,
                hillObjective,
                ObjectiveFrame(elapsedTicks = 199, contenders = setOf(first)),
            ).get()
        active.state shouldBe MatchState.ACTIVE

        val completed =
            objectiveCoordinator.evaluateObjective(
                match.id,
                hillObjective,
                ObjectiveFrame(elapsedTicks = 200, contenders = setOf(first)),
            ).get()
        completed.state shouldBe MatchState.COMPLETED
        completed.winner shouldBe first
        completed.endReason shouldBe MatchEndReason.OBJECTIVE
    }

    "players are owned while waiting so a second racing reservation is rejected" {
        val pendingReservations = CopyOnWriteArrayList<CompletableFuture<ArenaReservation>>()
        val racingCoordinator =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator {
                    CompletableFuture<ArenaReservation>().also(pendingReservations::add)
                },
                InMemoryStatisticsRepository(),
            )

        val firstAttempt = racingCoordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY))
        shouldThrow<IllegalStateException> {
            racingCoordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY))
        }
        pendingReservations shouldHaveSize 1

        pendingReservations[0].complete(ArenaReservation(ArenaId("race-1")) {})
        val winner = firstAttempt.get()

        racingCoordinator.findByPlayer(first)?.id shouldBe winner.id
        racingCoordinator.activeMatches() shouldHaveSize 1
    }

    "a pending match id cannot reserve a second arena" {
        val reservations = CopyOnWriteArrayList<CompletableFuture<ArenaReservation>>()
        val collisionCoordinator =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator { CompletableFuture<ArenaReservation>().also(reservations::add) },
                InMemoryStatisticsRepository(),
            )
        val sharedId = MatchId(UUID.randomUUID())
        val third = PlayerId(UUID.randomUUID())
        val fourth = PlayerId(UUID.randomUUID())

        val firstReservation = collisionCoordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), sharedId)

        shouldThrow<IllegalStateException> {
            collisionCoordinator.reserve(third, fourth, DuelRules(DuelMode.OWN_INVENTORY), sharedId)
        }
        reservations shouldHaveSize 1

        reservations.single().complete(ArenaReservation(ArenaId("collision-safe")) {})
        firstReservation.get().id shouldBe sharedId
    }

    "cancelling an arena wait releases both queued player ownerships" {
        val arenaFutures = CopyOnWriteArrayList<CompletableFuture<ArenaReservation>>()
        val cancellable =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator { CompletableFuture<ArenaReservation>().also(arenaFutures::add) },
                InMemoryStatisticsRepository(),
            )

        val firstWait = cancellable.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY))
        cancellable.isQueuedOrMatched(first) shouldBe true

        firstWait.cancel(false) shouldBe true

        cancellable.isQueuedOrMatched(first) shouldBe false
        cancellable.isQueuedOrMatched(second) shouldBe false
        cancellable.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY))
        arenaFutures shouldHaveSize 2
    }

    "late failed allocation cannot clear a replacement reservation" {
        val allocations = CopyOnWriteArrayList<NonCancellableFuture<ArenaReservation>>()
        val replacementCoordinator =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator { NonCancellableFuture<ArenaReservation>().also(allocations::add) },
                InMemoryStatisticsRepository(),
            )
        val matchId = MatchId(UUID.randomUUID())
        val cancelled = replacementCoordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), matchId)
        cancelled.cancel(false) shouldBe true
        val replacement = replacementCoordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), matchId)

        allocations[0].completeExceptionally(IllegalStateException("late allocation failure"))
        allocations[1].complete(ArenaReservation(ArenaId("replacement")) {})

        replacement.get().id shouldBe matchId
        replacementCoordinator.findByPlayer(first)?.id shouldBe matchId
    }

    "late successful allocation closes only its own reservation" {
        val allocations = CopyOnWriteArrayList<NonCancellableFuture<ArenaReservation>>()
        val lateReleases = AtomicInteger()
        val replacementCoordinator =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator { NonCancellableFuture<ArenaReservation>().also(allocations::add) },
                InMemoryStatisticsRepository(),
            )
        val matchId = MatchId(UUID.randomUUID())
        val cancelled = replacementCoordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), matchId)
        cancelled.cancel(false) shouldBe true
        val replacement = replacementCoordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), matchId)

        allocations[0].complete(ArenaReservation(ArenaId("late"), lateReleases::incrementAndGet))
        allocations[1].complete(ArenaReservation(ArenaId("replacement")) {})

        lateReleases.get() shouldBe 1
        replacement.get().id shouldBe matchId
        replacementCoordinator.findByPlayer(first)?.id shouldBe matchId
    }

    "allocator throw releases the reservation attempt" {
        var fail = true
        val coordinator =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator {
                    if (fail) {
                        fail = false
                        throw IllegalStateException("allocator rejected reservation")
                    }
                    CompletableFuture.completedFuture(ArenaReservation(ArenaId("recovered")) {})
                },
                InMemoryStatisticsRepository(),
            )
        val matchId = MatchId(UUID.randomUUID())

        shouldThrow<IllegalStateException> {
            coordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), matchId)
        }

        coordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), matchId).get().id shouldBe matchId
    }

    "construction failure releases its reservation and preserves the close error" {
        var failConstruction = true
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant {
                    if (failConstruction) {
                        failConstruction = false
                        throw IllegalStateException("clock failed during match construction")
                    }
                    return Instant.parse("2026-08-13T10:00:00Z")
                }
            }
        val coordinator =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator {
                    CompletableFuture.completedFuture(ArenaReservation(ArenaId("throwing-close")) {
                        error("reservation release failed")
                    })
                },
                InMemoryStatisticsRepository(),
                clock = clock,
            )
        val matchId = MatchId(UUID.randomUUID())

        val failure = shouldThrow<ExecutionException> {
            coordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), matchId).get(1, TimeUnit.SECONDS)
        }.cause ?: error("missing construction failure")

        failure.message shouldBe "clock failed during match construction"
        failure.suppressed.single().message shouldBe "reservation release failed"
        coordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), matchId).get(1, TimeUnit.SECONDS).id shouldBe matchId
    }

    "late reservation close failure cannot strand its replacement" {
        val allocations = CopyOnWriteArrayList<NonCancellableFuture<ArenaReservation>>()
        val coordinator =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator { NonCancellableFuture<ArenaReservation>().also(allocations::add) },
                InMemoryStatisticsRepository(),
            )
        val matchId = MatchId(UUID.randomUUID())
        val cancelled = coordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), matchId)
        cancelled.cancel(false) shouldBe true
        val replacement = coordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY), matchId)

        allocations[0].complete(ArenaReservation(ArenaId("late")) { error("late release failed") })
        allocations[1].complete(ArenaReservation(ArenaId("replacement")) {})

        replacement.get().id shouldBe matchId
        coordinator.findByPlayer(first)?.id shouldBe matchId
    }

    "overlapping successful persistence retries publish completion events once" {
        val writes = CopyOnWriteArrayList<CompletableFuture<PersistedMatchResult>>()
        val retryEvents = CopyOnWriteArrayList<DuelEvent>()
        val delayedRepository =
            object : StatisticsRepository {
                override fun rememberPlayerName(playerId: PlayerId, playerName: String) =
                    CompletableFuture.completedFuture(Unit)

                override fun findPlayerName(playerId: PlayerId) = CompletableFuture.completedFuture<String?>(null)
                override fun find(playerId: PlayerId) = CompletableFuture.completedFuture(PlayerStatistics(playerId))
                override fun leaderboard(limit: Int) = CompletableFuture.completedFuture(emptyList<LeaderboardEntry>())

                override fun findMatch(matchId: MatchId) = CompletableFuture.completedFuture<RecordedMatch?>(null)

                override fun recentMatches(playerId: PlayerId, limit: Int) =
                    CompletableFuture.completedFuture(emptyList<RecordedMatch>())

                override fun headToHead(firstPlayer: PlayerId, secondPlayer: PlayerId) =
                    CompletableFuture.completedFuture(HeadToHeadRecord(firstPlayer, secondPlayer, 0, 0, null))

                override fun record(outcome: MatchOutcome): CompletableFuture<PersistedMatchResult> =
                    CompletableFuture<PersistedMatchResult>().also(writes::add)
            }
        val retryCoordinator =
            MatchCoordinator(
                ServerId("duels-1"),
                ArenaAllocator { CompletableFuture.completedFuture(ArenaReservation(ArenaId("retry-1")) {}) },
                delayedRepository,
                DuelEventPublisher { event ->
                    retryEvents += event
                    CompletableFuture.completedFuture(Unit)
                },
                Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC),
            )
        val match = retryCoordinator.reserve(first, second, DuelRules(DuelMode.OWN_INVENTORY)).get()
        retryCoordinator.beginCountdown(match.id)
        retryCoordinator.activate(match.id)
        val initial = retryCoordinator.recordRoundWinner(match.id, first)
        val retry = retryCoordinator.retryCompletion(match.id)
        writes shouldHaveSize 2
        val persisted =
            PersistedMatchResult(
                MatchOutcome(
                    match.id,
                    first,
                    second,
                    DuelMode.OWN_INVENTORY,
                    null,
                    ranked = false,
                    ServerId("duels-1"),
                    Instant.parse("2026-08-13T10:00:00Z"),
                ),
                winnerRatingAfter = 1_000,
                loserRatingAfter = 1_000,
                leaderboardRevision = 1,
                newlyRecorded = true,
            )

        writes[0].complete(persisted)
        writes[1].complete(persisted.copy(newlyRecorded = false))

        initial.get().state shouldBe MatchState.COMPLETED
        retry.get().state shouldBe MatchState.COMPLETED
        retryEvents shouldHaveSize 2
    }
})

private class NonCancellableFuture<T> : CompletableFuture<T>() {
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
}
