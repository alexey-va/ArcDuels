package ru.ruscrafting.duels.mysql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MySqlDuelMigrationsTest : StringSpec({
    "initial schema is versioned and idempotent" {
        val migration = MySqlDuelMigrations.all.single()

        migration.version shouldBe 1
        migration.statements shouldHaveSize 4
        migration.statements.first() shouldContain "CREATE TABLE IF NOT EXISTS"
        migration.statements[2] shouldContain "INSERT IGNORE"
    }
})
