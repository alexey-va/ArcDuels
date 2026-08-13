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
        val objective = migrations.last()

        migrations.map { it.version } shouldBe listOf(1, 2, 3, 4)
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
    }
})
