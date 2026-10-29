package ru.ruscrafting.duels.mysql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelPreset
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.DuelWager
import ru.ruscrafting.duels.domain.DuelWagerService
import ru.ruscrafting.duels.domain.DuelWagerWallet
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchOutcome
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.WagerLegStatus
import ru.ruscrafting.duels.domain.WagerPhase
import ru.ruscrafting.duels.domain.WagerTransferResult
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MySqlDuelWagerRepositoryIntegrationTest : StringSpec() {
    private lateinit var mysql: MySqlTestService
    private lateinit var runtime: SqlRuntime
    private lateinit var statistics: MySqlStatisticsRepository

    init {
        beforeSpec {
            mysql = MySqlTestService.start(MySqlTestSettings(
                image = "mysql:8.4.10", database = "arcduels_wagers", username = "arcduels", password = "integration-test-only",
            ))
            runtime = SqlRuntime.create(SqlConnectionConfig(
                host = mysql.endpoint.host, port = mysql.endpoint.port, database = mysql.endpoint.database,
                username = mysql.endpoint.username, password = mysql.endpoint.password, sslMode = SqlSslMode.DISABLED,
                minimumIdle = 0, maximumPoolSize = 4, connectionTimeoutMs = 10_000, socketTimeoutMs = 30_000,
                validationTimeoutMs = 5_000, maxLifetimeMs = 60_000, failFast = true,
            ), "arcduels-wager-it")
            statistics = MySqlStatisticsRepository(runtime)
            statistics.migrate().join()
        }
        afterSpec {
            if (::statistics.isInitialized) statistics.close()
            if (::mysql.isInitialized) mysql.close()
        }

        "duplicate insert and stale revision cannot overwrite a durable provider intent" {
            val repository = statistics.wagers()
            val initial = wager()
            repository.create(initial).join() shouldBe initial
            repository.create(initial.copy(stakeMinor = 2_000, legs = initial.legs.map { it.copy(amountMinor = -2_000) })).join() shouldBe initial
            val calling = initial.copy(revision = 1, legs = initial.legs.mapIndexed { index, leg ->
                if (index == 0) leg.copy(status = WagerLegStatus.CALLING, balanceBefore = BigDecimal("100.1234")) else leg
            })
            repository.save(initial, calling).join() shouldBe true
            repository.save(initial, calling.copy(reason = "stale_overwrite")).join() shouldBe false
            MySqlDuelWagerRepository(runtime).find(initial.matchId).join() shouldBe calling
            repository.incomplete(initial.serverId, null, 100).join().single() shouldBe calling
        }

        "reloaded CALLING intent is quarantined without invoking the provider again" {
            val initial = wager()
            val repository = statistics.wagers()
            repository.create(initial).join()
            val calling = initial.copy(revision = 1, legs = initial.legs.mapIndexed { index, leg ->
                if (index == 0) leg.copy(status = WagerLegStatus.CALLING, balanceBefore = BigDecimal("100.00")) else leg
            })
            repository.save(initial, calling).join() shouldBe true
            val wallet = IntegrationWagerWallet(initial)
            val diagnostics = mutableListOf<String>()
            val service = DuelWagerService(MySqlDuelWagerRepository(runtime), wallet, initial.serverId, { _, reason, _ -> diagnostics += reason })
            service.recover().join()
            repository.find(initial.matchId).join()?.phase shouldBe WagerPhase.RECONCILIATION
            repository.find(initial.matchId).join()?.legs?.first()?.status shouldBe WagerLegStatus.UNKNOWN
            wallet.calls shouldBe 0
            diagnostics.any { it.startsWith("manual_reconciliation:") } shouldBe true
            service.closeAsync().join()
            wallet.calls shouldBe 0
        }

        "durable match winner closes the completion-to-payout gap after restart" {
            val initial = wager()
            val repository = statistics.wagers()
            repository.create(initial).join()
            val held = initial.copy(revision = 1, phase = WagerPhase.HELD,
                legs = initial.legs.map { it.copy(status = WagerLegStatus.APPLIED, balanceBefore = BigDecimal("100.00"), balanceAfter = BigDecimal("90.00")) })
            repository.save(initial, held).join() shouldBe true
            val outcome = MatchOutcome(
                initial.matchId, initial.target, initial.challenger, DuelMode.OWN_INVENTORY, null, false,
                initial.serverId, Instant.parse("2026-10-29T12:00:00Z"), stakeMinor = initial.stakeMinor,
            )
            statistics.record(outcome).join()
            statistics.findMatch(initial.matchId).join()?.outcome?.rules?.stakeMinor shouldBe initial.stakeMinor
            val wallet = IntegrationWagerWallet(initial)
            val service = DuelWagerService(repository, wallet, initial.serverId,
                recordedWinner = { id -> statistics.findMatch(id).thenApply { it?.outcome?.winner } })
            service.recover().join()
            service.closeAsync().join()
            wallet.calls shouldBe 1
            wallet.balances[initial.target] shouldBe BigDecimal("110.00")
            wallet.balances[initial.challenger] shouldBe BigDecimal("90.00")
            MySqlDuelWagerRepository(runtime).find(initial.matchId).join()?.phase shouldBe WagerPhase.PAID
            DuelWagerService(repository, wallet, initial.serverId).recover().join()
            wallet.calls shouldBe 1
        }

        "saved presets retain stake cents and old zero-stake terms still round-trip" {
            val player = PlayerId(UUID.randomUUID())
            val preset = DuelPreset(player, 1, DuelRules(DuelMode.OWN_INVENTORY, stakeMinor = 12_345), null,
                Instant.parse("2026-10-29T12:00:00Z"))
            statistics.savePreset(preset).join()
            statistics.savePreset(preset.copy(slot = 2, rules = preset.rules.copy(stakeMinor = 0))).join()
            statistics.presets(player).join().map { it.rules.stakeMinor } shouldBe listOf(12_345L, 0L)
        }
    }

    private fun wager() = DuelWager(MatchId.random(), ServerId("wager-it-${UUID.randomUUID().toString().take(8)}"),
        PlayerId(UUID.randomUUID()), PlayerId(UUID.randomUUID()), 1_000)
}

private class IntegrationWagerWallet(wager: DuelWager) : DuelWagerWallet {
    val balances = mutableMapOf(wager.challenger to BigDecimal("90.00"), wager.target to BigDecimal("90.00"))
    var calls = 0
    override val available = true
    override fun balance(playerId: PlayerId): CompletableFuture<BigDecimal?> = CompletableFuture.completedFuture(balances[playerId])
    override fun transfer(playerId: PlayerId, amountMinor: Long, reason: String, expectedBalance: BigDecimal): CompletableFuture<WagerTransferResult> {
        check(balances[playerId] == expectedBalance)
        calls++
        val after = expectedBalance.add(BigDecimal.valueOf(amountMinor, 2))
        balances[playerId] = after
        return CompletableFuture.completedFuture(WagerTransferResult(WagerLegStatus.APPLIED, after))
    }
}
