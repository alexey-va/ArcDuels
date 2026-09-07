package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.BlockFace
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
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import ru.arc.paper.teleport.TeleportMatchTolerance
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
import ru.ruscrafting.duels.domain.ScoreRaceObjective
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.PlayerStateEscrow
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class DuelSessionManager internal constructor(
    private val plugin: JavaPlugin,
    private val coordinator: MatchCoordinator,
    private val arenas: PaperArenaCatalog,
    private val kits: KitRegistry,
    private val playerStates: DurablePlayerStateService,
    private val locales: LocaleService,
    private val countdownSeconds: Int,
    private val teleportStabilizationTicks: Long = 3L,
    private val serverNames: ServerDisplayNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning),
    private val remoteRecoveryTransfer: ((Player, ServerId) -> Unit)? = null,
    private val playerDataReady: (Player) -> Boolean = { true },
    private val recoveryApplyDelayTicks: Long = 40L,
    private val playerDataSaver: (Player) -> Unit = Player::saveData,
    private val syncProvider: PlayerDataSyncProvider = PlayerDataSyncProvider.NONE,
    private val celebrationDurationTicks: Long = 80L,
    private val seriesRoundIntermissionTicks: Long = 30L,
    private val externalCombatTagClear: (Player, MatchId, String) -> Unit = { _, _, _ -> },
    private val shutdownRecoveryTimeoutMillis: Long = 5_000L,
    private val defaultPostMatchReturnPolicy: PostMatchReturnPolicy = PostMatchReturnPolicy.PROMPT,
    private val runtimeSettings: () -> ArcDuelsRuntimeSettings? = { null },
) {
    private val sessions = ConcurrentHashMap<MatchId, PaperSession>()
    private val sessionByPlayer = ConcurrentHashMap<UUID, MatchId>()
    private val countdownTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val objectiveTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val matchDisplayTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val finaleTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val teleportStabilizationTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val healthIsolationTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val pendingStarts = ConcurrentHashMap<UUID, CompletableFuture<DuelMatch>>()
    private val preparingPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val recoveryTokens = ConcurrentHashMap<UUID, UUID>()
    private val remoteRecoveryTokens = ConcurrentHashMap<UUID, UUID>()
    private val restoringPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val expectedNetworkPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val networkLobbyPlayers = ConcurrentHashMap<UUID, MatchId>()
    @Volatile private var externalEngagement: ((Player) -> Boolean)? = null
    private val internalTeleports =
        ScopedTeleportAuthorizer(TeleportMatchTolerance(coordinate = 1.0e-7, angle = 1.0e-4f))
    private val celebrationEffects = CelebrationEffects(plugin)
    private val kitHealthIsolation = KitHealthIsolation(NamespacedKey(plugin, "kit_health_cap"))
    private val completionListeners = CopyOnWriteArrayList<(DuelMatch) -> Unit>()

    init {
        require(recoveryApplyDelayTicks in 0L..1_200L) { "Player data settle delay must be between 0 and 1200 ticks" }
        require(teleportStabilizationTicks in 0L..20L) { "Arena teleport stabilization must be between 0 and 20 ticks" }
        require(celebrationDurationTicks in 0L..200L) { "Celebration duration must be between 0 and 200 ticks" }
        require(seriesRoundIntermissionTicks in 0L..200L) { "Series round intermission must be between 0 and 200 ticks" }
        require(shutdownRecoveryTimeoutMillis in 100L..30_000L) { "Shutdown recovery timeout must be between 100 and 30000 ms" }
    }

    fun onCompleted(listener: (DuelMatch) -> Unit): AutoCloseable {
        completionListeners += listener
        return AutoCloseable { completionListeners -= listener }
    }

    fun start(challenge: DuelChallenge): CompletableFuture<DuelMatch> {
        check(challenge.status == ChallengeStatus.ACCEPTED) { "Only an accepted challenge can start" }
        val policy = sessionPolicy()
        val first = plugin.server.getPlayer(challenge.challenger.value)
            ?: return CompletableFuture.failedFuture(IllegalStateException("The first player left the server"))
        val second = plugin.server.getPlayer(challenge.target.value)
            ?: return CompletableFuture.failedFuture(IllegalStateException("The second player left the server"))
        val requestedMatchId = MatchId(challenge.id.value)
        DuelLog.info(
            "session-reserve",
            requestedMatchId,
            "network=false first={} second={} mode={} objective={} best_of={}",
            first.name,
            second.name,
            challenge.rules.mode,
            challenge.rules.objective,
            challenge.rules.bestOf,
        )
        val result = CompletableFuture<DuelMatch>()
        val reservation =
            runCatching {
                coordinator.reserve(
                    PlayerId(first.uniqueId),
                    PlayerId(second.uniqueId),
                    challenge.rules,
                    matchId = requestedMatchId,
                    arenaId = challenge.arenaSelection?.arenaId,
                )
            }
                .getOrElse { failure ->
                    result.completeExceptionally(failure)
                    return result
                }
        pendingStarts[first.uniqueId] = result
        pendingStarts[second.uniqueId] = result
        if (!reservation.isDone) {
            val position = arenas.queueSize()
            DuelLog.info("arena-queued", requestedMatchId, "position={}", position)
            first.sendMessage(locales.notice(first, "session.queued", LocaleService.text("position", position)))
            second.sendMessage(locales.notice(second, "session.queued", LocaleService.text("position", position)))
        }
        reservation
            .whenComplete { match, failure ->
                runSync {
                    if (failure != null) {
                        result.completeExceptionally(unwrap(failure))
                        return@runSync
                    }
                    val reservedMatch = requireNotNull(match)
                    DuelLog.info(
                        "arena-reserved",
                        reservedMatch.id,
                        "arena={} server={} first={} second={}",
                        reservedMatch.arenaId.value,
                        reservedMatch.serverId.value,
                        currentName(reservedMatch.firstPlayer),
                        currentName(reservedMatch.secondPlayer),
                    )
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
                    DuelLog.debug(
                        "snapshot-store-start",
                        reservedMatch.id,
                        "first={} second={} inventory_replaced={}",
                        currentFirst.name,
                        currentSecond.name,
                        reservedMatch.rules.mode == DuelMode.KIT,
                    )
                    val durableWrite =
                        runCatching {
                            playerStates.storePair(
                                reservedMatch.id,
                                currentFirst,
                                currentSecond,
                                inventoryReplaced = reservedMatch.rules.mode == DuelMode.KIT,
                            )
                        }
                            .getOrElse { CompletableFuture.failedFuture(it) }
                    durableWrite.whenComplete { stored, storageFailure ->
                        runSync {
                            if (storageFailure != null) {
                                DuelLog.warn(
                                    "snapshot-store-failed",
                                    reservedMatch.id,
                                    "error_type={} error={}",
                                    unwrap(storageFailure).javaClass.simpleName,
                                    unwrap(storageFailure).message,
                                )
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
                            DuelLog.info(
                                "snapshot-store-complete",
                                reservedMatch.id,
                                "players={} inventory_replaced={}",
                                requireNotNull(stored).size,
                                reservedMatch.rules.mode == DuelMode.KIT,
                            )
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
                            runCatching { prepareNewSession(reservedMatch, requireNotNull(stored), policy = policy) }
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

    fun expectNetworkMatch(challenge: DuelChallenge) {
        expectNetworkPlayers(listOf(challenge.challenger, challenge.target))
    }

    fun stopExpectingNetworkMatch(challenge: DuelChallenge) {
        stopExpectingNetworkPlayers(listOf(challenge.challenger, challenge.target))
    }

    internal fun expectNetworkPlayers(players: Collection<PlayerId>) {
        expectedNetworkPlayers.addAll(players.map(PlayerId::value))
    }

    internal fun stopExpectingNetworkPlayers(players: Collection<PlayerId>) {
        expectedNetworkPlayers.removeAll(players.map(PlayerId::value).toSet())
    }

    internal fun storeOriginSnapshot(
        challenge: DuelChallenge,
        player: Player,
    ): CompletableFuture<StoredPlayerSnapshot> =
        storeOriginSnapshot(MatchId(challenge.id.value), player, challenge.rules.mode == DuelMode.KIT)

    internal fun storeOriginSnapshot(
        matchId: MatchId,
        player: Player,
        inventoryReplaced: Boolean,
    ): CompletableFuture<StoredPlayerSnapshot> {
        check(plugin.server.isPrimaryThread) { "Origin snapshots must be captured on the Paper primary thread" }
        preparingPlayers += player.uniqueId
        DuelLog.debug(
            "origin-snapshot-freeze",
            matchId,
            player,
            "player={} locked_before_capture=true",
            player.name,
        )
        val future =
            runCatching {
                playerStates.store(
                    matchId,
                    player,
                    inventoryReplaced = inventoryReplaced,
                )
            }.getOrElse { failure ->
                preparingPlayers -= player.uniqueId
                throw failure
            }
        future.whenComplete { _, _ -> runSync { preparingPlayers -= player.uniqueId } }
        return future
    }

    fun hasOriginSnapshot(
        player: Player,
        challenge: DuelChallenge,
    ): Boolean = hasOriginSnapshot(player, MatchId(challenge.id.value))

    internal fun hasOriginSnapshot(player: Player, matchId: MatchId): Boolean =
        playerStates.pending(player.uniqueId)?.matchId == matchId

    fun startNetwork(
        challenge: DuelChallenge,
        origins: Map<PlayerId, ServerId>,
        recoveryMatchId: MatchId? = null,
    ): CompletableFuture<DuelMatch> {
        check(challenge.status == ChallengeStatus.ACCEPTED) { "Only an accepted challenge can start" }
        val policy = sessionPolicy()
        val first = plugin.server.getPlayer(challenge.challenger.value)
            ?: return CompletableFuture.failedFuture(IllegalStateException("The first player left the arena server"))
        val second = plugin.server.getPlayer(challenge.target.value)
            ?: return CompletableFuture.failedFuture(IllegalStateException("The second player left the arena server"))
        val matchId = MatchId(challenge.id.value)
        DuelLog.info(
            "session-reserve",
            matchId,
            "network=true first={} second={} origins={}",
            first.name,
            second.name,
            origins.entries.joinToString(",") { "${it.key.value}:${it.value.value}" },
        )
        val result = CompletableFuture<DuelMatch>()
        val snapshotsFuture = playerStates.findMatchSnapshots(recoveryMatchId ?: matchId, origins)
        val reservation =
            runCatching {
                coordinator.reserve(
                    PlayerId(first.uniqueId),
                    PlayerId(second.uniqueId),
                    challenge.rules,
                    matchId,
                    challenge.arenaSelection?.arenaId,
                )
            }.getOrElse { failure ->
                result.completeExceptionally(failure)
                return result
            }
        pendingStarts[first.uniqueId] = result
        pendingStarts[second.uniqueId] = result
        reservation.thenCombine(snapshotsFuture) { match, escrows -> match to escrows }
            .whenComplete { prepared, failure ->
                runSync {
                    if (failure != null) {
                        cancelOrReleaseReservation(reservation) { match ->
                            runCatching { coordinator.cancel(match.id, MatchEndReason.ADMIN_CANCEL) }
                        }
                        stopExpectingNetworkMatch(challenge)
                        result.completeExceptionally(unwrap(failure))
                        return@runSync
                    }
                    val (match, escrows) = requireNotNull(prepared)
                    if (result.isCancelled || !first.isOnline || !second.isOnline) {
                        runCatching { coordinator.cancel(match.id, MatchEndReason.ADMIN_CANCEL) }
                        stopExpectingNetworkMatch(challenge)
                        result.completeExceptionally(IllegalStateException("A player left before the network match started"))
                        return@runSync
                    }
                    val stored =
                        mapOf(
                            first.uniqueId to playerStates.decodeForArena(escrows.getValue(PlayerId(first.uniqueId)), first),
                            second.uniqueId to playerStates.decodeForArena(escrows.getValue(PlayerId(second.uniqueId)), second),
                        )
                    val arenaBaselines =
                        mapOf(
                            first.uniqueId to PlayerSnapshot.capture(first),
                            second.uniqueId to PlayerSnapshot.capture(second),
                        )
                    runCatching {
                        prepareNewSession(
                            match,
                            stored,
                            recoveryOwner = RecoveryOwner.ORIGIN_SERVERS,
                            arenaBaselines = arenaBaselines,
                            policy = policy,
                        )
                    }.onSuccess {
                        stopExpectingNetworkMatch(challenge)
                        result.complete(it)
                    }.onFailure { setupFailure ->
                        runCatching { coordinator.cancel(match.id, MatchEndReason.ADMIN_CANCEL) }
                        stopExpectingNetworkMatch(challenge)
                        result.completeExceptionally(setupFailure)
                    }
                }
            }
        result.whenComplete { _, _ ->
            pendingStarts.remove(first.uniqueId, result)
            pendingStarts.remove(second.uniqueId, result)
            if (result.isCancelled) {
                reservation.cancel(false)
                snapshotsFuture.cancel(false)
                stopExpectingNetworkMatch(challenge)
            }
        }
        return result
    }

    fun matchFor(player: Player): DuelMatch? =
        coordinator.findByPlayer(PlayerId(player.uniqueId))
            ?: sessionByPlayer[player.uniqueId]?.let(coordinator::find)

    fun isEngaged(player: Player): Boolean =
        isExternallyEngaged(player) ||
            coordinator.isQueuedOrMatched(PlayerId(player.uniqueId)) ||
            pendingStarts.containsKey(player.uniqueId)

    fun isStateLocked(player: Player): Boolean =
        !isExternallyEngaged(player) &&
            (preparingPlayers.contains(player.uniqueId) ||
                (playerStates.isPending(player.uniqueId) && !networkLobbyPlayers.containsKey(player.uniqueId)) ||
                matchFor(player) != null)

    /**
     * Keeps externally owned combat sessions unavailable to 1v1 matchmaking
     * without treating their shared durable escrow as an orphaned 1v1 lock.
     */
    internal fun attachExternalEngagement(probe: (Player) -> Boolean) {
        check(externalEngagement == null) { "An external duel owner is already attached" }
        externalEngagement = probe
    }

    private fun isExternallyEngaged(player: Player): Boolean = externalEngagement?.invoke(player) == true

    fun isPreparing(player: Player): Boolean = preparingPlayers.contains(player.uniqueId)

    fun countdownAnchor(player: Player): org.bukkit.Location? {
        val match = matchFor(player) ?: return null
        if (match.state != MatchState.COUNTDOWN) return null
        return sessions[match.id]?.arenaAnchors?.get(player.uniqueId)?.clone()
    }

    fun isPostMatchWaiting(player: Player): Boolean = networkLobbyPlayers.containsKey(player.uniqueId)

    fun clearPostMatchCombatTag(
        player: Player,
        reason: String,
    ) {
        networkLobbyPlayers[player.uniqueId]?.let { matchId -> externalCombatTagClear(player, matchId, reason) }
    }

    fun queueSize(): Int = arenas.queueSize()

    fun activeArenaCount(): Int = arenas.reservedCount()

    fun pendingRecoveryCount(): Int = playerStates.pendingCount()

    fun modifiedBlockCount(player: Player): Int =
        matchFor(player)?.let { match -> sessions[match.id]?.modifiedBlocks?.size } ?: 0

    fun isKitHealthCapApplied(player: Player): Boolean = kitHealthIsolation.isApplied(player)

    fun hasPendingRecovery(player: Player): Boolean =
        matchFor(player) == null && !pendingStarts.containsKey(player.uniqueId) && playerStates.isPending(player.uniqueId)

    fun handleJoin(player: Player) {
        if (player.uniqueId in expectedNetworkPlayers) return
        DuelLog.debug("player-join-recovery-check", player, "player={}", player.name)
        networkLobbyPlayers -= player.uniqueId
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
                    DuelLog.warn(
                        "recovery-discovery-failed",
                        player,
                        "error_type={} error={}",
                        unwrap(lookupFailure).javaClass.simpleName,
                        unwrap(lookupFailure).message,
                    )
                    plugin.logger.warning("Could not check pending duel state for ${player.uniqueId}: ${unwrap(lookupFailure).message}")
                    if (player.isOnline) {
                        if (notifyFailure) player.sendMessage(locales.notice(player, "session.recovery-check-retry"))
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
                    DuelLog.debug("recovery-none", player, "player={}", player.name)
                    preparingPlayers -= player.uniqueId
                    return@runSync
                }
                if (!player.isOnline) return@runSync
                if (!playerStates.isLocal(escrow)) {
                    DuelLog.info(
                        "recovery-route-remote",
                        escrow.matchId,
                        player,
                        "snapshot_server={} current_server={}",
                        escrow.serverId.value,
                        plugin.server.name,
                    )
                    routeRemoteRecovery(player, escrow)
                    return@runSync
                }
                schedulePendingRecovery(player, escrow)
            }
        }
    }

    fun requestRecovery(player: Player): Boolean {
        if (matchFor(player) != null || pendingStarts.containsKey(player.uniqueId)) return false
        networkLobbyPlayers -= player.uniqueId
        val escrow = playerStates.pending(player.uniqueId) ?: return false
        preparingPlayers += player.uniqueId
        if (!playerStates.isLocal(escrow)) {
            routeRemoteRecovery(player, escrow)
        } else {
            schedulePendingRecovery(player, escrow)
        }
        return true
    }

    internal fun adminRecover(player: Player): CompletableFuture<AdminRecoveryResult> {
        if (matchFor(player) != null || pendingStarts.containsKey(player.uniqueId)) {
            return CompletableFuture.completedFuture(AdminRecoveryResult(AdminRecoveryStatus.BUSY))
        }
        val pending = playerStates.pending(player.uniqueId)
        if (pending != null) {
            requestRecovery(player)
            return CompletableFuture.completedFuture(
                AdminRecoveryResult(
                    when {
                        playerStates.isLocal(pending) -> AdminRecoveryStatus.STARTED
                        remoteRecoveryTransfer != null -> AdminRecoveryStatus.TRANSFERRED
                        else -> AdminRecoveryStatus.WRONG_SERVER
                    },
                    pending.serverId,
                ),
            )
        }
        val result = CompletableFuture<AdminRecoveryResult>()
        playerStates.latestRetained(player.uniqueId).whenComplete { retained, failure ->
            runSync {
                if (failure != null) {
                    result.completeExceptionally(unwrap(failure))
                    return@runSync
                }
                if (retained == null) {
                    result.complete(AdminRecoveryResult(AdminRecoveryStatus.NO_SNAPSHOT))
                    return@runSync
                }
                if (!playerStates.isLocal(retained)) {
                    result.complete(AdminRecoveryResult(AdminRecoveryStatus.WRONG_SERVER, retained.serverId))
                    return@runSync
                }
                preparingPlayers += player.uniqueId
                if (!scheduleRecoveryWindow(player) { replayRetained(player, retained, result) }) {
                    result.complete(AdminRecoveryResult(AdminRecoveryStatus.BUSY))
                }
            }
        }
        return result
    }

    private fun schedulePendingRecovery(
        player: Player,
        escrow: PlayerStateEscrow,
    ) {
        scheduleRecoveryWindow(player) {
            if (playerStates.pending(player.uniqueId) != escrow) {
                preparingPlayers -= player.uniqueId
                return@scheduleRecoveryWindow
            }
            runCatching { playerStates.decode(escrow) }
                .onSuccess { stored ->
                    if (escrow.inventoryReplaced && !stored.state.inventoryMatches(player)) {
                        player.sendMessage(locales.notice(player, "session.recovering"))
                    }
                    if (restoreAndRetain(player, stored, skipApplyWhenInventoryMatches = true)) {
                        markRestored(escrow.matchId, player.uniqueId)
                    } else {
                        retryPendingRecovery(player, escrow)
                    }
                }
                .onFailure { failure ->
                    plugin.logger.severe("Could not decode pending duel state for ${player.uniqueId}: ${failure.message}")
                    if (escrow.inventoryReplaced) player.sendMessage(locales.notice(player, "session.recovery-failed"))
                    retryPendingRecovery(player, escrow)
                }
        }
    }

    private fun scheduleRecoveryWindow(
        player: Player,
        action: () -> Unit,
    ): Boolean {
        val token = UUID.randomUUID()
        if (recoveryTokens.putIfAbsent(player.uniqueId, token) != null) return false
        awaitPlayerData(player, token, action)
        return true
    }

    private fun awaitPlayerData(
        player: Player,
        token: UUID,
        action: () -> Unit,
    ) {
        if (!player.isOnline || recoveryTokens[player.uniqueId] != token) {
            recoveryTokens.remove(player.uniqueId, token)
            return
        }
        if (!playerDataReady(player)) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable { awaitPlayerData(player, token, action) }, RECOVERY_READY_POLL_TICKS)
            return
        }
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (!player.isOnline || !recoveryTokens.remove(player.uniqueId, token)) return@Runnable
                action()
            },
            runtimeSettings()?.playerDataSettleDelayTicks ?: recoveryApplyDelayTicks,
        )
    }

    private fun retryPendingRecovery(
        player: Player,
        escrow: PlayerStateEscrow,
    ) {
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (player.isOnline && playerStates.pending(player.uniqueId) == escrow) {
                    schedulePendingRecovery(player, escrow)
                }
            },
            RECOVERY_RETRY_TICKS,
        )
    }

    private fun replayRetained(
        player: Player,
        escrow: PlayerStateEscrow,
        result: CompletableFuture<AdminRecoveryResult>,
    ) {
        val replayed =
            runCatching {
                val stored = playerStates.decode(escrow)
                stored.state.restore(player, ::teleportInternally)
                playerDataSaver(player)
            }
        preparingPlayers -= player.uniqueId
        replayed.onSuccess {
            if (escrow.inventoryReplaced) player.sendMessage(locales.notice(player, "session.admin-restored"))
            result.complete(AdminRecoveryResult(AdminRecoveryStatus.REPLAYED, escrow.serverId))
        }.onFailure { failure ->
            plugin.logger.severe("Administrator replay failed for ${player.uniqueId}: ${failure.message}")
            result.completeExceptionally(failure)
        }
    }

    private fun routeRemoteRecovery(
        player: Player,
        escrow: PlayerStateEscrow,
    ): Boolean {
        val transfer = remoteRecoveryTransfer
        if (transfer == null) {
            player.sendMessage(
                locales.notice(
                    player,
                    "session.remote-recovery-unavailable",
                    LocaleService.component("server", serverNames.display(escrow.serverId)),
                ),
            )
            return false
        }
        val token = UUID.randomUUID()
        if (remoteRecoveryTokens.putIfAbsent(player.uniqueId, token) != null) return true
        player.sendMessage(
            locales.notice(
                player,
                "session.remote-recovery",
                LocaleService.component("server", serverNames.display(escrow.serverId)),
            ),
        )
        requestRemoteRecoveryTransfer(player, escrow, token, transfer)
        return true
    }

    private fun requestRemoteRecoveryTransfer(
        player: Player,
        escrow: PlayerStateEscrow,
        token: UUID,
        transfer: (Player, ServerId) -> Unit,
    ) {
        if (!player.isOnline || remoteRecoveryTokens[player.uniqueId] != token) return
        runCatching { transfer(player, escrow.serverId) }
            .onFailure { failure ->
                plugin.logger.warning(
                    "Could not transfer duel recovery player ${player.uniqueId} to ${escrow.serverId}: ${unwrap(failure).message}",
                )
            }
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable { retryRemoteRecoveryTransfer(player, escrow, token, transfer) },
            REMOTE_RECOVERY_RETRY_TICKS,
        )
    }

    private fun retryRemoteRecoveryTransfer(
        player: Player,
        escrow: PlayerStateEscrow,
        token: UUID,
        transfer: (Player, ServerId) -> Unit,
    ) {
        if (!player.isOnline || remoteRecoveryTokens[player.uniqueId] != token) return
        playerStates.discover(player.uniqueId).whenComplete { current, failure ->
            runSync {
                if (!player.isOnline || remoteRecoveryTokens[player.uniqueId] != token) return@runSync
                if (failure != null) {
                    plugin.logger.warning(
                        "Could not verify remote duel recovery for ${player.uniqueId}: ${unwrap(failure).message}",
                    )
                    requestRemoteRecoveryTransfer(player, escrow, token, transfer)
                    return@runSync
                }
                if (current == escrow) {
                    requestRemoteRecoveryTransfer(player, escrow, token, transfer)
                    return@runSync
                }

                remoteRecoveryTokens.remove(player.uniqueId, token)
                when {
                    current == null -> preparingPlayers -= player.uniqueId
                    playerStates.isLocal(current) -> schedulePendingRecovery(player, current)
                    else -> routeRemoteRecovery(player, current)
                }
            }
        }
    }

    fun handleElimination(loser: Player) {
        val match = matchFor(loser) ?: return
        if (match.state != MatchState.ACTIVE) return
        objectiveTasks.remove(match.id)?.cancel()
        val winner = match.opponentOf(PlayerId(loser.uniqueId))
        DuelLog.info(
            "round-elimination",
            match.id,
            loser,
            "loser={} winner={} score={}:{} objective={}",
            loser.name,
            currentName(winner),
            match.score.first,
            match.score.second,
            match.rules.objective,
        )
        coordinator.recordRoundWinner(match.id, winner).whenComplete { updated, failure ->
            runSync { handleRoundResult(match.id, winner, updated, failure) }
        }
    }

    fun allowsProjectiles(player: Player): Boolean = matchFor(player)?.rules?.modifiers?.projectiles ?: true

    fun allowsConsumables(player: Player): Boolean = matchFor(player)?.rules?.modifiers?.consumables ?: true

    fun allowsEnderPearls(player: Player): Boolean = matchFor(player)?.rules?.modifiers?.enderPearls ?: true

    fun allowsNaturalRegeneration(player: Player): Boolean = matchFor(player)?.rules?.modifiers?.naturalRegeneration ?: true

    fun allowsFluidPlacement(
        player: Player,
        block: Block,
        bucket: Material,
    ): Boolean {
        if (bucket !in DUEL_FLUID_BUCKETS) return false
        val match = matchFor(player) ?: return false
        if (match.state != MatchState.ACTIVE || !match.rules.modifiers.consumables) return false
        return arenas.get(match.arenaId).bounds.contains(block.location.add(0.5, 0.5, 0.5))
    }

    fun allowsFluidPickup(
        player: Player,
        block: Block,
    ): Boolean {
        val match = matchFor(player) ?: return false
        val session = sessions[match.id] ?: return false
        return match.state == MatchState.ACTIVE &&
            match.rules.modifiers.consumables &&
            session.modifiedBlocks.containsKey(BlockKey.of(block))
    }

    fun trackFluidPlacement(
        player: Player,
        block: Block,
    ): Boolean {
        val match = matchFor(player) ?: return false
        return sessions[match.id]?.rememberOriginal(block) == true
    }

    fun trackFluidFlow(
        from: Block,
        to: Block,
    ): Boolean? {
        val sourceKey = BlockKey.of(from)
        val session = sessions.values.firstOrNull { sourceKey in it.modifiedBlocks } ?: return null
        val match = coordinator.find(session.matchId) ?: return false
        if (match.state !in setOf(MatchState.COUNTDOWN, MatchState.ACTIVE)) return false
        if (!arenas.get(match.arenaId).bounds.contains(to.location.add(0.5, 0.5, 0.5))) return false
        return session.rememberOriginal(to)
    }

    /** Tracks lava fire, burning, and water/lava block reactions for exact arena rollback. */
    fun trackFluidSideEffect(
        source: Block?,
        affected: Block,
    ): Boolean? {
        val affectedKey = BlockKey.of(affected)
        val sourceKey = source?.let(BlockKey::of)
        val neighboringKeys = FLUID_REACTION_FACES.map { face -> BlockKey.of(affected.getRelative(face)) }
        val session =
            sessions.values.firstOrNull { affectedKey in it.modifiedBlocks }
                ?: sourceKey?.let { key -> sessions.values.firstOrNull { key in it.modifiedBlocks } }
                ?: neighboringKeys.firstNotNullOfOrNull { key -> sessions.values.firstOrNull { key in it.modifiedBlocks } }
                ?: return null
        val match = coordinator.find(session.matchId) ?: return false
        if (match.state !in setOf(MatchState.COUNTDOWN, MatchState.ACTIVE) || !match.rules.modifiers.consumables) return false
        if (!arenas.get(match.arenaId).bounds.contains(affected.location.add(0.5, 0.5, 0.5))) return false
        return session.rememberOriginal(affected)
    }

    fun isSumo(player: Player): Boolean = matchFor(player)?.rules?.objective == DuelObjectiveType.SUMO

    fun isHitRace(player: Player): Boolean = matchFor(player)?.rules?.objective?.isHitRace == true

    fun recordMeleeHit(
        attacker: Player,
        victim: Player,
    ) {
        val match = matchFor(attacker) ?: return
        if (match.state != MatchState.ACTIVE || !match.rules.objective.isHitRace) return
        if (matchFor(victim)?.id != match.id || match.opponentOf(PlayerId(attacker.uniqueId)).value != victim.uniqueId) return
        val session = sessions[match.id] ?: return
        val progress = session.hitRace.record(match.rules.objective, PlayerId(attacker.uniqueId), PlayerId(victim.uniqueId))
        DuelLog.debug(
            "objective-hit",
            match.id,
            attacker,
            "attacker={} victim={} score={}",
            attacker.name,
            victim.name,
            progress.scores,
        )
        showHitRaceProgress(match, progress.scores)
        val target =
            when (match.rules.objective) {
                DuelObjectiveType.BOXING -> match.rules.modifiers.boxingHitsToWin
                DuelObjectiveType.COMBO -> match.rules.modifiers.comboHitsToWin
                else -> return
            }
        coordinator.evaluateObjective(
            match.id,
            ScoreRaceObjective(match.rules.objective.name.lowercase(), target),
            ObjectiveFrame(session.roundElapsedTicks, emptySet(), progress.scores),
        ).whenComplete { updated, failure ->
            if (failure != null) {
                runSync { handleRoundResult(match.id, progress.scorer, null, failure) }
            } else if (updated.state != MatchState.ACTIVE) {
                runSync { handleRoundResult(match.id, progress.scorer, updated, null) }
            }
        }
    }

    fun handleQuit(player: Player) {
        DuelLog.info("player-quit", matchFor(player)?.id, player, "player={} state_locked={}", player.name, isStateLocked(player))
        recoveryTokens.remove(player.uniqueId)
        remoteRecoveryTokens.remove(player.uniqueId)
        networkLobbyPlayers -= player.uniqueId
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
        sessions[match.id]?.takeIf { it.recoveryOwner == RecoveryOwner.ARENA_SERVER }
            ?.snapshots?.get(player.uniqueId)?.let { stored ->
                if (restoreAndRetain(player, stored)) markRestored(match.id, player.uniqueId)
            }
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
        DuelLog.info("match-forfeit", match.id, player, "player={} state={}", player.name, match.state)
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

    fun boundaryDistance(
        player: Player,
        location: org.bukkit.Location,
    ): Double? {
        val match = matchFor(player) ?: return null
        return arenas.get(match.arenaId).bounds.distanceToEdge(location)
    }

    fun boundaryWarningPoints(
        player: Player,
        location: org.bukkit.Location,
    ): List<org.bukkit.Location> {
        val match = matchFor(player) ?: return emptyList()
        return arenas.get(match.arenaId).bounds.horizontalBoundaryPoints(location)
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

    internal fun clearExternalCombatTags(
        match: DuelMatch,
        reason: String,
    ) {
        participants(match).forEach { player -> externalCombatTagClear(player, match.id, reason) }
    }

    fun retryCompletion(matchId: MatchId) {
        coordinator.retryCompletion(matchId).whenComplete { completed, failure ->
            runSync {
                if (failure != null) announcePersistenceFailure(matchId, unwrap(failure)) else finish(completed)
            }
        }
    }

    internal fun shutdown(): DuelShutdownReport {
        DuelLog.info(
            "sessions-shutdown",
            "sessions={} pending_starts={} preparing={} recoveries={}",
            sessions.size,
            pendingStarts.size,
            preparingPlayers.size,
            restoringPlayers.size,
        )
        countdownTasks.values.forEach(BukkitTask::cancel)
        countdownTasks.clear()
        teleportStabilizationTasks.values.forEach(BukkitTask::cancel)
        teleportStabilizationTasks.clear()
        healthIsolationTasks.values.forEach(BukkitTask::cancel)
        healthIsolationTasks.clear()
        objectiveTasks.values.forEach(BukkitTask::cancel)
        objectiveTasks.clear()
        matchDisplayTasks.values.forEach(BukkitTask::cancel)
        matchDisplayTasks.clear()
        finaleTasks.values.forEach(BukkitTask::cancel)
        finaleTasks.clear()
        pendingStarts.values.toSet().forEach { it.cancel(false) }
        pendingStarts.clear()
        coordinator.activeMatches()
            .filter { !sessions.containsKey(it.id) }
            .forEach { match -> runCatching { coordinator.cancel(match.id, MatchEndReason.SERVER_SHUTDOWN) } }
        var localSnapshotsApplied = 0
        var networkPlayersNormalized = 0
        for ((matchId, session) in sessions) {
            restoreArenaBlocks(session)
            hideMatchDisplay(session)
            val match = coordinator.find(matchId)
            match?.let {
                if (match.state !in setOf(MatchState.COMPLETING, MatchState.COMPLETED, MatchState.CANCELLED)) {
                    runCatching { coordinator.cancel(matchId, MatchEndReason.SERVER_SHUTDOWN) }
                }
            }
            when (session.recoveryOwner) {
                RecoveryOwner.ARENA_SERVER -> {
                    restore(session)
                    localSnapshotsApplied += session.restoredPlayers.size
                }
                RecoveryOwner.ORIGIN_SERVERS -> {
                    if (match != null) {
                        moveNetworkPlayersToLobby(match, session)
                        networkPlayersNormalized += session.postMatchMovedPlayers.size
                    }
                }
            }
            coordinator.find(matchId)?.takeIf { it.state == MatchState.COMPLETED }?.let {
                runCatching { coordinator.releaseCompleted(matchId) }
            }
        }
        val shutdownTimeout = runtimeSettings()?.shutdownRecoveryTimeout ?: Duration.ofMillis(shutdownRecoveryTimeoutMillis)
        val retention = playerStates.awaitRetentions(shutdownTimeout)
        DuelLog.info(
            "shutdown-recovery-drain",
            "local_snapshots_applied={} network_players_normalized={} retentions_observed={} acknowledged={} failed={} timed_out={}",
            localSnapshotsApplied,
            networkPlayersNormalized,
            retention.observed,
            retention.acknowledged,
            retention.failed,
            retention.timedOut,
        )
        if (retention.failed > 0 || retention.timedOut > 0) {
            plugin.logger.warning(
                "ArcDuels shutdown left ${retention.failed + retention.timedOut} recovery acknowledgement(s) retryable; " +
                    "the durable active snapshots were not discarded",
            )
        }
        sessions.clear()
        sessionByPlayer.clear()
        recoveryTokens.clear()
        remoteRecoveryTokens.clear()
        restoringPlayers.clear()
        expectedNetworkPlayers.clear()
        networkLobbyPlayers.clear()
        preparingPlayers.clear()
        return DuelShutdownReport(localSnapshotsApplied, networkPlayersNormalized, retention)
    }

    private fun prepareNewSession(
        match: DuelMatch,
        snapshots: Map<UUID, StoredPlayerSnapshot>,
        recoveryOwner: RecoveryOwner = RecoveryOwner.ARENA_SERVER,
        arenaBaselines: Map<UUID, PlayerSnapshot> = emptyMap(),
        policy: SessionRuntimePolicy,
    ): DuelMatch {
        check(plugin.server.isPrimaryThread) { "Paper duel setup must run on the main thread" }
        DuelLog.info(
            "session-prepare",
            match.id,
            "arena={} mode={} objective={} recovery_owner={} snapshots={}",
            match.arenaId.value,
            match.rules.mode,
            match.rules.objective,
            recoveryOwner,
            snapshots.size,
        )
        val session =
            PaperSession(
                match.id,
                snapshots = snapshots,
                recoveryOwner = recoveryOwner,
                arenaBaselines = arenaBaselines,
                policy = policy,
            )
        sessions[match.id] = session
        session.snapshots.keys.forEach { sessionByPlayer[it] = match.id }
        try {
            prepareRound(match)
            session.snapshots.keys.forEach { networkLobbyPlayers -= it }
            coordinator.beginCountdown(match.id)
            scheduleCountdown(match.id)
            return coordinator.find(match.id) ?: error("Match disappeared during Paper setup")
        } catch (failure: Throwable) {
            DuelLog.warn(
                "session-prepare-failed",
                match.id,
                "error_type={} error={}",
                failure.javaClass.simpleName,
                failure.message,
            )
            restoreArenaBlocks(session)
            hideMatchDisplay(session)
            teleportStabilizationTasks.remove(match.id)?.cancel()
            healthIsolationTasks.remove(match.id)?.cancel()
            session.snapshots.keys.forEach { playerId ->
                plugin.server.getPlayer(playerId)?.let(kitHealthIsolation::clear)
            }
            sessions.remove(match.id)
            session.snapshots.keys.forEach { sessionByPlayer.remove(it, match.id) }
            if (session.recoveryOwner == RecoveryOwner.ARENA_SERVER) restore(session)
            throw failure
        }
    }

    private fun prepareRound(match: DuelMatch) {
        val session = sessions[match.id] ?: error("Missing Paper session for match ${match.id}")
        objectiveTasks.remove(match.id)?.cancel()
        session.hillCapture.reset()
        session.hitRace.reset()
        session.roundElapsedTicks = 0L
        session.suddenDeathStarted = false
        val arena = arenas.get(match.arenaId)
        val first = requireOnline(match.firstPlayer)
        val second = requireOnline(match.secondPlayer)
        DuelLog.info(
            "round-prepare",
            match.id,
            "round={} arena={} first={} second={} mode={}",
            session.roundsPrepared + 1,
            arena.id.value,
            first.name,
            second.name,
            match.rules.mode,
        )
        if (match.rules.mode == DuelMode.OWN_INVENTORY && session.roundsPrepared == 0) {
            val firstMismatches = session.snapshots.getValue(first.uniqueId).state.inventoryMismatches(first)
            val secondMismatches = session.snapshots.getValue(second.uniqueId).state.inventoryMismatches(second)
            if (firstMismatches.isNotEmpty()) {
                DuelLog.warn(
                    "snapshot-inventory-mismatch",
                    match.id,
                    first,
                    "player={} role=first fields={}",
                    first.name,
                    firstMismatches.joinToString(","),
                )
            }
            if (secondMismatches.isNotEmpty()) {
                DuelLog.warn(
                    "snapshot-inventory-mismatch",
                    match.id,
                    second,
                    "player={} role=second fields={}",
                    second.name,
                    secondMismatches.joinToString(","),
                )
            }
            check(firstMismatches.isEmpty()) {
                "The first player's synchronized inventory no longer matches its protected snapshot"
            }
            check(secondMismatches.isEmpty()) {
                "The second player's synchronized inventory no longer matches its protected snapshot"
            }
        }
        resetPlayer(first, match.rules.mode, match.rules.kitId)
        resetPlayer(second, match.rules.mode, match.rules.kitId)
        scheduleKitHealthIsolation(match)
        session.arenaAnchors[first.uniqueId] = arena.firstSpawn.clone()
        session.arenaAnchors[second.uniqueId] = arena.secondSpawn.clone()
        check(teleportInternally(first, arena.firstSpawn)) { "Could not teleport the first player to the arena" }
        check(teleportInternally(second, arena.secondSpawn)) { "Could not teleport the second player to the arena" }
        DuelLog.info(
            "arena-teleport-complete",
            match.id,
            "arena={} first_world={} second_world={}",
            arena.id.value,
            first.world.name,
            second.world.name,
        )
        clearExternalCombatTags(match, "round-prepared")
        scheduleTeleportStabilization(match, session)
        first.sendActionBar(scoreLine(match, first))
        second.sendActionBar(scoreLine(match, second))
        showCountdownDisplay(match, session.policy.countdownSeconds)
        session.roundsPrepared++
    }

    private fun resetPlayer(
        player: Player,
        mode: DuelMode,
        kitId: ru.ruscrafting.duels.domain.KitId?,
    ) {
        player.gameMode = GameMode.SURVIVAL
        player.allowFlight = false
        player.isFlying = false
        player.fireTicks = 0
        player.fallDistance = 0f
        player.noDamageTicks = 0
        player.absorptionAmount = 0.0
        player.velocity = Vector()
        player.foodLevel = 20
        player.saturation = 5f
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        if (mode == DuelMode.KIT) {
            val kit = kits.get(requireNotNull(kitId))
            player.setItemOnCursor(ItemStack.empty())
            player.inventory.clear()
            player.inventory.armorContents = arrayOfNulls<ItemStack>(4)
            player.inventory.setItemInOffHand(kit.offhand?.clone())
            for ((slot, item) in kit.items) player.inventory.setItem(slot, item.clone())
            player.inventory.helmet = kit.helmet?.clone()
            player.inventory.chestplate = kit.chestplate?.clone()
            player.inventory.leggings = kit.leggings?.clone()
            player.inventory.boots = kit.boots?.clone()
            check(kitHealthIsolation.enforce(player)) { "Could not isolate kit health for ${player.uniqueId}" }
            player.health = minOf(KitHealthIsolation.VANILLA_MAX_HEALTH, player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0)
        } else {
            kitHealthIsolation.clear(player)
            player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        }
        player.updateInventory()
    }

    private fun scheduleCountdown(matchId: MatchId) {
        countdownTasks.remove(matchId)?.cancel()
        val task =
            object : BukkitRunnable() {
                var seconds = sessions[matchId]?.policy?.countdownSeconds ?: countdownSeconds

                override fun run() {
                    val match = coordinator.find(matchId)
                    if (match == null || match.state != MatchState.COUNTDOWN) {
                        countdownTasks.remove(matchId)
                        cancel()
                        return
                    }
                    val players = participants(match)
                    if (seconds > 0) {
                        showCountdownDisplay(match, seconds)
                        players.forEach { player ->
                            val opponent = requireOnline(match.opponentOf(PlayerId(player.uniqueId)))
                            player.showTitle(
                                Title.title(
                                    locales.component(player, "session.countdown-title", LocaleService.text("seconds", seconds)),
                                    locales.component(player, "session.countdown-subtitle", LocaleService.text("opponent", opponent.name)),
                                    Title.Times.times(Duration.ZERO, Duration.ofMillis(850), Duration.ZERO),
                                ),
                            )
                            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.4f)
                        }
                        seconds--
                        return
                    }
                    val active = coordinator.activate(matchId)
                    DuelLog.info(
                        "round-active",
                        active.id,
                        "arena={} objective={} score={}:{}",
                        active.arenaId.value,
                        active.rules.objective,
                        active.score.first,
                        active.score.second,
                    )
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
                    scheduleMatchDisplay(active)
                    countdownTasks.remove(matchId)
                    cancel()
                }
            }.runTaskTimer(plugin, sessions[matchId]?.policy?.teleportStabilizationTicks ?: teleportStabilizationTicks, 20L)
        countdownTasks[matchId] = task
    }

    private fun scheduleKitHealthIsolation(match: DuelMatch) {
        healthIsolationTasks.remove(match.id)?.cancel()
        if (match.rules.mode != DuelMode.KIT) return
        healthIsolationTasks[match.id] =
            plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable {
                    val current = coordinator.find(match.id)
                    if (current == null || current.state !in setOf(MatchState.COUNTDOWN, MatchState.ACTIVE)) {
                        healthIsolationTasks.remove(match.id)?.cancel()
                        return@Runnable
                    }
                    participants(current).forEach { player ->
                        if (!kitHealthIsolation.enforce(player)) {
                            plugin.logger.severe("Could not maintain the 20 HP kit cap for ${player.uniqueId} in ${current.id}")
                        }
                    }
                },
                1L,
                1L,
            )
    }

    private fun scheduleTeleportStabilization(
        match: DuelMatch,
        session: PaperSession,
    ) {
        teleportStabilizationTasks.remove(match.id)?.cancel()
        val stabilizationTicks = session.policy.teleportStabilizationTicks
        if (stabilizationTicks == 0L) return
        val task =
            object : BukkitRunnable() {
                var ticksRemaining = stabilizationTicks

                override fun run() {
                    val current = coordinator.find(match.id)
                    if (current == null || current.state != MatchState.COUNTDOWN || ticksRemaining-- <= 0L) {
                        teleportStabilizationTasks.remove(match.id)
                        cancel()
                        return
                    }
                    session.arenaAnchors.forEach { (playerId, anchor) ->
                        val player = plugin.server.getPlayer(playerId) ?: return@forEach
                        player.velocity = Vector()
                        player.fallDistance = 0f
                        val currentLocation = player.location
                        val displaced = currentLocation.world?.uid != anchor.world?.uid || currentLocation.distanceSquared(anchor) > 0.25
                        if (displaced) {
                            DuelLog.warn(
                                "arena-teleport-reanchor",
                                match.id,
                                player,
                                "player={} stabilization_tick={} from_world={} to_world={}",
                                player.name,
                                stabilizationTicks - ticksRemaining,
                                currentLocation.world?.name,
                                anchor.world?.name,
                            )
                            if (!teleportInternally(player, anchor)) {
                                DuelLog.warn(
                                    "arena-teleport-reanchor-failed",
                                    match.id,
                                    player,
                                    "player={}",
                                    player.name,
                                )
                            }
                        }
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L)
        teleportStabilizationTasks[match.id] = task
    }

    private fun scheduleObjective(match: DuelMatch) {
        objectiveTasks.remove(match.id)?.cancel()
        val session = sessions[match.id] ?: return
        if (match.rules.objective.isHitRace) return
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

    private fun scheduleMatchDisplay(match: DuelMatch) {
        matchDisplayTasks.remove(match.id)?.cancel()
        updateMatchDisplay(match)
        val task =
            plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable {
                    val current = coordinator.find(match.id)
                    if (current == null || current.state != MatchState.ACTIVE) {
                        matchDisplayTasks.remove(match.id)?.cancel()
                        return@Runnable
                    }
                    updateMatchDisplay(current)
                },
                20L,
                20L,
            )
        matchDisplayTasks[match.id] = task
    }

    private fun showCountdownDisplay(match: DuelMatch, seconds: Int) {
        val session = sessions[match.id] ?: return
        participants(match).forEach { player ->
            val opponent = requireOnline(match.opponentOf(PlayerId(player.uniqueId)))
            val bar = session.bossBars.computeIfAbsent(player.uniqueId) {
                BossBar.bossBar(Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS).also(player::showBossBar)
            }
            bar.name(
                locales.component(
                    player,
                    "session.bossbar-countdown",
                    LocaleService.text("opponent", opponent.name),
                    LocaleService.text("seconds", seconds),
                ),
            )
            bar.color(BossBar.Color.BLUE)
            val totalSeconds = session.policy.countdownSeconds
            bar.progress(if (totalSeconds == 0) 1f else (seconds.toFloat() / totalSeconds).coerceIn(0f, 1f))
        }
    }

    private fun updateMatchDisplay(match: DuelMatch) {
        val session = sessions[match.id] ?: return
        participants(match).forEach { player ->
            val ownId = PlayerId(player.uniqueId)
            val opponent = requireOnline(match.opponentOf(ownId))
            val bar = session.bossBars.computeIfAbsent(player.uniqueId) {
                BossBar.bossBar(Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS).also(player::showBossBar)
            }
            if (match.rules.objective.isHitRace) {
                val scores = session.hitRace.scores(match.rules.objective)
                val target =
                    if (match.rules.objective == DuelObjectiveType.BOXING) {
                        match.rules.modifiers.boxingHitsToWin
                    } else {
                        match.rules.modifiers.comboHitsToWin
                    }
                val own = scores[ownId] ?: 0L
                val enemy = scores[match.opponentOf(ownId)] ?: 0L
                bar.name(
                    locales.component(
                        player,
                        "session.bossbar-hits",
                        LocaleService.text("opponent", opponent.name),
                        LocaleService.text("own", own),
                        LocaleService.text("enemy", enemy),
                        LocaleService.text("target", target),
                    ),
                )
                bar.progress((own.toFloat() / target).coerceIn(0f, 1f))
                bar.color(BossBar.Color.BLUE)
            } else {
                val total = match.rules.modifiers.suddenDeathAfterSeconds
                val remaining = (total - session.roundElapsedTicks / 20L).coerceAtLeast(0L)
                val ownScore = if (ownId == match.firstPlayer) match.score.first else match.score.second
                val enemyScore = if (ownId == match.firstPlayer) match.score.second else match.score.first
                bar.name(
                    locales.component(
                        player,
                        activeBossBarLocaleKey(match.rules.bestOf),
                        LocaleService.text("opponent", opponent.name),
                        LocaleService.text("own", ownScore),
                        LocaleService.text("enemy", enemyScore),
                        LocaleService.text("time", formatDuelTime(remaining)),
                    ),
                )
                bar.progress(remainingBossBarProgress(session.roundElapsedTicks, total))
                bar.color(if (session.suddenDeathStarted) BossBar.Color.RED else BossBar.Color.BLUE)
            }
        }
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

    private fun showHitRaceProgress(
        match: DuelMatch,
        progress: Map<PlayerId, Long>,
    ) {
        val target =
            when (match.rules.objective) {
                DuelObjectiveType.BOXING -> match.rules.modifiers.boxingHitsToWin
                DuelObjectiveType.COMBO -> match.rules.modifiers.comboHitsToWin
                else -> return
            }
        participants(match).forEach { player ->
            val ownId = PlayerId(player.uniqueId)
            player.sendActionBar(
                locales.component(
                    player,
                    "session.hit-progress",
                    LocaleService.text("score", progress[ownId] ?: 0L),
                    LocaleService.text("target", target),
                    LocaleService.text("opponent", progress[match.opponentOf(ownId)] ?: 0L),
                ),
            )
        }
    }

    private fun startSuddenDeathIfNeeded(
        match: DuelMatch,
        session: PaperSession,
    ) {
        if (match.rules.objective.isHitRace) return
        if (session.suddenDeathStarted || session.roundElapsedTicks < match.rules.modifiers.suddenDeathAfterSeconds * 20L) return
        session.suddenDeathStarted = true
        participants(match).forEach { player ->
            player.addPotionEffect(PotionEffect(PotionEffectType.WITHER, Int.MAX_VALUE, 0, false, false, true))
            player.sendMessage(locales.notice(player, "session.sudden-death"))
            player.playSound(player.location, Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.2f)
        }
    }

    private fun handleRoundResult(
        matchId: MatchId,
        winner: PlayerId,
        updated: DuelMatch?,
        failure: Throwable?,
    ) {
        val session = sessions[matchId]
        session?.let(::restoreArenaBlocks)
        objectiveTasks.remove(matchId)?.cancel()
        matchDisplayTasks.remove(matchId)?.cancel()
        if (failure != null) {
            DuelLog.warn(
                "round-result-failed",
                matchId,
                "error_type={} error={}",
                unwrap(failure).javaClass.simpleName,
                unwrap(failure).message,
            )
            announcePersistenceFailure(matchId, unwrap(failure))
        } else if (updated?.state == MatchState.COUNTDOWN) {
            DuelLog.info(
                "round-complete",
                matchId,
                "winner={} score={}:{} next_state={}",
                currentName(winner),
                updated.score.first,
                updated.score.second,
                updated.state,
            )
            announceRound(updated, winner)
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val current = coordinator.find(updated.id)
                if (current?.state == MatchState.COUNTDOWN) {
                    prepareRound(current)
                    scheduleCountdown(current.id)
                }
            }, session?.policy?.seriesRoundIntermissionTicks ?: seriesRoundIntermissionTicks)
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
        DuelLog.info(
            "match-complete",
            match.id,
            "winner={} score={}:{} reason={} celebration_ticks={}",
            currentName(requireNotNull(match.winner)),
            match.score.first,
            match.score.second,
            match.endReason,
            session.policy.celebrationDurationTicks,
        )
        clearExternalCombatTags(match, "match-complete")
        countdownTasks.remove(match.id)?.cancel()
        teleportStabilizationTasks.remove(match.id)?.cancel()
        healthIsolationTasks.remove(match.id)?.cancel()
        objectiveTasks.remove(match.id)?.cancel()
        matchDisplayTasks.remove(match.id)?.cancel()
        val winnerId = requireNotNull(match.winner)
        listOf(match.firstPlayer.value, match.secondPlayer.value).forEach { playerId ->
            ExternalArcDuelTelemetryBridge.completed(playerId, match.id.value.toString())
        }
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
        showFinaleDisplay(match, session, winnerId)
        plugin.server.getPlayer(winnerId.value)?.let { celebrate(it, session) }
        scheduleFinalization(match, session, session.policy.celebrationDurationTicks)
    }

    private fun scheduleFinalization(match: DuelMatch, session: PaperSession, delayTicks: Long) {
        finaleTasks.remove(match.id)?.cancel()
        if (delayTicks == 0L) {
            finalizeMatch(match, session)
            return
        }
        finaleTasks[match.id] =
            plugin.server.scheduler.runTaskLater(
                plugin,
                Runnable {
                    finaleTasks.remove(match.id)
                    finalizeMatch(match, session)
                },
                delayTicks,
            )
    }

    private fun finalizeMatch(match: DuelMatch, session: PaperSession) {
        if (sessions[match.id] !== session) return
        clearExternalCombatTags(match, "match-finalize")
        DuelLog.debug(
            "match-finalize-attempt",
            match.id,
            "recovery_owner={} restored={}/{} moved={}/{}",
            session.recoveryOwner,
            session.restoredPlayers.size,
            session.snapshots.size,
            session.postMatchMovedPlayers.size,
            session.snapshots.size,
        )
        if (session.recoveryOwner == RecoveryOwner.ARENA_SERVER) {
            if (!restore(session)) {
                scheduleFinalization(match, session, 20L)
                return
            }
            val arena = arenas.get(match.arenaId)
            if ((arena.postMatchAction?.asReturnPolicy() ?: session.policy.defaultPostMatchReturnPolicy) == PostMatchReturnPolicy.PROMPT &&
                !moveLocalPlayersToLobby(match, session, arena)
            ) {
                scheduleFinalization(match, session, 20L)
                return
            }
        }
        if (session.recoveryOwner == RecoveryOwner.ORIGIN_SERVERS) {
            if (!moveNetworkPlayersToLobby(match, session)) {
                scheduleFinalization(match, session, 20L)
                return
            }
        }
        hideMatchDisplay(session)
        sessions.remove(match.id, session)
        session.snapshots.keys.forEach { sessionByPlayer.remove(it, match.id) }
        runCatching { coordinator.releaseCompleted(match.id) }
            .onSuccess { released ->
                DuelLog.info("match-released", match.id, "released={}", released)
                if (released) {
                    completionListeners.forEach { listener ->
                        runCatching { listener(match) }
                            .onFailure { plugin.logger.warning("Duel completion listener failed for ${match.id}: ${it.message}") }
                    }
                }
            }
            .onFailure { plugin.logger.severe("Could not release completed duel ${match.id}: ${it.message}") }
    }

    private fun showFinaleDisplay(match: DuelMatch, session: PaperSession, winner: PlayerId) {
        participants(match).forEach { player ->
            session.bossBars[player.uniqueId]?.apply {
                name(locales.component(player, if (player.uniqueId == winner.value) "session.match-win" else "session.match-loss"))
                progress(1f)
                color(if (player.uniqueId == winner.value) BossBar.Color.GREEN else BossBar.Color.RED)
            }
        }
    }

    private fun hideMatchDisplay(session: PaperSession) {
        session.bossBars.forEach { (playerId, bar) -> plugin.server.getPlayer(playerId)?.hideBossBar(bar) }
        session.bossBars.clear()
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

    private fun celebrate(
        player: Player,
        session: PaperSession,
    ) {
        celebrationEffects.play(player, session.policy.runtimeSettings)
    }

    private fun sessionPolicy(): SessionRuntimePolicy {
        val settings = runtimeSettings()
        return SessionRuntimePolicy(
            countdownSeconds = settings?.countdownSeconds ?: countdownSeconds,
            teleportStabilizationTicks = settings?.teleportStabilizationTicks ?: teleportStabilizationTicks,
            celebrationDurationTicks = settings?.celebrationDurationTicks ?: celebrationDurationTicks,
            seriesRoundIntermissionTicks = settings?.seriesRoundIntermissionTicks ?: seriesRoundIntermissionTicks,
            defaultPostMatchReturnPolicy = settings?.defaultPostMatchReturnPolicy ?: defaultPostMatchReturnPolicy,
            runtimeSettings = settings,
        )
    }

    private fun moveNetworkPlayersToLobby(
        match: DuelMatch,
        session: PaperSession,
    ): Boolean {
        var moved = true
        val arena = arenas.get(match.arenaId)
        if (arena.lobby == null) {
            plugin.logger.warning("Arena ${arena.id} has no lobby; using each participant's assigned arena spawn after this match")
        }
        session.snapshots.forEach { (playerId, origin) ->
            if (playerId in session.postMatchMovedPlayers) return@forEach
            val player = plugin.server.getPlayer(playerId) ?: return@forEach
            runCatching {
                kitHealthIsolation.clear(player)
                if (syncProvider.sharesInventoryBetweenServers) {
                    if (origin.escrow.inventoryReplaced) {
                        origin.state.restoreState(player)
                    } else {
                        origin.state.restoreStateWithoutInventory(player)
                    }
                } else {
                    session.arenaBaselines.getValue(playerId).restoreState(player)
                }
                playerDataSaver(player)
                val destination = postMatchDestination(arena, match, PlayerId(playerId))
                check(teleportInternally(player, destination)) { "Could not move ${player.uniqueId} to the post-match waiting point" }
                networkLobbyPlayers[player.uniqueId] = match.id
                session.postMatchMovedPlayers += playerId
            }.onFailure { failure ->
                moved = false
                plugin.logger.severe("Could not safely move ${player.uniqueId} to the post-match lobby: ${failure.message}")
            }
        }
        return moved
    }

    private fun moveLocalPlayersToLobby(
        match: DuelMatch,
        session: PaperSession,
        arena: PaperArena,
    ): Boolean {
        var moved = true
        session.snapshots.keys.forEach { playerId ->
            if (playerId in session.postMatchMovedPlayers) return@forEach
            val player = plugin.server.getPlayer(playerId) ?: return@forEach
            runCatching {
                check(teleportInternally(player, postMatchDestination(arena, match, PlayerId(playerId)))) {
                    "Could not move ${player.uniqueId} to the local arena lobby"
                }
                playerDataSaver(player)
                session.postMatchMovedPlayers += playerId
            }.onFailure { failure ->
                moved = false
                plugin.logger.severe("Could not safely move ${player.uniqueId} to the local arena lobby: ${failure.message}")
            }
        }
        return moved
    }

    private fun restoreArenaBlocks(session: PaperSession) {
        session.modifiedBlocks.values.toList().asReversed().forEach { original ->
            runCatching { original.update(true, false) }
                .onFailure { failure ->
                    plugin.logger.warning(
                        "Could not restore duel-modified block ${original.world.name}:${original.x},${original.y},${original.z}: ${failure.message}",
                    )
                }
        }
        session.modifiedBlocks.clear()
    }

    private fun restore(session: PaperSession): Boolean {
        var restored = true
        session.snapshots.forEach { (uuid, snapshot) ->
            if (uuid !in session.restoredPlayers) {
                val player = plugin.server.getPlayer(uuid)
                if (player == null || !restoreAndRetain(player, snapshot)) {
                    restored = false
                } else {
                    session.restoredPlayers += uuid
                }
            }
        }
        return restored && session.restoredPlayers.containsAll(session.snapshots.keys)
    }

    private fun markRestored(
        matchId: MatchId,
        playerId: UUID,
    ) {
        val session = sessions[matchId] ?: return
        session.restoredPlayers += playerId
        coordinator.find(matchId)?.takeIf { it.state == MatchState.COMPLETED }?.let(::finish)
    }

    private fun restoreAndRetain(
        player: Player,
        stored: StoredPlayerSnapshot,
        skipApplyWhenInventoryMatches: Boolean = false,
    ): Boolean {
        if (!restoringPlayers.add(player.uniqueId)) return true
        val preserveInventory = !stored.escrow.inventoryReplaced
        val alreadyMatches = skipApplyWhenInventoryMatches && stored.state.inventoryMatches(player)
        DuelLog.info(
            "inventory-restore-start",
            stored.escrow.matchId,
            player,
            "player={} inventory_replaced={} preserve_live_inventory={} already_matches={}",
            player.name,
            stored.escrow.inventoryReplaced,
            preserveInventory,
            alreadyMatches,
        )
        val restored = runCatching {
            kitHealthIsolation.clear(player)
            if (preserveInventory) {
                stored.state.restoreWithoutInventory(player, ::teleportInternally)
                playerDataSaver(player)
            } else if (alreadyMatches) {
                val stateChanged = !stored.state.nonInventoryStateMatches(player)
                val locationChanged = !stored.state.locationMatches(player)
                if (stateChanged || locationChanged) {
                    stored.state.restoreWithoutInventory(player, ::teleportInternally)
                    playerDataSaver(player)
                }
            } else {
                stored.state.restore(player, ::teleportInternally)
                playerDataSaver(player)
            }
        }
            .onFailure { failure ->
                DuelLog.warn(
                    "inventory-restore-failed",
                    stored.escrow.matchId,
                    player,
                    "error_type={} error={}",
                    failure.javaClass.simpleName,
                    failure.message,
                )
                plugin.logger.severe("Could not fully restore duel player ${player.uniqueId}: ${failure.javaClass.simpleName}: ${failure.message}")
            }
            .isSuccess
        if (!restored) {
            restoringPlayers -= player.uniqueId
            return false
        }
        playerStates.retain(stored).whenComplete { _, failure ->
            runSync {
                if (failure == null) {
                    DuelLog.info(
                        "inventory-snapshot-retained",
                        stored.escrow.matchId,
                        player,
                        "inventory_replaced={} live_inventory_preserved={}",
                        stored.escrow.inventoryReplaced,
                        preserveInventory,
                    )
                    preparingPlayers -= player.uniqueId
                    restoringPlayers -= player.uniqueId
                    if (player.isOnline && stored.escrow.inventoryReplaced && !alreadyMatches) {
                        locales.optionalComponent(player, "session.restored")?.let { player.sendActionBar(it) }
                    }
                } else {
                    plugin.logger.severe(
                        "Could not archive restored duel state for ${player.uniqueId}; the active snapshot remains retryable: ${unwrap(failure).message}",
                    )
                    if (player.isOnline && stored.escrow.inventoryReplaced) {
                        player.sendMessage(locales.notice(player, "session.ack-retry"))
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
                    restoringPlayers -= player.uniqueId
                } else if (plugin.isEnabled) {
                    plugin.logger.severe("Player state archival retry failed for ${player.uniqueId}: ${unwrap(failure).message}")
                    if (player.isOnline) {
                        plugin.server.scheduler.runTaskLater(
                            plugin,
                            Runnable { retryRetention(player, stored) },
                            RECOVERY_RETRY_TICKS,
                        )
                    } else {
                        restoringPlayers -= player.uniqueId
                    }
                }
            }
        }
    }

    private fun teleportInternally(
        player: Player,
        destination: org.bukkit.Location,
    ): Boolean {
        val matchId = matchFor(player)?.id ?: playerStates.pending(player.uniqueId)?.matchId
        DuelLog.debug(
            "teleport-internal",
            matchId,
            player,
            "player={} from={}:{},{},{} to={}:{},{},{}",
            player.name,
            player.world.name,
            player.location.blockX,
            player.location.blockY,
            player.location.blockZ,
            destination.world?.name,
            destination.blockX,
            destination.blockY,
            destination.blockZ,
        )
        val teleported = internalTeleports.authorize(player.uniqueId, destination) { player.teleport(destination) }
        DuelLog.debug("teleport-internal-result", matchId, player, "success={} world={}", teleported, player.world.name)
        return teleported
    }

    private fun participants(match: DuelMatch): List<Player> =
        listOfNotNull(plugin.server.getPlayer(match.firstPlayer.value), plugin.server.getPlayer(match.secondPlayer.value))

    private fun requireOnline(playerId: PlayerId): Player =
        plugin.server.getPlayer(playerId.value) ?: error("Player $playerId left the server")

    private fun currentName(playerId: PlayerId): String =
        plugin.server.getPlayer(playerId.value)?.name ?: playerId.value.toString()

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
        val recoveryOwner: RecoveryOwner,
        val arenaBaselines: Map<UUID, PlayerSnapshot>,
        val policy: SessionRuntimePolicy,
        val arenaAnchors: MutableMap<UUID, org.bukkit.Location> = ConcurrentHashMap(),
        val restoredPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        val hillCapture: HillCaptureTracker = HillCaptureTracker(),
        val hitRace: HitRaceTracker = HitRaceTracker(),
        val bossBars: MutableMap<UUID, BossBar> = ConcurrentHashMap(),
        val postMatchMovedPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet(),
        val modifiedBlocks: MutableMap<BlockKey, BlockState> = LinkedHashMap(),
        var roundElapsedTicks: Long = 0L,
        var suddenDeathStarted: Boolean = false,
        var roundsPrepared: Int = 0,
        val finishing: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(),
    ) {
        fun rememberOriginal(block: Block): Boolean {
            val key = BlockKey.of(block)
            if (key in modifiedBlocks) return true
            if (modifiedBlocks.size >= MAX_MODIFIED_BLOCKS) return false
            modifiedBlocks[key] = block.state
            return true
        }
    }

    private data class SessionRuntimePolicy(
        val countdownSeconds: Int,
        val teleportStabilizationTicks: Long,
        val celebrationDurationTicks: Long,
        val seriesRoundIntermissionTicks: Long,
        val defaultPostMatchReturnPolicy: PostMatchReturnPolicy,
        val runtimeSettings: ArcDuelsRuntimeSettings?,
    )

    private enum class RecoveryOwner {
        ARENA_SERVER,
        ORIGIN_SERVERS,
    }

    private companion object {
        val PLAYER_COMBAT_TELEPORTS =
            setOf(PlayerTeleportEvent.TeleportCause.ENDER_PEARL, PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT)
        const val OBJECTIVE_PERIOD_TICKS = 10L
        const val HILL_PARTICLES = 16
        const val RECOVERY_RETRY_TICKS = 60L
        const val REMOTE_RECOVERY_RETRY_TICKS = 100L
        const val RECOVERY_READY_POLL_TICKS = 5L
        val DUEL_FLUID_BUCKETS = setOf(Material.LAVA_BUCKET, Material.WATER_BUCKET, Material.POWDER_SNOW_BUCKET)
        val FLUID_REACTION_FACES = listOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)
        const val MAX_MODIFIED_BLOCKS = 4_096
    }
}

private data class BlockKey(
    val worldId: UUID,
    val x: Int,
    val y: Int,
    val z: Int,
) {
    companion object {
        fun of(block: Block): BlockKey = BlockKey(block.world.uid, block.x, block.y, block.z)
    }
}

internal fun postMatchDestination(
    arena: PaperArena,
    match: DuelMatch,
    player: PlayerId,
): org.bukkit.Location =
    (arena.lobby ?: when (player) {
        match.firstPlayer -> arena.firstSpawn
        match.secondPlayer -> arena.secondSpawn
        else -> error("Player $player is not part of duel ${match.id}")
    }).clone()

internal fun formatDuelTime(seconds: Long): String = "%d:%02d".format(seconds / 60L, seconds % 60L)

internal fun activeBossBarLocaleKey(bestOf: Int): String =
    if (bestOf == 1) "session.bossbar-active-single" else "session.bossbar-active"

internal data class DuelShutdownReport(
    val localSnapshotsApplied: Int,
    val networkPlayersNormalized: Int,
    val retention: RetentionDrainReport,
)

internal fun remainingBossBarProgress(elapsedTicks: Long, durationSeconds: Int): Float {
    if (durationSeconds <= 0) return 0f
    return (1.0 - elapsedTicks.toDouble() / (durationSeconds * 20.0)).coerceIn(0.0, 1.0).toFloat()
}

internal fun <T> cancelOrReleaseReservation(
    reservation: CompletableFuture<T>,
    release: (T) -> Unit,
) {
    if (!reservation.cancel(false)) reservation.thenAccept(release)
}

internal enum class AdminRecoveryStatus {
    STARTED,
    TRANSFERRED,
    REPLAYED,
    NO_SNAPSHOT,
    BUSY,
    WRONG_SERVER,
}

internal data class AdminRecoveryResult(
    val status: AdminRecoveryStatus,
    val server: ServerId? = null,
)
