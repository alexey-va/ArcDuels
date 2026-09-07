package ru.ruscrafting.duels.paper

import java.util.UUID

/** Optional ARC outcome bridge; duel completion remains independent of telemetry. */
internal object ExternalArcDuelTelemetryBridge {
    private val recordMethod = lazy {
        Class.forName("ru.arc.metrics.ExternalProductTelemetryBridge").getMethod(
            "recordEvent", UUID::class.java, String::class.java, String::class.java, String::class.java,
        )
    }

    fun completed(playerId: UUID, matchId: String) {
        runCatching { recordMethod.value.invoke(null, playerId, "arcduels", "duel_completed", "duel:$matchId") }
    }
}
