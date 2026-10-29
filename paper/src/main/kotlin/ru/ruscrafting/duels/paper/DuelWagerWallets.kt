package ru.ruscrafting.duels.paper

import ru.ruscrafting.duels.domain.DuelWagerWallet
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.WagerLegStatus
import ru.ruscrafting.duels.domain.WagerTransferResult
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Always-loadable optional boundary; no provider type appears in its fields or method descriptors. */
object DuelWagerWallets {
    fun create(
        executor: Executor,
        dispatch: (() -> WagerTransferResult) -> CompletableFuture<WagerTransferResult>,
    ): DuelWagerWallet {
        val loader = DuelWagerWallets::class.java.classLoader
        val present = try {
            Class.forName("dev.unnm3d.rediseconomy.api.RedisEconomyAPI", false, loader)
            Class.forName("net.milkbowl.vault.economy.EconomyResponse", false, loader)
            true
        } catch (_: ClassNotFoundException) { false } catch (_: LinkageError) { false }
        return if (present) RedisEconomyDuelWagerWallet(executor, dispatch) else UnavailableDuelWagerWallet
    }
}

private object UnavailableDuelWagerWallet : DuelWagerWallet {
    override val available: Boolean = false
    override fun balance(playerId: PlayerId): CompletableFuture<BigDecimal?> = CompletableFuture.completedFuture(null)
    override fun transfer(playerId: PlayerId, amountMinor: Long, reason: String, expectedBalance: BigDecimal): CompletableFuture<WagerTransferResult> =
        CompletableFuture.completedFuture(WagerTransferResult(WagerLegStatus.REJECTED, reason = "provider_unavailable"))
}
