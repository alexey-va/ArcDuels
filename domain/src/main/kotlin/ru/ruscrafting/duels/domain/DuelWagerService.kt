package ru.ruscrafting.duels.domain

import java.math.BigDecimal
import java.util.ConcurrentModificationException
import java.util.concurrent.CompletableFuture

/**
 * Serial asynchronous money owner. MySQL acknowledges each CALLING intent before one provider call.
 * Cancelling an observer never cancels accepted work. The caller must drain this owner before SQL
 * and the economy provider are retired; no gameplay thread waits for completion here.
 */
class DuelWagerService(
    private val repository: DuelWagerRepository,
    private val wallet: DuelWagerWallet,
    private val serverId: ServerId,
    private val diagnostic: (MatchId, String, Throwable?) -> Unit = { _, _, _ -> },
    private val recordedWinner: (MatchId) -> CompletableFuture<PlayerId?> = { CompletableFuture.completedFuture(null) },
) {
    private val admission = Any()
    // ponytail: one async money lane per backend; split by participant only if measured demand needs it.
    private var tail = CompletableFuture.completedFuture(Unit)
    @Volatile private var closing: CompletableFuture<Unit>? = null
    @Volatile private var recovered = false

    val available: Boolean get() = recovered && closing == null && wallet.available

    fun prepare(challenge: DuelChallenge): CompletableFuture<WagerPrepareResult> {
        if (challenge.rules.stakeMinor == 0L) return done(WagerPrepareResult.Ready)
        if (!recovered) return done(WagerPrepareResult.Rejected("recovering"))
        val matchId = MatchId(challenge.id.value)
        return enqueue {
            val requested = DuelWager(matchId, serverId, challenge.challenger, challenge.target, challenge.rules.stakeMinor)
            repository.create(requested).thenCompose { existing ->
                check(existing.serverId == serverId && existing.challenger == requested.challenger &&
                    existing.target == requested.target && existing.stakeMinor == requested.stakeMinor) {
                    "Wager identity collides with different accepted terms: $matchId"
                }
                prepareRecord(existing)
            }.thenApply { wager ->
                when (wager.phase) {
                    WagerPhase.HELD -> WagerPrepareResult.Ready
                    WagerPhase.RECONCILIATION -> WagerPrepareResult.Reconciliation(wager.reason ?: "manual_reconciliation")
                    else -> WagerPrepareResult.Rejected(wager.reason ?: "already_settled")
                }
            }.whenComplete { _, failure -> if (failure != null) diagnostic(matchId, "prepare_failed", failure) }
        }
    }

    /** A null winner refunds; a winner is accepted only after both debits are durably confirmed. */
    fun settle(matchId: MatchId, winner: PlayerId?): CompletableFuture<WagerSettlementResult> = enqueue {
        repository.find(matchId).thenCompose { wager ->
            if (wager == null) return@thenCompose done(WagerSettlementResult.NoWager)
            require(wager.serverId == serverId) { "Wager belongs to another backend" }
            require(winner == null || winner == wager.challenger || winner == wager.target) { "Winner is not a wager participant" }
            if (wager.winner != null && winner != null) require(wager.winner == winner) { "Conflicting durable wager winner" }
            val finishing = if (winner == null && wager.phase == WagerPhase.HELD) {
                attempt { recordedWinner(matchId) }.thenCompose { finish(wager, it) }
            } else finish(wager, winner)
            finishing.thenApply(::settlementResult)
        }.whenComplete { _, failure -> if (failure != null) diagnostic(matchId, "settlement_failed", failure) }
    }

    /** Run before admitting stakes. Confirmed old holds refund unless match history has a winner. */
    fun recover(): CompletableFuture<Unit> = if (recovered) done(Unit) else enqueue {
        if (recovered) done(Unit) else recoverPage(null, retryDeferredCredits = true).thenApply { recovered = true; Unit }
    }

    /** Drains accepted work. Credits whose native callback cannot enter remain durable for next startup. */
    fun closeAsync(): CompletableFuture<Unit> {
        val predecessor: CompletableFuture<Unit>
        val result: CompletableFuture<Unit>
        synchronized(admission) {
            closing?.let { return it.copy() }
            result = CompletableFuture()
            closing = result
            predecessor = tail
        }
        predecessor.whenComplete { _, _ ->
            attempt { recoverPage(null, retryDeferredCredits = false) }.whenComplete { _, failure ->
                if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
            }
        }
        return result.copy()
    }

    private fun prepareRecord(wager: DuelWager): CompletableFuture<DuelWager> {
        if (wager.phase != WagerPhase.PREPARING) return done(wager)
        val uncertain = wager.legs.firstOrNull { it.status == WagerLegStatus.CALLING || it.status == WagerLegStatus.UNKNOWN }
        if (uncertain != null) return quarantine(wager, "unconfirmed_${uncertain.kind.name.lowercase()}")
        val rejected = wager.legs.firstOrNull { it.status == WagerLegStatus.REJECTED || it.status == WagerLegStatus.DEFERRED }
        if (rejected != null) return refund(wager, rejected.reason ?: "withdrawal_rejected").thenCompose(::release)
        val pending = wager.legs.firstOrNull { it.status == WagerLegStatus.PENDING }
        if (pending == null) return save(wager, wager.copy(phase = WagerPhase.HELD))
        return applyLeg(wager, pending).thenCompose(::prepareRecord)
    }

    private fun finish(wager: DuelWager, winner: PlayerId?): CompletableFuture<DuelWager> {
        if (wager.complete || wager.phase == WagerPhase.RECONCILIATION) return done(wager)
        if (wager.legs.any { it.status == WagerLegStatus.CALLING || it.status == WagerLegStatus.UNKNOWN }) {
            return quarantine(wager, "provider_outcome_unknown")
        }
        return when (wager.phase) {
            WagerPhase.PREPARING -> refund(wager, wager.reason ?: "cancelled_before_start").thenCompose(::release)
            WagerPhase.HELD -> {
                if (winner == null) refund(wager, "cancelled_or_draw").thenCompose(::release)
                else {
                    val payout = WagerLeg(WagerLegKind.PAYOUT, winner, wager.stakeMinor * 2)
                    save(wager, wager.copy(phase = WagerPhase.PAYOUT, winner = winner, legs = wager.legs + payout)).thenCompose(::release)
                }
            }
            WagerPhase.PAYOUT, WagerPhase.REFUND -> release(wager)
            else -> done(wager)
        }
    }

    private fun refund(wager: DuelWager, reason: String): CompletableFuture<DuelWager> {
        val refunds = wager.legs.filter { it.amountMinor < 0 && it.status == WagerLegStatus.APPLIED }.map { debit ->
            WagerLeg(
                if (debit.kind == WagerLegKind.WITHDRAW_CHALLENGER) WagerLegKind.REFUND_CHALLENGER else WagerLegKind.REFUND_TARGET,
                debit.playerId,
                -debit.amountMinor,
            )
        }
        return save(wager, wager.copy(phase = WagerPhase.REFUND, legs = wager.legs + refunds, reason = reason))
    }

    private fun release(wager: DuelWager): CompletableFuture<DuelWager> {
        val credits = wager.legs.filter { it.amountMinor > 0 }
        val failed = credits.firstOrNull { it.status in setOf(WagerLegStatus.CALLING, WagerLegStatus.UNKNOWN, WagerLegStatus.REJECTED) }
        if (failed != null) return quarantine(wager, failed.reason ?: "unconfirmed_${failed.kind.name.lowercase()}")
        // A retired native scope cannot run a credit. Keep its decision and do not loop or retry on close.
        if (credits.any { it.status == WagerLegStatus.DEFERRED }) return done(wager)
        val pending = credits.firstOrNull { it.status == WagerLegStatus.PENDING }
        if (pending != null) return applyLeg(wager, pending).thenCompose(::release)
        return save(wager, wager.copy(phase = if (wager.phase == WagerPhase.PAYOUT) WagerPhase.PAID else WagerPhase.REFUNDED))
    }

    private fun applyLeg(wager: DuelWager, leg: WagerLeg): CompletableFuture<DuelWager> =
        attempt { wallet.balance(leg.playerId) }.handle { balance, failure ->
            if (failure != null) diagnostic(wager.matchId, "balance_unavailable_${leg.kind.name.lowercase()}", failure)
            balance
        }.thenCompose { balance ->
            if (balance == null || balance.signum() < 0) {
                return@thenCompose replaceLeg(wager, leg.copy(status = WagerLegStatus.REJECTED, reason = "provider_unavailable"))
            }
            if (leg.amountMinor < 0 && balance < BigDecimal.valueOf(-leg.amountMinor, 2)) {
                return@thenCompose replaceLeg(wager, leg.copy(status = WagerLegStatus.REJECTED, balanceBefore = balance, reason = "insufficient_funds"))
            }
            val calling = leg.copy(status = WagerLegStatus.CALLING, balanceBefore = balance)
            replaceLeg(wager, calling).thenCompose { intended ->
                val reason = "arc-duel:${wager.matchId}:${leg.kind.name.lowercase()}"
                attempt { wallet.transfer(leg.playerId, leg.amountMinor, reason, balance) }
                    .handle { result, failure ->
                        if (failure != null) {
                            diagnostic(wager.matchId, "provider_outcome_unknown_${leg.kind.name.lowercase()}", failure)
                            WagerTransferResult(WagerLegStatus.UNKNOWN, reason = "provider_exception")
                        } else requireNotNull(result)
                    }.thenCompose { result ->
                        if (result.failure != null) diagnostic(wager.matchId, result.reason ?: "provider_failed", result.failure)
                        replaceLeg(intended, calling.copy(status = result.status, balanceAfter = result.balanceAfter, reason = result.reason))
                    }
            }
        }

    private fun quarantine(wager: DuelWager, reason: String): CompletableFuture<DuelWager> {
        val held = wager.copy(
            phase = WagerPhase.RECONCILIATION,
            reason = reason,
            legs = wager.legs.map { if (it.status == WagerLegStatus.CALLING) it.copy(status = WagerLegStatus.UNKNOWN, reason = "interrupted_provider_call") else it },
        )
        return save(wager, held).thenApply {
            diagnostic(it.matchId, "manual_reconciliation:$reason", null)
            it
        }
    }

    private fun recoverPage(after: MatchId?, retryDeferredCredits: Boolean): CompletableFuture<Unit> =
        repository.incomplete(serverId, after, RECOVERY_BATCH).thenCompose { wagers ->
            var page = done(Unit)
            wagers.forEach { wager ->
                page = page.thenCompose {
                    val prepared = if (retryDeferredCredits && wager.phase in setOf(WagerPhase.PAYOUT, WagerPhase.REFUND) &&
                        wager.legs.any { it.amountMinor > 0 && it.status == WagerLegStatus.DEFERRED }) {
                        save(wager, wager.copy(legs = wager.legs.map {
                            if (it.amountMinor > 0 && it.status == WagerLegStatus.DEFERRED) {
                                it.copy(status = WagerLegStatus.PENDING, balanceBefore = null, balanceAfter = null, reason = null)
                            } else it
                        }))
                    } else done(wager)
                    prepared.thenCompose { current ->
                        if (current.phase == WagerPhase.RECONCILIATION) {
                            diagnostic(current.matchId, "manual_reconciliation:${current.reason}", null)
                            done(Unit)
                        } else if (current.phase == WagerPhase.HELD) {
                            attempt { recordedWinner(current.matchId) }.thenCompose { finish(current, it) }.thenApply { Unit }
                        } else finish(current, current.winner).thenApply { Unit }
                    }
                }
            }
            page.thenCompose { if (wagers.size == RECOVERY_BATCH) recoverPage(wagers.last().matchId, retryDeferredCredits) else done(Unit) }
        }

    private fun replaceLeg(wager: DuelWager, leg: WagerLeg): CompletableFuture<DuelWager> =
        save(wager, wager.copy(legs = wager.legs.map { if (it.kind == leg.kind) leg else it })).thenApply {
            if (leg.status in setOf(WagerLegStatus.REJECTED, WagerLegStatus.UNKNOWN, WagerLegStatus.DEFERRED)) {
                diagnostic(wager.matchId, "${leg.kind.name.lowercase()}:${leg.reason ?: leg.status.name.lowercase()}", null)
            }
            it
        }

    private fun save(wager: DuelWager, updated: DuelWager): CompletableFuture<DuelWager> {
        val next = updated.copy(revision = Math.addExact(wager.revision, 1))
        return repository.save(wager, next).thenApply { saved ->
            if (!saved) throw ConcurrentModificationException("Wager changed before durable transition: ${wager.matchId}")
            next
        }
    }

    private fun settlementResult(wager: DuelWager): WagerSettlementResult = when (wager.phase) {
        WagerPhase.PAID -> WagerSettlementResult.Paid
        WagerPhase.REFUNDED -> WagerSettlementResult.Refunded
        WagerPhase.PAYOUT, WagerPhase.REFUND -> WagerSettlementResult.Deferred(
            wager.legs.firstOrNull { it.status == WagerLegStatus.DEFERRED }?.reason ?: "native_dispatch_deferred",
        )
        else -> WagerSettlementResult.Reconciliation(wager.reason ?: "manual_reconciliation")
    }

    private fun <T> enqueue(block: () -> CompletableFuture<T>): CompletableFuture<T> {
        val predecessor: CompletableFuture<Unit>
        val result = CompletableFuture<T>()
        synchronized(admission) {
            if (closing != null) return CompletableFuture.failedFuture(IllegalStateException("Wager service is closing"))
            predecessor = tail
            tail = result.handle { _, _ -> Unit }
        }
        predecessor.whenComplete { _, _ ->
            attempt(block).whenComplete { value, failure ->
                if (failure == null) result.complete(value) else result.completeExceptionally(failure)
            }
        }
        return result.copy()
    }

    private fun <T> attempt(block: () -> CompletableFuture<T>): CompletableFuture<T> =
        try { block() } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }

    private fun <T> done(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)

    private companion object { const val RECOVERY_BATCH = 100 }
}
