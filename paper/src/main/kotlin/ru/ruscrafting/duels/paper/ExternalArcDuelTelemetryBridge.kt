package ru.ruscrafting.duels.paper

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC outcome bridge; duel completion remains independent of telemetry. */
internal object ExternalArcDuelTelemetryBridge {
    fun completed(playerId: UUID, matchId: String) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ARC")) return
        runCatching { AvailableArcTelemetry.completed(playerId, matchId) }
    }

    private object AvailableArcTelemetry {
        fun completed(playerId: UUID, matchId: String) {
            Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java)
                ?.recordEvent(playerId, "arcduels", "duel_completed", "duel:$matchId")
        }
    }
}
