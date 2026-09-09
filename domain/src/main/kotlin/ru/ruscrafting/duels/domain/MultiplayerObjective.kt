package ru.ruscrafting.duels.domain

/** Objective observation supplied by the Paper multiplayer adapter. */
data class MultiplayerObjectiveFrame(
    val elapsedTicks: Long,
    val contenders: Set<PlayerId>,
    val progress: Map<PlayerId, Long> = emptyMap(),
) {
    init {
        require(elapsedTicks >= 0) { "Elapsed ticks cannot be negative" }
        require(progress.values.all { it >= 0 }) { "Objective progress cannot be negative" }
    }
}

sealed interface MultiplayerObjectiveDecision {
    data object Continue : MultiplayerObjectiveDecision

    data class Complete(
        val winners: Set<PlayerId>,
        val winningTeam: Int?,
    ) : MultiplayerObjectiveDecision
}

/** Evaluates the non-elimination objectives using shared team progress. */
class MultiplayerScoreObjective(
    private val objective: DuelObjectiveType,
    private val captureSeconds: Int,
    private val targetScore: Int,
) {
    init {
        require(objective == DuelObjectiveType.KING_OF_THE_HILL || objective.isHitRace) {
            "Unsupported multiplayer score objective"
        }
        require(captureSeconds in 5..120) { "Capture time must be between 5 and 120 seconds" }
        require(targetScore > 0) { "Target score must be positive" }
    }

    fun evaluate(
        match: MultiplayerMatch,
        frame: MultiplayerObjectiveFrame,
    ): MultiplayerObjectiveDecision {
        require((frame.contenders + frame.progress.keys).all(match.roster.playerIds::contains)) {
            "Objective frame contains an outsider"
        }
        // Progress belongs to the side, not to the particular player currently holding
        // the side's accumulator. A teammate may take over after that player is eliminated.
        val scores = frame.progress
        val threshold = if (objective == DuelObjectiveType.KING_OF_THE_HILL) captureSeconds.toLong() * 20L else targetScore.toLong()
        val completed = scoresBySide(match.roster, scores, frame.contenders)
            .filterValues { it >= threshold }
            .toList()
            .sortedWith(compareByDescending<Pair<MultiplayerSide, Long>> { it.second }.thenBy { it.first.sortKey })
        val winner = completed.firstOrNull()?.first ?: return MultiplayerObjectiveDecision.Continue
        return MultiplayerObjectiveDecision.Complete(winner.players, winner.team)
    }

    private fun scoresBySide(
        roster: MultiplayerRoster,
        scores: Map<PlayerId, Long>,
        contenders: Set<PlayerId>,
    ): Map<MultiplayerSide, Long> {
        val eligible = if (objective == DuelObjectiveType.KING_OF_THE_HILL) {
            val contenderSides = roster.participants.filter { it.playerId in contenders }.map { sideKey(it) }.toSet()
            roster.playerIds.filterTo(linkedSetOf()) { id ->
                val participant = roster.participant(id)
                sideKey(participant) in contenderSides
            }
        } else {
            roster.playerIds
        }
        return roster.participants
            .filter { it.playerId in eligible }
            .groupBy {
                if (roster.rules.layout == MultiplayerLayout.FREE_FOR_ALL) {
                    MultiplayerSide(null, setOf(it.playerId))
                } else {
                    MultiplayerSide(it.team, roster.participants.filter { participant -> participant.team == it.team }.map { participant -> participant.playerId }.toSet())
                }
            }
            .mapValues { (_, players) -> players.sumOf { scores[it.playerId] ?: 0L } }
    }

    private fun sideKey(participant: MultiplayerParticipant): String = participant.team?.let { "team:$it" } ?: "player:${participant.playerId.value}"

    private data class MultiplayerSide(val team: Int?, val players: Set<PlayerId>) {
        val sortKey: String = team?.toString() ?: players.minOfOrNull { it.value.toString() }.orEmpty()
    }
}
