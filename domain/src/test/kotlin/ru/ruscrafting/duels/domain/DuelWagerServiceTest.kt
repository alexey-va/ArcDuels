package ru.ruscrafting.duels.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class DuelWagerServiceTest : StringSpec({
    "duplicate acceptance and completion move exactly two stakes and one pot" {
        val f = WagerFixture()
        f.service.prepare(f.challenge).join() shouldBe WagerPrepareResult.Ready
        f.service.prepare(f.challenge).join() shouldBe WagerPrepareResult.Ready
        f.wallet.calls.map { it.second } shouldBe listOf(-1_000L, -1_000L)
        f.wallet.balances.values.reduce(BigDecimal::add) shouldBe BigDecimal("180.1234")
        f.service.settle(f.id, f.first).join() shouldBe WagerSettlementResult.Paid
        f.service.settle(f.id, f.first).join() shouldBe WagerSettlementResult.Paid
        f.service.settle(f.id, null).join() shouldBe WagerSettlementResult.Paid
        f.wallet.calls.map { it.second } shouldBe listOf(-1_000L, -1_000L, 2_000L)
        f.wallet.balances.values.reduce(BigDecimal::add) shouldBe BigDecimal("200.1234")
        f.repository.rows.getValue(f.id).phase shouldBe WagerPhase.PAID
    }

    "insufficient second participant refunds only the first confirmed withdrawal" {
        val f = WagerFixture()
        f.wallet.balances[f.second] = BigDecimal("9.99")
        f.service.prepare(f.challenge).join() shouldBe WagerPrepareResult.Rejected("insufficient_funds")
        f.wallet.calls shouldBe listOf(f.first to -1_000L, f.first to 1_000L)
        f.wallet.balances[f.first] shouldBe BigDecimal("100.1234")
        f.wallet.balances[f.second] shouldBe BigDecimal("9.99")
        f.service.prepare(f.challenge).join() shouldBe WagerPrepareResult.Rejected("insufficient_funds")
        f.wallet.calls.size shouldBe 2
    }

    "draw and interrupted held match refund without minting" {
        val f = WagerFixture()
        f.service.prepare(f.challenge).join()
        f.service.settle(f.id, null).join() shouldBe WagerSettlementResult.Refunded
        f.wallet.balances[f.first] shouldBe BigDecimal("100.1234")
        f.wallet.balances[f.second] shouldBe BigDecimal("100.00")
        f.reload().recover().join()
        f.wallet.calls.size shouldBe 4
    }

    "unknown provider withdrawal is retained and never repeated on recovery" {
        val f = WagerFixture()
        f.wallet.unknownNext = true
        (f.service.prepare(f.challenge).join() is WagerPrepareResult.Reconciliation) shouldBe true
        f.wallet.calls.size shouldBe 1
        f.repository.rows.getValue(f.id).legs.first().status shouldBe WagerLegStatus.UNKNOWN
        f.reload().recover().join()
        f.wallet.calls.size shouldBe 1
        f.repository.rows.getValue(f.id).phase shouldBe WagerPhase.RECONCILIATION
        f.diagnostics.any { it.contains("manual_reconciliation") } shouldBe true
    }

    "lost acknowledgement after a provider call leaves its intent for manual reconciliation" {
        val f = WagerFixture()
        f.repository.failAppliedOnce = true
        shouldThrow<CompletionException> { f.service.prepare(f.challenge).join() }
        f.wallet.calls.size shouldBe 1
        f.repository.rows.getValue(f.id).legs.first().status shouldBe WagerLegStatus.CALLING
        f.reload().recover().join()
        f.wallet.calls.size shouldBe 1
        f.repository.rows.getValue(f.id).phase shouldBe WagerPhase.RECONCILIATION
    }

    "a durable payout decision resumes once after reload" {
        val f = WagerFixture()
        f.service.prepare(f.challenge).join()
        val held = f.repository.rows.getValue(f.id)
        f.repository.rows[f.id] = held.copy(
            phase = WagerPhase.PAYOUT, winner = f.second,
            legs = held.legs + WagerLeg(WagerLegKind.PAYOUT, f.second, 2_000),
        )
        val reloaded = f.reload()
        reloaded.recover().join()
        reloaded.recover().join()
        reloaded.settle(f.id, null).join() shouldBe WagerSettlementResult.Paid
        f.wallet.calls.count { it.second > 0 } shouldBe 1
        f.wallet.balances[f.second] shouldBe BigDecimal("110.00")
    }

    "recorded completion wins over the recovery and late cancellation refund paths" {
        val f = WagerFixture()
        f.service.prepare(f.challenge).join()
        f.winner = f.first
        f.service.settle(f.id, null).join() shouldBe WagerSettlementResult.Paid
        f.wallet.calls.last() shouldBe (f.first to 2_000L)
        val g = WagerFixture()
        g.service.prepare(g.challenge).join()
        g.winner = g.second
        g.reload().recover().join()
        g.wallet.calls.last() shouldBe (g.second to 2_000L)
    }

    "durable intent precedes the provider and shutdown drains accepted preparation then refunds" {
        val f = WagerFixture()
        val allowIntent = CompletableFuture<Boolean>()
        f.repository.nextIntent = allowIntent
        val preparing = f.service.prepare(f.challenge)
        val duplicate = f.service.prepare(f.challenge)
        preparing.isDone shouldBe false
        f.wallet.calls.size shouldBe 0
        val closing = f.service.closeAsync()
        closing.isDone shouldBe false
        allowIntent.complete(true)
        preparing.join() shouldBe WagerPrepareResult.Ready
        duplicate.join() shouldBe WagerPrepareResult.Ready
        closing.join()
        f.wallet.calls.map { it.second } shouldBe listOf(-1_000L, -1_000L, 1_000L, 1_000L)
        f.wallet.balances.values.reduce(BigDecimal::add) shouldBe BigDecimal("200.1234")
        f.service.available shouldBe false
    }

    "an observer cancellation cannot cancel the admitted debit or its shutdown refund" {
        val f = WagerFixture()
        val allowIntent = CompletableFuture<Boolean>()
        f.repository.nextIntent = allowIntent
        val observer = f.service.prepare(f.challenge)
        observer.cancel(false) shouldBe true
        val closing = f.service.closeAsync()
        allowIntent.complete(true)
        closing.join()
        f.repository.rows.getValue(f.id).phase shouldBe WagerPhase.REFUNDED
        f.wallet.balances.values.reduce(BigDecimal::add) shouldBe BigDecimal("200.1234")
    }

    "shutdown preserves an unattempted refund and startup retries only its credit once" {
        val f = WagerFixture()
        f.wallet.defer = { player, amount -> player == f.second || amount > 0 }
        f.service.prepare(f.challenge).join() shouldBe WagerPrepareResult.Rejected("native_dispatch_deferred")
        val receipt = f.repository.rows.getValue(f.id)
        receipt.phase shouldBe WagerPhase.REFUND
        receipt.legs.first { it.kind == WagerLegKind.WITHDRAW_TARGET }.status shouldBe WagerLegStatus.DEFERRED
        receipt.legs.first { it.kind == WagerLegKind.REFUND_CHALLENGER }.status shouldBe WagerLegStatus.DEFERRED
        f.wallet.calls shouldBe listOf(f.first to -1_000L)
        val attempts = f.wallet.attempts
        f.service.closeAsync().join()
        f.wallet.attempts shouldBe attempts
        f.wallet.defer = { _, _ -> false }
        val reloaded = f.reload()
        reloaded.recover().join()
        reloaded.recover().join()
        reloaded.closeAsync().join()
        f.wallet.calls shouldBe listOf(f.first to -1_000L, f.first to 1_000L)
        f.repository.rows.getValue(f.id).phase shouldBe WagerPhase.REFUNDED
        f.wallet.balances.values.reduce(BigDecimal::add) shouldBe BigDecimal("200.1234")
    }

    "deferred winner payout preserves its decision and next activation pays the pot exactly once" {
        val f = WagerFixture()
        f.service.prepare(f.challenge).join()
        f.wallet.defer = { _, amount -> amount > 0 }
        f.service.settle(f.id, f.first).join() shouldBe WagerSettlementResult.Deferred("native_dispatch_deferred")
        f.repository.rows.getValue(f.id).phase shouldBe WagerPhase.PAYOUT
        f.repository.rows.getValue(f.id).winner shouldBe f.first
        val attempts = f.wallet.attempts
        f.service.closeAsync().join()
        f.wallet.attempts shouldBe attempts
        f.wallet.defer = { _, _ -> false }
        val reloaded = f.reload()
        reloaded.recover().join()
        reloaded.settle(f.id, null).join() shouldBe WagerSettlementResult.Paid
        reloaded.closeAsync().join()
        f.wallet.calls.map { it.second } shouldBe listOf(-1_000L, -1_000L, 2_000L)
        f.wallet.balances.values.reduce(BigDecimal::add) shouldBe BigDecimal("200.1234")
    }

    "free duel never touches persistence or an absent wallet and invalid stake is rejected" {
        val f = WagerFixture()
        f.wallet.enabled = false
        f.service.prepare(f.challenge.copy(rules = f.challenge.rules.copy(stakeMinor = 0))).join() shouldBe WagerPrepareResult.Ready
        f.repository.rows.size shouldBe 0
        f.wallet.calls.size shouldBe 0
        shouldThrow<IllegalArgumentException> { f.challenge.rules.copy(stakeMinor = -1) }
        shouldThrow<IllegalArgumentException> { f.challenge.rules.copy(stakeMinor = MAX_DUEL_STAKE_MINOR + 1) }
        f.challenge.rules.copy(stakeMinor = 1).stakeMinor shouldBe 1
    }
})

