package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class PlayerDataSyncGate(
    private val explicitSyncRequired: Boolean,
) : Listener {
    private val readyPlayers = ConcurrentHashMap.newKeySet<UUID>()

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        joined(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        left(event.player.uniqueId)
    }

    fun joined(playerId: UUID) {
        if (explicitSyncRequired) readyPlayers.remove(playerId) else readyPlayers.add(playerId)
    }

    fun synchronized(playerId: UUID) {
        readyPlayers.add(playerId)
    }

    fun left(playerId: UUID) {
        readyPlayers.remove(playerId)
    }

    fun isReady(player: Player): Boolean = !explicitSyncRequired || player.uniqueId in readyPlayers
}
