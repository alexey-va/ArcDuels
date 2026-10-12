package ru.ruscrafting.duels.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.Configuration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import ru.arc.staffspells.api.StaffSpellDuelContext
import java.util.Locale
import java.util.UUID

internal data class MagicDuelEchoSettings(val windowSeconds: Long = 4L, val cooldownSeconds: Long = 18L) {
    init {
        require(windowSeconds in 1L..10L) { "magic-duel.echo.window-seconds must be between 1 and 10" }
        require(cooldownSeconds in windowSeconds..300L) {
            "magic-duel.echo.cooldown-seconds must be between window-seconds and 300"
        }
    }

    companion object {
        fun parse(config: Configuration) = MagicDuelEchoSettings(
            config.strictLong("magic-duel.echo.window-seconds", 4L),
            config.strictLong("magic-duel.echo.cooldown-seconds", 18L),
        )
    }
}

/** One anchor per living magic-kit participant. All state and Bukkit calls are Paper-thread owned. */
internal class MagicDuelEcho(
    tasks: LifecycleTaskScope,
    private val locales: LocaleService,
    private val settingsForNewRound: () -> MagicDuelEchoSettings,
    private val activeContext: (Player) -> StaffSpellDuelContext?,
    private val isInsideArena: (Player, Location) -> Boolean,
    private val teleport: (Player, Location) -> Boolean,
    private val nowNanos: () -> Long = System::nanoTime,
    private val safeLanding: (Player, Location) -> Boolean = MagicDuelTeleportSafety(isInsideArena)::isSafe,
    private val drawAnchor: (Player, Location, Double) -> Unit = MagicDuelEchoEffects::anchor,
    private val playCue: (Player, Location, MagicDuelEchoCue) -> Unit = MagicDuelEchoEffects::cue,
) : Listener, AutoCloseable {
    private class State(val context: StaffSpellDuelContext, val settings: MagicDuelEchoSettings) {
        var anchor: Location? = null
        var expiresAt = 0L
        var readyAt = 0L
        var used = false
        var inputAt: Long? = null
        var feedbackUntil = 0L
        var feedback: String? = null
        val bar: BossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS)

        fun matches(other: StaffSpellDuelContext?) = other != null &&
            context.matchId() == other.matchId() && context.round() == other.round()
    }

    private val safety = MagicDuelTeleportSafety(isInsideArena)
    private val states = mutableMapOf<UUID, State>()
    private val teleporting = mutableSetOf<UUID>()
    private var closed = false
    private var frame = 0
    private val task = tasks.runTimer(1L, 2L, ::tick)

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (closed) return
        val player = event.player
        val context = activeContext(player) ?: return
        // Cancel before either duel listener's generic inventory lock. Never remove or reissue the item.
        event.isCancelled = true
        if (!isInsideArena(player, player.location)) {
            clear(player.uniqueId)
            return
        }
        if (player.isSneaking || player.uniqueId in teleporting) return
        val state = stateFor(player, context)
        val now = nowNanos()
        if (state.inputAt?.let { now - it < 250_000_000L } == true) return
        val justExpired = expire(player, state, now)
        val anchor = state.anchor
        if (anchor == null) {
            if (state.used && now < state.readyAt) {
                if (!justExpired) feedback(player, state, "cooldown", MagicDuelEchoCue.DENIED, now)
                return
            }
            val origin = player.location.clone()
            if (!safeLanding(player, origin)) {
                feedback(player, state, "unsafe", MagicDuelEchoCue.DENIED, now)
                return
            }
            state.inputAt = now
            state.anchor = origin
            state.expiresAt = now + state.settings.windowSeconds * SECOND
            state.readyAt = now + state.settings.cooldownSeconds * SECOND
            state.used = true
            state.feedback = null
            playCue(player, origin, MagicDuelEchoCue.PLACED)
            drawAnchor(player, origin, 1.0)
        } else {
            // Keep the current aim; return changes position only, never health, potion effects or spell cooldowns.
            val destination = anchor.clone().apply { yaw = player.location.yaw; pitch = player.location.pitch }
            if (!safeLanding(player, destination)) {
                feedback(player, state, "blocked", MagicDuelEchoCue.DENIED, now)
                return
            }
            if (!state.matches(activeContext(player)) || !isInsideArena(player, destination)) {
                clear(player.uniqueId)
                return
            }
            val origin = player.location.clone()
            teleporting += player.uniqueId
            val returned = try {
                teleport(player, destination) && state.matches(activeContext(player)) &&
                    states[player.uniqueId] === state && safety.isExactDestination(player.location, destination)
            } finally {
                teleporting -= player.uniqueId
            }
            if (!returned) {
                if (!state.matches(activeContext(player))) clear(player.uniqueId)
                else feedback(player, state, "blocked", MagicDuelEchoCue.DENIED, now)
                return
            }
            state.inputAt = now
            state.anchor = null
            player.velocity = Vector()
            player.fallDistance = 0f
            playCue(player, origin, MagicDuelEchoCue.DEPARTED)
            feedback(player, state, "returned", MagicDuelEchoCue.RETURNED, now)
        }
        showStatus(player, state, now)
    }

    /** Dropping from an inventory is editing the kit, not casting Q in the world. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onInventoryDrop(event: InventoryClickEvent) {
        if (closed || event.action !in DROP_ACTIONS) return
        val player = event.whoClicked as? Player ?: return
        if (activeContext(player) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = clear(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) = clear(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = clear(event.player.uniqueId)

    /** Match/round teardown invalidates anchors immediately, before player-state restoration. */
    fun reset(playerIds: Collection<UUID>) = playerIds.forEach(::clear)

    override fun close() {
        if (closed) return
        closed = true
        task?.cancel()
        states.keys.toList().forEach(::clear)
        teleporting.clear()
    }

    private fun tick() {
        if (closed) return
        val now = nowNanos()
        frame++
        Bukkit.getOnlinePlayers().forEach { player ->
            val context = activeContext(player)
            if (context == null || !isInsideArena(player, player.location)) {
                clear(player.uniqueId)
                return@forEach
            }
            val state = stateFor(player, context)
            expire(player, state, now)
            state.anchor?.let { anchor ->
                if (anchor.world?.uid != player.world.uid || !isInsideArena(player, anchor)) {
                    state.anchor = null
                } else if (frame % 2 == 0) {
                    drawAnchor(player, anchor, (state.expiresAt - now).toDouble() / (state.settings.windowSeconds * SECOND))
                }
            }
            showStatus(player, state, now)
        }
        states.keys.toList().filter { Bukkit.getPlayer(it) == null }.forEach(::clear)
    }

    private fun stateFor(player: Player, context: StaffSpellDuelContext): State {
        states[player.uniqueId]?.takeIf { it.matches(context) }?.let { return it }
        clear(player.uniqueId)
        return State(context, settingsForNewRound()).also {
            states[player.uniqueId] = it
            player.showBossBar(it.bar)
        }
    }

    private fun expire(player: Player, state: State, now: Long): Boolean {
        if (state.anchor != null && now >= state.expiresAt) {
            state.anchor = null
            feedback(player, state, "expired", MagicDuelEchoCue.EXPIRED, now)
            return true
        }
        return false
    }

    private fun feedback(player: Player, state: State, key: String, cue: MagicDuelEchoCue, now: Long) {
        if (state.feedback != key || now >= state.feedbackUntil) playCue(player, player.location, cue)
        state.feedback = key
        state.feedbackUntil = now + SECOND
        showStatus(player, state, now)
    }

    private fun showStatus(player: Player, state: State, now: Long) {
        val active = state.anchor != null
        val remaining = when {
            active -> (state.expiresAt - now).coerceAtLeast(0L)
            state.used -> (state.readyAt - now).coerceAtLeast(0L)
            else -> 0L
        }
        val key = state.feedback?.takeIf { now < state.feedbackUntil }
            ?: if (active) "active" else if (remaining > 0L) "cooldown" else "ready"
        state.bar.name(locales.component(player, "magic.echo.$key",
            LocaleService.text("seconds", String.format(Locale.ROOT, "%.1f", remaining.toDouble() / SECOND)),
        ))
        state.bar.color(if (active) BossBar.Color.BLUE else BossBar.Color.PURPLE)
        val progress = if (active) remaining.toDouble() / (state.settings.windowSeconds * SECOND)
            else 1.0 - remaining.toDouble() / (state.settings.cooldownSeconds * SECOND)
        state.bar.progress(progress.coerceIn(0.0, 1.0).toFloat())
    }

    private fun clear(playerId: UUID) {
        states.remove(playerId)?.let { state -> Bukkit.getPlayer(playerId)?.hideBossBar(state.bar) }
    }

    private companion object {
        const val SECOND = 1_000_000_000L
        val DROP_ACTIONS = setOf(InventoryAction.DROP_ALL_CURSOR, InventoryAction.DROP_ONE_CURSOR,
            InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT)
    }
}

internal enum class MagicDuelEchoCue { PLACED, DEPARTED, RETURNED, EXPIRED, DENIED }
