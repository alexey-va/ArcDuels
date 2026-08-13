package ru.ruscrafting.duels.domain

/** Extension boundary used later by objectives such as KOTH. */
interface MatchObjective {
    val type: String

    fun onActivated(match: DuelMatch): ObjectiveDecision = ObjectiveDecision.Continue

    fun onTick(
        match: DuelMatch,
        elapsedTicks: Long,
    ): ObjectiveDecision = ObjectiveDecision.Continue
}

sealed interface ObjectiveDecision {
    data object Continue : ObjectiveDecision

    data class Complete(val winner: PlayerId) : ObjectiveDecision
}

object EliminationObjective : MatchObjective {
    override val type: String = "elimination"
}
