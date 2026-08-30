package ru.ruscrafting.duels.mysql

import ru.arc.sql.SqlMigration

object MySqlDuelMigrations {
    const val CURRENT_VERSION = 9

    val all: List<SqlMigration> =
        listOf(
            SqlMigration(
                version = 1,
                description = "create duel statistics and match history",
                statements =
                    listOf(
                        """
                        CREATE TABLE IF NOT EXISTS `arcduels_player_stats` (
                            `player_id` BINARY(16) NOT NULL,
                            `wins` BIGINT UNSIGNED NOT NULL DEFAULT 0,
                            `losses` BIGINT UNSIGNED NOT NULL DEFAULT 0,
                            `current_win_streak` INT UNSIGNED NOT NULL DEFAULT 0,
                            `best_win_streak` INT UNSIGNED NOT NULL DEFAULT 0,
                            `rating` INT UNSIGNED NOT NULL DEFAULT 1000,
                            `revision` BIGINT UNSIGNED NOT NULL DEFAULT 0,
                            `updated_at` DATETIME(3) NOT NULL,
                            PRIMARY KEY (`player_id`),
                            KEY `idx_arcduels_leaderboard` (`rating` DESC, `wins` DESC)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent(),
                        """
                        CREATE TABLE IF NOT EXISTS `arcduels_meta` (
                            `name` VARCHAR(64) NOT NULL,
                            `value` BIGINT UNSIGNED NOT NULL,
                            PRIMARY KEY (`name`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent(),
                        """
                        INSERT IGNORE INTO `arcduels_meta` (`name`, `value`)
                        VALUES ('leaderboard_revision', 0)
                        """.trimIndent(),
                        """
                        CREATE TABLE IF NOT EXISTS `arcduels_matches` (
                            `match_id` BINARY(16) NOT NULL,
                            `winner_id` BINARY(16) NOT NULL,
                            `loser_id` BINARY(16) NOT NULL,
                            `mode` VARCHAR(32) NOT NULL,
                            `kit_id` VARCHAR(48) NULL,
                            `ranked` BOOLEAN NOT NULL,
                            `server_id` VARCHAR(48) NOT NULL,
                            `completed_at` DATETIME(3) NOT NULL,
                            `winner_rating_after` INT UNSIGNED NOT NULL,
                            `loser_rating_after` INT UNSIGNED NOT NULL,
                            `leaderboard_revision` BIGINT UNSIGNED NOT NULL,
                            PRIMARY KEY (`match_id`),
                            KEY `idx_arcduels_winner_history` (`winner_id`, `completed_at` DESC),
                            KEY `idx_arcduels_loser_history` (`loser_id`, `completed_at` DESC)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent(),
                    ),
            ),
            SqlMigration(
                version = 2,
                description = "remember player names for global leaderboards",
                statements =
                    listOf(
                        """
                        CREATE TABLE IF NOT EXISTS `arcduels_player_names` (
                            `player_id` BINARY(16) NOT NULL,
                            `last_known_name` VARCHAR(32) NOT NULL,
                            `updated_at` DATETIME(3) NOT NULL,
                            PRIMARY KEY (`player_id`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent(),
                    ),
            ),
            SqlMigration(
                version = 3,
                description = "create crash-safe active player state escrow",
                statements =
                    listOf(
                        """
                        CREATE TABLE IF NOT EXISTS `arcduels_player_state_escrow` (
                            `player_id` BINARY(16) NOT NULL,
                            `match_id` BINARY(16) NOT NULL,
                            `server_id` VARCHAR(48) NOT NULL,
                            `format_version` INT UNSIGNED NOT NULL,
                            `payload` MEDIUMBLOB NOT NULL,
                            `payload_sha256` BINARY(32) NOT NULL,
                            `created_at` DATETIME(3) NOT NULL,
                            PRIMARY KEY (`player_id`),
                            KEY `idx_arcduels_escrow_match` (`match_id`),
                            KEY `idx_arcduels_escrow_server` (`server_id`, `created_at`)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent(),
                    ),
            ),
            SqlMigration(
                version = 4,
                description = "record duel objective in match history",
                statements =
                    listOf(
                        """
                        SET @arcduels_migration_4 = (
                            SELECT IF(
                                EXISTS(
                                    SELECT 1
                                    FROM `information_schema`.`COLUMNS`
                                    WHERE `TABLE_SCHEMA` = DATABASE()
                                      AND `TABLE_NAME` = 'arcduels_matches'
                                      AND `COLUMN_NAME` = 'objective'
                                ),
                                'ALTER TABLE `arcduels_matches` MODIFY COLUMN `objective` VARCHAR(32) NOT NULL DEFAULT ''ELIMINATION'' AFTER `mode`',
                                'ALTER TABLE `arcduels_matches` ADD COLUMN `objective` VARCHAR(32) NOT NULL DEFAULT ''ELIMINATION'' AFTER `mode`'
                            )
                        )
                        """.trimIndent(),
                        """
                        PREPARE arcduels_migration_4_statement FROM @arcduels_migration_4
                        """.trimIndent(),
                        """
                        EXECUTE arcduels_migration_4_statement
                        """.trimIndent(),
                        """
                        DEALLOCATE PREPARE arcduels_migration_4_statement
                        """.trimIndent(),
                    ),
            ),
            SqlMigration(
                version = 5,
                description = "retain restored player snapshots before expiry",
                statements =
                    listOf(
                        """
                        CREATE TABLE IF NOT EXISTS `arcduels_player_state_archive` (
                            `player_id` BINARY(16) NOT NULL,
                            `match_id` BINARY(16) NOT NULL,
                            `server_id` VARCHAR(48) NOT NULL,
                            `format_version` INT UNSIGNED NOT NULL,
                            `payload` MEDIUMBLOB NOT NULL,
                            `payload_sha256` BINARY(32) NOT NULL,
                            `created_at` DATETIME(3) NOT NULL,
                            `restored_at` DATETIME(3) NOT NULL,
                            `purge_after` DATETIME(3) NOT NULL,
                            PRIMARY KEY (`player_id`, `match_id`),
                            KEY `idx_arcduels_archive_expiry` (`purge_after`),
                            KEY `idx_arcduels_archive_player` (`player_id`, `restored_at` DESC)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent(),
                    ),
            ),
            SqlMigration(
                version = 6,
                description = "record whether duel recovery replaced the player inventory",
                statements =
                    listOf(
                        migration6Column("arcduels_player_state_escrow", "escrow"),
                        "PREPARE arcduels_migration_6_escrow_statement FROM @arcduels_migration_6_escrow",
                        "EXECUTE arcduels_migration_6_escrow_statement",
                        "DEALLOCATE PREPARE arcduels_migration_6_escrow_statement",
                        migration6Column("arcduels_player_state_archive", "archive"),
                        "PREPARE arcduels_migration_6_archive_statement FROM @arcduels_migration_6_archive",
                        "EXECUTE arcduels_migration_6_archive_statement",
                        "DEALLOCATE PREPARE arcduels_migration_6_archive_statement",
                    ),
            ),
            SqlMigration(
                version = 7,
                description = "store complete replayable duel history",
                statements =
                    listOf(
                        migration7Column(1, "arena_id", "VARCHAR(48) NULL AFTER `server_id`"),
                        migration7Column(2, "best_of", "INT UNSIGNED NOT NULL DEFAULT 1 AFTER `ranked`"),
                        migration7Column(3, "projectiles", "BOOLEAN NOT NULL DEFAULT TRUE AFTER `best_of`"),
                        migration7Column(4, "consumables", "BOOLEAN NOT NULL DEFAULT TRUE AFTER `projectiles`"),
                        migration7Column(5, "ender_pearls", "BOOLEAN NOT NULL DEFAULT TRUE AFTER `consumables`"),
                        migration7Column(6, "natural_regeneration", "BOOLEAN NOT NULL DEFAULT TRUE AFTER `ender_pearls`"),
                        migration7Column(7, "sudden_death_seconds", "INT UNSIGNED NOT NULL DEFAULT 300 AFTER `natural_regeneration`"),
                        migration7Column(8, "koth_capture_seconds", "INT UNSIGNED NOT NULL DEFAULT 15 AFTER `sudden_death_seconds`"),
                        migration7Column(9, "boxing_hits_to_win", "INT UNSIGNED NOT NULL DEFAULT 100 AFTER `koth_capture_seconds`"),
                        migration7Column(10, "combo_hits_to_win", "INT UNSIGNED NOT NULL DEFAULT 10 AFTER `boxing_hits_to_win`"),
                        migration7Column(11, "winner_score", "INT UNSIGNED NOT NULL DEFAULT 1 AFTER `loser_rating_after`"),
                        migration7Column(12, "loser_score", "INT UNSIGNED NOT NULL DEFAULT 0 AFTER `winner_score`"),
                        migration7Column(13, "end_reason", "VARCHAR(32) NOT NULL DEFAULT 'ELIMINATION' AFTER `loser_score`"),
                    ).flatMapIndexed { index, setup ->
                        val suffix = index + 1
                        listOf(
                            setup,
                            "PREPARE arcduels_migration_7_${suffix}_statement FROM @arcduels_migration_7_$suffix",
                            "EXECUTE arcduels_migration_7_${suffix}_statement",
                            "DEALLOCATE PREPARE arcduels_migration_7_${suffix}_statement",
                        )
                    } +
                        listOf(
                            // Versions before 7 did not persist combat modifiers. The
                            // generic defaults are invalid for hit-race objectives, so
                            // normalize legacy rows before the new reader sees them.
                            """
                            UPDATE `arcduels_matches`
                            SET `projectiles` = FALSE,
                                `consumables` = FALSE,
                                `ender_pearls` = FALSE,
                                `natural_regeneration` = FALSE,
                                `end_reason` = 'OBJECTIVE'
                            WHERE `objective` IN ('BOXING', 'COMBO')
                            """.trimIndent(),
                            """
                            UPDATE `arcduels_matches`
                            SET `end_reason` = 'OBJECTIVE'
                            WHERE `objective` = 'KING_OF_THE_HILL'
                              AND `end_reason` = 'ELIMINATION'
                            """.trimIndent(),
                        ),
            ),
            SqlMigration(
                version = 8,
                description = "create saved duel rule presets",
                statements =
                    listOf(
                        """
                        CREATE TABLE IF NOT EXISTS `arcduels_rule_presets` (
                            `player_id` BINARY(16) NOT NULL,
                            `slot` TINYINT UNSIGNED NOT NULL,
                            `mode` VARCHAR(32) NOT NULL,
                            `objective` VARCHAR(32) NOT NULL,
                            `kit_id` VARCHAR(48) NULL,
                            `ranked` BOOLEAN NOT NULL,
                            `best_of` INT UNSIGNED NOT NULL,
                            `projectiles` BOOLEAN NOT NULL,
                            `consumables` BOOLEAN NOT NULL,
                            `ender_pearls` BOOLEAN NOT NULL,
                            `natural_regeneration` BOOLEAN NOT NULL,
                            `sudden_death_seconds` INT UNSIGNED NOT NULL,
                            `koth_capture_seconds` INT UNSIGNED NOT NULL,
                            `boxing_hits_to_win` INT UNSIGNED NOT NULL,
                            `combo_hits_to_win` INT UNSIGNED NOT NULL,
                            `selected_arena_server` VARCHAR(48) NULL,
                            `selected_arena_id` VARCHAR(48) NULL,
                            `updated_at` DATETIME(3) NOT NULL,
                            PRIMARY KEY (`player_id`, `slot`),
                            CONSTRAINT `chk_arcduels_preset_slot` CHECK (`slot` BETWEEN 1 AND 5),
                            CONSTRAINT `chk_arcduels_preset_arena_pair` CHECK (
                                (`selected_arena_server` IS NULL) = (`selected_arena_id` IS NULL)
                            )
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent(),
                ),
            ),
            SqlMigration(
                version = 9,
                description = "create durable multiplayer match history",
                statements =
                    listOf(
                        """
                        CREATE TABLE IF NOT EXISTS `arcduels_multiplayer_matches` (
                            `match_id` BINARY(16) NOT NULL,
                            `server_id` VARCHAR(48) NOT NULL,
                            `arena_id` VARCHAR(48) NOT NULL,
                            `layout` VARCHAR(32) NOT NULL,
                            `kit_policy` VARCHAR(32) NOT NULL,
                            `shared_kit_id` VARCHAR(48) NULL,
                            `winning_team` TINYINT UNSIGNED NULL,
                            `end_reason` VARCHAR(32) NOT NULL,
                            `completed_at` DATETIME(3) NOT NULL,
                            `outcome_sha256` BINARY(32) NOT NULL,
                            PRIMARY KEY (`match_id`),
                            KEY `idx_arcduels_multiplayer_completed` (`completed_at` DESC)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent(),
                        """
                        CREATE TABLE IF NOT EXISTS `arcduels_multiplayer_participants` (
                            `match_id` BINARY(16) NOT NULL,
                            `player_id` BINARY(16) NOT NULL,
                            `team` TINYINT UNSIGNED NULL,
                            `kit_id` VARCHAR(48) NOT NULL,
                            `placement` TINYINT UNSIGNED NOT NULL,
                            `won` BOOLEAN NOT NULL,
                            PRIMARY KEY (`match_id`, `player_id`),
                            KEY `idx_arcduels_multiplayer_player` (`player_id`, `match_id`),
                            CONSTRAINT `fk_arcduels_multiplayer_match`
                                FOREIGN KEY (`match_id`) REFERENCES `arcduels_multiplayer_matches` (`match_id`)
                                ON DELETE CASCADE
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent(),
                    ),
            ),
        )

    private fun migration6Column(table: String, variable: String): String =
        """
        SET @arcduels_migration_6_$variable = (
            SELECT IF(
                EXISTS(
                    SELECT 1
                    FROM `information_schema`.`COLUMNS`
                    WHERE `TABLE_SCHEMA` = DATABASE()
                      AND `TABLE_NAME` = '$table'
                      AND `COLUMN_NAME` = 'inventory_replaced'
                ),
                'SELECT 1',
                'ALTER TABLE `$table` ADD COLUMN `inventory_replaced` BOOLEAN NOT NULL DEFAULT FALSE AFTER `format_version`'
            )
        )
        """.trimIndent()

    private fun migration7Column(
        variable: Int,
        column: String,
        definition: String,
    ): String {
        val escapedDefinition = definition.replace("'", "''")
        return """
            SET @arcduels_migration_7_$variable = (
                SELECT IF(
                    EXISTS(
                        SELECT 1
                        FROM `information_schema`.`COLUMNS`
                        WHERE `TABLE_SCHEMA` = DATABASE()
                          AND `TABLE_NAME` = 'arcduels_matches'
                          AND `COLUMN_NAME` = '$column'
                    ),
                    'SELECT 1',
                    'ALTER TABLE `arcduels_matches` ADD COLUMN `$column` $escapedDefinition'
                )
            )
        """.trimIndent()
    }

}
