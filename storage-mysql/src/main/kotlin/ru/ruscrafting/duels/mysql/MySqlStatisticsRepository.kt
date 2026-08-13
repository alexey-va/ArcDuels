package ru.ruscrafting.duels.mysql

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlMigrationReport
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.LeaderboardEntry
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchOutcome
import ru.ruscrafting.duels.domain.PersistedMatchResult
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.PlayerStatistics
import ru.ruscrafting.duels.domain.RatingCalculator
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.StatisticsRepository
import ru.ruscrafting.duels.domain.validatePlayerName
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CompletableFuture

class MySqlStatisticsRepository(
    private val runtime: SqlRuntime,
) : StatisticsRepository, AutoCloseable {
    fun migrate(): CompletableFuture<SqlMigrationReport> =
        runtime.executor.submit {
            MySqlMigrator(runtime.dataSource, MIGRATION_NAMESPACE).migrate(MySqlDuelMigrations.all)
        }

    override fun rememberPlayerName(
        playerId: PlayerId,
        playerName: String,
    ): CompletableFuture<Unit> {
        val safeName = validatePlayerName(playerName)
        return runtime.executor.write { connection ->
            connection.prepareStatement(
                """
                INSERT INTO `rusduels_player_names` (`player_id`, `last_known_name`, `updated_at`)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE `last_known_name` = ?, `updated_at` = ?
                """.trimIndent(),
            ).use { statement ->
                val now = Timestamp.from(Instant.now())
                statement.setBytes(1, UuidBytes.encode(playerId.value))
                statement.setString(2, safeName)
                statement.setTimestamp(3, now)
                statement.setString(4, safeName)
                statement.setTimestamp(5, now)
                statement.executeUpdate()
            }
            Unit
        }
    }

    override fun find(playerId: PlayerId): CompletableFuture<PlayerStatistics> =
        runtime.executor.read { connection ->
            connection.prepareStatement("SELECT $STAT_COLUMNS FROM `rusduels_player_stats` WHERE `player_id` = ?").use { statement ->
                statement.setBytes(1, UuidBytes.encode(playerId.value))
                statement.executeQuery().use { result ->
                    if (result.next()) result.toStatistics(playerId) else PlayerStatistics(playerId)
                }
            }
        }

    override fun findPlayerName(playerId: PlayerId): CompletableFuture<String?> =
        runtime.executor.read { connection ->
            connection.prepareStatement(
                "SELECT `last_known_name` FROM `rusduels_player_names` WHERE `player_id` = ?",
            ).use { statement ->
                statement.setBytes(1, UuidBytes.encode(playerId.value))
                statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
            }
        }

    override fun record(outcome: MatchOutcome): CompletableFuture<PersistedMatchResult> =
        runtime.executor.transaction { connection -> recordTransaction(connection, outcome) }

    override fun leaderboard(limit: Int): CompletableFuture<List<LeaderboardEntry>> {
        require(limit in 1..100) { "Leaderboard limit must be between 1 and 100" }
        return runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT s.`player_id`, n.`last_known_name`, s.`rating`, s.`wins`, s.`losses`
                FROM `rusduels_player_stats` s
                LEFT JOIN `rusduels_player_names` n ON n.`player_id` = s.`player_id`
                ORDER BY s.`rating` DESC, s.`wins` DESC, s.`player_id` ASC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        var position = 1
                        while (result.next()) {
                            add(
                                LeaderboardEntry(
                                    position = position++,
                                    playerId = PlayerId(UuidBytes.decode(result.getBytes("player_id"))),
                                    playerName = result.getString("last_known_name"),
                                    rating = result.getInt("rating"),
                                    wins = result.getLong("wins"),
                                    losses = result.getLong("losses"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun close() = runtime.close()

    private fun recordTransaction(
        connection: Connection,
        outcome: MatchOutcome,
    ): PersistedMatchResult {
        listOf(outcome.winner, outcome.loser)
            .sortedBy { it.value }
            .forEach { ensurePlayer(connection, it, outcome.completedAt) }
        val locked = lockPlayers(connection, outcome.winner, outcome.loser)
        findReceipt(connection, outcome)?.let { return it }

        val winnerBefore = locked.getValue(outcome.winner)
        val loserBefore = locked.getValue(outcome.loser)
        val (winnerRating, loserRating) =
            if (outcome.ranked) {
                RatingCalculator.afterWin(winnerBefore.rating, loserBefore.rating)
            } else {
                winnerBefore.rating to loserBefore.rating
            }
        val winnerStreak = winnerBefore.currentWinStreak + 1
        val winnerAfter =
            winnerBefore.copy(
                wins = winnerBefore.wins + 1,
                currentWinStreak = winnerStreak,
                bestWinStreak = maxOf(winnerBefore.bestWinStreak, winnerStreak),
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
        updatePlayer(connection, winnerAfter, outcome.completedAt)
        updatePlayer(connection, loserAfter, outcome.completedAt)
        val leaderboardRevision = incrementLeaderboardRevision(connection)
        insertMatch(connection, outcome, winnerRating, loserRating, leaderboardRevision)
        return PersistedMatchResult(outcome, winnerRating, loserRating, leaderboardRevision, newlyRecorded = true)
    }

    private fun ensurePlayer(
        connection: Connection,
        playerId: PlayerId,
        now: Instant,
    ) {
        connection.prepareStatement(
            "INSERT IGNORE INTO `rusduels_player_stats` (`player_id`, `updated_at`) VALUES (?, ?)",
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(playerId.value))
            statement.setTimestamp(2, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun lockPlayers(
        connection: Connection,
        first: PlayerId,
        second: PlayerId,
    ): Map<PlayerId, PlayerStatistics> =
        connection.prepareStatement(
            """
            SELECT `player_id`, $STAT_COLUMNS
            FROM `rusduels_player_stats`
            WHERE `player_id` IN (?, ?)
            ORDER BY `player_id`
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(first.value))
            statement.setBytes(2, UuidBytes.encode(second.value))
            statement.executeQuery().use { result ->
                buildMap {
                    while (result.next()) {
                        val playerId = PlayerId(UuidBytes.decode(result.getBytes("player_id")))
                        put(playerId, result.toStatistics(playerId))
                    }
                }.also { check(it.size == 2) { "Could not lock both player statistics rows" } }
            }
        }

    private fun updatePlayer(
        connection: Connection,
        statistics: PlayerStatistics,
        now: Instant,
    ) {
        connection.prepareStatement(
            """
            UPDATE `rusduels_player_stats`
            SET `wins` = ?, `losses` = ?, `current_win_streak` = ?, `best_win_streak` = ?,
                `rating` = ?, `revision` = ?, `updated_at` = ?
            WHERE `player_id` = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, statistics.wins)
            statement.setLong(2, statistics.losses)
            statement.setInt(3, statistics.currentWinStreak)
            statement.setInt(4, statistics.bestWinStreak)
            statement.setInt(5, statistics.rating)
            statement.setLong(6, statistics.revision)
            statement.setTimestamp(7, Timestamp.from(now))
            statement.setBytes(8, UuidBytes.encode(statistics.playerId.value))
            check(statement.executeUpdate() == 1) { "Player statistics row disappeared during update" }
        }
    }

    private fun incrementLeaderboardRevision(connection: Connection): Long {
        val current =
            connection.prepareStatement(
                "SELECT `value` FROM `rusduels_meta` WHERE `name` = 'leaderboard_revision' FOR UPDATE",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next()) { "Missing leaderboard revision metadata" }
                    result.getLong(1)
                }
            }
        val next = current + 1
        connection.prepareStatement(
            "UPDATE `rusduels_meta` SET `value` = ? WHERE `name` = 'leaderboard_revision'",
        ).use { statement ->
            statement.setLong(1, next)
            check(statement.executeUpdate() == 1) { "Could not increment leaderboard revision" }
        }
        return next
    }

    private fun insertMatch(
        connection: Connection,
        outcome: MatchOutcome,
        winnerRating: Int,
        loserRating: Int,
        leaderboardRevision: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO `rusduels_matches`
                (`match_id`, `winner_id`, `loser_id`, `mode`, `kit_id`, `ranked`, `server_id`, `completed_at`,
                 `winner_rating_after`, `loser_rating_after`, `leaderboard_revision`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(outcome.matchId.value))
            statement.setBytes(2, UuidBytes.encode(outcome.winner.value))
            statement.setBytes(3, UuidBytes.encode(outcome.loser.value))
            statement.setString(4, outcome.mode.name)
            statement.setString(5, outcome.kitId?.value)
            statement.setBoolean(6, outcome.ranked)
            statement.setString(7, outcome.serverId.value)
            statement.setTimestamp(8, Timestamp.from(outcome.completedAt))
            statement.setInt(9, winnerRating)
            statement.setInt(10, loserRating)
            statement.setLong(11, leaderboardRevision)
            check(statement.executeUpdate() == 1) { "Could not insert duel match result" }
        }
    }

    private fun findReceipt(
        connection: Connection,
        expected: MatchOutcome,
    ): PersistedMatchResult? =
        connection.prepareStatement(
            """
            SELECT `winner_id`, `loser_id`, `mode`, `kit_id`, `ranked`, `server_id`, `completed_at`,
                   `winner_rating_after`, `loser_rating_after`, `leaderboard_revision`
            FROM `rusduels_matches`
            WHERE `match_id` = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(expected.matchId.value))
            statement.executeQuery().use resultUse@ { result ->
                if (!result.next()) return@resultUse null
                val stored =
                    MatchOutcome(
                        matchId = expected.matchId,
                        winner = PlayerId(UuidBytes.decode(result.getBytes("winner_id"))),
                        loser = PlayerId(UuidBytes.decode(result.getBytes("loser_id"))),
                        mode = DuelMode.valueOf(result.getString("mode")),
                        kitId = result.getString("kit_id")?.let(::KitId),
                        ranked = result.getBoolean("ranked"),
                        serverId = ServerId(result.getString("server_id")),
                        completedAt = result.getTimestamp("completed_at").toInstant(),
                    )
                check(stored == expected) { "Match id collision with a different duel outcome" }
                PersistedMatchResult(
                    outcome = stored,
                    winnerRatingAfter = result.getInt("winner_rating_after"),
                    loserRatingAfter = result.getInt("loser_rating_after"),
                    leaderboardRevision = result.getLong("leaderboard_revision"),
                    newlyRecorded = false,
                )
            }
        }

    private fun ResultSet.toStatistics(playerId: PlayerId): PlayerStatistics =
        PlayerStatistics(
            playerId = playerId,
            wins = getLong("wins"),
            losses = getLong("losses"),
            currentWinStreak = getInt("current_win_streak"),
            bestWinStreak = getInt("best_win_streak"),
            rating = getInt("rating"),
            revision = getLong("revision"),
        )

    private companion object {
        const val MIGRATION_NAMESPACE = "rusduels"
        const val STAT_COLUMNS =
            "`wins`, `losses`, `current_win_streak`, `best_win_streak`, `rating`, `revision`"
    }
}
