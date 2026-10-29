package ru.ruscrafting.duels.domain

import java.math.BigDecimal
import java.util.concurrent.CompletableFuture

/** Coins have 100 minor units. The largest participant stake is 1,000,000 coins. */
const val MAX_DUEL_STAKE_MINOR: Long = 100_000_000

enum class WagerPhase { PREPARING, HELD, PAYOUT, REFUND, PAID, REFUNDED, RECONCILIATION }
enum class WagerLegKind { WITHDRAW_CHALLENGER, WITHDRAW_TARGET, REFUND_CHALLENGER, REFUND_TARGET, PAYOUT }
/** DEFERRED proves that the native callback never entered; UNKNOWN never permits replay. */
enum class WagerLegStatus { PENDING, CALLING, APPLIED, REJECTED, UNKNOWN, DEFERRED }

data class WagerLeg(
    val kind: WagerLegKind,
    val playerId: PlayerId,
    /** Negative is withdrawal, positive is credit. */
    val amountMinor: Long,
    val status: WagerLegStatus = WagerLegStatus.PENDING,
    val balanceBefore: BigDecimal? = null,
    val balanceAfter: BigDecimal? = null,
    val reason: String? = null,
) {
    init {
        require(amountMinor != 0L && amountMinor in -MAX_DUEL_STAKE_MINOR..(MAX_DUEL_STAKE_MINOR * 2))
        require(reason == null || reason.length <= 160)
        require(balanceBefore == null || balanceBefore.signum() >= 0 && balanceBefore.toPlainString().length <= 64)
        require(balanceAfter == null || balanceAfter.signum() >= 0 && balanceAfter.toPlainString().length <= 64)
    }
}

/** Immutable accepted terms and every provider attempt survive reload; completed receipts are retained. */
data class DuelWager(
    val matchId: MatchId,
    val serverId: ServerId,
    val challenger: PlayerId,
    val target: PlayerId,
    val stakeMinor: Long,
    val phase: WagerPhase = WagerPhase.PREPARING,
    val winner: PlayerId? = null,
    val legs: List<WagerLeg> = listOf(
        WagerLeg(WagerLegKind.WITHDRAW_CHALLENGER, challenger, -stakeMinor),
        WagerLeg(WagerLegKind.WITHDRAW_TARGET, target, -stakeMinor),
    ),
    val revision: Long = 0,
    val reason: String? = null,
) {
    init {
        require(challenger != target)
        require(stakeMinor in 1..MAX_DUEL_STAKE_MINOR)
        require(winner == null || winner == challenger || winner == target)
        require(revision >= 0)
        require(reason == null || reason.length <= 160)
        require(legs.size in 2..4 && legs.map { it.kind }.distinct().size == legs.size)
        require(legs.count { it.kind == WagerLegKind.WITHDRAW_CHALLENGER } == 1)
        require(legs.count { it.kind == WagerLegKind.WITHDRAW_TARGET } == 1)
        legs.forEach { leg ->
            when (leg.kind) {
                WagerLegKind.WITHDRAW_CHALLENGER -> require(leg.playerId == challenger && leg.amountMinor == -stakeMinor)
                WagerLegKind.WITHDRAW_TARGET -> require(leg.playerId == target && leg.amountMinor == -stakeMinor)
                WagerLegKind.REFUND_CHALLENGER -> require(leg.playerId == challenger && leg.amountMinor == stakeMinor)
                WagerLegKind.REFUND_TARGET -> require(leg.playerId == target && leg.amountMinor == stakeMinor)
                WagerLegKind.PAYOUT -> require(leg.playerId == winner && leg.amountMinor == stakeMinor * 2)
            }
        }
        val debits = legs.filter { it.amountMinor < 0 }
        val credits = legs.filter { it.amountMinor > 0 }
        require(credits.sumOf { it.amountMinor } <= -debits.filter { it.status == WagerLegStatus.APPLIED }.sumOf { it.amountMinor }) {
            "Credits must be backed by confirmed withdrawals"
        }
        require(winner == null || debits.all { it.status == WagerLegStatus.APPLIED })
        require(credits.none { it.kind == WagerLegKind.PAYOUT } || credits.size == 1)
        credits.filter { it.kind != WagerLegKind.PAYOUT }.forEach { refund ->
            require(debits.any { it.playerId == refund.playerId && it.status == WagerLegStatus.APPLIED }) {
                "A refund must return that participant's confirmed withdrawal"
            }
        }
        when (phase) {
            WagerPhase.PREPARING -> require(winner == null && credits.isEmpty())
            WagerPhase.HELD -> require(winner == null && credits.isEmpty() && debits.all { it.status == WagerLegStatus.APPLIED })
            WagerPhase.PAYOUT, WagerPhase.PAID -> require(winner != null && credits.singleOrNull()?.kind == WagerLegKind.PAYOUT)
            WagerPhase.REFUND, WagerPhase.REFUNDED -> require(winner == null && credits.none { it.kind == WagerLegKind.PAYOUT })
            WagerPhase.RECONCILIATION -> Unit
        }
        require(!complete || credits.all { it.status == WagerLegStatus.APPLIED })
        require(!complete || credits.sumOf { it.amountMinor } == -debits.filter { it.status == WagerLegStatus.APPLIED }.sumOf { it.amountMinor }) {
            "A completed receipt must release every confirmed stake"
        }
    }

    val complete: Boolean get() = phase == WagerPhase.PAID || phase == WagerPhase.REFUNDED
}

interface DuelWagerRepository {
    /** Inserts the immutable terms or returns the existing receipt; never replaces another match. */
    fun create(wager: DuelWager): CompletableFuture<DuelWager>
    fun find(matchId: MatchId): CompletableFuture<DuelWager?>
    /** Atomic revision compare-and-set, including its bounded set of payment legs. */
    fun save(expected: DuelWager, updated: DuelWager): CompletableFuture<Boolean>
    fun incomplete(serverId: ServerId, after: MatchId?, limit: Int): CompletableFuture<List<DuelWager>>
}

data class WagerTransferResult(
    val status: WagerLegStatus,
    val balanceAfter: BigDecimal? = null,
    val reason: String? = null,
    val failure: Throwable? = null,
) {
    init { require(status in setOf(WagerLegStatus.APPLIED, WagerLegStatus.REJECTED, WagerLegStatus.UNKNOWN, WagerLegStatus.DEFERRED)) }
}

/** Implementations dispatch all I/O off gameplay threads. APPLIED means durable provider confirmation. */
interface DuelWagerWallet {
    val available: Boolean
    fun balance(playerId: PlayerId): CompletableFuture<BigDecimal?>
    /** One provider call at most. An unknown outcome must never be retried automatically. */
    fun transfer(playerId: PlayerId, amountMinor: Long, reason: String, expectedBalance: BigDecimal): CompletableFuture<WagerTransferResult>
}

sealed interface WagerPrepareResult {
    data object Ready : WagerPrepareResult
    data class Rejected(val reason: String) : WagerPrepareResult
    data class Reconciliation(val reason: String) : WagerPrepareResult
}

sealed interface WagerSettlementResult {
    data object NoWager : WagerSettlementResult
    data object Paid : WagerSettlementResult
    data object Refunded : WagerSettlementResult
    data class Deferred(val reason: String) : WagerSettlementResult
    data class Reconciliation(val reason: String) : WagerSettlementResult
}
