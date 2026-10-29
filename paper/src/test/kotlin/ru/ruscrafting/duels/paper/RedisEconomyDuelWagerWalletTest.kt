package ru.ruscrafting.duels.paper

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.milkbowl.vault.economy.EconomyResponse
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.WagerLegStatus
import ru.ruscrafting.duels.domain.WagerTransferResult
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RedisEconomyDuelWagerWalletTest : StringSpec({
    "explicit coin currency and UUID account use the real cached player name and retain fractional balances" {
        val f = RedisWagerFixture()
        val wallet = f.wallet()
        wallet.balance(f.player).join() shouldBe BigDecimal("100.1234")
        val result = wallet.transfer(f.player, -1, REASON, BigDecimal("100.1234")).join()
        result.status shouldBe WagerLegStatus.APPLIED
        result.balanceAfter?.subtract(BigDecimal("100.1134"))?.abs()?.toDouble()?.let { it < 0.00000001 } shouldBe true
        verify(exactly = 1) { f.currency.withdrawPlayer(f.player.value, "PlayerName", 0.01, REASON) }
        verify(exactly = 0) { f.api.defaultCurrency }
        verify(exactly = 0) { f.api.getCurrencyByName("tokens") }
    }

    "nonzero provider tax rejects before a mutation even when tax-only-pay is configured" {
        val f = RedisWagerFixture()
        every { f.currency.transactionTax } returns 0.05
        every { f.currency.isTaxOnlyPay } returns true
        val wallet = f.wallet()
        wallet.available shouldBe false
        wallet.transfer(f.player, -100, REASON, BigDecimal("100.1234")).join().status shouldBe WagerLegStatus.REJECTED
        verify(exactly = 0) { f.currency.withdrawPlayer(any<UUID>(), any(), any(), any()) }
    }

    "changed or stale balance prevents mutation" {
        val f = RedisWagerFixture()
        f.persisted = 99.0
        f.wallet().balance(f.player).join() shouldBe null
        val result = f.wallet().transfer(f.player, -100, REASON, BigDecimal("100.1234")).join()
        result.status shouldBe WagerLegStatus.REJECTED
        verify(exactly = 0) { f.currency.withdrawPlayer(any<UUID>(), any(), any(), any()) }
    }

    "a cache-only SUCCESS remains unknown and is never blindly replayed" {
        val f = RedisWagerFixture()
        f.persistWrites = false
        val result = f.wallet().transfer(f.player, -100, REASON, BigDecimal("100.1234")).join()
        result.status shouldBe WagerLegStatus.UNKNOWN
        result.reason shouldBe "provider_durability_unconfirmed"
        verify(exactly = 1) { f.currency.withdrawPlayer(any<UUID>(), any(), any(), any()) }
    }

    "a provider exception after changing the wallet remains unknown with its cause" {
        val f = RedisWagerFixture()
        val failure = IllegalStateException("provider disconnect")
        every { f.currency.withdrawPlayer(any<UUID>(), any(), any(), any()) } answers { f.cached -= 1.0; throw failure }
        val result = f.wallet().transfer(f.player, -100, REASON, BigDecimal("100.1234")).join()
        result.status shouldBe WagerLegStatus.UNKNOWN
        result.failure shouldBe failure
        verify(exactly = 1) { f.currency.withdrawPlayer(any<UUID>(), any(), any(), any()) }
    }

    "a rejected payout without any balance change preserves a known rejection" {
        val f = RedisWagerFixture()
        every { f.currency.depositPlayer(any<UUID>(), any(), any(), any()) } returns
            EconomyResponse(0.0, f.cached, EconomyResponse.ResponseType.FAILURE, "maximum balance")
        val result = f.wallet().transfer(f.player, 200_000_000, REASON, BigDecimal("100.1234")).join()
        result.status shouldBe WagerLegStatus.REJECTED
        verify(exactly = 1) { f.currency.depositPlayer(any<UUID>(), any(), any(), any()) }
    }

    "owning-thread balance recheck prevents overwriting a credit that arrived after worker preflight" {
        val f = RedisWagerFixture()
        val scheduler = TestTaskScheduler()
        LifecycleTaskScope(scheduler).use { scope ->
            val result = f.wallet(dispatch = { scope.callSync(it) }).transfer(f.player, -1_000, REASON, BigDecimal("100.1234"))
            result.isDone shouldBe false
            verify(exactly = 0) { f.currency.withdrawPlayer(any<UUID>(), any(), any(), any()) }
            f.cached += 50.0
            f.persisted = f.cached
            scheduler.executeImmediate()
            result.join().status shouldBe WagerLegStatus.REJECTED
            result.join().reason shouldBe "provider_balance_changed"
            f.cached shouldBe 150.1234
            f.persisted shouldBe 150.1234
            verify(exactly = 0) { f.currency.withdrawPlayer(any<UUID>(), any(), any(), any()) }
        }
    }

    "closing the native scope cancels a queued payment without a provider call" {
        val f = RedisWagerFixture()
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val result = f.wallet(dispatch = { scope.callSync(it) }).transfer(f.player, -100, REASON, BigDecimal("100.1234"))
        result.isDone shouldBe false
        scope.close()
        result.join().status shouldBe WagerLegStatus.DEFERRED
        scheduler.executeImmediate()
        f.cached shouldBe 100.1234
        verify(exactly = 0) { f.currency.withdrawPlayer(any<UUID>(), any(), any(), any()) }
    }

    "closing a scope after the native payment entered does not relabel an applied payment as deferred" {
        val f = RedisWagerFixture()
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        every { f.currency.withdrawPlayer(any<UUID>(), any(), any(), any()) } answers {
            scope.close()
            val amount = thirdArg<Double>()
            f.cached -= amount
            f.persisted = f.cached
            EconomyResponse(amount, f.cached, EconomyResponse.ResponseType.SUCCESS, null)
        }
        val result = f.wallet(dispatch = { scope.callSync(it) }).transfer(f.player, -100, REASON, BigDecimal("100.1234"))
        scheduler.executeImmediate()
        result.join().status shouldBe WagerLegStatus.APPLIED
        verify(exactly = 1) { f.currency.withdrawPlayer(any<UUID>(), any(), any(), any()) }
    }

    "Redis waits stay on the worker and provider mutation executes only on the owning dispatcher" {
        val f = RedisWagerFixture()
        val caller = Thread.currentThread()
        val threads = mutableListOf<Thread>()
        every { f.currency.getAccountRedis(f.player.value) } answers {
            threads += Thread.currentThread()
            CompletableFuture.completedFuture(f.persisted)
        }
        val worker = Executors.newSingleThreadExecutor()
        val owning = Executors.newSingleThreadExecutor()
        try {
            f.wallet(worker) { operation -> CompletableFuture.supplyAsync(operation, owning) }
                .transfer(f.player, -100, REASON, BigDecimal("100.1234")).get(5, TimeUnit.SECONDS).status shouldBe WagerLegStatus.APPLIED
            threads.isNotEmpty() shouldBe true
            threads.all { it !== caller } shouldBe true
            f.mutationThreads.size shouldBe 1
            threads.none { it === f.mutationThreads.single() } shouldBe true
            f.mutationThreads.single() shouldBe owning.submit<Thread> { Thread.currentThread() }.get(5, TimeUnit.SECONDS)
        } finally { worker.shutdownNow(); owning.shutdownNow() }
    }
})

