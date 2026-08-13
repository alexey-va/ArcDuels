package ru.ruscrafting.duels.domain

/** Platform-neutral observation supplied by a gameplay adapter on every objective tick. */
data class ObjectiveFrame(
    val elapsedTicks: Long,
    val contenders: Set<PlayerId>,
) {
    init {
        require(elapsedTicks >= 0) { "Elapsed ticks cannot be negative" }
    }
}

/** Extension boundary for alternate win conditions such as king of the hill. */
interface MatchObjective {
    val type: String

    fun evaluate(
        match: DuelMatch,
        frame: ObjectiveFrame,
    ): ObjectiveDecision = ObjectiveDecision.Continue
}

sealed interface ObjectiveDecision {
    data object Continue : ObjectiveDecision

    data class Complete(val winner: PlayerId) : ObjectiveDecision
}

object EliminationObjective : MatchObjective {
    override val type: String = "elimination"
}
