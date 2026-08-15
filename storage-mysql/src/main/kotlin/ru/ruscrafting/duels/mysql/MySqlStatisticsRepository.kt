package ru.ruscrafting.duels.mysql

import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlMigrationReport
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelPreset
import ru.ruscrafting.duels.domain.DuelPresetRepository
import ru.ruscrafting.duels.domain.CombatModifiers
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.HeadToHeadRecord
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.LeaderboardEntry
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchOutcome
import ru.ruscrafting.duels.domain.MatchEndReason
import ru.ruscrafting.duels.domain.RecordedMatch
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
) : StatisticsRepository, DuelPresetRepository, PlayerStateEscrowRepository, AutoCloseable {
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

    override fun findMatch(matchId: MatchId): CompletableFuture<RecordedMatch?> =
        runtime.executor.read { connection -> findRecordedMatch(connection, matchId) }

    override fun recentMatches(
        playerId: PlayerId,
        limit: Int,
    ): CompletableFuture<List<RecordedMatch>> {
        require(limit in 1..100) { "Match history limit must be between 1 and 100" }
        return runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT $MATCH_COLUMNS, wn.`last_known_name` AS `winner_name`, ln.`last_known_name` AS `loser_name`
                FROM `arcduels_matches` m
                LEFT JOIN `arcduels_player_names` wn ON wn.`player_id` = m.`winner_id`
                LEFT JOIN `arcduels_player_names` ln ON ln.`player_id` = m.`loser_id`
                WHERE m.`winner_id` = ? OR m.`loser_id` = ?
                ORDER BY m.`completed_at` DESC, m.`match_id` ASC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                val encoded = UuidBytes.encode(playerId.value)
                statement.setBytes(1, encoded)
                statement.setBytes(2, encoded)
                statement.setInt(3, limit)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toRecordedMatch()) } }
            }
        }
    }

    override fun headToHead(
        firstPlayer: PlayerId,
        secondPlayer: PlayerId,
    ): CompletableFuture<HeadToHeadRecord> {
        require(firstPlayer != secondPlayer) { "Head-to-head players must be different" }
        return runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT
                    COALESCE(SUM(CASE WHEN `winner_id` = ? THEN 1 ELSE 0 END), 0) AS `first_wins`,
                    COALESCE(SUM(CASE WHEN `winner_id` = ? THEN 1 ELSE 0 END), 0) AS `second_wins`,
                    MAX(`completed_at`) AS `last_completed_at`
                FROM `arcduels_matches`
                WHERE (`winner_id` = ? AND `loser_id` = ?)
                   OR (`winner_id` = ? AND `loser_id` = ?)
                """.trimIndent(),
            ).use { statement ->
                val first = UuidBytes.encode(firstPlayer.value)
                val second = UuidBytes.encode(secondPlayer.value)
                statement.setBytes(1, first)
                statement.setBytes(2, second)
                statement.setBytes(3, first)
                statement.setBytes(4, second)
                statement.setBytes(5, second)
                statement.setBytes(6, first)
                statement.executeQuery().use { result ->
                    check(result.next()) { "Head-to-head aggregate did not return a row" }
                    HeadToHeadRecord(
                        firstPlayer = firstPlayer,
                        secondPlayer = secondPlayer,
                        firstWins = result.getLong("first_wins"),
                        secondWins = result.getLong("second_wins"),
                        lastCompletedAt = result.getTimestamp("last_completed_at")?.toInstant(),
                    )
                }
            }
        }
    }

    override fun presets(playerId: PlayerId): CompletableFuture<List<DuelPreset>> =
        runtime.executor.read { connection ->
            connection.prepareStatement(
                "SELECT $PRESET_COLUMNS FROM `arcduels_rule_presets` WHERE `player_id` = ? ORDER BY `slot` ASC",
            ).use { statement ->
                statement.setBytes(1, UuidBytes.encode(playerId.value))
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toPreset(playerId)) } }
            }
        }

    override fun savePreset(preset: DuelPreset): CompletableFuture<Unit> =
        runtime.executor.write { connection ->
            connection.prepareStatement(
                """
                INSERT INTO `arcduels_rule_presets`
                    (`player_id`, `slot`, `mode`, `objective`, `kit_id`, `ranked`, `best_of`, `projectiles`, `consumables`,
                     `ender_pearls`, `natural_regeneration`, `sudden_death_seconds`, `koth_capture_seconds`,
                     `boxing_hits_to_win`, `combo_hits_to_win`, `selected_arena_server`, `selected_arena_id`, `updated_at`)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    `mode` = VALUES(`mode`), `objective` = VALUES(`objective`), `kit_id` = VALUES(`kit_id`),
                    `ranked` = VALUES(`ranked`), `best_of` = VALUES(`best_of`), `projectiles` = VALUES(`projectiles`),
                    `consumables` = VALUES(`consumables`), `ender_pearls` = VALUES(`ender_pearls`),
                    `natural_regeneration` = VALUES(`natural_regeneration`),
                    `sudden_death_seconds` = VALUES(`sudden_death_seconds`),
                    `koth_capture_seconds` = VALUES(`koth_capture_seconds`),
                    `boxing_hits_to_win` = VALUES(`boxing_hits_to_win`),
                    `combo_hits_to_win` = VALUES(`combo_hits_to_win`),
                    `selected_arena_server` = VALUES(`selected_arena_server`),
                    `selected_arena_id` = VALUES(`selected_arena_id`), `updated_at` = VALUES(`updated_at`)
                """.trimIndent(),
            ).use { statement ->
                val modifiers = preset.rules.modifiers
                statement.setBytes(1, UuidBytes.encode(preset.playerId.value))
                statement.setInt(2, preset.slot)
                statement.setString(3, preset.rules.mode.name)
                statement.setString(4, preset.rules.objective.name)
                statement.setString(5, preset.rules.kitId?.value)
                statement.setBoolean(6, preset.rules.ranked)
                statement.setInt(7, preset.rules.bestOf)
                statement.setBoolean(8, modifiers.projectiles)
                statement.setBoolean(9, modifiers.consumables)
                statement.setBoolean(10, modifiers.enderPearls)
                statement.setBoolean(11, modifiers.naturalRegeneration)
                statement.setInt(12, modifiers.suddenDeathAfterSeconds)
                statement.setInt(13, modifiers.kingOfTheHillCaptureSeconds)
                statement.setInt(14, modifiers.boxingHitsToWin)
                statement.setInt(15, modifiers.comboHitsToWin)
                statement.setString(16, preset.arenaSelection?.serverId?.value)
                statement.setString(17, preset.arenaSelection?.arenaId?.value)
                statement.setTimestamp(18, Timestamp.from(preset.updatedAt))
                // MySQL reports 0 when an idempotent upsert leaves an existing
                // row unchanged, 1 for insert, and 2 for a changed update.
                check(statement.executeUpdate() in 0..2) { "Could not save duel rule preset" }
            }
            Unit
        }

    override fun deletePreset(
        playerId: PlayerId,
        slot: Int,
    ): CompletableFuture<Boolean> {
        require(slot in 1..5) { "Preset slot must be between 1 and 5" }
        return runtime.executor.write { connection ->
            connection.prepareStatement(
                "DELETE FROM `arcduels_rule_presets` WHERE `player_id` = ? AND `slot` = ?",
            ).use { statement ->
                statement.setBytes(1, UuidBytes.encode(playerId.value))
                statement.setInt(2, slot)
                statement.executeUpdate() == 1
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

    override fun save(snapshot: PlayerStateEscrow): CompletableFuture<Unit> {
        validateEscrow(snapshot)
        return saveWithRetry(snapshot, attempt = 0)
    }

    override fun findPending(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> =
        runtime.executor.read { connection -> findEscrow(connection, playerId, lock = false) }

    override fun pending(serverId: ServerId): CompletableFuture<List<PlayerStateEscrow>> =
        runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT `player_id`, `match_id`, `server_id`, `format_version`, `inventory_replaced`, `payload`, `payload_sha256`, `created_at`
                FROM `arcduels_player_state_escrow`
                WHERE `server_id` = ?
                ORDER BY `created_at`, `player_id`
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, serverId.value)
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.toEscrow()) } }
            }
        }

    override fun findLatestRetained(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> =
        runtime.executor.read { connection ->
            connection.prepareStatement(
                """
                SELECT `player_id`, `match_id`, `server_id`, `format_version`, `inventory_replaced`, `payload`, `payload_sha256`, `created_at`
                FROM `arcduels_player_state_archive`
                WHERE `player_id` = ?
                ORDER BY `restored_at` DESC
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, UuidBytes.encode(playerId.value))
                statement.executeQuery().use { result -> if (result.next()) result.toEscrow() else null }
            }
        }

    override fun retainRestored(
        snapshot: PlayerStateEscrow,
        restoredAt: Instant,
        purgeAfter: Instant,
    ): CompletableFuture<Boolean> {
        validateEscrow(snapshot)
        require(purgeAfter.isAfter(restoredAt)) { "Retained escrow expiry must follow restoration" }
        return runtime.executor.transaction { connection ->
            val active = findEscrow(connection, snapshot.playerId, lock = true)
            val retained = findRetainedEscrow(connection, snapshot.playerId, snapshot.matchId, lock = true)
            if (active == null) {
                return@transaction retained?.sameContent(snapshot) == true
            }
            if (!active.sameContent(snapshot)) return@transaction false
            if (retained == null) {
                insertRetainedEscrow(connection, snapshot, restoredAt, purgeAfter)
            } else {
                check(retained.sameContent(snapshot)) {
                    "A different retained escrow already exists for ${snapshot.playerId} and ${snapshot.matchId}"
                }
            }
            checkNotNull(findRetainedEscrow(connection, snapshot.playerId, snapshot.matchId, lock = false)).also {
                check(it.sameContent(snapshot)) { "Retained escrow verification failed for ${snapshot.playerId}" }
                validateEscrow(it)
            }
            connection.prepareStatement(
                """
                DELETE FROM `arcduels_player_state_escrow`
                WHERE `player_id` = ? AND `match_id` = ? AND `payload_sha256` = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, UuidBytes.encode(snapshot.playerId.value))
                statement.setBytes(2, UuidBytes.encode(snapshot.matchId.value))
                statement.setBytes(3, snapshot.checksum)
                check(statement.executeUpdate() == 1) { "Active escrow changed before archival completed" }
            }
            true
        }
    }

    override fun purgeRetained(cutoff: Instant): CompletableFuture<Int> =
        runtime.executor.transaction { connection ->
            connection.prepareStatement(
                "DELETE FROM `arcduels_player_state_archive` WHERE `purge_after` <= ? ORDER BY `purge_after` LIMIT $PURGE_BATCH_SIZE",
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(cutoff))
                statement.executeUpdate()
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

    private fun saveWithRetry(
        snapshot: PlayerStateEscrow,
        attempt: Int,
    ): CompletableFuture<Unit> =
        runtime.executor.transaction { connection ->
            val existing = findEscrow(connection, snapshot.playerId, lock = true)
            if (existing == null) {
                insertEscrow(connection, snapshot)
            } else {
                check(existing.sameContent(snapshot)) {
                    "Player ${snapshot.playerId} already has a different pending state escrow"
                }
            }
            val committed = checkNotNull(findEscrow(connection, snapshot.playerId, lock = false))
            check(committed.sameContent(snapshot)) { "Committed escrow verification failed for ${snapshot.playerId}" }
            validateEscrow(committed)
        }.handle { result, failure ->
            if (failure == null) {
                CompletableFuture.completedFuture(result)
            } else {
                val cause = failure.unwrapCompletion()
                if (attempt < MAX_TRANSACTION_RETRIES && cause.isRetryableMySqlTransactionFailure()) {
                    CompletableFuture.runAsync(
                        {},
                        CompletableFuture.delayedExecutor(RETRY_BASE_DELAY_MS shl attempt, TimeUnit.MILLISECONDS),
                    ).thenCompose { saveWithRetry(snapshot, attempt + 1) }
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
                SELECT `player_id`, `match_id`, `server_id`, `format_version`, `inventory_replaced`, `payload`, `payload_sha256`, `created_at`
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
                (`player_id`, `match_id`, `server_id`, `format_version`, `inventory_replaced`, `payload`, `payload_sha256`, `created_at`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(snapshot.playerId.value))
            statement.setBytes(2, UuidBytes.encode(snapshot.matchId.value))
            statement.setString(3, snapshot.serverId.value)
            statement.setInt(4, snapshot.formatVersion)
            statement.setBoolean(5, snapshot.inventoryReplaced)
            statement.setBytes(6, snapshot.payload)
            statement.setBytes(7, snapshot.checksum)
            statement.setTimestamp(8, Timestamp.from(snapshot.createdAt))
            check(statement.executeUpdate() == 1) { "Could not insert player state escrow" }
        }
    }

    private fun insertRetainedEscrow(
        connection: Connection,
        snapshot: PlayerStateEscrow,
        restoredAt: Instant,
        purgeAfter: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO `arcduels_player_state_archive`
                (`player_id`, `match_id`, `server_id`, `format_version`, `inventory_replaced`, `payload`, `payload_sha256`, `created_at`, `restored_at`, `purge_after`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(snapshot.playerId.value))
            statement.setBytes(2, UuidBytes.encode(snapshot.matchId.value))
            statement.setString(3, snapshot.serverId.value)
            statement.setInt(4, snapshot.formatVersion)
            statement.setBoolean(5, snapshot.inventoryReplaced)
            statement.setBytes(6, snapshot.payload)
            statement.setBytes(7, snapshot.checksum)
            statement.setTimestamp(8, Timestamp.from(snapshot.createdAt))
            statement.setTimestamp(9, Timestamp.from(restoredAt))
            statement.setTimestamp(10, Timestamp.from(purgeAfter))
            check(statement.executeUpdate() == 1) { "Could not retain restored player state escrow" }
        }
    }

    private fun findEscrow(
        connection: Connection,
        playerId: PlayerId,
        lock: Boolean,
    ): PlayerStateEscrow? =
        connection.prepareStatement(
            """
            SELECT `player_id`, `match_id`, `server_id`, `format_version`, `inventory_replaced`, `payload`, `payload_sha256`, `created_at`
            FROM `arcduels_player_state_escrow`
            WHERE `player_id` = ?${if (lock) " FOR UPDATE" else ""}
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(playerId.value))
            statement.executeQuery().use { result -> if (result.next()) result.toEscrow() else null }
        }

    private fun findRetainedEscrow(
        connection: Connection,
        playerId: PlayerId,
        matchId: MatchId,
        lock: Boolean,
    ): PlayerStateEscrow? =
        connection.prepareStatement(
            """
            SELECT `player_id`, `match_id`, `server_id`, `format_version`, `inventory_replaced`, `payload`, `payload_sha256`, `created_at`
            FROM `arcduels_player_state_archive`
            WHERE `player_id` = ? AND `match_id` = ?${if (lock) " FOR UPDATE" else ""}
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(playerId.value))
            statement.setBytes(2, UuidBytes.encode(matchId.value))
            statement.executeQuery().use { result -> if (result.next()) result.toEscrow() else null }
        }

    private fun ResultSet.toEscrow(): PlayerStateEscrow =
        PlayerStateEscrow(
            playerId = PlayerId(UuidBytes.decode(getBytes("player_id"))),
            matchId = MatchId(UuidBytes.decode(getBytes("match_id"))),
            serverId = ServerId(getString("server_id")),
            formatVersion = getInt("format_version"),
            inventoryReplaced = getBoolean("inventory_replaced"),
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
                (`match_id`, `winner_id`, `loser_id`, `mode`, `objective`, `kit_id`, `ranked`, `best_of`, `projectiles`,
                 `consumables`, `ender_pearls`, `natural_regeneration`, `sudden_death_seconds`, `koth_capture_seconds`,
                 `boxing_hits_to_win`, `combo_hits_to_win`, `server_id`, `arena_id`, `completed_at`,
                 `winner_rating_after`, `loser_rating_after`, `winner_score`, `loser_score`, `end_reason`, `leaderboard_revision`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            val modifiers = outcome.modifiers
            statement.setBytes(1, UuidBytes.encode(outcome.matchId.value))
            statement.setBytes(2, UuidBytes.encode(outcome.winner.value))
            statement.setBytes(3, UuidBytes.encode(outcome.loser.value))
            statement.setString(4, outcome.mode.name)
            statement.setString(5, outcome.objective.name)
            statement.setString(6, outcome.kitId?.value)
            statement.setBoolean(7, outcome.ranked)
            statement.setInt(8, outcome.bestOf)
            statement.setBoolean(9, modifiers.projectiles)
            statement.setBoolean(10, modifiers.consumables)
            statement.setBoolean(11, modifiers.enderPearls)
            statement.setBoolean(12, modifiers.naturalRegeneration)
            statement.setInt(13, modifiers.suddenDeathAfterSeconds)
            statement.setInt(14, modifiers.kingOfTheHillCaptureSeconds)
            statement.setInt(15, modifiers.boxingHitsToWin)
            statement.setInt(16, modifiers.comboHitsToWin)
            statement.setString(17, outcome.serverId.value)
            statement.setString(18, outcome.arenaId?.value)
            statement.setTimestamp(19, Timestamp.from(outcome.completedAt))
            statement.setInt(20, winnerRating)
            statement.setInt(21, loserRating)
            statement.setInt(22, outcome.winnerScore)
            statement.setInt(23, outcome.loserScore)
            statement.setString(24, outcome.endReason.name)
            statement.setLong(25, leaderboardRevision)
            check(statement.executeUpdate() == 1) { "Could not insert duel match result" }
        }
    }

    private fun findReceipt(
        connection: Connection,
        expected: MatchOutcome,
    ): PersistedMatchResult? =
        connection.prepareStatement(
            """
            SELECT $MATCH_COLUMNS
            FROM `arcduels_matches` m
            WHERE m.`match_id` = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(expected.matchId.value))
            statement.executeQuery().use resultUse@ { result ->
                if (!result.next()) return@resultUse null
                val stored = result.toMatchOutcome()
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

    private fun findRecordedMatch(
        connection: Connection,
        matchId: MatchId,
    ): RecordedMatch? =
        connection.prepareStatement(
            """
            SELECT $MATCH_COLUMNS, wn.`last_known_name` AS `winner_name`, ln.`last_known_name` AS `loser_name`
            FROM `arcduels_matches` m
            LEFT JOIN `arcduels_player_names` wn ON wn.`player_id` = m.`winner_id`
            LEFT JOIN `arcduels_player_names` ln ON ln.`player_id` = m.`loser_id`
            WHERE m.`match_id` = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(matchId.value))
            statement.executeQuery().use { result -> if (result.next()) result.toRecordedMatch() else null }
        }

    private fun ResultSet.toMatchOutcome(): MatchOutcome =
        MatchOutcome(
            matchId = MatchId(UuidBytes.decode(getBytes("match_id"))),
            winner = PlayerId(UuidBytes.decode(getBytes("winner_id"))),
            loser = PlayerId(UuidBytes.decode(getBytes("loser_id"))),
            mode = DuelMode.valueOf(getString("mode")),
            kitId = getString("kit_id")?.let(::KitId),
            ranked = getBoolean("ranked"),
            serverId = ServerId(getString("server_id")),
            completedAt = getTimestamp("completed_at").toInstant(),
            objective = DuelObjectiveType.valueOf(getString("objective")),
            arenaId = getString("arena_id")?.let(::ArenaId),
            bestOf = getInt("best_of"),
            modifiers =
                CombatModifiers(
                    projectiles = getBoolean("projectiles"),
                    consumables = getBoolean("consumables"),
                    enderPearls = getBoolean("ender_pearls"),
                    naturalRegeneration = getBoolean("natural_regeneration"),
                    suddenDeathAfterSeconds = getInt("sudden_death_seconds"),
                    kingOfTheHillCaptureSeconds = getInt("koth_capture_seconds"),
                    boxingHitsToWin = getInt("boxing_hits_to_win"),
                    comboHitsToWin = getInt("combo_hits_to_win"),
                ),
            winnerScore = getInt("winner_score"),
            loserScore = getInt("loser_score"),
            endReason = MatchEndReason.valueOf(getString("end_reason")),
        )

    private fun ResultSet.toRecordedMatch(): RecordedMatch =
        RecordedMatch(
            outcome = toMatchOutcome(),
            winnerRatingAfter = getInt("winner_rating_after"),
            loserRatingAfter = getInt("loser_rating_after"),
            winnerName = getString("winner_name"),
            loserName = getString("loser_name"),
        )

    private fun ResultSet.toPreset(playerId: PlayerId): DuelPreset {
        val arenaServer = getString("selected_arena_server")
        val arenaId = getString("selected_arena_id")
        require((arenaServer == null) == (arenaId == null)) { "Stored preset arena selection is incomplete" }
        return DuelPreset(
            playerId = playerId,
            slot = getInt("slot"),
            rules =
                ru.ruscrafting.duels.domain.DuelRules(
                    mode = DuelMode.valueOf(getString("mode")),
                    kitId = getString("kit_id")?.let(::KitId),
                    ranked = getBoolean("ranked"),
                    bestOf = getInt("best_of"),
                    objective = DuelObjectiveType.valueOf(getString("objective")),
                    modifiers =
                        CombatModifiers(
                            projectiles = getBoolean("projectiles"),
                            consumables = getBoolean("consumables"),
                            enderPearls = getBoolean("ender_pearls"),
                            naturalRegeneration = getBoolean("natural_regeneration"),
                            suddenDeathAfterSeconds = getInt("sudden_death_seconds"),
                            kingOfTheHillCaptureSeconds = getInt("koth_capture_seconds"),
                            boxingHitsToWin = getInt("boxing_hits_to_win"),
                            comboHitsToWin = getInt("combo_hits_to_win"),
                        ),
                ),
            arenaSelection =
                if (arenaServer == null) null else ArenaSelection(ServerId(arenaServer), ArenaId(requireNotNull(arenaId))),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )
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
        // A four-connection pool can produce several consecutive InnoDB victims
        // when many servers deliver the same match receipt at once. Eight
        // bounded retries (nine total attempts) still fail fast for
        // non-rollback errors while the exponential delay drains that
        // duplicate burst safely.
        const val MAX_TRANSACTION_RETRIES = 8
        const val RETRY_BASE_DELAY_MS = 10L
        const val PURGE_BATCH_SIZE = 1_000
        const val MAX_ESCROW_PAYLOAD_BYTES = 8 * 1024 * 1024
        const val MIGRATION_NAMESPACE = "arcduels"
        const val STAT_COLUMNS =
            "`wins`, `losses`, `current_win_streak`, `best_win_streak`, `rating`, `revision`"
        const val MATCH_COLUMNS =
            "m.`match_id`, m.`winner_id`, m.`loser_id`, m.`mode`, m.`objective`, m.`kit_id`, m.`ranked`, " +
                "m.`best_of`, m.`projectiles`, m.`consumables`, m.`ender_pearls`, m.`natural_regeneration`, " +
                "m.`sudden_death_seconds`, m.`koth_capture_seconds`, m.`boxing_hits_to_win`, m.`combo_hits_to_win`, " +
                "m.`server_id`, m.`arena_id`, m.`completed_at`, m.`winner_rating_after`, m.`loser_rating_after`, " +
                "m.`winner_score`, m.`loser_score`, m.`end_reason`, m.`leaderboard_revision`"
        const val PRESET_COLUMNS =
            "`slot`, `mode`, `objective`, `kit_id`, `ranked`, `best_of`, `projectiles`, `consumables`, " +
                "`ender_pearls`, `natural_regeneration`, `sudden_death_seconds`, `koth_capture_seconds`, " +
                "`boxing_hits_to_win`, `combo_hits_to_win`, `selected_arena_server`, `selected_arena_id`, `updated_at`"
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
