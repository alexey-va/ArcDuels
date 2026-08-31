package ru.ruscrafting.duels.paper

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

internal class MultiplayerArenaCapacityException :
    IllegalStateException("No arena can procedurally place this multiplayer roster")

internal class MultiplayerArenasBusyException :
    IllegalStateException("All compatible multiplayer arenas are occupied")

internal enum class MultiplayerStartFailure(
    val code: String,
    val localeKey: String,
) {
    NO_ARENA_CAPACITY("no_arena_capacity", "multiplayer.no-arena-capacity"),
    ARENAS_BUSY("arenas_busy", "multiplayer.arenas-busy"),
    INTERNAL("internal", "multiplayer.start-failed"),
}

internal fun Throwable.multiplayerRootCause(): Throwable {
    var current = this
    while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
        current = requireNotNull(current.cause)
    }
    return current
}

internal fun Throwable.multiplayerStartFailure(): MultiplayerStartFailure =
    when (multiplayerRootCause()) {
        is MultiplayerArenaCapacityException -> MultiplayerStartFailure.NO_ARENA_CAPACITY
        is MultiplayerArenasBusyException -> MultiplayerStartFailure.ARENAS_BUSY
        else -> MultiplayerStartFailure.INTERNAL
    }
