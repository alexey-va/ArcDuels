package ru.ruscrafting.duels.domain

import java.time.Instant
import java.util.concurrent.CompletableFuture

const val MIN_MULTIPLAYER_PARTICIPANTS: Int = 3
const val MAX_MULTIPLAYER_PARTICIPANTS: Int = 12

enum class MultiplayerLayout(val teamCount: Int?) {
    FREE_FOR_ALL(null),
    TWO_TEAMS(2),
    THREE_TEAMS(3),
}

enum class MultiplayerKitPolicy {
    SHARED,
    PER_PLAYER,
}

data class MultiplayerRules(
    val layout: MultiplayerLayout,
    val kitPolicy: MultiplayerKitPolicy,
    val sharedKitId: KitId? = null,
    val modifiers: CombatModifiers = CombatModifiers(),
    val objective: DuelObjectiveType = DuelObjectiveType.ELIMINATION,
) {
    init {
        require((kitPolicy == MultiplayerKitPolicy.SHARED) == (sharedKitId != null)) {
            "A shared-kit match requires exactly one shared kit"
        }
        require(
            !objective.isHitRace ||
                (!modifiers.projectiles && !modifiers.consumables && !modifiers.enderPearls && !modifiers.naturalRegeneration),
        ) { "Hit-race objectives require locked combat-item modifiers" }
    }
}

fun defaultMultiplayerModifiers(objective: DuelObjectiveType): CombatModifiers =
    if (objective.isHitRace || objective == DuelObjectiveType.SUMO) CombatModifiers(
        projectiles = false,
        consumables = false,
        enderPearls = false,
        naturalRegeneration = false,
        suddenDeathAfterSeconds = if (objective == DuelObjectiveType.SUMO) 180 else 300,
    )
    else CombatModifiers()

data class MultiplayerParticipant(
    val playerId: PlayerId,
    val team: Int? = null,
    val kitId: KitId,
)

data class MultiplayerRoster(
    val rules: MultiplayerRules,
    val participants: List<MultiplayerParticipant>,
) {
    init {
        require(participants.size in MIN_MULTIPLAYER_PARTICIPANTS..MAX_MULTIPLAYER_PARTICIPANTS) {
            "A multiplayer match requires $MIN_MULTIPLAYER_PARTICIPANTS..$MAX_MULTIPLAYER_PARTICIPANTS players"
        }
        require(participants.map(MultiplayerParticipant::playerId).distinct().size == participants.size) {
            "A multiplayer roster cannot contain duplicate players"
        }
        when (val teamCount = rules.layout.teamCount) {
            null -> require(participants.all { it.team == null }) { "FFA participants cannot have a team" }
            else -> {
                require(participants.all { it.team in 1..teamCount }) {
                    "Team participants must use a team between 1 and $teamCount"
                }
                val sizes = (1..teamCount).map { team -> participants.count { it.team == team } }
                require(sizes.all { it > 0 }) { "Every configured team must have at least one participant" }
                require(requireNotNull(sizes.maxOrNull()) - requireNotNull(sizes.minOrNull()) <= 1) {
                    "Teams must be balanced within one participant"
                }
            }
        }
        rules.sharedKitId?.let { shared ->
            require(participants.all { it.kitId == shared }) { "Every participant must use the selected shared kit" }
        }
    }

    val playerIds: Set<PlayerId> = participants.mapTo(linkedSetOf(), MultiplayerParticipant::playerId)

    fun participant(playerId: PlayerId): MultiplayerParticipant =
        participants.firstOrNull { it.playerId == playerId } ?: error("Player is not a multiplayer participant")
}

enum class MultiplayerMatchState {
    RESERVED,
    COUNTDOWN,
    ACTIVE,
    COMPLETING,
    COMPLETED,
    CANCELLED,
}

