package ru.ruscrafting.duels.domain

import java.time.Instant
import java.util.concurrent.CompletableFuture

data class PlayerStatistics(
    val playerId: PlayerId,
    val wins: Long = 0,
    val losses: Long = 0,
    val currentWinStreak: Int = 0,
    val bestWinStreak: Int = 0,
    val rating: Int = RatingCalculator.DEFAULT_RATING,
    val revision: Long = 0,
) {
    init {
        require(wins >= 0 && losses >= 0) { "Win and loss counts cannot be negative" }
        require(currentWinStreak >= 0 && bestWinStreak >= currentWinStreak) { "Invalid win streak values" }
        require(rating in 0..RatingCalculator.MAX_RATING) { "Rating is outside the supported range" }
        require(revision >= 0) { "Statistics revision cannot be negative" }
    }

    val matches: Long get() = wins + losses
    val winRate: Double get() = if (matches == 0L) 0.0 else wins.toDouble() / matches
}

data class LeaderboardEntry(
    val position: Int,
    val playerId: PlayerId,
    val playerName: String?,
    val rating: Int,
    val wins: Long,
    val losses: Long,
)

data class MatchOutcome(
    val matchId: MatchId,
    val winner: PlayerId,
    val loser: PlayerId,
    val mode: DuelMode,
    val kitId: KitId?,
    val ranked: Boolean,
    val serverId: ServerId,
    val completedAt: Instant,
    val objective: DuelObjectiveType = DuelObjectiveType.ELIMINATION,
    val arenaId: ArenaId? = null,
    val bestOf: Int = 1,
    val modifiers: CombatModifiers = CombatModifiers(),
    val winnerScore: Int = bestOf / 2 + 1,
    val loserScore: Int = 0,
    val endReason: MatchEndReason = MatchEndReason.ELIMINATION,
) {
    init {
        require(winner != loser) { "Winner and loser must be different players" }
        DuelRules(mode, kitId, ranked, bestOf, objective, modifiers)
        require(winnerScore >= bestOf / 2 + 1) { "Winner score must complete the selected series" }
        require(loserScore in 0 until winnerScore) { "Loser score must be non-negative and below the winner score" }
        require(endReason in COMPLETED_REASONS) { "Match history cannot contain a cancelled match" }
    }

    val rules: DuelRules
        get() = DuelRules(mode, kitId, ranked, bestOf, objective, modifiers)

    /** MySQL stores timestamps at millisecond precision; all adapters share that canonical form. */
    fun canonicalized(): MatchOutcome =
        copy(completedAt = completedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))

    private companion object {
        val COMPLETED_REASONS =
            setOf(
                MatchEndReason.ELIMINATION,
                MatchEndReason.OBJECTIVE,
                MatchEndReason.FORFEIT,
                MatchEndReason.DISCONNECT,
            )
    }
}

data class PersistedMatchResult(
    val outcome: MatchOutcome,
    val winnerRatingAfter: Int,
    val loserRatingAfter: Int,
    val leaderboardRevision: Long,
    val newlyRecorded: Boolean,
)

data class RecordedMatch(
    val outcome: MatchOutcome,
    val winnerRatingAfter: Int,
    val loserRatingAfter: Int,
    val winnerName: String? = null,
    val loserName: String? = null,
) {
    init {
        require(winnerRatingAfter in 0..RatingCalculator.MAX_RATING) { "Winner rating is outside the supported range" }
        require(loserRatingAfter in 0..RatingCalculator.MAX_RATING) { "Loser rating is outside the supported range" }
    }

    fun opponentOf(playerId: PlayerId): PlayerId =
        when (playerId) {
            outcome.winner -> outcome.loser
            outcome.loser -> outcome.winner
            else -> error("Player is not a participant of match ${outcome.matchId}")
        }

    fun opponentNameOf(playerId: PlayerId): String? =
        when (playerId) {
            outcome.winner -> loserName
            outcome.loser -> winnerName
            else -> error("Player is not a participant of match ${outcome.matchId}")
        }

    fun wonBy(playerId: PlayerId): Boolean {
        require(playerId == outcome.winner || playerId == outcome.loser) { "Player is not a match participant" }
        return playerId == outcome.winner
    }

    fun ratingAfter(playerId: PlayerId): Int =
        when (playerId) {
            outcome.winner -> winnerRatingAfter
            outcome.loser -> loserRatingAfter
            else -> error("Player is not a participant of match ${outcome.matchId}")
        }

    fun scoreFor(playerId: PlayerId): Pair<Int, Int> =
        if (wonBy(playerId)) outcome.winnerScore to outcome.loserScore else outcome.loserScore to outcome.winnerScore
}

data class HeadToHeadRecord(
    val firstPlayer: PlayerId,
    val secondPlayer: PlayerId,
    val firstWins: Long,
    val secondWins: Long,
    val lastCompletedAt: Instant?,
) {
    init {
        require(firstPlayer != secondPlayer) { "Head-to-head players must be different" }
        require(firstWins >= 0 && secondWins >= 0) { "Head-to-head wins cannot be negative" }
    }

    val matches: Long get() = firstWins + secondWins

    fun winsFor(playerId: PlayerId): Long =
        when (playerId) {
            firstPlayer -> firstWins
            secondPlayer -> secondWins
            else -> error("Player is not part of this head-to-head record")
        }
}

interface StatisticsRepository {
    fun rememberPlayerName(
        playerId: PlayerId,
        playerName: String,
    ): CompletableFuture<Unit>

    fun findPlayerName(playerId: PlayerId): CompletableFuture<String?>

    fun find(playerId: PlayerId): CompletableFuture<PlayerStatistics>

    /** Must be idempotent by match id. */
    fun record(outcome: MatchOutcome): CompletableFuture<PersistedMatchResult>

    fun leaderboard(limit: Int): CompletableFuture<List<LeaderboardEntry>>

    fun findMatch(matchId: MatchId): CompletableFuture<RecordedMatch?>

    fun recentMatches(
        playerId: PlayerId,
        limit: Int,
    ): CompletableFuture<List<RecordedMatch>>

    fun headToHead(
        firstPlayer: PlayerId,
        secondPlayer: PlayerId,
    ): CompletableFuture<HeadToHeadRecord>
}

fun validatePlayerName(playerName: String): String {
    require(playerName.isNotBlank()) { "Player name must not be blank" }
    require(playerName.length <= 32) { "Player name must not exceed 32 characters" }
    require(playerName.none(Char::isISOControl)) { "Player name must not contain control characters" }
    return playerName
}

object RatingCalculator {
    const val DEFAULT_RATING: Int = 1_000
    const val MAX_RATING: Int = 10_000_000
    private const val K_FACTOR = 32.0

    fun afterWin(
        winnerRating: Int,
        loserRating: Int,
    ): Pair<Int, Int> {
        require(winnerRating in 0..MAX_RATING && loserRating in 0..MAX_RATING) {
            "Ratings are outside the supported range"
        }
        val expectedWinner = 1.0 / (1.0 + Math.pow(10.0, (loserRating - winnerRating) / 400.0))
        val delta = kotlin.math.max(1, kotlin.math.round(K_FACTOR * (1.0 - expectedWinner)).toInt())
        val nextWinner = kotlin.math.min(MAX_RATING, winnerRating + delta)
        return nextWinner to kotlin.math.max(0, loserRating - delta)
    }
}
