package ru.ruscrafting.duels.mysql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import com.zaxxer.hikari.HikariDataSource
import ru.arc.sql.SqlExecutor
import ru.arc.sql.SqlRuntime
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchOutcome
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.PlayerStateEscrow
import ru.ruscrafting.duels.domain.ServerId
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises repository entry points, independent of the implementation of retry. */
class MySqlRepositoryRetryContractTest : StringSpec({
    val matchId = MatchId(UUID.randomUUID())
    val server = ServerId("retry-contract")
    val players = List(2) { PlayerId(UUID.randomUUID()) }
    val payload = byteArrayOf(1, 2, 3)
    val snapshots = players.map { player ->
        PlayerStateEscrow(player, matchId, server, 1, payload = payload,
            checksum = MessageDigest.getInstance("SHA-256").digest(payload), createdAt = Instant.EPOCH)
    }
    val outcome = MatchOutcome(matchId, players[0], players[1], DuelMode.OWN_INVENTORY,
        null, false, server, Instant.EPOCH)
    val writes: Map<String, (MySqlStatisticsRepository) -> CompletableFuture<*>> = mapOf(
        "record" to { repo -> repo.record(outcome) },
        "save" to { repo -> repo.save(snapshots[0]) },
        "saveAll" to { repo -> repo.saveAll(snapshots) },
    )
    writes.forEach { (name, write) ->
        "$name retries rollback failures but stops when the next attempt has an uncertain outcome" {
            val attempts = AtomicInteger()
            val terminal = SQLException("connection lost", "08S01", 0)
            failingRuntime(attempts) { attempt ->
                if (attempt < 3) SQLException("deadlock", "40001", 1213) else terminal
            }.use { runtime ->
                val result = write(MySqlStatisticsRepository(runtime))
                shouldThrow<ExecutionException> { result.get(3, TimeUnit.SECONDS) }.cause shouldBe terminal
                attempts.get() shouldBe 3
            }
        }
        "$name stops at nine attempts and preserves the rollback failure" {
            val attempts = AtomicInteger()
            val failure = SQLException("lock timeout", "HY000", 1205)
            failingRuntime(attempts) { failure }.use { runtime ->
                val result = write(MySqlStatisticsRepository(runtime))
                shouldThrow<ExecutionException> { result.get(10, TimeUnit.SECONDS) }.cause shouldBe failure
                attempts.get() shouldBe 9
            }
        }
        "$name never replays an uncertain commit" {
            val attempts = AtomicInteger()
            val failure = SQLException("connection lost", "08S01", 0)
            failingRuntime(attempts) { failure }.use { runtime ->
                val result = write(MySqlStatisticsRepository(runtime))
                shouldThrow<ExecutionException> { result.get(1, TimeUnit.SECONDS) }.cause shouldBe failure
                attempts.get() shouldBe 1
            }
        }
    }
})

/** Uses the real SQL executor, with connection acquisition as the injected failure boundary. */
private fun failingRuntime(attempts: AtomicInteger, failure: (Int) -> SQLException): SqlRuntime {
    val dataSource = object : HikariDataSource() {
        override fun getConnection(): Connection = throw failure(attempts.incrementAndGet())
    }
    val executor = SqlExecutor(dataSource, 1, "retry-contract")
    return SqlRuntime::class.java.getDeclaredConstructor(HikariDataSource::class.java, SqlExecutor::class.java)
        .apply { isAccessible = true }
        .newInstance(dataSource, executor)
}
