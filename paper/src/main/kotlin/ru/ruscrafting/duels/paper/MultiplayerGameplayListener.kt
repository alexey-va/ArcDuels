package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.EnderPearl
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.ruscrafting.duels.domain.MultiplayerMatchState

internal class MultiplayerGameplayListener(
    private val sessions: MultiplayerSessionManager,
    private val locales: LocaleService,
    private val commandPolicy: DuelCommandPolicy = DuelCommandPolicy(),
) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamageByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = event.damager.attackingPlayer()
        val victimMatch = sessions.matchFor(victim)
        val attackerMatch = attacker?.let(sessions::matchFor)
        if (victimMatch == null && attackerMatch == null) return
        if (victimMatch == null || attacker == null || attackerMatch?.id != victimMatch.id ||
            victimMatch.state != MultiplayerMatchState.ACTIVE || !sessions.isEnemy(attacker, victim)
        ) {
            event.isCancelled = true
        } else if (victimMatch.roster.rules.objective == ru.ruscrafting.duels.domain.DuelObjectiveType.SUMO ||
            victimMatch.roster.rules.objective.isHitRace
        ) {
            event.damage = 0.0
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAcceptedMeleeHit(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = event.damager as? Player ?: return
        val match = sessions.matchFor(attacker) ?: return
        if (match.state != MultiplayerMatchState.ACTIVE || sessions.matchFor(victim)?.id != match.id || !sessions.isEnemy(attacker, victim)) return
        if (match.roster.rules.objective == ru.ruscrafting.duels.domain.DuelObjectiveType.SUMO) SumoCombat.afterHit(victim)
        if (sessions.isHitRace(attacker)) sessions.recordMeleeHit(attacker, victim)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLethalDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val match = sessions.matchFor(player) ?: return
        if (match.state != MultiplayerMatchState.ACTIVE) {
            event.isCancelled = true
            return
        }
        if (event.finalDamage >= player.health) {
            event.isCancelled = true
            player.noDamageTicks = 20
            sessions.eliminate(player)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMove(event: PlayerMoveEvent) {
        val match = sessions.matchFor(event.player) ?: return
        val moved = event.from.x != event.to.x || event.from.y != event.to.y || event.from.z != event.to.z
        when (match.state) {
            MultiplayerMatchState.RESERVED, MultiplayerMatchState.COUNTDOWN, MultiplayerMatchState.COMPLETING -> {
                if (moved) event.to = (sessions.anchor(event.player) ?: event.from).clone().apply {
                    yaw = event.to.yaw
                    pitch = event.to.pitch
                }
            }
            MultiplayerMatchState.ACTIVE -> if (sessions.isSumoRingOut(event.player, event.to)) {
                event.to = event.from
                sessions.eliminate(event.player)
            } else if (!sessions.isInsideArena(event.player, event.to)) {
                event.to = event.from
                sessions.eliminate(event.player)
            }
            else -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) = sessions.handleQuit(event.player)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (event.cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && !sessions.allowsEnderPearls(event.player)) {
            event.isCancelled = true
            return
        }
        if (!sessions.isTeleportAllowed(event.player, event.to, event.cause)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (!sessions.allowsConsumables(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val player = (event.entity.shooter as? Player) ?: return
        val blocked = (event.entity is EnderPearl && !sessions.allowsEnderPearls(player)) ||
            (event.entity !is EnderPearl && !sessions.allowsProjectiles(player))
        if (blocked) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) = lock(event.player) { event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        (event.entity as? Player)?.let { lock(it) { event.isCancelled = true } }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        (event.whoClicked as? Player)?.let { lock(it) { event.isCancelled = true } }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        (event.whoClicked as? Player)?.let { lock(it) { event.isCancelled = true } }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwap(event: PlayerSwapHandItemsEvent) = lock(event.player) { event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = lock(event.player) { event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) = lock(event.player) { event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) = lock(event.player) { event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) = lock(event.player) { event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val match = sessions.matchFor(event.player) ?: return
        if (match.state != MultiplayerMatchState.ACTIVE) {
            event.isCancelled = true
        } else if (event.clickedBlock != null) {
            // Item use remains available while arena-owned blocks stay immutable.
            event.setUseInteractedBlock(Event.Result.DENY)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!sessions.isLocked(event.player)) return
        val parts = event.message.removePrefix("/").trim().split(Regex("\\s+"))
        if (parts.firstOrNull()?.substringAfter(':')?.lowercase() in setOf("duel", "duels", "дуэль") &&
            parts.getOrNull(1)?.lowercase() in setOf("leave", "покинуть")
        ) {
            event.isCancelled = true
            if (sessions.handleForfeit(event.player)) {
                event.player.sendMessage(locales.notice(event.player, "controller.forfeit"))
            }
            return
        }
        if (event.player.hasPermission(COMMAND_BYPASS_PERMISSION) || commandPolicy.isAllowed(event.message)) return
        event.isCancelled = true
        event.player.sendMessage(locales.notice(event.player, "session.command-blocked"))
    }

    private inline fun lock(player: Player, action: () -> Unit) {
        if (sessions.isLocked(player)) action()
    }

    private fun org.bukkit.entity.Entity.attackingPlayer(): Player? =
        when (this) {
            is Player -> this
            is Projectile -> shooter as? Player
            else -> null
        }

    private companion object {
        const val COMMAND_BYPASS_PERMISSION = "arcduels.bypass"
    }
}
