package ru.ruscrafting.duels.mysql

import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Retries only rolled-back transactions; uncertain commits must not be replayed. */
internal fun <T> retryMySqlTransaction(
    attempt: Int = 0,
    transaction: () -> CompletableFuture<T>,
): CompletableFuture<T> =
    transaction().handle { result, failure ->
        if (failure == null) {
            CompletableFuture.completedFuture(result)
        } else {
            val cause = failure.unwrapCompletion()
            if (attempt < MAX_TRANSACTION_RETRIES && cause.isRetryableMySqlTransactionFailure()) {
                CompletableFuture.runAsync(
                    {},
                    CompletableFuture.delayedExecutor(RETRY_BASE_DELAY_MS shl attempt, TimeUnit.MILLISECONDS),
                ).thenCompose { retryMySqlTransaction(attempt + 1, transaction) }
            } else {
                CompletableFuture.failedFuture(cause)
            }
        }
    }.thenCompose { it }

// A four-connection pool can yield repeated InnoDB victims during a duplicate
// receipt burst. Nine total attempts with backoff drain that burst without
// replaying failures whose commit outcome is unknown.
private const val MAX_TRANSACTION_RETRIES = 8
private const val RETRY_BASE_DELAY_MS = 10L

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
