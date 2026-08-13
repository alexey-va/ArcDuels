package ru.ruscrafting.duels.paper

import ru.arc.redis.RedisManager
import ru.ruscrafting.duels.redis.CrossServerDuelBus

internal object NetworkLifecycle {
    fun initialize(
        manager: RedisManager,
        bus: CrossServerDuelBus,
        onUnavailable: (Throwable) -> Unit,
    ): Boolean =
        try {
            manager.init()
            true
        } catch (failure: Throwable) {
            close(bus, manager).onFailure(failure::addSuppressed)
            onUnavailable(failure)
            false
        }

    fun close(
        bus: CrossServerDuelBus,
        manager: RedisManager,
    ): Result<Unit> =
        runCatching {
            var firstFailure: Throwable? = null
            runCatching(bus::close).onFailure { firstFailure = it }
            runCatching(manager::close).onFailure { failure ->
                val existing = firstFailure
                if (existing == null) firstFailure = failure else existing.addSuppressed(failure)
            }
            firstFailure?.let { throw it }
        }
}
