package ru.ruscrafting.duels.domain

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class InMemoryStatisticsRepository : StatisticsRepository, DuelPresetRepository, MultiplayerMatchRepository {
    private val statistics = ConcurrentHashMap<PlayerId, PlayerStatistics>()
    private val playerNames = ConcurrentHashMap<PlayerId, String>()
    private val recordedMatches = ConcurrentHashMap<MatchId, PersistedMatchResult>()
    private val savedPresets = ConcurrentHashMap<Pair<PlayerId, Int>, DuelPreset>()
    private val multiplayerMatches = ConcurrentHashMap<MatchId, MultiplayerMatchOutcome>()
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
        val canonicalOutcome = outcome.canonicalized()
        val result =
            synchronized(writeLock) {
                recordedMatches[canonicalOutcome.matchId]?.let { recorded ->
                    check(recorded.outcome == canonicalOutcome) { "Match id collision with a different duel outcome" }
                    return@synchronized recorded.copy(newlyRecorded = false)
                }
                val winnerBefore = statistics[canonicalOutcome.winner] ?: PlayerStatistics(canonicalOutcome.winner)
                val loserBefore = statistics[canonicalOutcome.loser] ?: PlayerStatistics(canonicalOutcome.loser)
                val (winnerRating, loserRating) =
                    if (canonicalOutcome.ranked) {
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
                statistics[canonicalOutcome.winner] = winnerAfter
                statistics[canonicalOutcome.loser] = loserAfter
                val nextLeaderboardRevision = ++leaderboardRevision
                PersistedMatchResult(
                    outcome = canonicalOutcome,
                    winnerRatingAfter = winnerAfter.rating,
                    loserRatingAfter = loserAfter.rating,
                    leaderboardRevision = nextLeaderboardRevision,
                    newlyRecorded = true,
                )
                    .also { recordedMatches[canonicalOutcome.matchId] = it }
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

    override fun findMatch(matchId: MatchId): CompletableFuture<RecordedMatch?> =
        CompletableFuture.completedFuture(
            synchronized(writeLock) { recordedMatches[matchId]?.toRecordedMatch() },
        )

    override fun recentMatches(
        playerId: PlayerId,
        limit: Int,
    ): CompletableFuture<List<RecordedMatch>> {
        require(limit in 1..100) { "Match history limit must be between 1 and 100" }
        val matches =
            synchronized(writeLock) {
                recordedMatches.values
                    .asSequence()
                    .filter { it.outcome.winner == playerId || it.outcome.loser == playerId }
                    .sortedWith(compareByDescending<PersistedMatchResult> { it.outcome.completedAt }.thenBy { it.outcome.matchId.toString() })
                    .take(limit)
                    .map { it.toRecordedMatch() }
                    .toList()
            }
        return CompletableFuture.completedFuture(matches)
    }

    override fun headToHead(
        firstPlayer: PlayerId,
        secondPlayer: PlayerId,
    ): CompletableFuture<HeadToHeadRecord> {
        require(firstPlayer != secondPlayer) { "Head-to-head players must be different" }
        val outcomes =
            synchronized(writeLock) {
                recordedMatches.values.map(PersistedMatchResult::outcome).filter { outcome ->
                    (outcome.winner == firstPlayer && outcome.loser == secondPlayer) ||
                        (outcome.winner == secondPlayer && outcome.loser == firstPlayer)
                }
            }
        return CompletableFuture.completedFuture(
            HeadToHeadRecord(
                firstPlayer = firstPlayer,
                secondPlayer = secondPlayer,
                firstWins = outcomes.count { it.winner == firstPlayer }.toLong(),
                secondWins = outcomes.count { it.winner == secondPlayer }.toLong(),
                lastCompletedAt = outcomes.maxOfOrNull(MatchOutcome::completedAt),
            ),
        )
    }

    override fun presets(playerId: PlayerId): CompletableFuture<List<DuelPreset>> =
        CompletableFuture.completedFuture(
            synchronized(writeLock) {
                savedPresets.values.filter { it.playerId == playerId }.sortedBy(DuelPreset::slot)
            },
        )

    override fun savePreset(preset: DuelPreset): CompletableFuture<Unit> {
        val canonical = preset.copy(updatedAt = preset.updatedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
        synchronized(writeLock) { savedPresets[canonical.playerId to canonical.slot] = canonical }
        return CompletableFuture.completedFuture(Unit)
    }

    override fun deletePreset(
        playerId: PlayerId,
        slot: Int,
    ): CompletableFuture<Boolean> {
        require(slot in 1..MAX_DUEL_PRESETS) { "Preset slot must be between 1 and $MAX_DUEL_PRESETS" }
        return CompletableFuture.completedFuture(synchronized(writeLock) { savedPresets.remove(playerId to slot) != null })
    }

    override fun record(outcome: MultiplayerMatchOutcome): CompletableFuture<Boolean> {
        var newlyRecorded = false
        multiplayerMatches.compute(outcome.matchId) { _, existing ->
            check(existing == null || existing == outcome) { "Match id collision with a different multiplayer outcome" }
            newlyRecorded = existing == null
            outcome
        }
        return CompletableFuture.completedFuture(newlyRecorded)
    }

    private fun PersistedMatchResult.toRecordedMatch(): RecordedMatch =
        RecordedMatch(
            outcome,
            winnerRatingAfter,
            loserRatingAfter,
            playerNames[outcome.winner],
            playerNames[outcome.loser],
        )
}
