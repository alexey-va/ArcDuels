package ru.ruscrafting.duels.paper

import ru.ruscrafting.duels.domain.PlayerId

internal class HillCaptureTracker {
    private val progress = linkedMapOf<PlayerId, Long>()

    @Synchronized
    fun tick(contenders: Set<PlayerId>, elapsedTicks: Long): Map<PlayerId, Long> {
        require(elapsedTicks > 0) { "Objective tick duration must be positive" }
        if (contenders.size == 1) progress.merge(contenders.single(), elapsedTicks, Long::plus)
        return progress.toMap()
    }

    @Synchronized
    fun reset() = progress.clear()

    @Synchronized
    fun snapshot(): Map<PlayerId, Long> = progress.toMap()
}
