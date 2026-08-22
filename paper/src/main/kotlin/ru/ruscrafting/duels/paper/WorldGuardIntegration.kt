package ru.ruscrafting.duels.paper

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Waterlogged
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEvent
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

internal class DuelFluidListener(
    private val sessions: DuelSessionManager,
    private val overrideWorldGuard: Boolean,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onFluidInteract(event: PlayerInteractEvent) {
        if (!overrideWorldGuard || event.action != Action.RIGHT_CLICK_BLOCK) return
        val material = event.item?.type ?: return
        val target = event.fluidTarget(material) ?: return
        val allowed =
            if (material == Material.BUCKET) {
                sessions.allowsFluidPickup(event.player, target)
            } else {
                sessions.allowsFluidPlacement(event.player, target, material)
            }
        if (!allowed) return

        // WorldGuard denies the precursor interaction before Bukkit can emit the
        // bucket event. Permit only the held bucket here; the clicked block keeps
        // its original use result, so protected containers and buttons stay closed.
        event.setUseItemInHand(Event.Result.ALLOW)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (!sessions.allowsFluidPlacement(event.player, event.block, event.bucket)) return
        if (event.isCancelled && !overrideWorldGuard) return
        event.isCancelled = !sessions.trackFluidPlacement(event.player, event.block)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (!sessions.allowsFluidPickup(event.player, event.block)) return
        if (event.isCancelled && !overrideWorldGuard) return
        event.isCancelled = false
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onFluidFlow(event: BlockFromToEvent) {
        val allowed = sessions.trackFluidFlow(event.block, event.toBlock) ?: return
        if (!allowed) {
            event.isCancelled = true
        } else if (!event.isCancelled || overrideWorldGuard) {
            event.isCancelled = false
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onFluidForm(event: BlockFormEvent) {
        applySideEffectDecision(event, sessions.trackFluidSideEffect(null, event.block))
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onLavaIgnite(event: BlockIgniteEvent) {
        if (event.cause != BlockIgniteEvent.IgniteCause.LAVA) return
        applySideEffectDecision(event, sessions.trackFluidSideEffect(event.ignitingBlock, event.block))
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onFluidFireSpread(event: BlockSpreadEvent) {
        if (event.source.type != Material.FIRE && event.source.type != Material.SOUL_FIRE) return
        applySideEffectDecision(event, sessions.trackFluidSideEffect(event.source, event.block))
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onFluidBurn(event: BlockBurnEvent) {
        applySideEffectDecision(event, sessions.trackFluidSideEffect(event.ignitingBlock, event.block))
    }

    private fun applySideEffectDecision(
        event: org.bukkit.event.Cancellable,
        allowed: Boolean?,
    ) {
        if (allowed == null) return
        if (!allowed) {
            event.isCancelled = true
        } else if (!event.isCancelled || overrideWorldGuard) {
            event.isCancelled = false
        }
    }
}

private fun PlayerInteractEvent.fluidTarget(material: Material): Block? {
    val clicked = clickedBlock ?: return null
    if (material == Material.BUCKET) return clicked
    if (material == Material.WATER_BUCKET && clicked.blockData is Waterlogged) return clicked
    return clicked.getRelative(blockFace)
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
