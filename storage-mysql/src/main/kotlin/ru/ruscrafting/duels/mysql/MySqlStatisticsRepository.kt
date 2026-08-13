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
import ru.ruscrafting.duels.domain.PlayerStateEscrow
import ru.ruscrafting.duels.domain.PlayerStateEscrowRepository
import ru.ruscrafting.duels.domain.PlayerStatistics
import ru.ruscrafting.duels.domain.RatingCalculator
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.StatisticsRepository
import ru.ruscrafting.duels.domain.validatePlayerName
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class MySqlStatisticsRepository(
    private val runtime: SqlRuntime,
) : StatisticsRepository, PlayerStateEscrowRepository, AutoCloseable {
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
                INSERT INTO `arcduels_player_names` (`player_id`, `last_known_name`, `updated_at`)
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
            connection.prepareStatement("SELECT $STAT_COLUMNS FROM `arcduels_player_stats` WHERE `player_id` = ?").use { statement ->
                statement.setBytes(1, UuidBytes.encode(playerId.value))
                statement.executeQuery().use { result ->
                    if (result.next()) result.toStatistics(playerId) else PlayerStatistics(playerId)
                }
            }
        }

    override fun findPlayerName(playerId: PlayerId): CompletableFuture<String?> =
        runtime.executor.read { connection ->
            connection.prepareStatement(
                "SELECT `last_known_name` FROM `arcduels_player_names` WHERE `player_id` = ?",
            ).use { statement ->
                statement.setBytes(1, UuidBytes.encode(playerId.value))
                statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
            }
        }

    override fun record(outcome: MatchOutcome): CompletableFuture<PersistedMatchResult> =
        recordWithRetry(outcome.canonicalized(), attempt = 0)

    override fun leaderboard(limit: Int): CompletableFuture<List<LeaderboardEntry>> {
        require(limit in 1..100) { "Leaderboard limit must be between 1 and 100" }
        return runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT s.`player_id`, n.`last_known_name`, s.`rating`, s.`wins`, s.`losses`
                FROM `arcduels_player_stats` s
                LEFT JOIN `arcduels_player_names` n ON n.`player_id` = s.`player_id`
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

    override fun savePair(
        first: PlayerStateEscrow,
        second: PlayerStateEscrow,
    ): CompletableFuture<Unit> {
        require(first.playerId != second.playerId) { "Escrow participants must be different players" }
        require(first.matchId == second.matchId) { "Escrow participants must belong to the same match" }
        require(first.serverId == second.serverId) { "Escrow participants must belong to the same server" }
        validateEscrow(first)
        validateEscrow(second)
        return savePairWithRetry(listOf(first, second).sortedBy { it.playerId.value }, attempt = 0)
    }

    override fun findPending(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> =
        runtime.executor.read { connection -> findEscrow(connection, playerId, lock = false) }

    override fun pending(serverId: ServerId): CompletableFuture<List<PlayerStateEscrow>> =
        runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT `player_id`, `match_id`, `server_id`, `format_version`, `payload`, `payload_sha256`, `created_at`
                FROM `arcduels_player_state_escrow`
                WHERE `server_id` = ?
                ORDER BY `created_at`, `player_id`
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, serverId.value)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toEscrow()) } }
            }
        }

    override fun acknowledgeRestored(snapshot: PlayerStateEscrow): CompletableFuture<Boolean> =
        runtime.executor.transaction { connection ->
            connection.prepareStatement(
                """
                DELETE FROM `arcduels_player_state_escrow`
                WHERE `player_id` = ? AND `match_id` = ? AND `payload_sha256` = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, UuidBytes.encode(snapshot.playerId.value))
                statement.setBytes(2, UuidBytes.encode(snapshot.matchId.value))
                statement.setBytes(3, snapshot.checksum)
                when (statement.executeUpdate()) {
                    1 -> true
                    0 -> findEscrow(connection, snapshot.playerId, lock = true) == null
                    else -> error("Escrow acknowledgement affected more than one row")
                }
            }
        }

    override fun close() = runtime.close()

    private fun savePairWithRetry(
        snapshots: List<PlayerStateEscrow>,
        attempt: Int,
    ): CompletableFuture<Unit> =
        runtime.executor.transaction { connection -> savePairTransaction(connection, snapshots) }
            .handle { result, failure ->
                if (failure == null) {
                    CompletableFuture.completedFuture(result)
                } else {
                    val cause = failure.unwrapCompletion()
                    if (attempt < MAX_TRANSACTION_RETRIES && cause.isRetryableMySqlTransactionFailure()) {
                        CompletableFuture.runAsync(
                            {},
                            CompletableFuture.delayedExecutor(RETRY_BASE_DELAY_MS shl attempt, TimeUnit.MILLISECONDS),
                        ).thenCompose { savePairWithRetry(snapshots, attempt + 1) }
                    } else {
                        CompletableFuture.failedFuture(cause)
                    }
                }
            }.thenCompose { it }

    private fun savePairTransaction(
        connection: Connection,
        snapshots: List<PlayerStateEscrow>,
    ) {
        val existing =
            connection.prepareStatement(
                """
                SELECT `player_id`, `match_id`, `server_id`, `format_version`, `payload`, `payload_sha256`, `created_at`
                FROM `arcduels_player_state_escrow`
                WHERE `player_id` IN (?, ?)
                ORDER BY `player_id`
                FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, UuidBytes.encode(snapshots[0].playerId.value))
                statement.setBytes(2, UuidBytes.encode(snapshots[1].playerId.value))
                statement.executeQuery().use { result ->
                    buildMap {
                        while (result.next()) {
                            val escrow = result.toEscrow()
                            put(escrow.playerId, escrow)
                        }
                    }
                }
            }
        for (snapshot in snapshots) {
            existing[snapshot.playerId]?.let { stored ->
                check(stored.sameContent(snapshot)) {
                    "Player ${snapshot.playerId} already has a different pending state escrow"
                }
            } ?: insertEscrow(connection, snapshot)
        }
        for (snapshot in snapshots) {
            val committed = checkNotNull(findEscrow(connection, snapshot.playerId, lock = false))
            check(committed.sameContent(snapshot)) { "Committed escrow verification failed for ${snapshot.playerId}" }
            validateEscrow(committed)
        }
    }

    private fun insertEscrow(
        connection: Connection,
        snapshot: PlayerStateEscrow,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO `arcduels_player_state_escrow`
                (`player_id`, `match_id`, `server_id`, `format_version`, `payload`, `payload_sha256`, `created_at`)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(snapshot.playerId.value))
            statement.setBytes(2, UuidBytes.encode(snapshot.matchId.value))
            statement.setString(3, snapshot.serverId.value)
            statement.setInt(4, snapshot.formatVersion)
            statement.setBytes(5, snapshot.payload)
            statement.setBytes(6, snapshot.checksum)
            statement.setTimestamp(7, Timestamp.from(snapshot.createdAt))
            check(statement.executeUpdate() == 1) { "Could not insert player state escrow" }
        }
    }

    private fun findEscrow(
        connection: Connection,
        playerId: PlayerId,
        lock: Boolean,
    ): PlayerStateEscrow? =
        connection.prepareStatement(
            """
            SELECT `player_id`, `match_id`, `server_id`, `format_version`, `payload`, `payload_sha256`, `created_at`
            FROM `arcduels_player_state_escrow`
            WHERE `player_id` = ?${if (lock) " FOR UPDATE" else ""}
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(playerId.value))
            statement.executeQuery().use { result -> if (result.next()) result.toEscrow() else null }
        }

    private fun ResultSet.toEscrow(): PlayerStateEscrow =
        PlayerStateEscrow(
            playerId = PlayerId(UuidBytes.decode(getBytes("player_id"))),
            matchId = MatchId(UuidBytes.decode(getBytes("match_id"))),
            serverId = ServerId(getString("server_id")),
            formatVersion = getInt("format_version"),
            payload = getBytes("payload"),
            checksum = getBytes("payload_sha256"),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun validateEscrow(snapshot: PlayerStateEscrow) {
        require(snapshot.payload.size <= MAX_ESCROW_PAYLOAD_BYTES) { "Escrow payload exceeds the 8 MiB safety limit" }
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(snapshot.payload), snapshot.checksum)) {
            "Escrow payload checksum does not match its contents"
        }
    }

    private fun recordWithRetry(
        outcome: MatchOutcome,
        attempt: Int,
    ): CompletableFuture<PersistedMatchResult> =
        runtime.executor.transaction { connection -> recordTransaction(connection, outcome) }
            .handle { result, failure ->
                if (failure == null) {
                    CompletableFuture.completedFuture(result)
                } else {
                    val cause = failure.unwrapCompletion()
                    if (attempt < MAX_TRANSACTION_RETRIES && cause.isRetryableMySqlTransactionFailure()) {
                        CompletableFuture.runAsync(
                            {},
                            CompletableFuture.delayedExecutor(RETRY_BASE_DELAY_MS shl attempt, TimeUnit.MILLISECONDS),
                        ).thenCompose { recordWithRetry(outcome, attempt + 1) }
                    } else {
                        CompletableFuture.failedFuture(cause)
                    }
                }
            }.thenCompose { it }

    private fun recordTransaction(
        connection: Connection,
        outcome: MatchOutcome,
    ): PersistedMatchResult {
        ensurePlayers(connection, outcome.winner, outcome.loser, outcome.completedAt)
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

    private fun ensurePlayers(
        connection: Connection,
        first: PlayerId,
        second: PlayerId,
        now: Instant,
    ) {
        val players = listOf(first, second).sortedBy { it.value }
        connection.prepareStatement(
            "INSERT IGNORE INTO `arcduels_player_stats` (`player_id`, `updated_at`) VALUES (?, ?), (?, ?)",
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(players[0].value))
            statement.setTimestamp(2, Timestamp.from(now))
            statement.setBytes(3, UuidBytes.encode(players[1].value))
            statement.setTimestamp(4, Timestamp.from(now))
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
            FROM `arcduels_player_stats`
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
            UPDATE `arcduels_player_stats`
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
                "SELECT `value` FROM `arcduels_meta` WHERE `name` = 'leaderboard_revision' FOR UPDATE",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    check(result.next()) { "Missing leaderboard revision metadata" }
                    result.getLong(1)
                }
            }
        val next = current + 1
        connection.prepareStatement(
            "UPDATE `arcduels_meta` SET `value` = ? WHERE `name` = 'leaderboard_revision'",
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
            INSERT INTO `arcduels_matches`
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
            FROM `arcduels_matches`
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
        const val MAX_TRANSACTION_RETRIES = 4
        const val RETRY_BASE_DELAY_MS = 10L
        const val MAX_ESCROW_PAYLOAD_BYTES = 8 * 1024 * 1024
        const val MIGRATION_NAMESPACE = "arcduels"
        const val STAT_COLUMNS =
            "`wins`, `losses`, `current_win_streak`, `best_win_streak`, `rating`, `revision`"
    }
}

internal fun Throwable.isRetryableMySqlTransactionFailure(): Boolean =
    generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any { failure -> failure.sqlState == "40001" || failure.errorCode == 1_213 || failure.errorCode == 1_205 }

private fun Throwable.unwrapCompletion(): Throwable {
    var current = this
    while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
        current = requireNotNull(current.cause)
    }
    return current
}
