package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.whenCompleteSync
import ru.arc.paper.audience.NativePaperAudienceEffects
import ru.arc.paper.audience.PaperAudienceEffects
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import ru.arc.paper.chunk.PaperChunkTicketLease
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.teleport.NativePaperTeleportExecutor
import ru.arc.paper.teleport.PaperTeleportExecutor
import ru.arc.paper.teleport.ScopedTeleportAuthorizer
import ru.ruscrafting.duels.domain.MatchEndReason
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MultiplayerMatch
import ru.ruscrafting.duels.domain.MultiplayerMatchRepository
import ru.ruscrafting.duels.domain.MultiplayerMatchState
import ru.ruscrafting.duels.domain.MultiplayerObjectiveFrame
import ru.ruscrafting.duels.domain.MultiplayerObjectiveDecision
import ru.ruscrafting.duels.domain.MultiplayerScoreObjective
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.outcome
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Local-node multiplayer owner. The established cross-server 1v1 protocol is intentionally untouched. */
internal class MultiplayerSessionManager(
    private val serverId: ServerId,
    private val arenas: PaperArenaCatalog,
    private val kits: KitRegistry,
    private val playerStates: DurablePlayerStateService,
    private val results: MultiplayerMatchRepository,
    private val locales: LocaleService,
    private val tasks: LifecycleTaskScope,
    private val chunkTickets: PaperChunkTicketRegistry,
    private val audience: PaperAudienceEffects = NativePaperAudienceEffects,
    private val teleports: PaperTeleportExecutor = NativePaperTeleportExecutor,
    private val playerData: PaperPlayerDataPersistence = NativePaperPlayerDataPersistence,
    private val externalCombatTagClear: (Player, MatchId, String) -> Unit = { _, _, _ -> },
    private val networkReturn: (Player, ServerId) -> Unit = { _, _ -> },
    private val syncProvider: PlayerDataSyncProvider = PlayerDataSyncProvider.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val countdownSeconds: Int = 3,
    private val runtimeSettings: () -> ArcDuelsRuntimeSettings? = { null },
) : AutoCloseable {
    private data class Session(
        var match: MultiplayerMatch,
        val reservation: MultiplayerArenaReservation,
        val snapshots: Map<UUID, StoredPlayerSnapshot>,
        val anchors: Map<UUID, Location>,
        val leases: List<PaperChunkTicketLease>,
        val origins: Map<PlayerId, ServerId>? = null,
        val arenaBaselines: Map<UUID, PlayerSnapshot> = emptyMap(),
        val countdownSeconds: Int,
        val finishDelayTicks: Long,
        var restoreInFlight: Boolean = false,
        var arrivals: CompletableFuture<Void>? = null,
        var waitingForArrivals: Boolean = false,
        var objectiveTask: ScheduledTask? = null,
        var objectiveTicks: Long = 0,
        val hillCapture: MultiplayerHillCaptureTracker = MultiplayerHillCaptureTracker(),
        val hitRace: HitRaceTracker = HitRaceTracker(),
    )

    private data class RuntimePolicy(
        val countdownSeconds: Int,
        val finishDelayTicks: Long,
    )

    private val sessions = ConcurrentHashMap<MatchId, Session>()
    private val byPlayer = ConcurrentHashMap<UUID, MatchId>()
    private val teleportsAuthorized = ScopedTeleportAuthorizer()
    private val kitHealth = KitHealthIsolation(NamespacedKey("arcduels", "multiplayer-kit-health"))

    init {
        require(countdownSeconds in 0..10) { "Multiplayer countdown must be between 0 and 10 seconds" }
    }

    fun start(roster: MultiplayerRoster, onlinePlayers: Map<PlayerId, Player>): CompletableFuture<MatchId> {
        check(Bukkit.isPrimaryThread()) {
            "Multiplayer matches must start on the Paper primary thread"
        }
        require(onlinePlayers.keys == roster.playerIds) { "Every roster participant must be online on this Paper node" }
        require(onlinePlayers.values.all(Player::isOnline)) { "Every multiplayer participant must remain online" }
        require(onlinePlayers.values.none { isEngaged(it) || playerStates.isPending(it.uniqueId) }) {
            "A multiplayer participant is already engaged or awaiting recovery"
        }
        val policy = captureRuntimePolicy()
        val matchId = MatchId.random()
        val completion = CompletableFuture<MatchId>()
        arenas.reserveMultiplayer(roster).whenCompleteSync(tasks) { reservation, reservationFailure ->
            if (reservationFailure != null || reservation == null) {
                completion.completeExceptionally(reservationFailure ?: IllegalStateException("Multiplayer arena reservation failed"))
                return@whenCompleteSync
            }
            playerStates.storeAll(matchId, onlinePlayers.values, inventoryReplaced = true)
                .whenCompleteSync(tasks) { snapshots, escrowFailure ->
                    if (escrowFailure != null || snapshots == null) {
                        reservation.close()
                        completion.completeExceptionally(escrowFailure ?: IllegalStateException("Multiplayer escrow failed"))
                        return@whenCompleteSync
                    }
                    runCatching {
                        createSession(matchId, roster, onlinePlayers, reservation, snapshots, policy = policy)
                    }.onSuccess {
                        completion.complete(matchId)
                    }.onFailure { failure ->
                        completion.completeExceptionally(failure)
                    }
                }
        }
        return completion
    }

    fun startNetwork(
        matchId: MatchId,
        roster: MultiplayerRoster,
        onlinePlayers: Map<PlayerId, Player>,
        origins: Map<PlayerId, ServerId>,
        mayStart: () -> Boolean = { true },
    ): CompletableFuture<MatchId> {
        check(Bukkit.isPrimaryThread()) { "Network multiplayer matches must start on the Paper primary thread" }
        require(onlinePlayers.keys == roster.playerIds) { "Every roster participant must be online on this Paper node" }
        require(origins.keys == roster.playerIds) { "Every roster participant must have an origin route" }
        require(onlinePlayers.values.all(Player::isOnline)) { "Every multiplayer participant must remain online" }
        require(onlinePlayers.values.none { isEngaged(it) }) { "A multiplayer participant is already engaged" }
        val policy = captureRuntimePolicy()
        val completion = CompletableFuture<MatchId>()
        val snapshots = playerStates.findMatchSnapshots(matchId, origins)
        val reservation = arenas.reserveMultiplayer(roster)
        reservation.thenCombine(snapshots, ::Pair).whenCompleteSync(tasks) { prepared, failure ->
            if (failure != null || prepared == null) {
                reservation.whenCompleteSync(tasks) { reserved, _ -> reserved?.close() }
                completion.completeExceptionally(failure ?: IllegalStateException("Network multiplayer preparation failed"))
                return@whenCompleteSync
            }
            val (reserved, escrows) = prepared
            runCatching {
                check(mayStart()) { "Network group preparation was cancelled" }
                val stored = onlinePlayers.mapValues { (playerId, player) ->
                    playerStates.decodeForArena(escrows.getValue(playerId), player)
                }.mapKeys { it.key.value }
                val baselines = onlinePlayers.mapValues { (_, player) -> PlayerSnapshot.capture(player) }.mapKeys { it.key.value }
                createSession(matchId, roster, onlinePlayers, reserved, stored, origins, policy, baselines)
            }.onSuccess {
                completion.complete(matchId)
            }.onFailure { startFailure ->
                reserved.close()
                completion.completeExceptionally(startFailure)
            }
        }
        return completion
    }

    internal fun cancelStartingMatch(matchId: MatchId): Boolean {
        val session = sessions[matchId] ?: return false
        if (session.match.state in setOf(MultiplayerMatchState.COMPLETING, MultiplayerMatchState.COMPLETED, MultiplayerMatchState.CANCELLED)) return false
        session.match = session.match.cancel(clock.instant(), MatchEndReason.ADMIN_CANCEL)
        finishRestore(session)
        return true
    }

    fun matchFor(player: Player): MultiplayerMatch? = byPlayer[player.uniqueId]?.let(sessions::get)?.match

    fun isEngaged(player: Player): Boolean = byPlayer.containsKey(player.uniqueId)

    fun isLocked(player: Player): Boolean = isEngaged(player)

    fun hasArenaCapacity(roster: MultiplayerRoster): Boolean = arenas.hasMultiplayerCapacity(roster)

    fun isInsideArena(player: Player, destination: Location): Boolean =
        matchFor(player)?.let { arenas.get(it.arenaId).bounds.contains(destination) } ?: true

    fun isSumoRingOut(player: Player, destination: Location): Boolean {
        val match = matchFor(player) ?: return false
        if (match.roster.rules.objective != DuelObjectiveType.SUMO) return false
        val arena = arenas.get(match.arenaId)
        return destination.y < minOf(arena.firstSpawn.y, arena.secondSpawn.y) - 1.5
    }

    fun boundaryDistance(player: Player, location: Location): Double? =
        matchFor(player)?.let { arenas.get(it.arenaId).bounds.distanceToEdge(location) }

    fun isKitHealthCapApplied(player: Player): Boolean = kitHealth.isApplied(player)

    fun isSumo(player: Player): Boolean = matchFor(player)?.roster?.rules?.objective == DuelObjectiveType.SUMO

    fun isHitRace(player: Player): Boolean = matchFor(player)?.roster?.rules?.objective?.isHitRace == true

    fun allowsProjectiles(player: Player): Boolean = matchFor(player)?.roster?.rules?.modifiers?.projectiles ?: true

    fun allowsConsumables(player: Player): Boolean = matchFor(player)?.roster?.rules?.modifiers?.consumables ?: true

    fun allowsEnderPearls(player: Player): Boolean = matchFor(player)?.roster?.rules?.modifiers?.enderPearls ?: true

    fun recordMeleeHit(attacker: Player, victim: Player) {
        val session = byPlayer[attacker.uniqueId]?.let(sessions::get) ?: return
        val match = session.match
        if (match.state != MultiplayerMatchState.ACTIVE || !match.roster.rules.objective.isHitRace) return
        if (byPlayer[victim.uniqueId] != match.id || !match.isEnemy(PlayerId(attacker.uniqueId), PlayerId(victim.uniqueId))) return
        val progress = session.hitRace.record(
            match.roster.rules.objective,
            representative(match, PlayerId(attacker.uniqueId)),
            representative(match, PlayerId(victim.uniqueId)),
        )
        showHitRaceProgress(session, progress.scores)
        val target = when (match.roster.rules.objective) {
            DuelObjectiveType.BOXING -> match.roster.rules.modifiers.boxingHitsToWin
            DuelObjectiveType.COMBO -> match.roster.rules.modifiers.comboHitsToWin
            else -> return
        }
        when (val decision = MultiplayerScoreObjective(match.roster.rules.objective, match.roster.rules.modifiers.kingOfTheHillCaptureSeconds, target)
            .evaluate(match, MultiplayerObjectiveFrame(session.objectiveTicks, emptySet(), progress.scores))) {
            MultiplayerObjectiveDecision.Continue -> Unit
            is MultiplayerObjectiveDecision.Complete -> completeObjective(session, decision)
        }
    }

    fun anchor(player: Player): Location? =
        byPlayer[player.uniqueId]?.let(sessions::get)?.anchors?.get(player.uniqueId)?.clone()

    fun isEnemy(first: Player, second: Player): Boolean {
        val match = matchFor(first) ?: return false
        if (matchFor(second)?.id != match.id) return false
        return match.isEnemy(PlayerId(first.uniqueId), PlayerId(second.uniqueId))
    }

    fun isTeleportAllowed(
        player: Player,
        destination: Location?,
        cause: PlayerTeleportEvent.TeleportCause,
    ): Boolean {
        if (!isLocked(player) || teleportsAuthorized.isAuthorized(player.uniqueId, destination)) return true
        val session = byPlayer[player.uniqueId]?.let(sessions::get) ?: return false
        if (destination != null &&
            session.match.state == MultiplayerMatchState.ACTIVE &&
            cause in EXTERNAL_RESCUE_TELEPORT_CAUSES &&
            arenas.get(session.match.arenaId).bounds.contains(destination)
        ) {
            return true
        }
        val anchor = session.anchors[player.uniqueId] ?: return false
        if (cause != PlayerTeleportEvent.TeleportCause.PLUGIN || session.arrivals?.isDone != false) return false
        return runCatching {
            teleportsAuthorized.authorize(player.uniqueId, anchor) {
                teleportsAuthorized.isAuthorized(player.uniqueId, destination)
            }
        }.getOrDefault(false)
    }

    fun eliminate(player: Player, reason: MatchEndReason = MatchEndReason.ELIMINATION) {
        val session = byPlayer[player.uniqueId]?.let(sessions::get) ?: return
        if (session.match.state != MultiplayerMatchState.ACTIVE) return
        val playerId = PlayerId(player.uniqueId)
        if (playerId !in session.match.activePlayers) return
        session.match = session.match.eliminate(playerId, clock.instant(), reason)
        player.gameMode = GameMode.SPECTATOR
        audience.showTitle(
            player,
            Title.title(
                locales.component(player, "multiplayer.eliminated.title"),
                locales.component(player, "multiplayer.eliminated.subtitle"),
            ),
        )
        if (reason == MatchEndReason.FORFEIT) {
            audience.sendMessage(
                player,
                locales.notice(
                    player,
                    "multiplayer.forfeit-result",
                    LocaleService.component("mode", multiplayerModeComponent(player, session.match.roster)),
                ),
            )
        }
        if (session.match.state == MultiplayerMatchState.COMPLETING) complete(session)
    }

    fun handleForfeit(player: Player): Boolean {
        val session = byPlayer[player.uniqueId]?.let(sessions::get) ?: return false
        when (session.match.state) {
            MultiplayerMatchState.ACTIVE -> {
                val playerId = PlayerId(player.uniqueId)
                if (playerId in session.match.activePlayers) eliminate(player, MatchEndReason.FORFEIT)
            }
            MultiplayerMatchState.RESERVED, MultiplayerMatchState.COUNTDOWN -> {
                session.match = session.match.cancel(clock.instant(), MatchEndReason.ADMIN_CANCEL)
                participants(session).filterNot { it.uniqueId == player.uniqueId }.forEach {
                    audience.sendMessage(it, locales.notice(it, "multiplayer.cancelled"))
                }
                finishRestore(session)
            }
            else -> return false
        }
        return true
    }

    fun handleQuit(player: Player) {
        val match = matchFor(player) ?: return
        val session = byPlayer[player.uniqueId]?.let(sessions::get) ?: return
        if (match.state == MultiplayerMatchState.ACTIVE) {
            if (PlayerId(player.uniqueId) in match.activePlayers) eliminate(player, MatchEndReason.DISCONNECT)
            restoreDeparting(player, session.snapshots[player.uniqueId])
            byPlayer.remove(player.uniqueId, match.id)
        } else if (match.state in setOf(MultiplayerMatchState.RESERVED, MultiplayerMatchState.COUNTDOWN)) {
            session.match = session.match.cancel(clock.instant(), MatchEndReason.ADMIN_CANCEL)
            participants(session).filterNot { it.uniqueId == player.uniqueId }.forEach {
                audience.sendMessage(it, locales.notice(it, "multiplayer.player-left"))
            }
            finishRestore(session)
        } else if (match.state in setOf(MultiplayerMatchState.COMPLETING, MultiplayerMatchState.COMPLETED, MultiplayerMatchState.CANCELLED)) {
            restoreDeparting(player, session.snapshots[player.uniqueId])
            byPlayer.remove(player.uniqueId, match.id)
        }
    }

    fun activeCount(): Int = sessions.size

    override fun close() {
        sessions.values.toList().forEach { session ->
            session.match = runCatching { session.match.cancel(clock.instant(), MatchEndReason.SERVER_SHUTDOWN) }.getOrDefault(session.match)
            shutdownRestore(session)
        }
    }

    private fun createSession(
        matchId: MatchId,
        roster: MultiplayerRoster,
        onlinePlayers: Map<PlayerId, Player>,
        reservation: MultiplayerArenaReservation,
        snapshots: Map<UUID, StoredPlayerSnapshot>,
        origins: Map<PlayerId, ServerId>? = null,
        policy: RuntimePolicy,
        arenaBaselines: Map<UUID, PlayerSnapshot> = emptyMap(),
    ) {
        val leases = mutableListOf<PaperChunkTicketLease>()
        var session: Session? = null
        try {
            reservation.spawns.values.mapTo(leases, ::acquireChunkTicket)
            val match = MultiplayerMatch.reserve(matchId, reservation.arenaId, serverId, roster, clock.instant())
            val anchors = reservation.spawns.mapKeys { it.key.value }
            val created =
                Session(
                    match,
                    reservation,
                    snapshots,
                    anchors,
                    leases,
                    origins,
                    arenaBaselines,
                    countdownSeconds = policy.countdownSeconds,
                    finishDelayTicks = policy.finishDelayTicks,
                )
            session = created
            check(sessions.putIfAbsent(matchId, created) == null) { "Multiplayer match id collision" }
            check(roster.playerIds.none { byPlayer.putIfAbsent(it.value, matchId) != null }) { "A participant became engaged" }
            onlinePlayers.forEach { (playerId, player) ->
                prepareKit(player, kits.get(roster.participant(playerId).kitId))
            }
            val arrivals = onlinePlayers.map { (playerId, player) ->
                val destination = requireNotNull(reservation.spawns[playerId])
                teleportsAuthorized.authorize(player.uniqueId, destination) {
                    teleports.teleportAsync(player, destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                }.thenApply { arrived -> check(arrived) { "Could not teleport ${player.name} to the multiplayer arena" } }
            }
            val activeSession = requireNotNull(session)
            val arrivalBarrier = CompletableFuture.allOf(*arrivals.toTypedArray())
            activeSession.arrivals = arrivalBarrier
            arrivalBarrier.whenCompleteSync(tasks) { _, failure ->
                if (sessions[matchId] !== activeSession || activeSession.match.state != MultiplayerMatchState.RESERVED) {
                    return@whenCompleteSync
                }
                if (failure != null) {
                    val cause = failure.multiplayerRootCause()
                    DuelLog.warn(
                        "multiplayer-arrival-failed",
                        activeSession.match.id,
                        "reason=teleport error_type={} error={}",
                        cause.javaClass.simpleName,
                        cause.message,
                    )
                    participants(activeSession).forEach { player ->
                        audience.sendMessage(player, locales.notice(player, "multiplayer.start-failed"))
                    }
                    finishRestore(activeSession)
                    return@whenCompleteSync
                }
                activeSession.match = activeSession.match.beginCountdown()
                scheduleCountdown(activeSession, activeSession.countdownSeconds)
            }
        } catch (failure: Throwable) {
            if (session != null) {
                finishRestore(session)
            } else {
                leases.forEach { runCatching { it.close() } }
                abortBeforeStart(reservation, snapshots, onlinePlayers.values, origins, arenaBaselines)
            }
            throw failure
        }
    }

    private fun scheduleCountdown(session: Session, seconds: Int) {
        if (session.match.state != MultiplayerMatchState.COUNTDOWN) return
        if (seconds <= 0) {
            session.match = session.match.activate(clock.instant())
            startObjectiveLoop(session)
            participants(session).forEach { player ->
                audience.showTitle(player, Title.title(locales.component(player, "multiplayer.start.title"), Component.empty()))
            }
            return
        }
        participants(session).forEach { player ->
            audience.showTitle(
                player,
                Title.title(
                    locales.component(player, "multiplayer.countdown.title", LocaleService.text("seconds", seconds)),
                    locales.component(player, "multiplayer.countdown.subtitle"),
                ),
            )
        }
        tasks.runLater(20L) { scheduleCountdown(session, seconds - 1) }
    }

    private fun startObjectiveLoop(session: Session) {
        session.objectiveTask?.cancel()
        session.objectiveTask = tasks.runTimer(1L, 1L) {
            if (sessions[session.match.id] !== session || session.match.state != MultiplayerMatchState.ACTIVE) {
                session.objectiveTask?.cancel()
                return@runTimer
            }
            session.objectiveTicks++
            val arena = arenas.get(session.match.arenaId)
            participants(session).forEach { player ->
                if (PlayerId(player.uniqueId) in session.match.activePlayers && !arena.bounds.contains(player.location)) {
                    eliminate(player, MatchEndReason.FORFEIT)
                }
            }
            if (session.match.state != MultiplayerMatchState.ACTIVE) return@runTimer
            if (session.match.roster.rules.objective != DuelObjectiveType.KING_OF_THE_HILL) return@runTimer
            val hill = arena.hill ?: return@runTimer
            val contenders = participants(session).filter { it.gameMode != GameMode.SPECTATOR && hill.contains(it.location) }
                .mapTo(linkedSetOf()) { PlayerId(it.uniqueId) }
            val progress = session.hillCapture.tick(
                contenders,
                sideOf = { id -> session.match.roster.participant(id).team?.toString() ?: id.value.toString() },
                elapsedTicks = 1L,
            )
            if (session.objectiveTicks % 20L == 0L) showHillProgress(session, contenders, progress)
            when (val decision = MultiplayerScoreObjective(DuelObjectiveType.KING_OF_THE_HILL, session.match.roster.rules.modifiers.kingOfTheHillCaptureSeconds, 1)
                .evaluate(session.match, MultiplayerObjectiveFrame(session.objectiveTicks, contenders, progress))) {
                MultiplayerObjectiveDecision.Continue -> Unit
                is MultiplayerObjectiveDecision.Complete -> completeObjective(session, decision)
            }
        }
    }

    private fun representative(match: MultiplayerMatch, player: PlayerId): PlayerId {
        val participant = match.roster.participant(player)
        return participant.team?.let { team ->
            match.roster.participants.filter { it.team == team }.minBy { it.playerId.value }.playerId
        } ?: player
    }

    private fun showHillProgress(session: Session, contenders: Set<PlayerId>, progress: Map<PlayerId, Long>) {
        val target = session.match.roster.rules.modifiers.kingOfTheHillCaptureSeconds
        session.match.roster.playerIds.mapNotNull { Bukkit.getPlayer(it.value)?.takeIf(Player::isOnline) }.forEach { player ->
            val own = (progress[representative(session.match, PlayerId(player.uniqueId))] ?: 0L) / 20L
            audience.sendActionBar(player, locales.component(
                player,
                "multiplayer.objective-progress",
                LocaleService.component("objective", locales.component(player, objectiveLocaleKey(DuelObjectiveType.KING_OF_THE_HILL))),
                LocaleService.text("own", own),
                LocaleService.text("target", target),
                LocaleService.component("unit", locales.component(player, "multiplayer.progress-seconds")),
            ))
        }
    }

    private fun showHitRaceProgress(session: Session, progress: Map<PlayerId, Long>) {
        val match = session.match
        val target = when (match.roster.rules.objective) {
            DuelObjectiveType.BOXING -> match.roster.rules.modifiers.boxingHitsToWin
            DuelObjectiveType.COMBO -> match.roster.rules.modifiers.comboHitsToWin
            else -> return
        }
        match.roster.playerIds.mapNotNull { Bukkit.getPlayer(it.value)?.takeIf(Player::isOnline) }.forEach { player ->
            val own = progress[representative(match, PlayerId(player.uniqueId))] ?: 0L
            audience.sendActionBar(player, locales.component(
                player,
                "multiplayer.objective-progress",
                LocaleService.component("objective", locales.component(player, objectiveLocaleKey(match.roster.rules.objective))),
                LocaleService.text("own", own),
                LocaleService.text("target", target),
                LocaleService.component("unit", locales.component(player, "multiplayer.progress-hits")),
            ))
        }
    }

    private fun objectiveLocaleKey(objective: DuelObjectiveType): String = "objective.${objective.name.lowercase().replace("king_of_the_hill", "koth")}.name"

    private fun multiplayerModeComponent(player: Player, roster: MultiplayerRoster): Component {
        val rules = roster.rules
        val objective = locales.component(player, objectiveLocaleKey(rules.objective))
        val participant = roster.participant(PlayerId(player.uniqueId))
        val kit = locales.component(player, "kit.${participant.kitId.value}.name")
        val loadoutKey = if (rules.kitPolicy == ru.ruscrafting.duels.domain.MultiplayerKitPolicy.SHARED) {
            "multiplayer.loadout-shared"
        } else {
            "multiplayer.loadout-personal"
        }
        val loadout = locales.component(player, loadoutKey, LocaleService.component("kit", kit))
        return locales.component(
            player,
            "multiplayer.mode",
            LocaleService.component("objective", objective),
            LocaleService.component("loadout", loadout),
        )
    }

    private fun completeObjective(session: Session, decision: MultiplayerObjectiveDecision.Complete) {
        if (session.match.state != MultiplayerMatchState.ACTIVE) return
        session.objectiveTask?.cancel()
        session.match = session.match.completeObjective(decision.winners, decision.winningTeam, clock.instant())
        participants(session).filter { PlayerId(it.uniqueId) !in session.match.winners }.forEach { it.gameMode = GameMode.SPECTATOR }
        complete(session)
    }

    private fun complete(session: Session) {
        if (sessions[session.match.id] !== session || session.match.state != MultiplayerMatchState.COMPLETING) return
        results.record(session.match.outcome()).whenCompleteSync(tasks) { _, failure ->
            if (sessions[session.match.id] !== session || session.match.state != MultiplayerMatchState.COMPLETING) {
                return@whenCompleteSync
            }
            if (failure != null) {
                val cause = failure.multiplayerRootCause()
                DuelLog.warn(
                    "multiplayer-result-record-failed",
                    session.match.id,
                    "error_type={} error={}",
                    cause.javaClass.simpleName,
                    cause.message,
                )
                participants(session).forEach { audience.sendMessage(it, locales.notice(it, "multiplayer.result-pending")) }
                tasks.runLater(40L) { complete(session) }
                return@whenCompleteSync
            }
            session.match = session.match.markPersisted()
            participants(session).forEach { player ->
                val won = PlayerId(player.uniqueId) in session.match.winners
                audience.showTitle(
                    player,
                    Title.title(
                        locales.component(player, if (won) "multiplayer.victory.title" else "multiplayer.defeat.title"),
                        locales.component(
                            player,
                            "multiplayer.finish.subtitle",
                            LocaleService.component("mode", multiplayerModeComponent(player, session.match.roster)),
                        ),
                    ),
                )
                audience.sendMessage(
                    player,
                    locales.notice(
                        player,
                        if (won) "multiplayer.result-win" else "multiplayer.result-loss",
                        LocaleService.component("mode", multiplayerModeComponent(player, session.match.roster)),
                    ),
                )
            }
            tasks.runLater(session.finishDelayTicks) { finishRestore(session) }
        }
    }

    private fun finishRestore(session: Session) {
        if (sessions[session.match.id] !== session || session.restoreInFlight) return
        val arrivals = session.arrivals
        if (arrivals != null && !arrivals.isDone) {
            if (!session.waitingForArrivals) {
                session.waitingForArrivals = true
                arrivals.whenCompleteSync(tasks) { _, _ ->
                    session.waitingForArrivals = false
                    finishRestore(session)
                }
            }
            return
        }
        val players = participants(session)
        if (players.isEmpty()) return releaseSession(session)
        val applyFailure = players.firstNotNullOfOrNull { player ->
            runCatching { restoreOwnedState(session, player) }.exceptionOrNull()
        }
        if (applyFailure != null) {
            DuelLog.warn(
                "multiplayer-restore-failed",
                session.match.id,
                "error_type={} error={}",
                applyFailure.javaClass.simpleName,
                applyFailure.message,
            )
            tasks.runLater(RESTORE_RETRY_TICKS) { finishRestore(session) }
            return
        }
        session.restoreInFlight = true
        val retentions = players.filter { ownsEscrow(session, it.uniqueId) }
            .map { player -> playerStates.retain(requireNotNull(session.snapshots[player.uniqueId])) }
        CompletableFuture.allOf(*retentions.toTypedArray()).whenCompleteSync(tasks) { _, failure ->
            if (sessions[session.match.id] !== session) return@whenCompleteSync
            if (failure == null) {
                releaseSession(session)
            } else {
                session.restoreInFlight = false
                players.filter(Player::isOnline).forEach { player ->
                    audience.sendMessage(player, locales.notice(player, "session.ack-retry"))
                }
                DuelLog.warn(
                    "multiplayer-retain-failed",
                    session.match.id,
                    "error_type={} error={}",
                    failure.javaClass.simpleName,
                    failure.message,
                )
                tasks.runLater(RESTORE_RETRY_TICKS) { finishRestore(session) }
            }
        }
    }

    private fun applyRestoredState(player: Player, stored: StoredPlayerSnapshot) {
        check(player.isOnline) { "Cannot restore an offline multiplayer participant" }
        externalCombatTagClear(player, stored.escrow.matchId, "multiplayer-restore")
        kitHealth.clear(player)
        stored.state.restore(player) { target, destination ->
            teleportsAuthorized.authorize(target.uniqueId, destination) {
                target.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
            }
        }
        playerData.persist(player)
    }

    private fun restoreOwnedState(session: Session, player: Player) {
        if (ownsEscrow(session, player.uniqueId)) {
            applyRestoredState(player, requireNotNull(session.snapshots[player.uniqueId]))
        } else {
            val baseline = requireNotNull(session.arenaBaselines[player.uniqueId])
            check(player.isOnline) { "Cannot restore an offline multiplayer participant" }
            externalCombatTagClear(player, session.match.id, "multiplayer-foreign-restore")
            kitHealth.clear(player)
            baseline.restoreState(player)
            playerData.persist(player)
        }
    }

    private fun ownsEscrow(session: Session, playerId: UUID): Boolean =
        session.origins?.get(PlayerId(playerId))?.let { it == serverId } ?: true

    private fun restoreDeparting(player: Player, stored: StoredPlayerSnapshot?) {
        if (stored == null || !player.isOnline) return
        val session = byPlayer[player.uniqueId]?.let(sessions::get)
        runCatching {
            if (session == null || ownsEscrow(session, player.uniqueId)) {
                applyRestoredState(player, stored)
            } else {
                val baseline = requireNotNull(session.arenaBaselines[player.uniqueId])
                kitHealth.clear(player)
                baseline.restoreState(player)
                playerData.persist(player)
            }
        }.onSuccess {
            if (session == null || ownsEscrow(session, player.uniqueId)) playerStates.retain(stored).whenComplete { _, failure ->
                failure?.let {
                    DuelLog.warn("multiplayer-retain-failed", stored.escrow.matchId, player, "player={} error={}", player.name, it.message)
                }
            }
        }.onFailure { failure ->
            DuelLog.warn("multiplayer-restore-failed", stored.escrow.matchId, player, "player={} error={}", player.name, failure.message)
        }
    }

    private fun shutdownRestore(session: Session) {
        participants(session).forEach { player -> restoreDeparting(player, session.snapshots[player.uniqueId]) }
        releaseSession(session)
    }

    private fun releaseSession(session: Session) {
        if (!sessions.remove(session.match.id, session)) return
        session.objectiveTask?.cancel()
        session.match.roster.playerIds.forEach { byPlayer.remove(it.value, session.match.id) }
        session.leases.forEach { runCatching { it.close() } }
        runCatching { session.reservation.close() }
        session.origins?.forEach { (playerId, destination) ->
            if (destination != serverId) {
                Bukkit.getPlayer(playerId.value)?.takeIf(Player::isOnline)?.let { player ->
                    runCatching { networkReturn(player, destination) }
                        .onFailure { failure ->
                            DuelLog.warn(
                                "multiplayer-return-failed",
                                session.match.id,
                                player,
                                "player={} destination={} error={}",
                                player.name,
                                destination.value,
                                failure.message,
                            )
                        }
                }
            }
        }
    }

    private fun abortBeforeStart(
        reservation: MultiplayerArenaReservation,
        snapshots: Map<UUID, StoredPlayerSnapshot>,
        players: Collection<Player>,
        origins: Map<PlayerId, ServerId>? = null,
        arenaBaselines: Map<UUID, PlayerSnapshot> = emptyMap(),
    ) {
        players.forEach { player ->
            val stored = snapshots[player.uniqueId] ?: return@forEach
            val foreign = origins?.get(PlayerId(player.uniqueId))?.let { it != serverId } == true
            if (foreign) {
                runCatching {
                    val baseline = requireNotNull(arenaBaselines[player.uniqueId])
                    kitHealth.clear(player)
                    baseline.restoreState(player)
                    playerData.persist(player)
                }.onFailure { failure ->
                    DuelLog.warn("multiplayer-foreign-restore-failed", stored.escrow.matchId, player, "error_type={} error={}", failure.javaClass.simpleName, failure.message)
                }
            } else {
                restoreDeparting(player, stored)
            }
        }
        reservation.close()
    }

    private fun prepareKit(player: Player, kit: DuelKit) {
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
        player.setItemOnCursor(ItemStack.empty())
        player.inventory.clear()
        player.inventory.armorContents = arrayOfNulls<ItemStack>(4)
        kit.items.forEach { (slot, item) -> player.inventory.setItem(slot, item.clone()) }
        player.inventory.setItemInOffHand(kit.offhand?.clone())
        player.inventory.helmet = kit.helmet?.clone()
        player.inventory.chestplate = kit.chestplate?.clone()
        player.inventory.leggings = kit.leggings?.clone()
        player.inventory.boots = kit.boots?.clone()
        check(kitHealth.enforce(player)) { "Could not isolate multiplayer kit health for ${player.uniqueId}" }
        player.health = minOf(KitHealthIsolation.VANILLA_MAX_HEALTH, player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0)
        player.updateInventory()
    }

    private fun acquireChunkTicket(location: Location): PaperChunkTicketLease {
        val world = requireNotNull(location.world)
        val key = PaperChunkKey(world.uid, location.blockX shr 4, location.blockZ shr 4)
        return when (val result = chunkTickets.acquire(key)) {
            is PaperChunkTicketAcquireResult.Acquired -> result.lease
            is PaperChunkTicketAcquireResult.Failed -> throw result.failure
            PaperChunkTicketAcquireResult.RegistryClosed -> error("Multiplayer chunk ticket registry is closed")
            PaperChunkTicketAcquireResult.WorldUnavailable -> error("Multiplayer arena world is unavailable")
        }
    }

    private fun participants(session: Session): List<Player> =
        session.match.roster.playerIds
            .filter { playerId -> byPlayer[playerId.value] == session.match.id }
            .mapNotNull { playerId -> Bukkit.getPlayer(playerId.value) }

    private fun captureRuntimePolicy(): RuntimePolicy {
        val settings = runtimeSettings()
        return RuntimePolicy(
            countdownSeconds = settings?.countdownSeconds ?: countdownSeconds,
            finishDelayTicks = settings?.multiplayerFinishDelayTicks ?: DEFAULT_FINISH_DELAY_TICKS,
        )
    }

    private companion object {
        val EXTERNAL_RESCUE_TELEPORT_CAUSES =
            setOf(PlayerTeleportEvent.TeleportCause.COMMAND, PlayerTeleportEvent.TeleportCause.PLUGIN)
        const val RESTORE_RETRY_TICKS = 60L
        const val DEFAULT_FINISH_DELAY_TICKS = 60L
    }
}
