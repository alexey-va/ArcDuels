package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.PlayerId
import java.util.UUID

class HitRaceTrackerTest : StringSpec({
    val first = PlayerId(UUID.randomUUID())
    val second = PlayerId(UUID.randomUUID())

    "boxing counts total accepted hits independently" {
        val tracker = HitRaceTracker()
        tracker.record(DuelObjectiveType.BOXING, first, second)
        tracker.record(DuelObjectiveType.BOXING, second, first)
        tracker.record(DuelObjectiveType.BOXING, first, second).scores shouldBe mapOf(first to 2L, second to 1L)
    }

    "combo resets only the struck player's streak" {
        val tracker = HitRaceTracker()
        tracker.record(DuelObjectiveType.COMBO, first, second)
        tracker.record(DuelObjectiveType.COMBO, first, second)
        tracker.record(DuelObjectiveType.COMBO, second, first).scores shouldBe mapOf(first to 0L, second to 1L)
    }

    "reset clears all round progress" {
        val tracker = HitRaceTracker()
        tracker.record(DuelObjectiveType.BOXING, first, second)
        tracker.reset()
        tracker.record(DuelObjectiveType.BOXING, second, first).scores shouldBe mapOf(second to 1L)
    }
})