data class MultiplayerMatch(
    val id: MatchId,
    val arenaId: ArenaId,
    val serverId: ServerId,
    val roster: MultiplayerRoster,
    val state: MultiplayerMatchState,
    val activePlayers: Set<PlayerId>,
    val eliminationOrder: List<PlayerId>,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val winners: Set<PlayerId> = emptySet(),
    val winningTeam: Int? = null,
    val endReason: MatchEndReason? = null,
) {
    init {
        require(activePlayers.all(roster.playerIds::contains)) { "Active players must belong to the roster" }
        require(eliminationOrder.distinct().size == eliminationOrder.size) { "A player cannot be eliminated twice" }
        require(eliminationOrder.all(roster.playerIds::contains)) { "Eliminated players must belong to the roster" }
        require(activePlayers.intersect(eliminationOrder.toSet()).isEmpty()) {
            "A player cannot be both active and eliminated"
        }
        require(winners.all(roster.playerIds::contains)) { "Winners must belong to the roster" }
        require(winningTeam == null || winningTeam in 1..requireNotNull(roster.rules.layout.teamCount)) {
            "Winning team does not belong to the selected layout"
        }
    }

    fun beginCountdown(): MultiplayerMatch {
        require(state == MultiplayerMatchState.RESERVED) { "Only a reserved multiplayer match can begin countdown" }
        return copy(state = MultiplayerMatchState.COUNTDOWN)
    }

    fun activate(now: Instant): MultiplayerMatch {
        require(state == MultiplayerMatchState.COUNTDOWN) { "Only a countdown multiplayer match can activate" }
        return copy(state = MultiplayerMatchState.ACTIVE, startedAt = now)
    }

    fun eliminate(
        playerId: PlayerId,
        now: Instant,
        reason: MatchEndReason,
    ): MultiplayerMatch {
        require(state == MultiplayerMatchState.ACTIVE) { "Only an active multiplayer match can eliminate a player" }
        require(playerId in activePlayers) { "Player is not active in this multiplayer match" }
        require(reason in ELIMINATION_REASONS) { "Unsupported multiplayer elimination reason" }
        val remaining = activePlayers - playerId
        val eliminated = eliminationOrder + playerId
        val result = winnerAfter(remaining)
        return if (result == null) {
            copy(activePlayers = remaining, eliminationOrder = eliminated)
        } else {
            copy(
                state = MultiplayerMatchState.COMPLETING,
                activePlayers = remaining,
                eliminationOrder = eliminated,
                completedAt = now,
                winners = result.first,
                winningTeam = result.second,
                endReason = reason,
            )
        }
    }

    fun completeObjective(
        winners: Set<PlayerId>,
        winningTeam: Int?,
        now: Instant,
    ): MultiplayerMatch {
        require(state == MultiplayerMatchState.ACTIVE) { "Only an active multiplayer match can complete an objective" }
        require(winners.isNotEmpty() && winners.all(roster.playerIds::contains)) { "Objective winners must belong to the roster" }
        require(winningTeam == null || roster.rules.layout != MultiplayerLayout.FREE_FOR_ALL) {
            "FFA objective outcomes cannot have a winning team"
        }
        if (winningTeam != null) require(winners == roster.participants.filter { it.team == winningTeam }.mapTo(linkedSetOf()) { it.playerId }) {
            "Objective winners must contain the whole winning team"
        }
        val losers = roster.playerIds - winners
        return copy(
            state = MultiplayerMatchState.COMPLETING,
            activePlayers = emptySet(),
            eliminationOrder = losers.toList(),
            completedAt = now,
            winners = winners,
            winningTeam = winningTeam,
            endReason = MatchEndReason.OBJECTIVE,
        )
    }

    fun markPersisted(): MultiplayerMatch {
        require(state == MultiplayerMatchState.COMPLETING) { "Only a completing multiplayer match can be finalized" }
        return copy(state = MultiplayerMatchState.COMPLETED)
    }

    fun cancel(
        now: Instant,
        reason: MatchEndReason,
    ): MultiplayerMatch {
        require(state !in setOf(MultiplayerMatchState.COMPLETING, MultiplayerMatchState.COMPLETED, MultiplayerMatchState.CANCELLED)) {
            "Multiplayer match can no longer be cancelled"
        }
        require(reason in setOf(MatchEndReason.ADMIN_CANCEL, MatchEndReason.SERVER_SHUTDOWN)) {
            "Multiplayer cancellation requires an administrative reason"
        }
        return copy(state = MultiplayerMatchState.CANCELLED, completedAt = now, endReason = reason)
    }

    fun isEnemy(
        first: PlayerId,
        second: PlayerId,
    ): Boolean {
        require(first in roster.playerIds && second in roster.playerIds) { "Both players must belong to the roster" }
        if (first == second) return false
        return roster.rules.layout == MultiplayerLayout.FREE_FOR_ALL || roster.participant(first).team != roster.participant(second).team
    }

    fun placementOf(playerId: PlayerId): Int {
        require(playerId in roster.playerIds) { "Player is not a multiplayer participant" }
        if (playerId in winners) return 1
        val index = eliminationOrder.indexOf(playerId)
        check(index >= 0) { "Placement is unavailable before the participant is eliminated" }
        return roster.participants.size - index
    }

    private fun winnerAfter(remaining: Set<PlayerId>): Pair<Set<PlayerId>, Int?>? =
        when (roster.rules.layout) {
            MultiplayerLayout.FREE_FOR_ALL -> remaining.singleOrNull()?.let { setOf(it) to null }
            MultiplayerLayout.TWO_TEAMS, MultiplayerLayout.THREE_TEAMS -> {
                val remainingTeams = remaining.mapTo(linkedSetOf()) { roster.participant(it).team }
                remainingTeams.singleOrNull()?.let { team ->
                    val exactTeam = requireNotNull(team)
                    roster.participants.filterTo(linkedSetOf()) { it.team == exactTeam }.mapTo(linkedSetOf()) { it.playerId } to exactTeam
                }
            }
        }

    companion object {
        private val ELIMINATION_REASONS =
            setOf(MatchEndReason.ELIMINATION, MatchEndReason.FORFEIT, MatchEndReason.DISCONNECT)

        fun reserve(
            id: MatchId,
            arenaId: ArenaId,
            serverId: ServerId,
            roster: MultiplayerRoster,
            now: Instant,
        ): MultiplayerMatch =
            MultiplayerMatch(
                id = id,
                arenaId = arenaId,
                serverId = serverId,
                roster = roster,
                state = MultiplayerMatchState.RESERVED,
                activePlayers = roster.playerIds,
                eliminationOrder = emptyList(),
                createdAt = now,
            )
    }
}

