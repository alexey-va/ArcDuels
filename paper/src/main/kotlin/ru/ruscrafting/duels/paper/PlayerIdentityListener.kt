package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.StatisticsRepository

class PlayerIdentityListener(
    private val plugin: JavaPlugin,
    private val statistics: StatisticsRepository,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = remember(event.player)

    fun remember(player: Player) {
        runCatching { statistics.rememberPlayerName(PlayerId(player.uniqueId), player.name) }
            .onSuccess { write ->
                write.exceptionally { failure ->
                    plugin.logger.warning("Could not remember duel player name for ${player.uniqueId}: ${failure.message}")
                    Unit
                }
            }
            .onFailure { failure ->
                plugin.logger.warning("Could not remember duel player name for ${player.uniqueId}: ${failure.message}")
            }
    }
}
