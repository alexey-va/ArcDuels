package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.duels.domain.PlayerId
import java.util.UUID

class MultiplayerObjectiveTrackerTest : StringSpec({
    "contested hill pauses and team progress is stored once per side" {
        val first = PlayerId(UUID(0, 1))
        val teammate = PlayerId(UUID(0, 2))
        val enemy = PlayerId(UUID(0, 3))
        val tracker = MultiplayerHillCaptureTracker()
        val side = { id: PlayerId -> if (id == enemy) "2" else "1" }

        tracker.tick(setOf(first, enemy), side, 1) shouldBe emptyMap()
        tracker.tick(setOf(first, teammate), side, 3) shouldBe mapOf(first to 3L)
        tracker.tick(setOf(teammate), side, 2) shouldBe mapOf(first to 5L)
    }
})
