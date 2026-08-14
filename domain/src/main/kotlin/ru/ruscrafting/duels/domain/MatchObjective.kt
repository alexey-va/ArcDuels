package ru.ruscrafting.duels.domain

/** Platform-neutral observation supplied by a gameplay adapter on every objective tick. */
data class ObjectiveFrame(
    val elapsedTicks: Long,
    val contenders: Set<PlayerId>,
    val progress: Map<PlayerId, Long> = emptyMap(),
) {
    init {
        require(elapsedTicks >= 0) { "Elapsed ticks cannot be negative" }
        require(progress.values.all { it >= 0 }) { "Objective progress cannot be negative" }
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

class KingOfTheHillObjective(
    captureSeconds: Int,
) : MatchObjective {
    override val type: String = "king_of_the_hill"
    private val captureTicks = captureSeconds.toLong() * TICKS_PER_SECOND

    init {
        require(captureSeconds in 5..120) { "Capture time must be between 5 and 120 seconds" }
    }

    override fun evaluate(
        match: DuelMatch,
        frame: ObjectiveFrame,
    ): ObjectiveDecision {
        val completed =
            frame.progress.entries
                .filter { (player, progress) ->
                    (player == match.firstPlayer || player == match.secondPlayer) &&
                        player in frame.contenders &&
                        progress >= captureTicks
                }
                .sortedWith(compareByDescending<Map.Entry<PlayerId, Long>> { it.value }.thenBy { it.key.value })
        return completed.firstOrNull()?.let { ObjectiveDecision.Complete(it.key) } ?: ObjectiveDecision.Continue
    }

    companion object {
        const val TICKS_PER_SECOND: Long = 20L
    }
}

/** First participant to reach a configured score wins the round. */
class ScoreRaceObjective(
    override val type: String,
    targetScore: Int,
) : MatchObjective {
    private val targetScore = targetScore.toLong()

    init {
        require(type.isNotBlank()) { "Score-race type cannot be blank" }
        require(targetScore > 0) { "Target score must be positive" }
    }

    override fun evaluate(
        match: DuelMatch,
        frame: ObjectiveFrame,
    ): ObjectiveDecision {
        val completed =
            frame.progress.entries
                .filter { (player, score) ->
                    (player == match.firstPlayer || player == match.secondPlayer) && score >= targetScore
                }
                .sortedWith(compareByDescending<Map.Entry<PlayerId, Long>> { it.value }.thenBy { it.key.value })
        return completed.firstOrNull()?.let { ObjectiveDecision.Complete(it.key) } ?: ObjectiveDecision.Continue
    }
}
