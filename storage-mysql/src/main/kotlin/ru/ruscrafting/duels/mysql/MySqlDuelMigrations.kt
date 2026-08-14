package ru.ruscrafting.duels.mysql

import ru.arc.sql.SqlMigration

object MySqlDuelMigrations {
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
}
