package ru.ruscrafting.duels.domain

import java.time.Instant
import java.util.concurrent.CompletableFuture

const val MAX_DUEL_PRESETS: Int = 5

data class DuelPreset(
    val playerId: PlayerId,
    val slot: Int,
    val rules: DuelRules,
    val arenaSelection: ArenaSelection?,
    val updatedAt: Instant,
) {
    init {
        require(slot in 1..MAX_DUEL_PRESETS) { "Preset slot must be between 1 and $MAX_DUEL_PRESETS" }
    }
}

interface DuelPresetRepository {
    fun presets(playerId: PlayerId): CompletableFuture<List<DuelPreset>>

    fun savePreset(preset: DuelPreset): CompletableFuture<Unit>

    fun deletePreset(
        playerId: PlayerId,
        slot: Int,
    ): CompletableFuture<Boolean>
}
