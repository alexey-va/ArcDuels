package ru.ruscrafting.duels.domain

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class InMemoryStatisticsRepository : StatisticsRepository {
    private val statistics = ConcurrentHashMap<PlayerId, PlayerStatistics>()
    private val playerNames = ConcurrentHashMap<PlayerId, String>()
    private val recordedMatches = ConcurrentHashMap<MatchId, PersistedMatchResult>()
    private val writeLock = Any()
    private var leaderboardRevision = 0L

    override fun rememberPlayerName(
        playerId: PlayerId,
        playerName: String,
    ): CompletableFuture<Unit> {
        playerNames[playerId] = validatePlayerName(playerName)
        return CompletableFuture.completedFuture(Unit)
    }

    override fun find(playerId: PlayerId): CompletableFuture<PlayerStatistics> =
        CompletableFuture.completedFuture(
            synchronized(writeLock) { statistics[playerId] ?: PlayerStatistics(playerId) },
        )

    override fun findPlayerName(playerId: PlayerId): CompletableFuture<String?> =
        CompletableFuture.completedFuture(playerNames[playerId])

    override fun record(outcome: MatchOutcome): CompletableFuture<PersistedMatchResult> {
        val result =
            synchronized(writeLock) {
                recordedMatches[outcome.matchId]?.let { recorded ->
                    check(recorded.outcome == outcome) { "Match id collision with a different duel outcome" }
                    return@synchronized recorded.copy(newlyRecorded = false)
                }
                val winnerBefore = statistics[outcome.winner] ?: PlayerStatistics(outcome.winner)
                val loserBefore = statistics[outcome.loser] ?: PlayerStatistics(outcome.loser)
                val (winnerRating, loserRating) =
                    if (outcome.ranked) {
                        RatingCalculator.afterWin(winnerBefore.rating, loserBefore.rating)
                    } else {
                        winnerBefore.rating to loserBefore.rating
                    }
                val streak = winnerBefore.currentWinStreak + 1
                val winnerAfter =
                    winnerBefore.copy(
                        wins = winnerBefore.wins + 1,
                        currentWinStreak = streak,
                        bestWinStreak = maxOf(winnerBefore.bestWinStreak, streak),
                        rating = winnerRating,
                        revision = winnerBefore.revision + 1,
                    )
                val loserAfter =
                    loserBefore.copy(
                        losses = loserBefore.losses + 1,
                        currentWinStreak = 0,
                        rating = loserRating,
                        revision = loserBefore.revision + 1,
                    )
                statistics[outcome.winner] = winnerAfter
                statistics[outcome.loser] = loserAfter
                val nextLeaderboardRevision = ++leaderboardRevision
                PersistedMatchResult(
                    outcome = outcome,
                    winnerRatingAfter = winnerAfter.rating,
                    loserRatingAfter = loserAfter.rating,
                    leaderboardRevision = nextLeaderboardRevision,
                    newlyRecorded = true,
                )
                    .also { recordedMatches[outcome.matchId] = it }
            }
        return CompletableFuture.completedFuture(result)
    }

    override fun leaderboard(limit: Int): CompletableFuture<List<LeaderboardEntry>> {
        require(limit in 1..100) { "Leaderboard limit must be between 1 and 100" }
        val entries =
            synchronized(writeLock) {
                statistics.values
                    .sortedWith(
                        compareByDescending<PlayerStatistics> { it.rating }
                            .thenByDescending { it.wins }
                            .thenBy { it.playerId.toString() },
                    )
                    .take(limit)
                    .mapIndexed { index, stats ->
                        LeaderboardEntry(
                            position = index + 1,
                            playerId = stats.playerId,
                            playerName = playerNames[stats.playerId],
                            rating = stats.rating,
                            wins = stats.wins,
                            losses = stats.losses,
                        )
                    }
            }
        return CompletableFuture.completedFuture(entries)
    }
}
