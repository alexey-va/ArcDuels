package ru.ruscrafting.duels.paper

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.ThrownPotion
import org.bukkit.entity.EnderPearl
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
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
import net.kyori.adventure.title.Title
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import ru.ruscrafting.duels.domain.MatchState
import ru.ruscrafting.duels.domain.PlayerId

internal class DuelGameplayListener(
    private val sessions: DuelSessionManager,
    private val locales: LocaleService,
    private val commandPolicy: DuelCommandPolicy = DuelCommandPolicy(),
    private val controller: DuelController? = null,
    private val boundaryWarningDistance: Double = 12.0,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val runtimeSettings: () -> ArcDuelsRuntimeSettings? = { null },
) : Listener {
    private val boundaryWarnings = ConcurrentHashMap<UUID, Long>()
    private val boundaryParticles = ConcurrentHashMap<UUID, Long>()

    init {
        require(boundaryWarningDistance in 1.0..16.0) { "Boundary warning distance must be between 1 and 16 blocks" }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDecorativeFireworkDamage(event: EntityDamageByEntityEvent) {
        if (CelebrationEffects.isDecorativeFirework(event.damager)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = event.damager.attackingPlayer()
        if (sessions.isPostMatchWaiting(victim) || (attacker != null && sessions.isPostMatchWaiting(attacker))) {
            event.isCancelled = true
            sessions.clearPostMatchCombatTag(victim, "post-match-damage-blocked")
            attacker?.let { sessions.clearPostMatchCombatTag(it, "post-match-damage-blocked") }
            return
        }
        val victimMatch = sessions.matchFor(victim)
        val attackerMatch = attacker?.let(sessions::matchFor)
        if (victimMatch == null && attackerMatch == null) {
            if (sessions.isStateLocked(victim) || (attacker != null && sessions.isStateLocked(attacker))) event.isCancelled = true
            return
        }
        if (victimMatch == null || attackerMatch?.id != victimMatch.id) {
            event.isCancelled = true
            victimMatch?.let { sessions.clearExternalCombatTags(it, "foreign-damage-blocked") }
            return
        }
        val expectedAttacker = victimMatch.opponentOf(PlayerId(victim.uniqueId))
        if (attacker.uniqueId != expectedAttacker.value || victimMatch.state != MatchState.ACTIVE) {
            event.isCancelled = true
            sessions.clearExternalCombatTags(victimMatch, "inactive-damage-blocked")
            return
        }
        if (event.damager is Projectile && !sessions.allowsProjectiles(attacker)) {
            event.isCancelled = true
            return
        }
        if (sessions.isSumo(victim) || sessions.isHitRace(victim)) event.damage = 0.0
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAcceptedMeleeHit(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = event.damager as? Player ?: return
        if (sessions.isHitRace(attacker)) sessions.recordMeleeHit(attacker, victim)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onAcceptedDamageTrace(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        val match = sessions.matchFor(victim) ?: return
        if (match.state != MatchState.ACTIVE) return
        val attacker = (event as? EntityDamageByEntityEvent)?.damager?.attackingPlayer()
        DuelLog.debug(
            "damage-accepted",
            match.id,
            victim,
            "victim={} attacker={} cause={} raw={} final={} health_before={}",
            victim.name,
            attacker?.name ?: "environment",
            event.cause,
            event.damage,
            event.finalDamage,
            victim.health,
        )
        sessions.clearExternalCombatTags(match, "damage-accepted")
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLethalDamage(event: EntityDamageEvent) {
        if (event is EntityDamageByEntityEvent && CelebrationEffects.isDecorativeFirework(event.damager)) {
            event.isCancelled = true
            return
        }
        val player = event.entity as? Player ?: return
        if (sessions.isPostMatchWaiting(player)) {
            event.isCancelled = true
            sessions.clearPostMatchCombatTag(player, "post-match-damage-blocked")
            return
        }
        val match = sessions.matchFor(player)
        if (match == null) {
            if (sessions.isStateLocked(player)) event.isCancelled = true
            return
        }
        if (match.state != MatchState.ACTIVE) {
            event.isCancelled = true
            return
        }
        if ((sessions.isSumo(player) || sessions.isHitRace(player)) && event.cause != EntityDamageEvent.DamageCause.WITHER) {
            event.damage = 0.0
            return
        }
        if (event.finalDamage >= player.health) {
            DuelLog.info(
                "damage-lethal",
                match.id,
                player,
                "victim={} cause={} final_damage={} health={}",
                player.name,
                event.cause,
                event.finalDamage,
                player.health,
            )
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
            boundaryWarnings.remove(event.player.uniqueId)
            boundaryParticles.remove(event.player.uniqueId)
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
            MatchState.COUNTDOWN, MatchState.COMPLETING, MatchState.COMPLETED -> {
                if (event.from.x != destination.x || event.from.y != destination.y || event.from.z != destination.z) {
                    event.to = (sessions.countdownAnchor(event.player) ?: event.from).clone().apply {
                        yaw = destination.yaw
                        pitch = destination.pitch
                    }
                }
            }
            MatchState.ACTIVE -> {
                if (!sessions.isInsideArena(event.player, destination)) {
                    event.to = event.from
                    warnBoundary(event.player, event.from, reachedBoundary = true, movingCloser = true)
                } else {
                    val distance = sessions.boundaryDistance(event.player, destination) ?: return
                    val previousDistance = sessions.boundaryDistance(event.player, event.from)
                    val warningDistance = runtimeSettings()?.boundaryWarningDistance ?: boundaryWarningDistance
                    if (distance <= warningDistance) {
                        warnBoundary(
                            event.player,
                            destination,
                            reachedBoundary = false,
                            movingCloser = previousDistance == null || distance < previousDistance,
                        )
                    } else if (distance > warningDistance + 2.0) {
                        boundaryWarnings.remove(event.player.uniqueId)
                        boundaryParticles.remove(event.player.uniqueId)
                    }
                }
            }
            else -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) {
        boundaryWarnings.remove(event.player.uniqueId)
        boundaryParticles.remove(event.player.uniqueId)
        controller?.handleQuit(event.player)
        sessions.handleQuit(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onJoin(event: PlayerJoinEvent) = sessions.handleJoin(event.player)

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (!sessions.isTeleportAllowed(event.player, event.to, event.cause)) {
            DuelLog.debug(
                "teleport-blocked",
                sessions.matchFor(event.player)?.id,
                event.player,
                "player={} cause={} destination_world={}",
                event.player.name,
                event.cause,
                event.to.world.name,
            )
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
        if (requiresFullFreeze(event.player) ||
            (event.item?.type == org.bukkit.Material.ENDER_PEARL && !sessions.allowsEnderPearls(event.player))
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (requiresFullFreeze(event.player) || !sessions.allowsConsumables(event.player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        val player = projectile.shooter as? Player ?: return
        if (!sessions.isStateLocked(player)) return
        if ((projectile is EnderPearl && !sessions.allowsEnderPearls(player)) ||
            (projectile !is EnderPearl && !sessions.allowsProjectiles(player)) ||
            (projectile is ThrownPotion && !sessions.allowsConsumables(player))
        ) {
            event.isCancelled = true
        }
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

    @EventHandler(priority = EventPriority.LOWEST)
    fun onCommandEarly(event: PlayerCommandPreprocessEvent) {
        if (!shouldBlockCommand(event)) return
        DuelLog.debug(
            "command-blocked",
            sessions.matchFor(event.player)?.id,
            event.player,
            "player={} command={}",
            event.player.name,
            event.message.substringBefore(' '),
        )
        event.isCancelled = true
        event.player.sendMessage(locales.notice(event.player, "session.command-blocked"))
    }

    /** Reassert the lock if another command interceptor uncancels the event. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCommandFinal(event: PlayerCommandPreprocessEvent) {
        if (shouldBlockCommand(event)) event.isCancelled = true
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onNaturalRegeneration(event: EntityRegainHealthEvent) {
        val player = event.entity as? Player ?: return
        if (event.regainReason == EntityRegainHealthEvent.RegainReason.SATIATED && !sessions.allowsNaturalRegeneration(player)) {
            event.isCancelled = true
        }
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

    private fun shouldBlockCommand(event: PlayerCommandPreprocessEvent): Boolean =
        sessions.isStateLocked(event.player) &&
            !event.player.hasPermission(COMMAND_BYPASS_PERMISSION) &&
            !commandPolicy.isAllowed(event.message)

    private fun warnBoundary(
        player: Player,
        location: org.bukkit.Location,
        reachedBoundary: Boolean,
        movingCloser: Boolean,
    ) {
        val now = nowMillis()
        val previousParticles = boundaryParticles[player.uniqueId]
        if (previousParticles == null || now - previousParticles >= BOUNDARY_PARTICLE_COOLDOWN_MILLIS) {
            boundaryParticles[player.uniqueId] = now
            sessions.boundaryWarningPoints(player, location).forEach { point ->
                player.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, BOUNDARY_DUST)
            }
        }
        if (!movingCloser) return
        val previousWarning = boundaryWarnings[player.uniqueId]
        if (previousWarning == null || now - previousWarning >= BOUNDARY_WARNING_COOLDOWN_MILLIS) {
            boundaryWarnings[player.uniqueId] = now
            showBoundaryTitle(player, reachedBoundary)
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.65f, if (reachedBoundary) 0.55f else 0.8f)
        }
    }

    private fun showBoundaryTitle(player: Player, reachedBoundary: Boolean) {
        val sumo = sessions.isSumo(player)
        val titleKey = if (reachedBoundary) "session.boundary-left-title" else "session.boundary-warning-title"
        val subtitleKey =
            when {
                reachedBoundary -> "session.boundary-left-subtitle"
                sumo -> "session.boundary-warning-sumo-subtitle"
                else -> "session.boundary-warning-subtitle"
            }
        player.showTitle(
            Title.title(
                locales.component(player, titleKey),
                locales.component(player, subtitleKey),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1_800), Duration.ofMillis(250)),
            ),
        )
    }

    private companion object {
        const val COMMAND_BYPASS_PERMISSION = "arcduels.bypass"
        const val BOUNDARY_WARNING_COOLDOWN_MILLIS = 2_000L
        const val BOUNDARY_PARTICLE_COOLDOWN_MILLIS = 250L
        val BOUNDARY_DUST = Particle.DustOptions(Color.fromRGB(255, 48, 48), 1.25f)
    }
}