private class WagerFixture {
    val first = PlayerId(UUID.randomUUID())
    val second = PlayerId(UUID.randomUUID())
    val challenge = DuelChallenge(
        ChallengeId.random(), first, second, DuelRules(DuelMode.OWN_INVENTORY, stakeMinor = 1_000),
        Instant.EPOCH, Instant.EPOCH.plusSeconds(30),
    )
    val id = MatchId(challenge.id.value)
    val server = ServerId("wager-test")
    val repository = MemoryWagerRepository()
    val wallet = MemoryWagerWallet(mutableMapOf(first to BigDecimal("100.1234"), second to BigDecimal("100.00")))
    val diagnostics = mutableListOf<String>()
    var winner: PlayerId? = null
    fun reload() = DuelWagerService(repository, wallet, server, { _, reason, _ -> diagnostics += reason }, { CompletableFuture.completedFuture(winner) })
    val service = reload().also { it.recover().join() }
}

private class MemoryWagerRepository : DuelWagerRepository {
    val rows = linkedMapOf<MatchId, DuelWager>()
    var nextIntent: CompletableFuture<Boolean>? = null
    var failAppliedOnce = false
    override fun create(wager: DuelWager): CompletableFuture<DuelWager> = CompletableFuture.completedFuture(rows.getOrPut(wager.matchId) { wager })
    override fun find(matchId: MatchId): CompletableFuture<DuelWager?> = CompletableFuture.completedFuture(rows[matchId])
    override fun save(expected: DuelWager, updated: DuelWager): CompletableFuture<Boolean> {
        if (failAppliedOnce && updated.legs.any { it.status == WagerLegStatus.APPLIED }) {
            failAppliedOnce = false
            return CompletableFuture.failedFuture(IllegalStateException("Simulated lost SQL acknowledgement"))
        }
        val deferred = nextIntent?.takeIf { updated.legs.any { it.status == WagerLegStatus.CALLING } }
        if (deferred != null) {
            nextIntent = null
            return deferred.thenApply { allowed -> if (allowed) rows[updated.matchId] = updated; allowed }
        }
        val current = rows[expected.matchId]
        if (current?.revision != expected.revision) return CompletableFuture.completedFuture(false)
        rows[updated.matchId] = updated
        return CompletableFuture.completedFuture(true)
    }
    override fun incomplete(serverId: ServerId, after: MatchId?, limit: Int): CompletableFuture<List<DuelWager>> =
        CompletableFuture.completedFuture(rows.values.filter { it.serverId == serverId && !it.complete && (after == null || it.matchId.toString() > after.toString()) }
            .sortedBy { it.matchId.toString() }.take(limit))
}

