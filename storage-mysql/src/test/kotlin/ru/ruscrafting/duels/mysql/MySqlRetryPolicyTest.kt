package ru.ruscrafting.duels.mysql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    "successful transactions run once and retain nullable results" {
        var attempts = 0
        retryMySqlTransaction<String?> {
            attempts++
            CompletableFuture.completedFuture(null)
        }.get(1, TimeUnit.SECONDS) shouldBe null
        attempts shouldBe 1
    }

    "a rolled-back transaction is resubmitted until it succeeds" {
        val attempts = AtomicInteger()
        retryMySqlTransaction {
            if (attempts.incrementAndGet() < 3) {
                CompletableFuture.failedFuture(CompletionException(SQLException("deadlock", "40001", 1213)))
            } else {
                CompletableFuture.completedFuture("committed")
            }
        }.get(2, TimeUnit.SECONDS) shouldBe "committed"
        attempts.get() shouldBe 3
    }

    "retry exhaustion preserves the last failure after nine attempts" {
        val attempts = AtomicInteger()
        val failure = SQLException("lock timeout", "HY000", 1205)
        val result = retryMySqlTransaction<Unit> {
            attempts.incrementAndGet()
            CompletableFuture.failedFuture(failure)
        }
        shouldThrow<ExecutionException> { result.get(10, TimeUnit.SECONDS) }.cause shouldBe failure
        attempts.get() shouldBe 9
    }

    "an uncertain commit fails immediately without replaying side effects" {
        var attempts = 0
        val failure = SQLException("connection lost", "08S01", 0)
        val result = retryMySqlTransaction<Unit> {
            attempts++
            CompletableFuture.failedFuture(CompletionException(ExecutionException(failure)))
        }
        shouldThrow<ExecutionException> { result.get(1, TimeUnit.SECONDS) }.cause shouldBe failure
        attempts shouldBe 1
    }
})
