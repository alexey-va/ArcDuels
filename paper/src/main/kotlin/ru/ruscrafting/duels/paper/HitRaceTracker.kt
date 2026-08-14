package ru.ruscrafting.duels.paper

import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.PlayerId

internal data class HitRaceProgress(
    val scores: Map<PlayerId, Long>,
    val scorer: PlayerId,
)

/** Per-round melee score for Boxing and Combo objectives. */
internal class HitRaceTracker {
    private val totalHits = linkedMapOf<PlayerId, Long>()
    private val comboHits = linkedMapOf<PlayerId, Long>()

    fun reset() {
        totalHits.clear()
        comboHits.clear()
    }

    fun record(
        objective: DuelObjectiveType,
        attacker: PlayerId,
        victim: PlayerId,
    ): HitRaceProgress {
        require(objective.isHitRace) { "A hit-race tracker only supports Boxing and Combo" }
        require(attacker != victim) { "A player cannot score a hit against themselves" }
        val scores =
            when (objective) {
                DuelObjectiveType.BOXING -> {
                    totalHits[attacker] = totalHits.getOrDefault(attacker, 0L) + 1L
                    totalHits.toMap()
                }
                DuelObjectiveType.COMBO -> {
                    comboHits[attacker] = comboHits.getOrDefault(attacker, 0L) + 1L
                    comboHits[victim] = 0L
                    comboHits.toMap()
                }
                else -> error("Unsupported hit-race objective $objective")
            }
        return HitRaceProgress(scores, attacker)
    }
}
