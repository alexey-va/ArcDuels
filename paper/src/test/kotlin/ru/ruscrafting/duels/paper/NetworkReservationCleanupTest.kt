package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture

class NetworkReservationCleanupTest : StringSpec({
    "a preparation failure cancels a reservation that is still queued" {
        val reservation = CompletableFuture<String>()
        val released = mutableListOf<String>()

        cancelOrReleaseReservation(reservation, released::add)

        reservation.isCancelled shouldBe true
        released shouldBe emptyList()
    }

    "a preparation failure releases an arena that was already reserved" {
        val reservation = CompletableFuture.completedFuture("arena-1")
        val released = mutableListOf<String>()

        cancelOrReleaseReservation(reservation, released::add)

        reservation.isCancelled shouldBe false
        released shouldBe listOf("arena-1")
    }
})
