package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.Particle
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.KingOfTheHillObjective
import ru.ruscrafting.duels.domain.MatchCoordinator
import ru.ruscrafting.duels.domain.MatchEndReason
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchState
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ObjectiveFrame
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class DuelSessionManager internal constructor(
    private val plugin: JavaPlugin,
    private val coordinator: MatchCoordinator,
    private val arenas: PaperArenaCatalog,
    private val kits: KitRegistry,
    private val playerStates: DurablePlayerStateService,
    private val locales: LocaleService,
) {
    private val miniMessage = MiniMessage.miniMessage()
    private val sessions = ConcurrentHashMap<MatchId, PaperSession>()
    private val sessionByPlayer = ConcurrentHashMap<UUID, MatchId>()
    private val countdownTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val objectiveTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val pendingStarts = ConcurrentHashMap<UUID, CompletableFuture<DuelMatch>>()
    private val preparingPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val internalTeleports = InternalTeleportAuthorizer()
    private val celebrationEffects = CelebrationEffects(plugin)

    fun start(challenge: DuelChallenge): CompletableFuture<DuelMatch> {
        check(challenge.status == ChallengeStatus.ACCEPTED) { "Only an accepted challenge can start" }
        val first = plugin.server.getPlayer(challenge.challenger.value)
            ?: return CompletableFuture.failedFuture(IllegalStateException("The first player left the server"))
        val second = plugin.server.getPlayer(challenge.target.value)
            ?: return CompletableFuture.failedFuture(IllegalStateException("The second player left the server"))
        val result = CompletableFuture<DuelMatch>()
        val reservation =
            runCatching { coordinator.reserve(PlayerId(first.uniqueId), PlayerId(second.uniqueId), challenge.rules) }
                .getOrElse { failure ->
                    result.completeExceptionally(failure)
                    return result
                }
        pendingStarts[first.uniqueId] = result
        pendingStarts[second.uniqueId] = result
        if (!reservation.isDone) {
            val position = arenas.queueSize()
            first.sendMessage(locales.component(first, "session.queued", LocaleService.text("position", position)))
            second.sendMessage(locales.component(second, "session.queued", LocaleService.text("position", position)))
        }
        reservation
            .whenComplete { match, failure ->
                runSync {
                    if (failure != null) {
                        result.completeExceptionally(unwrap(failure))
                        return@runSync
                    }
                    val reservedMatch = requireNotNull(match)
                    if (result.isCancelled) {
                        runCatching { coordinator.cancel(reservedMatch.id, MatchEndReason.ADMIN_CANCEL) }
                        return@runSync
                    }
                    val currentFirst = plugin.server.getPlayer(first.uniqueId)
                    val currentSecond = plugin.server.getPlayer(second.uniqueId)
                    if (currentFirst == null || currentSecond == null) {
                        runCatching { coordinator.cancel(reservedMatch.id, MatchEndReason.ADMIN_CANCEL) }
                        result.completeExceptionally(IllegalStateException("A player left while waiting for an arena"))
                        return@runSync
                    }
                    preparingPlayers += currentFirst.uniqueId
                    preparingPlayers += currentSecond.uniqueId
                    val durableWrite =
                        runCatching { playerStates.storePair(reservedMatch.id, currentFirst, currentSecond) }
                            .getOrElse { CompletableFuture.failedFuture(it) }
                    durableWrite.whenComplete { stored, storageFailure ->
                        runSync {
                            if (storageFailure != null) {
                                runCatching { coordinator.cancel(reservedMatch.id, MatchEndReason.ADMIN_CANCEL) }
                                // Do not unlock either participant until a fresh read proves that
                                // an unknown COMMIT outcome left no recoverable snapshot behind.
                                discoverPendingState(currentFirst, notifyFailure = true)
                                discoverPendingState(currentSecond, notifyFailure = true)
                                result.completeExceptionally(
                                    IllegalStateException("Could not durably store both inventories in MySQL", unwrap(storageFailure)),
                                )
                                return@runSync
                            }
                            if (result.isCancelled || !currentFirst.isOnline || !currentSecond.isOnline) {
                                preparingPlayers -= currentFirst.uniqueId
                                preparingPlayers -= currentSecond.uniqueId
                                runCatching { coordinator.cancel(reservedMatch.id, MatchEndReason.ADMIN_CANCEL) }
                                requireNotNull(stored).values.forEach { saved ->
                                    plugin.server.getPlayer(saved.escrow.playerId.value)?.let { restoreAndRetain(it, saved) }
                                }
                                result.completeExceptionally(IllegalStateException("A player left before the match started"))
                                return@runSync
                            }
                            runCatching { prepareNewSession(reservedMatch, requireNotNull(stored)) }
                                .onSuccess(result::complete)
                                .onFailure { setupFailure ->
                                    requireNotNull(stored).values.forEach { saved ->
                                        plugin.server.getPlayer(saved.escrow.playerId.value)?.let { restoreAndRetain(it, saved) }
                                    }
                                    runCatching { coordinator.cancel(reservedMatch.id, MatchEndReason.ADMIN_CANCEL) }
                                    result.completeExceptionally(setupFailure)
                                }
                            preparingPlayers -= currentFirst.uniqueId
                            preparingPlayers -= currentSecond.uniqueId
                        }
                    }
                }
            }
        result.whenComplete { _, _ ->
            pendingStarts.remove(first.uniqueId, result)
            pendingStarts.remove(second.uniqueId, result)
            if (result.isCancelled) reservation.cancel(false)
        }
        return result
    }

    fun matchFor(player: Player): DuelMatch? =
        coordinator.findByPlayer(PlayerId(player.uniqueId))
            ?: sessionByPlayer[player.uniqueId]?.let(coordinator::find)

    fun isEngaged(player: Player): Boolean =
        coordinator.isQueuedOrMatched(PlayerId(player.uniqueId)) || pendingStarts.containsKey(player.uniqueId)

    fun isStateLocked(player: Player): Boolean =
        preparingPlayers.contains(player.uniqueId) || playerStates.isPending(player.uniqueId) || matchFor(player) != null

    fun isPreparing(player: Player): Boolean = preparingPlayers.contains(player.uniqueId)

    fun queueSize(): Int = arenas.queueSize()

    fun activeArenaCount(): Int = arenas.reservedCount()

    fun handleJoin(player: Player) {
        preparingPlayers += player.uniqueId
        discoverPendingState(player, notifyFailure = true)
    }

    private fun discoverPendingState(
        player: Player,
        notifyFailure: Boolean,
    ) {
        playerStates.discover(player.uniqueId).whenComplete { escrow, lookupFailure ->
            runSync {
                if (lookupFailure != null) {
                    plugin.logger.warning("Could not check pending duel state for ${player.uniqueId}: ${unwrap(lookupFailure).message}")
                    if (player.isOnline) {
                        if (notifyFailure) player.sendMessage(locales.component(player, "session.recovery-check-retry"))
                        plugin.server.scheduler.runTaskLater(
                            plugin,
                            Runnable {
                                if (player.isOnline && player.uniqueId in preparingPlayers) {
                                    discoverPendingState(player, notifyFailure = false)
                                }
                            },
                            RECOVERY_RETRY_TICKS,
                        )
                    }
                    return@runSync
                }
                if (escrow == null) {
                    preparingPlayers -= player.uniqueId
                    return@runSync
                }
                if (!player.isOnline) return@runSync
                if (!playerStates.isLocal(escrow)) {
                    player.sendMessage(locales.component(player, "session.remote-recovery", LocaleService.text("server", escrow.serverId.value)))
                    return@runSync
                }
                player.sendMessage(locales.component(player, "session.recovering"))
                runCatching { playerStates.decode(escrow) }
                    .onSuccess { restoreAndRetain(player, it) }
                    .onFailure { failure ->
                        plugin.logger.severe("Could not decode pending duel state for ${player.uniqueId}: ${failure.message}")
                        player.sendMessage(locales.component(player, "session.recovery-failed"))
                    }
            }
        }
    }

    fun recover(player: Player): Boolean {
        if (matchFor(player) != null || pendingStarts.containsKey(player.uniqueId)) return false
        val escrow = playerStates.pending(player.uniqueId) ?: return false
        preparingPlayers += player.uniqueId
        runCatching { playerStates.decode(escrow) }
            .onSuccess { restoreAndRetain(player, it) }
            .onFailure { failure ->
                plugin.logger.severe("Manual duel recovery failed for ${player.uniqueId}: ${failure.message}")
            }
        return true
    }

    fun handleElimination(loser: Player) {
        val match = matchFor(loser) ?: return
        if (match.state != MatchState.ACTIVE) return
        objectiveTasks.remove(match.id)?.cancel()
        val winner = match.opponentOf(PlayerId(loser.uniqueId))
        coordinator.recordRoundWinner(match.id, winner).whenComplete { updated, failure ->
            runSync { handleRoundResult(match.id, winner, updated, failure) }
        }
    }

    fun allowsProjectiles(player: Player): Boolean = matchFor(player)?.rules?.modifiers?.projectiles ?: true

    fun allowsConsumables(player: Player): Boolean = matchFor(player)?.rules?.modifiers?.consumables ?: true

    fun allowsEnderPearls(player: Player): Boolean = matchFor(player)?.rules?.modifiers?.enderPearls ?: true

    fun allowsNaturalRegeneration(player: Player): Boolean = matchFor(player)?.rules?.modifiers?.naturalRegeneration ?: true

    fun isSumo(player: Player): Boolean = matchFor(player)?.rules?.objective == DuelObjectiveType.SUMO

    fun handleQuit(player: Player) {
        pendingStarts[player.uniqueId]?.cancel(false)
        val match = matchFor(player)
        if (match == null) {
            preparingPlayers -= player.uniqueId
            return
        }
        if (preparingPlayers.contains(player.uniqueId) && sessions[match.id] == null) {
            runCatching { coordinator.cancel(match.id, MatchEndReason.ADMIN_CANCEL) }
            return
        }
        sessions[match.id]?.snapshots?.get(player.uniqueId)?.let { restoreAndRetain(player, it) }
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
        pendingStarts[player.uniqueId]?.let { pending ->
            pending.cancel(false)
            return true
        }
        val match = matchFor(player) ?: return false
        if (preparingPlayers.contains(player.uniqueId) && sessions[match.id] == null) {
            runCatching { coordinator.cancel(match.id, MatchEndReason.ADMIN_CANCEL) }
            return true
        }
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
        val match = matchFor(player) ?: return !isStateLocked(player)
        if (match.state != MatchState.ACTIVE || destination == null) return false
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && !match.rules.modifiers.enderPearls) return false
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
        objectiveTasks.values.forEach(BukkitTask::cancel)
        objectiveTasks.clear()
        pendingStarts.values.toSet().forEach { it.cancel(false) }
        pendingStarts.clear()
        coordinator.activeMatches()
            .filter { !sessions.containsKey(it.id) }
            .forEach { match -> runCatching { coordinator.cancel(match.id, MatchEndReason.SERVER_SHUTDOWN) } }
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
        preparingPlayers.clear()
    }

    private fun prepareNewSession(
        match: DuelMatch,
        snapshots: Map<UUID, StoredPlayerSnapshot>,
    ): DuelMatch {
        check(plugin.server.isPrimaryThread) { "Paper duel setup must run on the main thread" }
        val session =
            PaperSession(
                match.id,
                snapshots = snapshots,
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
        objectiveTasks.remove(match.id)?.cancel()
        session.hillCapture.reset()
        session.roundElapsedTicks = 0L
        session.suddenDeathStarted = false
        val arena = arenas.get(match.arenaId)
        val first = requireOnline(match.firstPlayer)
        val second = requireOnline(match.secondPlayer)
        resetPlayer(first, session.snapshots.getValue(first.uniqueId), match.rules.mode, match.rules.kitId)
        resetPlayer(second, session.snapshots.getValue(second.uniqueId), match.rules.mode, match.rules.kitId)
        check(teleportInternally(first, arena.firstSpawn)) { "Could not teleport the first player to the arena" }
        check(teleportInternally(second, arena.secondSpawn)) { "Could not teleport the second player to the arena" }
        first.sendActionBar(scoreLine(match, first))
        second.sendActionBar(scoreLine(match, second))
    }

    private fun resetPlayer(
        player: Player,
        snapshot: StoredPlayerSnapshot,
        mode: DuelMode,
        kitId: ru.ruscrafting.duels.domain.KitId?,
    ) {
        snapshot.snapshot.restoreState(player)
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
                                locales.component(player, "session.fight-title"),
                                locales.component(player, "session.fight-subtitle"),
                                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(250)),
                            ),
                        )
                        player.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.45f, 1.5f)
                    }
                    scheduleObjective(active)
                    countdownTasks.remove(matchId)
                    cancel()
                }
            }.runTaskTimer(plugin, 0L, 20L)
        countdownTasks[matchId] = task
    }

    private fun scheduleObjective(match: DuelMatch) {
        objectiveTasks.remove(match.id)?.cancel()
        val session = sessions[match.id] ?: return
        val task =
            object : BukkitRunnable() {
                override fun run() {
                    val current = coordinator.find(match.id)
                    if (current == null || current.state != MatchState.ACTIVE) {
                        objectiveTasks.remove(match.id)
                        cancel()
                        return
                    }
                    session.roundElapsedTicks += OBJECTIVE_PERIOD_TICKS
                    startSuddenDeathIfNeeded(current, session)
                    if (current.rules.objective != DuelObjectiveType.KING_OF_THE_HILL) return
                    val hill = requireNotNull(arenas.get(current.arenaId).hill)
                    if (session.roundElapsedTicks % 20L == 0L) showHillBoundary(hill)
                    val contenders = participants(current).filter { hill.contains(it.location) }.mapTo(linkedSetOf()) { PlayerId(it.uniqueId) }
                    val progress = session.hillCapture.tick(contenders, OBJECTIVE_PERIOD_TICKS)
                    showHillProgress(current, contenders, progress)
                    val objective = KingOfTheHillObjective(current.rules.modifiers.kingOfTheHillCaptureSeconds)
                    coordinator.evaluateObjective(
                        current.id,
                        objective,
                        ObjectiveFrame(session.roundElapsedTicks, contenders, progress),
                    ).whenComplete { updated, failure ->
                        if (failure != null) {
                            runSync { handleRoundResult(current.id, current.firstPlayer, null, failure) }
                        } else if (updated.state != MatchState.ACTIVE) {
                            val winner = updated.winner ?: updated.score.let { score ->
                                when {
                                    score.first > current.score.first -> current.firstPlayer
                                    score.second > current.score.second -> current.secondPlayer
                                    else -> null
                                }
                            }
                            if (winner != null) runSync { handleRoundResult(current.id, winner, updated, null) }
                        }
                    }
                }
            }.runTaskTimer(plugin, OBJECTIVE_PERIOD_TICKS, OBJECTIVE_PERIOD_TICKS)
        objectiveTasks[match.id] = task
    }

    private fun showHillProgress(
        match: DuelMatch,
        contenders: Set<PlayerId>,
        progress: Map<PlayerId, Long>,
    ) {
        val target = match.rules.modifiers.kingOfTheHillCaptureSeconds * 20L
        participants(match).forEach { player ->
            val own = (progress[PlayerId(player.uniqueId)] ?: 0L).coerceAtMost(target)
            val percent = (own * 100L / target).toInt()
            val stateKey =
                when {
                    contenders.size > 1 -> "session.hill-contested"
                    PlayerId(player.uniqueId) in contenders -> "session.hill-capturing"
                    contenders.isEmpty() -> "session.hill-free"
                    else -> "session.hill-enemy"
                }
            player.sendActionBar(locales.component(player, "session.hill-progress", LocaleService.component("state", locales.component(player, stateKey)), LocaleService.text("percent", percent)))
        }
    }

    private fun showHillBoundary(hill: HillZone) {
        val world = hill.center.world ?: return
        repeat(HILL_PARTICLES) { index ->
            val angle = Math.PI * 2.0 * index / HILL_PARTICLES
            world.spawnParticle(
                Particle.END_ROD,
                hill.center.x + kotlin.math.cos(angle) * hill.radius,
                hill.center.y + 0.15,
                hill.center.z + kotlin.math.sin(angle) * hill.radius,
                1,
                0.0,
                0.0,
                0.0,
                0.0,
            )
        }
    }

    private fun startSuddenDeathIfNeeded(
        match: DuelMatch,
        session: PaperSession,
    ) {
        if (session.suddenDeathStarted || session.roundElapsedTicks < match.rules.modifiers.suddenDeathAfterSeconds * 20L) return
        session.suddenDeathStarted = true
        participants(match).forEach { player ->
            player.addPotionEffect(PotionEffect(PotionEffectType.WITHER, Int.MAX_VALUE, 0, false, false, true))
            player.sendMessage(locales.component(player, "session.sudden-death"))
            player.playSound(player.location, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.2f)
        }
    }

    private fun handleRoundResult(
        matchId: MatchId,
        winner: PlayerId,
        updated: DuelMatch?,
        failure: Throwable?,
    ) {
        objectiveTasks.remove(matchId)?.cancel()
        if (failure != null) {
            announcePersistenceFailure(matchId, unwrap(failure))
        } else if (updated?.state == MatchState.COUNTDOWN) {
            announceRound(updated, winner)
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val current = coordinator.find(updated.id)
                if (current?.state == MatchState.COUNTDOWN) {
                    prepareRound(current)
                    scheduleCountdown(current.id)
                }
            }, 30L)
        } else if (updated != null) {
            finish(updated)
        }
    }

    private fun announceRound(
        match: DuelMatch,
        winner: PlayerId,
    ) {
        participants(match).forEach { player ->
            val won = player.uniqueId == winner.value
            player.showTitle(
                Title.title(
                    locales.component(player, if (won) "session.round-win" else "session.round-loss"),
                    scoreLine(match, player),
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
        objectiveTasks.remove(match.id)?.cancel()
        val winnerId = requireNotNull(match.winner)
        participants(match).forEach { player ->
            val won = player.uniqueId == winnerId.value
            player.showTitle(
                Title.title(
                    locales.component(player, if (won) "session.match-win" else "session.match-loss"),
                    scoreLine(match, player),
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
        if (!restore(session)) {
            session.finishing.set(false)
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                coordinator.find(match.id)?.let(::finish)
            }, 20L)
            return
        }
        plugin.server.getPlayer(winnerId.value)?.let(::celebrate)
        sessions.remove(match.id, session)
        session.snapshots.keys.forEach { sessionByPlayer.remove(it, match.id) }
        runCatching { coordinator.releaseCompleted(match.id) }
            .onFailure { plugin.logger.severe("Could not release completed duel ${match.id}: ${it.message}") }
    }

    private fun announcePersistenceFailure(
        matchId: MatchId,
        failure: Throwable,
    ) {
        plugin.logger.severe("Could not persist duel $matchId: ${failure.javaClass.simpleName}: ${failure.message}")
        coordinator.find(matchId)?.let { match ->
            participants(match).forEach { player ->
                player.sendActionBar(locales.component(player, "session.persistence-retry"))
            }
        }
        plugin.server.scheduler.runTaskLater(plugin, Runnable { retryCompletion(matchId) }, 60L)
    }

    private fun celebrate(player: Player) {
        celebrationEffects.play(player)
    }

    private fun restore(session: PaperSession): Boolean {
        var restored = true
        session.snapshots.forEach { (uuid, snapshot) ->
            plugin.server.getPlayer(uuid)?.let { player ->
                if (!restoreAndRetain(player, snapshot)) restored = false
            }
        }
        return restored
    }

    private fun restoreAndRetain(
        player: Player,
        stored: StoredPlayerSnapshot,
    ): Boolean {
        val restored = runCatching {
            stored.snapshot.restore(player, ::teleportInternally)
            player.saveData()
        }
            .onFailure { failure ->
                plugin.logger.severe("Could not fully restore duel player ${player.uniqueId}: ${failure.javaClass.simpleName}: ${failure.message}")
            }
            .isSuccess
        if (!restored) return false
        playerStates.retain(stored).whenComplete { _, failure ->
            runSync {
                if (failure == null) {
                    preparingPlayers -= player.uniqueId
                    if (player.isOnline) {
                        player.sendActionBar(locales.component(player, "session.restored"))
                    }
                } else {
                    plugin.logger.severe(
                        "Could not archive restored duel state for ${player.uniqueId}; the active snapshot remains retryable: ${unwrap(failure).message}",
                    )
                    if (player.isOnline) {
                        player.sendMessage(locales.component(player, "session.ack-retry"))
                    }
                    plugin.server.scheduler.runTaskLater(
                        plugin,
                        Runnable { retryRetention(player, stored) },
                        RECOVERY_RETRY_TICKS,
                    )
                }
            }
        }
        return true
    }

    private fun retryRetention(
        player: Player,
        stored: StoredPlayerSnapshot,
    ) {
        playerStates.retain(stored).whenComplete { _, failure ->
            runSync {
                if (failure == null) {
                    preparingPlayers -= player.uniqueId
                } else if (plugin.isEnabled) {
                    plugin.logger.severe("Player state archival retry failed for ${player.uniqueId}: ${unwrap(failure).message}")
                    if (player.isOnline) {
                        plugin.server.scheduler.runTaskLater(
                            plugin,
                            Runnable { retryRetention(player, stored) },
                            RECOVERY_RETRY_TICKS,
                        )
                    }
                }
            }
        }
    }

    private fun teleportInternally(
        player: Player,
        destination: org.bukkit.Location,
    ): Boolean = internalTeleports.authorize(player.uniqueId, destination) { player.teleport(destination) }

    private fun participants(match: DuelMatch): List<Player> =
        listOfNotNull(plugin.server.getPlayer(match.firstPlayer.value), plugin.server.getPlayer(match.secondPlayer.value))

    private fun requireOnline(playerId: PlayerId): Player =
        plugin.server.getPlayer(playerId.value) ?: error("Player $playerId left the server")

    private fun scoreLine(match: DuelMatch, player: Player): Component =
        locales.component(player, "session.score", LocaleService.text("first", match.score.first), LocaleService.text("second", match.score.second))

    private fun runSync(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (plugin.server.isPrimaryThread) block() else plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private fun unwrap(failure: Throwable): Throwable = failure.cause ?: failure

    private data class PaperSession(
        val matchId: MatchId,
        val snapshots: Map<UUID, StoredPlayerSnapshot>,
        val hillCapture: HillCaptureTracker = HillCaptureTracker(),
        var roundElapsedTicks: Long = 0L,
        var suddenDeathStarted: Boolean = false,
        val finishing: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(),
    )

    private companion object {
        val PLAYER_COMBAT_TELEPORTS =
            setOf(PlayerTeleportEvent.TeleportCause.ENDER_PEARL, PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT)
        const val OBJECTIVE_PERIOD_TICKS = 10L
        const val HILL_PARTICLES = 16
        const val RECOVERY_RETRY_TICKS = 60L
    }
}
