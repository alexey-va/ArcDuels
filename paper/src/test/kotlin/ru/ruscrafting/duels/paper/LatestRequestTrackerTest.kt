package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.util.UUID

class LatestRequestTrackerTest : StringSpec({
    "only the latest asynchronous menu request may update a player view" {
        val tracker = LatestRequestTracker()
        val player = UUID.randomUUID()
        val first = tracker.begin(player)
        val second = tracker.begin(player)

        tracker.isCurrent(player, first).shouldBeFalse()
        tracker.isCurrent(player, second).shouldBeTrue()
    }

    "closing a menu invalidates its outstanding asynchronous request" {
        val tracker = LatestRequestTracker()
        val player = UUID.randomUUID()
        val request = tracker.begin(player)

        tracker.invalidate(player)

        tracker.isCurrent(player, request).shouldBeFalse()
    }
})
