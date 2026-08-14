package ru.ruscrafting.duels.paper

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.MatchState
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

internal fun interface ArenaEnvironmentInspector {
    fun inspect(arena: PaperArena)

    companion object {
        fun create(plugin: JavaPlugin): ArenaEnvironmentInspector =
            if (plugin.server.pluginManager.isPluginEnabled("WorldGuard")) {
                WorldGuardArenaInspector(plugin.logger)
            } else {
                ArenaEnvironmentInspector { _ -> }
            }
    }
}

private class WorldGuardArenaInspector(
    private val logger: Logger,
) : ArenaEnvironmentInspector {
    override fun inspect(arena: PaperArena) {
        val denied = arena.inspectionPoints().filter { (_, location) -> pvpState(location) == StateFlag.State.DENY }
        if (denied.isNotEmpty()) {
            logger.warning(
                "Arena ${arena.id} is covered by a WorldGuard PvP denial at ${denied.joinToString { it.first }}; " +
                    "ArcDuels will override it only for participants of the active match inside this arena",
            )
        }
    }

    private fun pvpState(location: Location): StateFlag.State? =
        WorldGuard.getInstance()
            .platform
            .regionContainer
            .createQuery()
            .queryState(BukkitAdapter.adapt(location), null, Flags.PVP)
}

internal class WorldGuardDuelListener(
    private val sessions: DuelSessionManager,
    private val logger: Logger,
) : Listener {
    private val warnedArenas = ConcurrentHashMap.newKeySet<ArenaId>()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDisallowedPvp(event: DisallowedPVPEvent) {
        val attackerMatch = sessions.matchFor(event.attacker) ?: return
        val defenderMatch = sessions.matchFor(event.defender) ?: return
        if (attackerMatch.id != defenderMatch.id || attackerMatch.state != MatchState.ACTIVE) return
        if (!sessions.isInsideArena(event.attacker, event.attacker.location) ||
            !sessions.isInsideArena(event.defender, event.defender.location)
        ) {
            return
        }
        event.isCancelled = true
        if (warnedArenas.add(attackerMatch.arenaId)) {
            logger.warning(
                "WorldGuard denied PvP in active arena ${attackerMatch.arenaId}; " +
                    "ArcDuels applied its participant-only arena override",
            )
        }
    }
}

private fun PaperArena.inspectionPoints(): List<Pair<String, Location>> {
    val world = requireNotNull(firstSpawn.world)
    val centerX = (bounds.minX + bounds.maxX) / 2.0
    val centerY = (bounds.minY + bounds.maxY) / 2.0
    val centerZ = (bounds.minZ + bounds.maxZ) / 2.0
    val points =
        mutableListOf(
            "first spawn" to firstSpawn,
            "second spawn" to secondSpawn,
            "center" to Location(world, centerX, centerY, centerZ),
        )
    for (x in listOf(bounds.minX, bounds.maxX)) {
        for (z in listOf(bounds.minZ, bounds.maxZ)) {
            points += "boundary($x,$centerY,$z)" to Location(world, x, centerY, z)
        }
    }
    hill?.let { points += "hill" to it.center }
    return points
}
