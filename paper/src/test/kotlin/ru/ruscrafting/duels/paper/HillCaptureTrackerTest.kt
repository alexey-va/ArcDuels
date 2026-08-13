package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.duels.domain.PlayerId
import java.util.UUID

class HillCaptureTrackerTest : StringSpec({
    "capture advances for exactly one contender and pauses when empty or contested" {
        val first = PlayerId(UUID.randomUUID())
        val second = PlayerId(UUID.randomUUID())
        val tracker = HillCaptureTracker()

        tracker.tick(setOf(first), 10) shouldBe mapOf(first to 10L)
        tracker.tick(emptySet(), 10) shouldBe mapOf(first to 10L)
        tracker.tick(setOf(first, second), 10) shouldBe mapOf(first to 10L)
        tracker.tick(setOf(second), 10) shouldBe mapOf(first to 10L, second to 10L)
        tracker.tick(setOf(first), 10) shouldBe mapOf(first to 20L, second to 10L)

        tracker.reset()
        tracker.snapshot() shouldBe emptyMap()
        shouldThrow<IllegalArgumentException> { tracker.tick(setOf(first), 0) }
    }
})
