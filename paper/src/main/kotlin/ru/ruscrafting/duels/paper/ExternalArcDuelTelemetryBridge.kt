package ru.ruscrafting.duels.paper

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC outcome bridge; duel completion remains independent of telemetry. */
internal object ExternalArcDuelTelemetryBridge {
    private val telemetry by lazy { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }

    fun completed(playerId: UUID, matchId: String) {
        runCatching { telemetry?.recordEvent(playerId, "arcduels", "duel_completed", "duel:$matchId") }
    }
}