data class MultiplayerMatchOutcome(
    val matchId: MatchId,
    val serverId: ServerId,
    val arenaId: ArenaId,
    val roster: MultiplayerRoster,
    val winners: Set<PlayerId>,
    val winningTeam: Int?,
    val eliminationOrder: List<PlayerId>,
    val completedAt: Instant,
    val endReason: MatchEndReason,
) {
    init {
        require(winners.isNotEmpty() && winners.all(roster.playerIds::contains)) { "Outcome winners must belong to the roster" }
        require(eliminationOrder.distinct().size == eliminationOrder.size) { "Outcome cannot eliminate a player twice" }
        require(eliminationOrder.all(roster.playerIds::contains)) { "Outcome elimination order contains an outsider" }
        require(winners.intersect(eliminationOrder.toSet()).isEmpty() || winningTeam != null) {
            "An FFA winner cannot also be eliminated"
        }
        require(endReason in setOf(MatchEndReason.ELIMINATION, MatchEndReason.OBJECTIVE, MatchEndReason.FORFEIT, MatchEndReason.DISCONNECT)) {
            "A completed multiplayer outcome requires a gameplay reason"
        }
    }
}

fun MultiplayerMatch.outcome(): MultiplayerMatchOutcome {
    require(state in setOf(MultiplayerMatchState.COMPLETING, MultiplayerMatchState.COMPLETED)) {
        "Only a completing multiplayer match has a durable outcome"
    }
    return MultiplayerMatchOutcome(
        matchId = id,
        serverId = serverId,
        arenaId = arenaId,
        roster = roster,
        winners = winners,
        winningTeam = winningTeam,
        eliminationOrder = eliminationOrder,
        completedAt = requireNotNull(completedAt),
        endReason = requireNotNull(endReason),
    )
}

fun interface MultiplayerMatchRepository {
    /** Stores one complete multiplayer result idempotently by match id. */
    fun record(outcome: MultiplayerMatchOutcome): CompletableFuture<Boolean>
}
