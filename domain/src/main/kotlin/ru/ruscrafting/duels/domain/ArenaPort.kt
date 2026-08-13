package ru.ruscrafting.duels.domain

import java.util.concurrent.CompletableFuture

fun interface ArenaAllocator {
    fun reserve(rules: DuelRules): CompletableFuture<ArenaReservation>
}

data class ArenaReservation(
    val arenaId: ArenaId,
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    override fun close() = releaseAction()
}
