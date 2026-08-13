package ru.ruscrafting.duels.domain

import java.time.Instant

enum class MatchState {
    RESERVED,
    COUNTDOWN,
    ACTIVE,
    COMPLETING,
    COMPLETED,
    CANCELLED,
}

enum class MatchEndReason {
    ELIMINATION,
    FORFEIT,
    DISCONNECT,
    ADMIN_CANCEL,
    SERVER_SHUTDOWN,
}

data class MatchScore(
    val first: Int = 0,
    val second: Int = 0,
) {
    init {
        require(first >= 0 && second >= 0) { "Match score cannot be negative" }
    }

    fun winFor(
        player: PlayerId,
        firstPlayer: PlayerId,
        secondPlayer: PlayerId,
    ): MatchScore =
        when (player) {
            firstPlayer -> copy(first = first + 1)
            secondPlayer -> copy(second = second + 1)
            else -> error("Winner is not a match participant")
        }
}

data class DuelMatch(
    val id: MatchId,
    val firstPlayer: PlayerId,
    val secondPlayer: PlayerId,
    val arenaId: ArenaId,
    val serverId: ServerId,
    val rules: DuelRules,
    val state: MatchState,
    val score: MatchScore,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val winner: PlayerId? = null,
    val endReason: MatchEndReason? = null,
) {
    init {
        require(firstPlayer != secondPlayer) { "A match requires two distinct players" }
        require(winner == null || winner == firstPlayer || winner == secondPlayer) { "Winner is not a participant" }
    }

    fun beginCountdown(): DuelMatch {
        require(state == MatchState.RESERVED) { "Only a reserved match can begin countdown" }
        return copy(state = MatchState.COUNTDOWN)
    }

    fun activate(now: Instant): DuelMatch {
        require(state == MatchState.COUNTDOWN) { "Only a countdown match can activate" }
        return copy(state = MatchState.ACTIVE, startedAt = now)
    }

    fun recordRoundWinner(
        player: PlayerId,
        now: Instant,
        reason: MatchEndReason = MatchEndReason.ELIMINATION,
    ): DuelMatch {
        require(state == MatchState.ACTIVE) { "Only an active match can record a round" }
        require(reason == MatchEndReason.ELIMINATION) { "Round completion requires an elimination" }
        val nextScore = score.winFor(player, firstPlayer, secondPlayer)
        val wonMatch =
            when (player) {
                firstPlayer -> nextScore.first >= rules.roundsToWin
                secondPlayer -> nextScore.second >= rules.roundsToWin
                else -> error("Winner is not a match participant")
            }
        return if (wonMatch) {
            copy(
                state = MatchState.COMPLETING,
                score = nextScore,
                winner = player,
                completedAt = now,
                endReason = reason,
            )
        } else {
            copy(state = MatchState.COUNTDOWN, score = nextScore)
        }
    }

    fun markPersisted(): DuelMatch {
        require(state == MatchState.COMPLETING) { "Only a completing match can be finalized" }
        return copy(state = MatchState.COMPLETED)
    }

    fun forfeit(
        losingPlayer: PlayerId,
        now: Instant,
        reason: MatchEndReason,
    ): DuelMatch {
        require(state in setOf(MatchState.RESERVED, MatchState.COUNTDOWN, MatchState.ACTIVE)) {
            "Only a pending or active match can be forfeited"
        }
        require(reason == MatchEndReason.FORFEIT || reason == MatchEndReason.DISCONNECT) {
            "Forfeit requires a player-driven end reason"
        }
        val winningPlayer = opponentOf(losingPlayer)
        val finalScore =
            if (winningPlayer == firstPlayer) {
                score.copy(first = rules.roundsToWin)
            } else {
                score.copy(second = rules.roundsToWin)
            }
        return copy(
            state = MatchState.COMPLETING,
            score = finalScore,
            winner = winningPlayer,
            completedAt = now,
            endReason = reason,
        )
    }

    fun cancel(
        now: Instant,
        reason: MatchEndReason,
    ): DuelMatch {
        require(state !in TERMINAL_STATES && state != MatchState.COMPLETING) { "Match can no longer be cancelled" }
        require(reason == MatchEndReason.ADMIN_CANCEL || reason == MatchEndReason.SERVER_SHUTDOWN) {
            "Cancellation requires an administrative reason"
        }
        return copy(state = MatchState.CANCELLED, completedAt = now, endReason = reason)
    }

    fun opponentOf(player: PlayerId): PlayerId =
        when (player) {
            firstPlayer -> secondPlayer
            secondPlayer -> firstPlayer
            else -> error("Player is not a match participant")
        }

    companion object {
        private val TERMINAL_STATES = setOf(MatchState.COMPLETED, MatchState.CANCELLED)

        fun reserve(
            firstPlayer: PlayerId,
            secondPlayer: PlayerId,
            arenaId: ArenaId,
            serverId: ServerId,
            rules: DuelRules,
            now: Instant,
        ): DuelMatch =
            DuelMatch(
                id = MatchId.random(),
                firstPlayer = firstPlayer,
                secondPlayer = secondPlayer,
                arenaId = arenaId,
                serverId = serverId,
                rules = rules,
                state = MatchState.RESERVED,
                score = MatchScore(),
                createdAt = now,
            )
    }
}
