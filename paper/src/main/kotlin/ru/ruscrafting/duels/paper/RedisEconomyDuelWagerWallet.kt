package ru.ruscrafting.duels.paper

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI
import dev.unnm3d.rediseconomy.currency.Currency
import ru.ruscrafting.duels.domain.DuelWagerWallet
import ru.ruscrafting.duels.domain.MAX_DUEL_STAKE_MINOR
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.WagerLegStatus
import ru.ruscrafting.duels.domain.WagerTransferResult
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exact RedisEconomy UUID API: the second mutation argument is the cached player name.
 * Calls use only vault, never payPlayer (which can tax transfers). All Redis waits run on the
 * supplied worker; the cached recheck and single mutation run through the owning-thread dispatcher.
 * SUCCESS alone is insufficient: the expected persisted balance must be observed.
 */
internal class RedisEconomyDuelWagerWallet(
    private val executor: Executor,
    private val dispatch: (() -> WagerTransferResult) -> CompletableFuture<WagerTransferResult>,
    private val apiProvider: () -> RedisEconomyAPI? = RedisEconomyAPI::getAPI,
) : DuelWagerWallet {
    override val available: Boolean get() = runCatching { currency()?.let(::safeCurrency) == true }.getOrDefault(false)

    override fun balance(playerId: PlayerId): CompletableFuture<BigDecimal?> = CompletableFuture.supplyAsync({
        val currency = currency() ?: return@supplyAsync null
        if (!safeCurrency(currency) || !currency.hasAccount(playerId.value)) return@supplyAsync null
        val cached = decimal(currency.getBalance(playerId.value)) ?: return@supplyAsync null
        val persisted = readRedis(currency, playerId.value) ?: return@supplyAsync null
        cached.takeIf { sameBalance(it, persisted) }
    }, executor)

    override fun transfer(
        playerId: PlayerId,
        amountMinor: Long,
        reason: String,
        expectedBalance: BigDecimal,
    ): CompletableFuture<WagerTransferResult> {
        require(amountMinor != 0L && amountMinor in -MAX_DUEL_STAKE_MINOR..MAX_DUEL_STAKE_MINOR * 2)
        require(reason.length <= 100 && reason.startsWith("arc-duel:"))
        return CompletableFuture.supplyAsync({ prepare(playerId.value, amountMinor, expectedBalance) }, executor).thenCompose { prepared ->
            when (prepared) {
                is Preparation.Stopped -> CompletableFuture.completedFuture(prepared.result)
                is Preparation.Ready -> {
                    val entered = AtomicBoolean()
                    val native = try {
                        dispatch {
                            entered.set(true)
                            mutateCached(prepared, playerId.value, amountMinor, reason, expectedBalance)
                        }
                    } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
                    native.handle { result, failure ->
                        if (failure == null) requireNotNull(result)
                        else {
                            val cause = if (failure is CompletionException) failure.cause ?: failure else failure
                            WagerTransferResult(
                                if (entered.get()) WagerLegStatus.UNKNOWN else WagerLegStatus.DEFERRED,
                                reason = if (entered.get()) "native_dispatch_outcome_unknown" else "native_dispatch_deferred",
                                failure = cause.takeUnless { it is CancellationException },
                            )
                        }
                    }.thenApplyAsync({ result -> confirm(prepared, playerId.value, expectedBalance, result) }, executor)
                }
            }
        }
    }

    private fun prepare(playerId: UUID, amountMinor: Long, before: BigDecimal): Preparation {
        try {
            val api = apiProvider() ?: return Preparation.Stopped(rejected("provider_unavailable"))
            val currency = api.getCurrencyByName("vault") ?: return Preparation.Stopped(rejected("provider_unavailable"))
            if (!safeCurrency(currency)) return Preparation.Stopped(rejected("provider_tax_or_currency_invalid"))
            if (!currency.hasAccount(playerId)) return Preparation.Stopped(rejected("provider_account_missing"))
            val cached = decimal(currency.getBalance(playerId)) ?: return Preparation.Stopped(rejected("provider_balance_invalid"))
            val observed = readRedis(currency, playerId)
            if (observed == null || !sameBalance(before, cached) || !sameBalance(before, observed)) {
                return Preparation.Stopped(rejected("provider_balance_changed", observed))
            }
            val signed = BigDecimal.valueOf(amountMinor, 2)
            val expected = before.add(signed)
            if (expected.signum() < 0) return Preparation.Stopped(rejected("insufficient_funds", observed))
            if (!sameBalance(expected, expected)) return Preparation.Stopped(rejected("provider_precision_unsafe", observed))
            return Preparation.Ready(api, currency, expected, signed.abs())
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return Preparation.Stopped(WagerTransferResult(WagerLegStatus.REJECTED, reason = "provider_unavailable", failure = failure))
        }
    }

    /** Owning thread only: no Redis reads, waits, filesystem or SQL in this callback. */
    private fun mutateCached(prepared: Preparation.Ready, playerId: UUID, amountMinor: Long, reason: String, before: BigDecimal): WagerTransferResult {
        var called = false
        try {
            val api = apiProvider() ?: return rejected("provider_unavailable")
            val currency = prepared.currency
            if (api !== prepared.api || api.getCurrencyByName("vault") !== currency) return rejected("provider_changed")
            if (!safeCurrency(currency)) return rejected("provider_tax_or_currency_invalid")
            if (!currency.hasAccount(playerId)) return rejected("provider_account_missing")
            val cached = decimal(currency.getBalance(playerId)) ?: return rejected("provider_balance_invalid")
            if (!sameBalance(before, cached)) return rejected("provider_balance_changed", cached)
            val cachedName = api.getUsernameFromUUIDCache(playerId)
            called = true
            val response = if (amountMinor > 0) currency.depositPlayer(playerId, cachedName, prepared.amount.toDouble(), reason)
                else currency.withdrawPlayer(playerId, cachedName, prepared.amount.toDouble(), reason)
            if (!response.transactionSuccess()) {
                val cacheAfter = decimal(currency.getBalance(playerId))
                return if (cacheAfter != null && sameBalance(before, cacheAfter)) rejected("provider_rejected", cacheAfter)
                else WagerTransferResult(WagerLegStatus.UNKNOWN, cacheAfter, "provider_rejection_changed_balance")
            }
            if (!response.amount.isFinite() || !sameBalance(prepared.amount, BigDecimal.valueOf(response.amount))) {
                return WagerTransferResult(WagerLegStatus.UNKNOWN, reason = "provider_amount_mismatch")
            }
            // Internal candidate only. transfer() never exposes APPLIED before worker confirmation.
            return WagerTransferResult(WagerLegStatus.APPLIED, decimal(currency.getBalance(playerId)))
        } catch (failure: Exception) {
            return WagerTransferResult(
                if (called) WagerLegStatus.UNKNOWN else WagerLegStatus.REJECTED,
                reason = if (called) "provider_call_unconfirmed" else "provider_unavailable",
                failure = failure,
            )
        }
    }

    /** Worker only; even a provider rejection is checked against persisted state before acknowledgement. */
    private fun confirm(prepared: Preparation.Ready, playerId: UUID, before: BigDecimal, result: WagerTransferResult): WagerTransferResult {
        val rejectedAfterCall = result.status == WagerLegStatus.REJECTED && result.reason == "provider_rejected"
        if (result.status != WagerLegStatus.APPLIED && !rejectedAfterCall) return result
        var observed: BigDecimal? = null
        try {
            val currency = prepared.currency
            val expected = if (rejectedAfterCall) before else prepared.expected
            repeat(CONFIRM_ATTEMPTS) { attempt ->
                observed = readRedis(currency, playerId)
                val cacheAfter = decimal(currency.getBalance(playerId))
                if (observed != null && cacheAfter != null && sameBalance(expected, observed) && sameBalance(expected, cacheAfter)) {
                    return result.copy(balanceAfter = observed)
                }
                if (attempt + 1 < CONFIRM_ATTEMPTS) Thread.sleep(CONFIRM_DELAY_MILLIS)
            }
            return WagerTransferResult(WagerLegStatus.UNKNOWN, observed, "provider_durability_unconfirmed")
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return WagerTransferResult(
                WagerLegStatus.UNKNOWN,
                observed,
                "provider_call_unconfirmed",
                failure,
            )
        }
    }

    private sealed interface Preparation {
        data class Stopped(val result: WagerTransferResult) : Preparation
        data class Ready(val api: RedisEconomyAPI, val currency: Currency, val expected: BigDecimal, val amount: BigDecimal) : Preparation
    }

    private fun currency(): Currency? = apiProvider()?.getCurrencyByName("vault")
    private fun safeCurrency(currency: Currency): Boolean =
        currency.isEnabled && currency.currencyName == "vault" && currency.transactionTax == 0.0

    private fun readRedis(currency: Currency, playerId: UUID): BigDecimal? =
        currency.getAccountRedis(playerId).toCompletableFuture().get(REDIS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)?.let(::decimal)

    private fun rejected(reason: String, balance: BigDecimal? = null): WagerTransferResult =
        WagerTransferResult(WagerLegStatus.REJECTED, balance, reason)

    private fun decimal(value: Double): BigDecimal? = value.takeIf { it.isFinite() && it >= 0.0 }?.let(BigDecimal::valueOf)

    private fun sameBalance(first: BigDecimal, second: BigDecimal): Boolean {
        val tolerance = maxOf(0.00000001, Math.ulp(maxOf(first.abs().toDouble(), second.abs().toDouble())) * 4)
        // Refuse magnitudes where floating-point error could conceal even one tenth of a cent.
        return tolerance.isFinite() && tolerance < 0.001 && first.subtract(second).abs() <= BigDecimal.valueOf(tolerance)
    }

    private companion object {
        const val REDIS_TIMEOUT_MILLIS = 1_000L
        const val CONFIRM_ATTEMPTS = 10
        const val CONFIRM_DELAY_MILLIS = 25L
    }
}
