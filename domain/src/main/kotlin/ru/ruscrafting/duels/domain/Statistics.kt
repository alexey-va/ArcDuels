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
) {
    init {
        require(winner != loser) { "Winner and loser must be different players" }
        require((mode == DuelMode.KIT) == (kitId != null)) { "Outcome mode and kit do not agree" }
        require(!ranked || mode == DuelMode.KIT) { "Ranked outcome must use a kit" }
    }

    /** MySQL stores timestamps at millisecond precision; all adapters share that canonical form. */
    fun canonicalized(): MatchOutcome =
        copy(completedAt = completedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
}

data class PersistedMatchResult(
    val outcome: MatchOutcome,
    val winnerRatingAfter: Int,
    val loserRatingAfter: Int,
    val leaderboardRevision: Long,
    val newlyRecorded: Boolean,
)

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
