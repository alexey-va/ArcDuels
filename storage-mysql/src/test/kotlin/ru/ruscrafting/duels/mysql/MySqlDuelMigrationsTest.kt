package ru.ruscrafting.duels.mysql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MySqlDuelMigrationsTest : StringSpec({
    "schema migrations are ordered and idempotent" {
        val migrations = MySqlDuelMigrations.all
        val initial = migrations.first()
        val names = migrations.last()

        migrations.map { it.version } shouldBe listOf(1, 2)
        initial.statements shouldHaveSize 4
        initial.statements.first() shouldContain "CREATE TABLE IF NOT EXISTS"
        initial.statements[2] shouldContain "INSERT IGNORE"
        names.statements.single() shouldContain "CREATE TABLE IF NOT EXISTS `arcduels_player_names`"
    }
})
