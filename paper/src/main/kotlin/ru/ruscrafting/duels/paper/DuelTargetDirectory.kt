package ru.ruscrafting.duels.paper

import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.redis.NetworkPlayerDirectory
import java.util.UUID

data class DuelTarget(
    val uniqueId: UUID,
    val name: String,
    val server: ServerId,
    val local: Boolean,
)

class DuelTargetDirectory(
    private val plugin: JavaPlugin,
    private val localServer: ServerId,
    private val network: NetworkPlayerDirectory?,
) {
    fun players(): List<DuelTarget> {
        val result = LinkedHashMap<UUID, DuelTarget>()
        network?.players()?.forEach { player ->
            if (player.server == localServer && plugin.server.getPlayer(player.uuid) == null) return@forEach
            result[player.uuid] = DuelTarget(player.uuid, player.username, player.server, player.server == localServer)
        }
        plugin.server.onlinePlayers.forEach { player ->
            result[player.uniqueId] = DuelTarget(player.uniqueId, player.name, localServer, local = true)
        }
        return result.values.sortedWith { first, second -> String.CASE_INSENSITIVE_ORDER.compare(first.name, second.name) }
    }

    fun find(uniqueId: UUID): DuelTarget? =
        plugin.server.getPlayer(uniqueId)?.let { DuelTarget(it.uniqueId, it.name, localServer, local = true) }
            ?: network?.find(uniqueId)?.takeIf { it.server != localServer }
                ?.let { DuelTarget(it.uuid, it.username, it.server, local = false) }

    fun find(name: String): DuelTarget? =
        plugin.server.getPlayerExact(name)?.let { DuelTarget(it.uniqueId, it.name, localServer, local = true) }
            ?: network?.find(name)?.takeIf { it.server != localServer }
                ?.let { DuelTarget(it.uuid, it.username, it.server, local = false) }

    fun local(player: org.bukkit.entity.Player): DuelTarget = DuelTarget(player.uniqueId, player.name, localServer, local = true)
}