private const val REASON = "arc-duel:00000000-0000-0000-0000-000000000001:withdraw_challenger"

private class RedisWagerFixture {
    val api = mockk<RedisEconomyAPI>()
    val currency = mockk<Currency>()
    val player = PlayerId(UUID.randomUUID())
    var cached = 100.1234
    var persisted = cached
    var persistWrites = true
    val mutationThreads = mutableListOf<Thread>()
    init {
        every { api.getCurrencyByName("vault") } returns currency
        every { api.getUsernameFromUUIDCache(player.value) } returns "PlayerName"
        every { currency.currencyName } returns "vault"
        every { currency.isEnabled } returns true
        every { currency.transactionTax } returns 0.0
        every { currency.hasAccount(player.value) } returns true
        every { currency.getBalance(player.value) } answers { cached }
        every { currency.getAccountRedis(player.value) } answers { CompletableFuture.completedFuture(persisted) }
        every { currency.withdrawPlayer(any<UUID>(), any(), any(), any()) } answers {
            mutationThreads += Thread.currentThread()
            val amount = thirdArg<Double>()
            cached -= amount
            if (persistWrites) persisted = cached
            EconomyResponse(amount, cached, EconomyResponse.ResponseType.SUCCESS, null)
        }
        every { currency.depositPlayer(any<UUID>(), any(), any(), any()) } answers {
            mutationThreads += Thread.currentThread()
            val amount = thirdArg<Double>()
            cached += amount
            if (persistWrites) persisted = cached
            EconomyResponse(amount, cached, EconomyResponse.ResponseType.SUCCESS, null)
        }
    }
    fun wallet(
        executor: Executor = Executor(Runnable::run),
        dispatch: (() -> WagerTransferResult) -> CompletableFuture<WagerTransferResult> = { CompletableFuture.completedFuture(it()) },
    ) = RedisEconomyDuelWagerWallet(executor, dispatch) { api }
}
