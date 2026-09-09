package ru.ruscrafting.duels.paper

import ru.ruscrafting.duels.domain.PlayerId

/** Stores objective progress once per side; contested zones pause capture. */
internal class MultiplayerHillCaptureTracker {
    private val progress = linkedMapOf<PlayerId, Long>()
    private val sideOwners = linkedMapOf<String, PlayerId>()

    @Synchronized
    fun tick(contenders: Set<PlayerId>, sideOf: (PlayerId) -> String, elapsedTicks: Long): Map<PlayerId, Long> {
        require(elapsedTicks > 0) { "Objective tick duration must be positive" }
        val sides = contenders.groupBy(sideOf)
        if (sides.size == 1 && sides.isNotEmpty()) {
            val sidePlayers = sides.values.single()
            val side = sides.keys.single()
            val key = sideOwners.getOrPut(side) { sidePlayers.minBy { it.value } }
            progress.merge(key, elapsedTicks, Long::plus)
        }
        return progress.toMap()
    }

    @Synchronized
    fun reset() {
        progress.clear()
        sideOwners.clear()
    }
}
