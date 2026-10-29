package ru.ruscrafting.duels.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MatchCancellationCleanupTest : StringSpec({
    "retained cancellation reports one refund outcome and holds gameplay ownership until native cleanup" {
        val first = PlayerId(UUID.randomUUID())
        val second = PlayerId(UUID.randomUUID())
        val observed = mutableListOf<DuelMatch>()
        val statistics = InMemoryStatisticsRepository()
        var releases = 0
        val coordinator = MatchCoordinator(
            ServerId("duels-1"),
            ArenaAllocator { CompletableFuture.completedFuture(ArenaReservation(ArenaId("one")) { releases++ }) },
            statistics,
            onTerminated = { observed += it },
        )
        val match = coordinator.reserve(first, second, DuelRules(DuelMode.KIT, KitId("classic"), stakeMinor = 10_000L)).join()
        coordinator.beginCountdown(match.id)

        val cancelled = coordinator.cancelRetainingResources(match.id, MatchEndReason.ADMIN_CANCEL)

        cancelled.state shouldBe MatchState.CANCELLED
        cancelled.startedAt shouldBe null
        observed.single().winner shouldBe null
        statistics.findMatch(match.id).join() shouldBe null
        releases shouldBe 0
        coordinator.findByPlayer(first)?.id shouldBe match.id
        coordinator.isQueuedOrMatched(second) shouldBe true
        shouldThrow<IllegalArgumentException> { coordinator.cancelRetainingResources(match.id, MatchEndReason.ADMIN_CANCEL) }
        observed.size shouldBe 1
        coordinator.releaseCancelled(match.id) shouldBe true
        coordinator.releaseCancelled(match.id) shouldBe false
        releases shouldBe 1
        coordinator.findByPlayer(first) shouldBe null
        coordinator.findByPlayer(second) shouldBe null
        observed.size shouldBe 1
    }
})
