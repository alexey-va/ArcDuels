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
        )
}
