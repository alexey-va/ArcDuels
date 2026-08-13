package ru.ruscrafting.duels.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class KingOfTheHillObjectiveTest : StringSpec({
    val first = PlayerId(UUID.randomUUID())
    val second = PlayerId(UUID.randomUUID())
    val match =
        DuelMatch.reserve(
            first,
            second,
            ArenaId("hill"),
            ServerId("test"),
            DuelRules(DuelMode.KIT, KitId("classic"), objective = DuelObjectiveType.KING_OF_THE_HILL),
            Instant.EPOCH,
        ).beginCountdown().activate(Instant.EPOCH)

    "king of the hill completes exactly at configured capture time" {
        val objective = KingOfTheHillObjective(15)
        objective.evaluate(match, ObjectiveFrame(299, setOf(first), mapOf(first to 299))) shouldBe ObjectiveDecision.Continue
        objective.evaluate(match, ObjectiveFrame(300, setOf(first), mapOf(first to 300))) shouldBe ObjectiveDecision.Complete(first)
        objective.evaluate(match, ObjectiveFrame(301, emptySet(), mapOf(first to 300))) shouldBe ObjectiveDecision.Continue
    }

    "foreign progress cannot win and invalid progress is rejected" {
        val stranger = PlayerId(UUID.randomUUID())
        val objective = KingOfTheHillObjective(10)
        objective.evaluate(match, ObjectiveFrame(200, setOf(first), mapOf(stranger to 10_000))) shouldBe ObjectiveDecision.Continue
        shouldThrow<IllegalArgumentException> { ObjectiveFrame(1, emptySet(), mapOf(first to -1)) }
        shouldThrow<IllegalArgumentException> { KingOfTheHillObjective(4) }
    }

    "sumo cannot use an uncontrolled own inventory" {
        shouldThrow<IllegalArgumentException> {
            DuelRules(DuelMode.OWN_INVENTORY, objective = DuelObjectiveType.SUMO)
        }
    }
})
