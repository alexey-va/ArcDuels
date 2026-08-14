package ru.ruscrafting.duels.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class ScoreRaceObjectiveTest : StringSpec({
    val first = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val second = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    val match =
        DuelMatch.reserve(
            first,
            second,
            ArenaId("boxing"),
            ServerId("test"),
            DuelRules(
                DuelMode.KIT,
                KitId("boxing"),
                objective = DuelObjectiveType.BOXING,
                modifiers = CombatModifiers(false, false, false, false),
            ),
            Instant.EPOCH,
        ).beginCountdown().activate(Instant.EPOCH)

    "score race completes at the target and resolves a simultaneous tie deterministically" {
        val objective = ScoreRaceObjective("boxing", 100)
        objective.evaluate(match, ObjectiveFrame(1, emptySet(), mapOf(first to 99, second to 40))) shouldBe ObjectiveDecision.Continue
        objective.evaluate(match, ObjectiveFrame(2, emptySet(), mapOf(first to 100, second to 100))) shouldBe ObjectiveDecision.Complete(first)
    }

    "foreign progress cannot complete a score race" {
        val stranger = PlayerId(UUID.randomUUID())
        ScoreRaceObjective("combo", 10).evaluate(
            match,
            ObjectiveFrame(1, emptySet(), mapOf(stranger to 100)),
        ) shouldBe ObjectiveDecision.Continue
    }

    "hit races require a controlled kit and bounded targets" {
        shouldThrow<IllegalArgumentException> {
            DuelRules(DuelMode.OWN_INVENTORY, objective = DuelObjectiveType.BOXING)
        }
        shouldThrow<IllegalArgumentException> {
            DuelRules(DuelMode.KIT, KitId("boxing"), objective = DuelObjectiveType.BOXING)
        }
        shouldThrow<IllegalArgumentException> { CombatModifiers(boxingHitsToWin = 9) }
        shouldThrow<IllegalArgumentException> { CombatModifiers(comboHitsToWin = 51) }
        shouldThrow<IllegalArgumentException> { ScoreRaceObjective("", 10) }
    }
})
