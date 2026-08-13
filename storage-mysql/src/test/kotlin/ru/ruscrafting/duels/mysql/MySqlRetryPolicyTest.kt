package ru.ruscrafting.duels.mysql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLException
import java.util.concurrent.CompletionException

class MySqlRetryPolicyTest : StringSpec({
    "only rolled-back deadlock and lock-timeout failures are retryable" {
        SQLException("deadlock", "40001", 1_213).isRetryableMySqlTransactionFailure() shouldBe true
        SQLException("lock timeout", "HY000", 1_205).isRetryableMySqlTransactionFailure() shouldBe true
        CompletionException(SQLException("nested deadlock", "40001", 1_213))
            .isRetryableMySqlTransactionFailure() shouldBe true

        SQLException("duplicate", "23000", 1_062).isRetryableMySqlTransactionFailure() shouldBe false
        SQLException("connection lost", "08S01", 0).isRetryableMySqlTransactionFailure() shouldBe false
        IllegalStateException("not sql").isRetryableMySqlTransactionFailure() shouldBe false
    }
})
