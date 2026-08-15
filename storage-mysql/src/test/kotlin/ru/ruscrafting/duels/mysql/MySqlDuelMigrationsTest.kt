package ru.ruscrafting.duels.mysql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MySqlDuelMigrationsTest : StringSpec({
    "schema migrations are ordered and idempotent" {
        val migrations = MySqlDuelMigrations.all
        val initial = migrations.first()
        val names = migrations[1]
        val escrow = migrations[2]
        val objective = migrations[3]
        val archive = migrations[4]
        val inventoryMode = migrations[5]
        val history = migrations[6]
        val presets = migrations[7]

        migrations.map { it.version } shouldBe listOf(1, 2, 3, 4, 5, 6, 7, 8)
        initial.statements shouldHaveSize 4
        initial.statements.first() shouldContain "CREATE TABLE IF NOT EXISTS"
        initial.statements[2] shouldContain "INSERT IGNORE"
        names.statements.single() shouldContain "CREATE TABLE IF NOT EXISTS `arcduels_player_names`"
        escrow.statements.single() shouldContain "CREATE TABLE IF NOT EXISTS `arcduels_player_state_escrow`"
        escrow.statements.single() shouldContain "MEDIUMBLOB"
        escrow.statements.single() shouldContain "BINARY(32)"
        objective.statements shouldHaveSize 4
        objective.statements.first() shouldContain "`information_schema`.`COLUMNS`"
        objective.statements.first() shouldContain "ADD COLUMN `objective`"
        objective.statements.first() shouldContain "MODIFY COLUMN `objective`"
        objective.statements.first() shouldContain "DEFAULT ''ELIMINATION''"
        objective.statements[1] shouldContain "PREPARE arcduels_migration_4_statement"
        objective.statements[2] shouldContain "EXECUTE arcduels_migration_4_statement"
        objective.statements[3] shouldContain "DEALLOCATE PREPARE arcduels_migration_4_statement"
        archive.statements.single() shouldContain "CREATE TABLE IF NOT EXISTS `arcduels_player_state_archive`"
        archive.statements.single() shouldContain "`restored_at` DATETIME(3) NOT NULL"
        archive.statements.single() shouldContain "`purge_after` DATETIME(3) NOT NULL"
        archive.statements.single() shouldContain "PRIMARY KEY (`player_id`, `match_id`)"
        inventoryMode.statements shouldHaveSize 8
        inventoryMode.statements[0] shouldContain "`arcduels_player_state_escrow`"
        inventoryMode.statements[0] shouldContain "ADD COLUMN `inventory_replaced`"
        inventoryMode.statements[4] shouldContain "`arcduels_player_state_archive`"
        history.statements shouldHaveSize 54
        history.statements.first() shouldContain "ADD COLUMN `arena_id`"
        history.statements[51] shouldContain "DEALLOCATE PREPARE arcduels_migration_7_13_statement"
        history.statements[52] shouldContain "WHERE `objective` IN ('BOXING', 'COMBO')"
        history.statements[53] shouldContain "WHERE `objective` = 'KING_OF_THE_HILL'"
        presets.statements.single() shouldContain "CREATE TABLE IF NOT EXISTS `arcduels_rule_presets`"
        presets.statements.single() shouldContain "PRIMARY KEY (`player_id`, `slot`)"
    }
})
