package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.projectiles.ProjectileSource
import ru.ruscrafting.duels.domain.MatchState
import ru.ruscrafting.duels.domain.PlayerId

class DuelGameplayListener(
    private val sessions: DuelSessionManager,
) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = event.damager.attackingPlayer()
        val victimMatch = sessions.matchFor(victim)
        val attackerMatch = attacker?.let(sessions::matchFor)
        if (victimMatch == null && attackerMatch == null) return
        if (victimMatch == null || attackerMatch?.id != victimMatch.id) {
            event.isCancelled = true
            return
        }
        val expectedAttacker = victimMatch.opponentOf(PlayerId(victim.uniqueId))
        if (attacker.uniqueId != expectedAttacker.value || victimMatch.state != MatchState.ACTIVE) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLethalDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val match = sessions.matchFor(player) ?: return
        if (match.state != MatchState.ACTIVE) {
            event.isCancelled = true
            return
        }
        if (event.finalDamage >= player.health) {
            event.isCancelled = true
            player.noDamageTicks = 20
            sessions.handleElimination(player)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onMove(event: PlayerMoveEvent) {
        val match = sessions.matchFor(event.player) ?: return
        if (match.state != MatchState.COUNTDOWN) return
        val destination = event.to
        if (event.from.x != destination.x || event.from.y != destination.y || event.from.z != destination.z) {
            event.to = event.from.clone().apply {
                yaw = destination.yaw
                pitch = destination.pitch
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) = sessions.handleQuit(event.player)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (sessions.matchFor(event.player) != null && event.cause != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (sessions.matchFor(event.player) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (sessions.matchFor(player) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (sessions.matchFor(event.player) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (sessions.matchFor(event.player) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHunger(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (sessions.matchFor(player) != null) event.isCancelled = true
    }

    private fun org.bukkit.entity.Entity.attackingPlayer(): Player? =
        when (this) {
            is Player -> this
            is Projectile -> shooter.asPlayer()
            else -> null
        }

    private fun ProjectileSource?.asPlayer(): Player? = this as? Player
}
