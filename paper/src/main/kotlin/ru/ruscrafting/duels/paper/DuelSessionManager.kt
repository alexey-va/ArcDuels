package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.MatchCoordinator
import ru.ruscrafting.duels.domain.MatchEndReason
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchState
import ru.ruscrafting.duels.domain.PlayerId
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class DuelSessionManager(
    private val plugin: JavaPlugin,
    private val coordinator: MatchCoordinator,
    private val arenas: PaperArenaCatalog,
    private val kits: KitRegistry,
) {
    private val miniMessage = MiniMessage.miniMessage()
    private val sessions = ConcurrentHashMap<MatchId, PaperSession>()
    private val sessionByPlayer = ConcurrentHashMap<UUID, MatchId>()
    private val countdownTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val internalTeleports = InternalTeleportAuthorizer()

    fun start(challenge: DuelChallenge): CompletableFuture<DuelMatch> {
        check(challenge.status == ChallengeStatus.ACCEPTED) { "Only an accepted challenge can start" }
        val first = plugin.server.getPlayer(challenge.challenger.value)
            ?: return CompletableFuture.failedFuture(IllegalStateException("Первый игрок вышел с сервера"))
        val second = plugin.server.getPlayer(challenge.target.value)
            ?: return CompletableFuture.failedFuture(IllegalStateException("Второй игрок вышел с сервера"))
        val result = CompletableFuture<DuelMatch>()
        val reservation =
            runCatching { coordinator.reserve(PlayerId(first.uniqueId), PlayerId(second.uniqueId), challenge.rules) }
                .getOrElse { failure ->
                    result.completeExceptionally(failure)
                    return result
                }
        reservation
            .whenComplete { match, failure ->
                runSync {
                    if (failure != null) {
                        result.completeExceptionally(unwrap(failure))
                        return@runSync
                    }
                    val reservedMatch = requireNotNull(match)
                    runCatching { prepareNewSession(reservedMatch, first, second) }
                        .onSuccess(result::complete)
                        .onFailure { setupFailure ->
                            runCatching { coordinator.cancel(reservedMatch.id, MatchEndReason.ADMIN_CANCEL) }
                            result.completeExceptionally(setupFailure)
                        }
                }
            }
        return result
    }

    fun matchFor(player: Player): DuelMatch? =
        coordinator.findByPlayer(PlayerId(player.uniqueId))
            ?: sessionByPlayer[player.uniqueId]?.let(coordinator::find)

    fun handleElimination(loser: Player) {
        val match = matchFor(loser) ?: return
        if (match.state != MatchState.ACTIVE) return
        val winner = match.opponentOf(PlayerId(loser.uniqueId))
        coordinator.recordRoundWinner(match.id, winner).whenComplete { updated, failure ->
            runSync {
                if (failure != null) {
                    announcePersistenceFailure(match.id, unwrap(failure))
                } else if (updated.state == MatchState.COUNTDOWN) {
                    announceRound(updated, winner)
                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
                        val current = coordinator.find(updated.id)
                        if (current?.state == MatchState.COUNTDOWN) {
                            prepareRound(current)
                            scheduleCountdown(current.id)
                        }
                    }, 30L)
                } else {
                    finish(updated)
                }
            }
        }
    }

    fun handleQuit(player: Player) {
        val match = matchFor(player) ?: return
        sessions[match.id]?.snapshots?.get(player.uniqueId)?.let { restorePlayer(player, it) }
        if (match.state !in setOf(MatchState.COMPLETING, MatchState.COMPLETED, MatchState.CANCELLED)) {
            coordinator.forfeit(match.id, PlayerId(player.uniqueId), MatchEndReason.DISCONNECT)
                .whenComplete { completed, failure ->
                    runSync {
                        if (failure != null) announcePersistenceFailure(match.id, unwrap(failure)) else finish(completed)
                    }
                }
        }
    }

    fun handleForfeit(player: Player): Boolean {
        val match = matchFor(player) ?: return false
        if (match.state !in setOf(MatchState.RESERVED, MatchState.COUNTDOWN, MatchState.ACTIVE)) return false
        val completion =
            runCatching { coordinator.forfeit(match.id, PlayerId(player.uniqueId), MatchEndReason.FORFEIT) }
                .getOrElse { return false }
        completion.whenComplete { completed, failure ->
            runSync {
                if (failure != null) announcePersistenceFailure(match.id, unwrap(failure)) else finish(completed)
            }
        }
        return true
    }

    fun isInsideArena(
        player: Player,
        destination: org.bukkit.Location,
    ): Boolean {
        val match = matchFor(player) ?: return true
        return arenas.get(match.arenaId).bounds.contains(destination)
    }

    fun isTeleportAllowed(
        player: Player,
        destination: org.bukkit.Location?,
        cause: PlayerTeleportEvent.TeleportCause,
    ): Boolean {
        if (internalTeleports.isAuthorized(player.uniqueId, destination)) return true
        val match = matchFor(player) ?: return true
        if (match.state != MatchState.ACTIVE || destination == null) return false
        return cause in PLAYER_COMBAT_TELEPORTS && arenas.get(match.arenaId).bounds.contains(destination)
    }

    fun retryCompletion(matchId: MatchId) {
        coordinator.retryCompletion(matchId).whenComplete { completed, failure ->
            runSync {
                if (failure != null) announcePersistenceFailure(matchId, unwrap(failure)) else finish(completed)
            }
        }
    }

    fun shutdown() {
        countdownTasks.values.forEach(BukkitTask::cancel)
        countdownTasks.clear()
        for ((matchId, session) in sessions) {
            coordinator.find(matchId)?.let { match ->
                if (match.state !in setOf(MatchState.COMPLETING, MatchState.COMPLETED, MatchState.CANCELLED)) {
                    runCatching { coordinator.cancel(matchId, MatchEndReason.SERVER_SHUTDOWN) }
                }
            }
            restore(session)
            coordinator.find(matchId)?.takeIf { it.state == MatchState.COMPLETED }?.let {
                runCatching { coordinator.releaseCompleted(matchId) }
            }
        }
        sessions.clear()
        sessionByPlayer.clear()
    }

    private fun prepareNewSession(
        match: DuelMatch,
        first: Player,
        second: Player,
    ): DuelMatch {
        check(plugin.server.isPrimaryThread) { "Paper duel setup must run on the main thread" }
        val session =
            PaperSession(
                match.id,
                snapshots = mapOf(first.uniqueId to PlayerSnapshot.capture(first), second.uniqueId to PlayerSnapshot.capture(second)),
            )
        sessions[match.id] = session
        session.snapshots.keys.forEach { sessionByPlayer[it] = match.id }
        try {
            prepareRound(match)
            coordinator.beginCountdown(match.id)
            scheduleCountdown(match.id)
            return coordinator.find(match.id) ?: error("Match disappeared during Paper setup")
        } catch (failure: Throwable) {
            sessions.remove(match.id)
            session.snapshots.keys.forEach { sessionByPlayer.remove(it, match.id) }
            restore(session)
            throw failure
        }
    }

    private fun prepareRound(match: DuelMatch) {
        val session = sessions[match.id] ?: error("Missing Paper session for match ${match.id}")
        val arena = arenas.get(match.arenaId)
        val first = requireOnline(match.firstPlayer)
        val second = requireOnline(match.secondPlayer)
        resetPlayer(first, session.snapshots.getValue(first.uniqueId), match.rules.mode, match.rules.kitId)
        resetPlayer(second, session.snapshots.getValue(second.uniqueId), match.rules.mode, match.rules.kitId)
        check(teleportInternally(first, arena.firstSpawn)) { "Could not teleport the first player to the arena" }
        check(teleportInternally(second, arena.secondSpawn)) { "Could not teleport the second player to the arena" }
        first.sendActionBar(scoreLine(match))
        second.sendActionBar(scoreLine(match))
    }

    private fun resetPlayer(
        player: Player,
        snapshot: PlayerSnapshot,
        mode: DuelMode,
        kitId: ru.ruscrafting.duels.domain.KitId?,
    ) {
        snapshot.restoreState(player)
        player.gameMode = GameMode.SURVIVAL
        player.allowFlight = false
        player.isFlying = false
        player.fireTicks = 0
        player.fallDistance = 0f
        player.noDamageTicks = 0
        player.absorptionAmount = 0.0
        player.velocity = Vector()
        player.setItemOnCursor(ItemStack.empty())
        player.foodLevel = 20
        player.saturation = 5f
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        if (mode == DuelMode.KIT) {
            val kit = kits.get(requireNotNull(kitId))
            player.inventory.clear()
            player.inventory.armorContents = arrayOfNulls<ItemStack>(4)
            player.inventory.setItemInOffHand(null)
            for ((slot, item) in kit.items) player.inventory.setItem(slot, item.clone())
            player.inventory.helmet = kit.helmet?.clone()
            player.inventory.chestplate = kit.chestplate?.clone()
            player.inventory.leggings = kit.leggings?.clone()
            player.inventory.boots = kit.boots?.clone()
        }
        player.updateInventory()
    }

    private fun scheduleCountdown(matchId: MatchId) {
        countdownTasks.remove(matchId)?.cancel()
        val task =
            object : BukkitRunnable() {
                var seconds = 3

                override fun run() {
                    val match = coordinator.find(matchId)
                    if (match == null || match.state != MatchState.COUNTDOWN) {
                        countdownTasks.remove(matchId)
                        cancel()
                        return
                    }
                    val players = participants(match)
                    if (seconds > 0) {
                        val color = if (seconds == 1) "<red>" else "<gold>"
                        val title = miniMessage.deserialize("$color<bold>$seconds</bold>")
                        players.forEach { player ->
                            player.showTitle(Title.title(title, Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(850), Duration.ZERO)))
                            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.4f)
                        }
                        seconds--
                        return
                    }
                    val active = coordinator.activate(matchId)
                    participants(active).forEach { player ->
                        player.showTitle(
                            Title.title(
                                miniMessage.deserialize("<red><bold>В БОЙ!</bold></red>"),
                                miniMessage.deserialize("<gray>Удачи — она пригодится</gray>"),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(250)),
                            ),
                        )
                        player.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.45f, 1.5f)
                    }
                    countdownTasks.remove(matchId)
                    cancel()
                }
            }.runTaskTimer(plugin, 0L, 20L)
        countdownTasks[matchId] = task
    }

    private fun announceRound(
        match: DuelMatch,
        winner: PlayerId,
    ) {
        participants(match).forEach { player ->
            val won = player.uniqueId == winner.value
            player.showTitle(
                Title.title(
                    miniMessage.deserialize(if (won) "<green><bold>РАУНД ТВОЙ</bold></green>" else "<red><bold>РАУНД ПРОИГРАН</bold></red>"),
                    scoreLine(match),
                ),
            )
        }
    }

    private fun finish(match: DuelMatch) {
        if (match.state != MatchState.COMPLETED) return
        val session = sessions[match.id]
        if (session == null) {
            runCatching { coordinator.releaseCompleted(match.id) }
                .onFailure { plugin.logger.severe("Could not release completed duel ${match.id}: ${it.message}") }
            return
        }
        if (!session.finishing.compareAndSet(false, true)) return
        countdownTasks.remove(match.id)?.cancel()
        val winnerId = requireNotNull(match.winner)
        participants(match).forEach { player ->
            val won = player.uniqueId == winnerId.value
            player.showTitle(
                Title.title(
                    miniMessage.deserialize(if (won) "<gradient:#55ff55:#00aa00><bold>ПОБЕДА</bold></gradient>" else "<gradient:#ff5555:#aa0000><bold>ПОРАЖЕНИЕ</bold></gradient>"),
                    scoreLine(match),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500)),
                ),
            )
            player.playSound(
                player.location,
                if (won) Sound.UI_TOAST_CHALLENGE_COMPLETE else Sound.ENTITY_WITHER_HURT,
                if (won) 0.9f else 0.5f,
                1.0f,
            )
        }
        try {
            restore(session)
            plugin.server.getPlayer(winnerId.value)?.let(::celebrate)
        } finally {
            sessions.remove(match.id, session)
            session.snapshots.keys.forEach { sessionByPlayer.remove(it, match.id) }
            runCatching { coordinator.releaseCompleted(match.id) }
                .onFailure { plugin.logger.severe("Could not release completed duel ${match.id}: ${it.message}") }
        }
    }

    private fun announcePersistenceFailure(
        matchId: MatchId,
        failure: Throwable,
    ) {
        plugin.logger.severe("Could not persist duel $matchId: ${failure.javaClass.simpleName}: ${failure.message}")
        coordinator.find(matchId)?.let { match ->
            participants(match).forEach { player ->
                player.sendActionBar(miniMessage.deserialize("<red>Не удалось сохранить результат. Повтор через 3 секунды…</red>"))
            }
        }
        plugin.server.scheduler.runTaskLater(plugin, Runnable { retryCompletion(matchId) }, 60L)
    }

    private fun celebrate(player: Player) {
        val center = player.location.add(0.0, 1.0, 0.0)
        player.world.spawnParticle(Particle.FIREWORK, center, 120, 1.2, 1.5, 1.2, 0.2)
        player.world.spawnParticle(Particle.FLASH, center, 8, 0.7, 0.8, 0.7, 0.0)
        player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 80, 0.8, 1.0, 0.8, 0.15)
        player.world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.8f, 1.0f)
        player.world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.2f)
    }

    private fun restore(session: PaperSession) {
        session.snapshots.forEach { (uuid, snapshot) -> plugin.server.getPlayer(uuid)?.let { restorePlayer(it, snapshot) } }
    }

    private fun restorePlayer(
        player: Player,
        snapshot: PlayerSnapshot,
    ) {
        runCatching { snapshot.restore(player, ::teleportInternally) }
            .onFailure { failure ->
                plugin.logger.severe("Could not fully restore duel player ${player.uniqueId}: ${failure.javaClass.simpleName}: ${failure.message}")
            }
    }

    private fun teleportInternally(
        player: Player,
        destination: org.bukkit.Location,
    ): Boolean = internalTeleports.authorize(player.uniqueId, destination) { player.teleport(destination) }

    private fun participants(match: DuelMatch): List<Player> =
        listOfNotNull(plugin.server.getPlayer(match.firstPlayer.value), plugin.server.getPlayer(match.secondPlayer.value))

    private fun requireOnline(playerId: PlayerId): Player =
        plugin.server.getPlayer(playerId.value) ?: error("Игрок $playerId вышел с сервера")

    private fun scoreLine(match: DuelMatch): Component =
        miniMessage.deserialize("<gray>Счёт:</gray> <aqua>${match.score.first}</aqua> <dark_gray>—</dark_gray> <red>${match.score.second}</red>")

    private fun runSync(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (plugin.server.isPrimaryThread) block() else plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private fun unwrap(failure: Throwable): Throwable = failure.cause ?: failure

    private data class PaperSession(
        val matchId: MatchId,
        val snapshots: Map<UUID, PlayerSnapshot>,
        val finishing: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(),
    )

    private companion object {
        val PLAYER_COMBAT_TELEPORTS =
            setOf(PlayerTeleportEvent.TeleportCause.ENDER_PEARL, PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT)
    }
}