private class MemoryWagerWallet(val balances: MutableMap<PlayerId, BigDecimal>) : DuelWagerWallet {
    var enabled = true
    var unknownNext = false
    var attempts = 0
    var defer: (PlayerId, Long) -> Boolean = { _, _ -> false }
    val calls = mutableListOf<Pair<PlayerId, Long>>()
    override val available: Boolean get() = enabled
    override fun balance(playerId: PlayerId): CompletableFuture<BigDecimal?> = CompletableFuture.completedFuture(if (enabled) balances[playerId] else null)
    override fun transfer(playerId: PlayerId, amountMinor: Long, reason: String, expectedBalance: BigDecimal): CompletableFuture<WagerTransferResult> {
        check(balances.getValue(playerId) == expectedBalance)
        attempts++
        if (defer(playerId, amountMinor)) {
            return CompletableFuture.completedFuture(WagerTransferResult(WagerLegStatus.DEFERRED, reason = "native_dispatch_deferred"))
        }
        calls += playerId to amountMinor
        val after = expectedBalance.add(BigDecimal.valueOf(amountMinor, 2))
        balances[playerId] = after
        val unknown = unknownNext.also { unknownNext = false }
        return CompletableFuture.completedFuture(WagerTransferResult(if (unknown) WagerLegStatus.UNKNOWN else WagerLegStatus.APPLIED, after, if (unknown) "provider_timeout" else null))
    }
}
