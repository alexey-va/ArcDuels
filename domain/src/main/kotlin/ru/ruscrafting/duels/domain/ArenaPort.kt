package ru.ruscrafting.duels.domain

import java.util.concurrent.CompletableFuture

fun interface ArenaAllocator {
    fun reserve(rules: DuelRules): CompletableFuture<ArenaReservation>

    /**
     * Reserves one exact arena when a player explicitly selected it.
     *
     * Implementations that do not expose named arenas remain compatible with
     * automatic allocation and fail closed for an explicit selection.
     */
    fun reserve(
        rules: DuelRules,
        arenaId: ArenaId?,
    ): CompletableFuture<ArenaReservation> =
        if (arenaId == null) {
            reserve(rules)
        } else {
            CompletableFuture.failedFuture(IllegalArgumentException("Explicit arena selection is not supported"))
        }
}

data class ArenaSelection(
    val serverId: ServerId,
    val arenaId: ArenaId,
)

data class ArenaReservation(
    val arenaId: ArenaId,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    override fun close() = releaseAction()
}
