package ru.ruscrafting.duels.paper

import net.william278.husksync.api.BukkitHuskSyncAPI
import net.william278.husksync.event.BukkitSyncCompleteEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

internal class HuskSyncReadinessListener(
    private val gate: PlayerDataSyncGate,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onSyncComplete(event: BukkitSyncCompleteEvent) {
        gate.synchronized(event.user.uuid)
    }

    fun inspectAlreadyOnline(player: Player) {
        val user = BukkitHuskSyncAPI.getInstance().getUser(player)
        if (!user.isLocked) gate.synchronized(player.uniqueId)
    }
}
