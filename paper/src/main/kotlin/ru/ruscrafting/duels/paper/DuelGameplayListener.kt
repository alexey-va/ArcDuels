package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.projectiles.ProjectileSource
import ru.ruscrafting.duels.domain.MatchState
import ru.ruscrafting.duels.domain.PlayerId

internal class DuelGameplayListener(
    private val sessions: DuelSessionManager,
    private val commandPolicy: DuelCommandPolicy = DuelCommandPolicy(),
) : Listener {
    private val miniMessage = MiniMessage.miniMessage()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDecorativeFireworkDamage(event: EntityDamageByEntityEvent) {
        if (CelebrationEffects.isDecorativeFirework(event.damager)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = event.damager.attackingPlayer()
        val victimMatch = sessions.matchFor(victim)
        val attackerMatch = attacker?.let(sessions::matchFor)
        if (victimMatch == null && attackerMatch == null) {
            if (sessions.isStateLocked(victim) || (attacker != null && sessions.isStateLocked(attacker))) event.isCancelled = true
            return
        }
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
        if (event is EntityDamageByEntityEvent && CelebrationEffects.isDecorativeFirework(event.damager)) {
            event.isCancelled = true
            return
        }
        val player = event.entity as? Player ?: return
        val match = sessions.matchFor(player)
        if (match == null) {
            if (sessions.isStateLocked(player)) event.isCancelled = true
            return
        }
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
        val match = sessions.matchFor(event.player)
        val destination = event.to
        if (match == null) {
            if (sessions.isStateLocked(event.player) &&
                (event.from.x != destination.x || event.from.y != destination.y || event.from.z != destination.z)
            ) {
                event.to = event.from.clone().apply {
                    yaw = destination.yaw
                    pitch = destination.pitch
                }
            }
            return
        }
        when (match.state) {
            MatchState.COUNTDOWN -> {
                if (event.from.x != destination.x || event.from.y != destination.y || event.from.z != destination.z) {
                    event.to = event.from.clone().apply {
                        yaw = destination.yaw
                        pitch = destination.pitch
                    }
                }
            }
            MatchState.ACTIVE -> {
                if (!sessions.isInsideArena(event.player, destination)) {
                    event.to = event.from
                    sessions.handleElimination(event.player)
                }
            }
            else -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) = sessions.handleQuit(event.player)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onJoin(event: PlayerJoinEvent) = sessions.handleJoin(event.player)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (!sessions.isTeleportAllowed(event.player, event.to, event.cause)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (sessions.isStateLocked(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (sessions.isStateLocked(player) &&
            (requiresFullFreeze(player) || event.view.topInventory.type != InventoryType.CRAFTING)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (sessions.isStateLocked(player) &&
            (requiresFullFreeze(player) || event.view.topInventory.type != InventoryType.CRAFTING)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (sessions.isStateLocked(player) &&
            (requiresFullFreeze(player) || event.inventory.type != InventoryType.CRAFTING)
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (sessions.isStateLocked(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        if (sessions.isStateLocked(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (sessions.isStateLocked(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (requiresFullFreeze(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (requiresFullFreeze(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        if (requiresFullFreeze(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHeldSlotChange(event: PlayerItemHeldEvent) {
        if (requiresFullFreeze(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onExperience(event: PlayerExpChangeEvent) {
        if (requiresFullFreeze(event.player)) event.amount = 0
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!sessions.isStateLocked(event.player) || commandPolicy.isAllowed(event.message)) return
        event.isCancelled = true
        event.player.sendMessage(
            miniMessage.deserialize("<red>Эта команда недоступна во время дуэли.</red> <gray>Сдаться: <white>/duel leave</white></gray>"),
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (sessions.isStateLocked(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (sessions.isStateLocked(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHunger(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (sessions.isStateLocked(player)) event.isCancelled = true
    }

    private fun org.bukkit.entity.Entity.attackingPlayer(): Player? =
        when (this) {
            is Player -> this
            is Projectile -> shooter.asPlayer()
            else -> null
        }

    private fun ProjectileSource?.asPlayer(): Player? = this as? Player

    private fun requiresFullFreeze(player: Player): Boolean =
        sessions.isPreparing(player) || (sessions.isStateLocked(player) && sessions.matchFor(player) == null)
}
