package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import ru.arc.paper.network.BackendTransferResult
import ru.ruscrafting.duels.domain.ChallengeId
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.ChallengeRegistry
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.StatisticsRepository
import ru.ruscrafting.duels.redis.ChallengeMessageType
import ru.ruscrafting.duels.redis.CrossServerChallengeBus
import ru.ruscrafting.duels.redis.CrossServerChallengeMessage
import ru.ruscrafting.duels.redis.NetworkArenaDirectory
import ru.ruscrafting.duels.redis.ArenaChoice
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

class DuelController(
    private val plugin: JavaPlugin,
    private val challenges: ChallengeRegistry,
    private val sessions: DuelSessionManager,
    private val statistics: StatisticsRepository,
    private val locales: LocaleService,
    private val targets: DuelTargetDirectory,
    private val localServer: ServerId,
    private val serverNames: ServerDisplayNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning),
    private val challengeBus: CrossServerChallengeBus? = null,
    private val arenaDirectory: NetworkArenaDirectory? = null,
    private val transfer: PlayerTransfer? = null,
    private val playerDataReady: (Player) -> Boolean = { true },
    private val clock: Clock = Clock.systemUTC(),
    private val transferTimeout: Duration = Duration.ofSeconds(30),
    private val returnPolicy: PostMatchReturnPolicy = PostMatchReturnPolicy.PROMPT,
    private val automaticReturnTimeout: Duration = Duration.ofMinutes(2),
    private val arenaReturnPolicy: (ArenaId) -> PostMatchReturnPolicy? = { null },
    private val rematchWindow: Duration = Duration.ofMinutes(3),
    private val kitAvailable: (KitId) -> Boolean = { true },
    private val kitFingerprint: (KitId) -> String? = { null },
    private val arenaChoices: ((DuelRules) -> List<ArenaChoice>)? =
        arenaDirectory?.let { directory ->
            { rules -> directory.choices(rules, rules.kitId?.let(kitFingerprint)) }
        },
    private val runtimeSettings: () -> ArcDuelsRuntimeSettings? = { null },
) : AutoCloseable {
    private val contexts = ConcurrentHashMap<ChallengeId, ChallengeContext>()
    private val acceptedMatches = ConcurrentHashMap<ChallengeId, AcceptedMatch>()
    private val acceptedTasks = ConcurrentHashMap<ChallengeId, BukkitTask>()
    private val challengeExpiryTasks = ConcurrentHashMap<ChallengeId, BukkitTask>()
    private val expiredNotificationPlayers = ConcurrentHashMap.newKeySet<Pair<ChallengeId, UUID>>()
    private val transferRequests = ConcurrentHashMap.newKeySet<Pair<ChallengeId, PlayerId>>()
    private val transferFailureNotifications = ConcurrentHashMap.newKeySet<Pair<ChallengeId, PlayerId>>()
    private val originSnapshots = ConcurrentHashMap<Pair<ChallengeId, PlayerId>, java.util.concurrent.CompletableFuture<Unit>>()
    private val networkPendingPlayers = ConcurrentHashMap.newKeySet<PlayerId>()
    private val returnRoutes = ConcurrentHashMap<MatchId, ReturnRoutes>()
    private val returnTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val returnRequests = ConcurrentHashMap.newKeySet<Pair<MatchId, PlayerId>>()
    private val returnFailureNotifications = ConcurrentHashMap.newKeySet<Pair<MatchId, PlayerId>>()
    private val returnOffers = ConcurrentHashMap<PlayerId, ReturnOffer>()
    private val networkSubscription = challengeBus?.subscribe(::onNetworkMessage)
    private val completionSubscription = sessions.onCompleted(::onMatchCompleted)
    private val playerComponents = DuelPlayerComponents(statistics, locales)

    /** Accepted challenges retain their original kit and arena ids until routing completes. */
    internal fun activeAcceptedMatchCount(): Int = acceptedMatches.size

    init {
        require(!transferTimeout.isNegative && !transferTimeout.isZero) { "Network transfer timeout must be positive" }
        require(!rematchWindow.isNegative && !rematchWindow.isZero && rematchWindow <= Duration.ofMinutes(15)) {
            "Rematch window must be between 1 millisecond and 15 minutes"
        }
        require(automaticReturnTimeout in Duration.ofSeconds(30)..Duration.ofMinutes(10)) {
            "Automatic return timeout must be between 30 seconds and 10 minutes"
        }
        require(challengeBus == null || (arenaDirectory != null && transfer != null)) {
            "Cross-server challenges require arena discovery and a player transfer gateway"
        }
    }

    fun challenge(
        challenger: Player,
        requestedTarget: DuelTarget,
        rules: DuelRules,
        arenaSelection: ArenaSelection? = null,
    ) = challengeInternal(challenger, requestedTarget, rules, arenaSelection, null)

    internal fun hasReturnOffer(player: Player): Boolean = returnOffers.containsKey(PlayerId(player.uniqueId))

    private fun challengeInternal(
        challenger: Player,
        requestedTarget: DuelTarget,
        rules: DuelRules,
        arenaSelection: ArenaSelection?,
        recoveryMatchId: MatchId?,
    ) {
        DuelLog.debug(
            "challenge-request",
            challenger,
            "challenger={} target={} mode={} objective={} best_of={} selected_arena={}",
            challenger.name,
            requestedTarget.name,
            rules.mode,
            rules.objective,
            rules.bestOf,
            arenaSelection?.let { "${it.serverId.value}:${it.arenaId.value}" } ?: "auto",
        )
        val target = targets.find(requestedTarget.uniqueId)
        if (target == null || target.uniqueId == challenger.uniqueId) {
            challenger.sendMessage(locales.notice(challenger, "error.player-left"))
            return
        }
        val localTarget = plugin.server.getPlayer(target.uniqueId)
        if (sessions.isEngaged(challenger) || sessions.isStateLocked(challenger) || PlayerId(challenger.uniqueId) in networkPendingPlayers ||
            (localTarget != null &&
                (sessions.isEngaged(localTarget) || sessions.isStateLocked(localTarget) || PlayerId(localTarget.uniqueId) in networkPendingPlayers))
        ) {
            challenger.sendMessage(locales.notice(challenger, "controller.busy"))
            return
        }
        val capturedKitFingerprint = rules.kitId?.let(kitFingerprint)
        if ((challengeBus != null || arenaDirectory != null) && rules.kitId != null && capturedKitFingerprint == null) {
            challenger.sendMessage(locales.notice(challenger, "controller.failed"))
            return
        }
        runCatching { challenges.create(PlayerId(challenger.uniqueId), PlayerId(target.uniqueId), rules, arenaSelection) }
            .onSuccess { challenge ->
                DuelLog.info(
                    "challenge-created",
                    MatchId(challenge.id.value),
                    challenger,
                    "challenger={} target={} target_server={} expires_at={}",
                    challenger.name,
                    target.name,
                    target.server.value,
                    challenge.expiresAt,
                )
                val context =
                    ChallengeContext(
                        challengerName = challenger.name,
                        targetName = target.name,
                        challengerRoute = participantRoute(localServer, returnOffers[challenge.challenger]?.destination),
                        targetRoute = participantRoute(target.server, returnOffers[challenge.target]?.destination),
                        expiresAtMillis = challenge.expiresAt.toEpochMilli(),
                        recoveryMatchId = recoveryMatchId,
                        kitFingerprint = capturedKitFingerprint,
                    )
                rememberContext(challenge.id, context)
                if (localTarget != null) {
                    notifyChallenge(localTarget, challenger.name, challenge)
                } else {
                    val bus = challengeBus ?: run {
                        challenges.registerResolution(challenge.resolve(ChallengeStatus.CANCELLED, clock.instant()))
                        challenger.sendMessage(locales.notice(challenger, "controller.network-unavailable"))
                        return@onSuccess
                    }
                    val published = publishMessage(
                        bus,
                        CrossServerChallengeMessage(
                            messageId = "${challenge.id}:offer",
                            sourceServer = localServer,
                            type = ChallengeMessageType.OFFER,
                            challenge = challenge,
                            kitFingerprint = context.kitFingerprint,
                            challengerName = context.challengerName,
                            targetName = context.targetName,
                            challengerServer = context.challengerRoute.originServer,
                            targetServer = context.targetRoute.originServer,
                            matchServer = null,
                            challengerCurrentServer = context.challengerRoute.currentServer,
                            targetCurrentServer = context.targetRoute.currentServer,
                            recoveryMatchId = context.recoveryMatchId,
                        ),
                    )
                    if (!published) {
                        runCatching {
                            challenges.resolve(challenge.id, challenge.challenger, ChallengeStatus.CANCELLED)
                        }
                        contexts.remove(challenge.id)
                        challenger.sendMessage(locales.notice(challenger, "controller.network-unavailable"))
                        return@onSuccess
                    }
                }
                sendPlayerNotice(challenger, target.uniqueId, target.name, "controller.sent", challenge.id)
                scheduleChallengeExpiry(challenge)
            }
            .onFailure {
                DuelLog.warn("challenge-create-failed", challenger, "target={} error={}", target.name, it.message)
                challenger.sendMessage(locales.notice(challenger, "controller.failed"))
            }
    }

    fun accept(
        player: Player,
        challengeId: ChallengeId? = null,
    ) {
        val challenge = resolveCandidate(player, challengeId, incoming = true) ?: return
        DuelLog.debug("challenge-accept-request", MatchId(challenge.id.value), player, "player={}", player.name)
        if (challenge.challenger in networkPendingPlayers || challenge.target in networkPendingPlayers) {
            player.sendMessage(locales.notice(player, "controller.busy"))
            return
        }
        val bothLocal = participants(challenge).size == 2
        if (!bothLocal && challengeBus == null) {
            player.sendMessage(locales.notice(player, "controller.network-unavailable"))
            return
        }
        val requiredKitFingerprint = contexts[challenge.id]?.kitFingerprint
        val matchServer =
            arenaDirectory?.select(challenge.rules, requiredKitFingerprint, challenge.arenaSelection)
                ?: challenge.arenaSelection?.serverId?.takeIf { arenaDirectory == null && it == localServer }
        if ((arenaDirectory != null || challenge.arenaSelection != null) && matchServer == null) {
            player.sendMessage(locales.notice(player, "controller.no-network-arena"))
            return
        }
        val accepted =
            runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), ChallengeStatus.ACCEPTED) }
                .getOrElse {
                    player.sendMessage(locales.notice(player, "controller.failed"))
                    return
                }
        cancelChallengeExpiry(challenge.id)
        if (accepted.status != ChallengeStatus.ACCEPTED) {
            DuelLog.info("challenge-expired-during-accept", MatchId(accepted.id.value), player, "status={}", accepted.status)
            announceExpired(accepted)
            return
        }
        DuelLog.info(
            "challenge-accepted",
            MatchId(accepted.id.value),
            player,
            "host={} both_local={} mode={} objective={} selected_arena={}",
            matchServer?.value ?: localServer.value,
            bothLocal,
            accepted.rules.mode,
            accepted.rules.objective,
            accepted.arenaSelection?.arenaId?.value ?: "auto",
        )
        val continuesFromArenaLobby =
            returnOffers.containsKey(challenge.challenger) || returnOffers.containsKey(challenge.target)
        if (shouldStartDirectLocalMatch(bothLocal, continuesFromArenaLobby, matchServer, localServer)) {
            scheduleAcceptedMatch(accepted, localServer)
        } else {
            if (!publishResolution(accepted, requireNotNull(matchServer))) {
                participants(accepted).forEach { it.sendMessage(locales.notice(it, "controller.network-unavailable")) }
            }
        }
    }

    fun deny(player: Player, challengeId: ChallengeId? = null) =
        resolveChallengeAction(player, challengeId, ChallengeStatus.DENIED)

    fun cancel(player: Player) =
        resolveChallengeAction(player, null, ChallengeStatus.CANCELLED)

    private fun resolveChallengeAction(
        player: Player,
        challengeId: ChallengeId?,
        status: ChallengeStatus,
    ) {
        val incoming = status == ChallengeStatus.DENIED
        val requestLog = if (incoming) "challenge-deny-request" else "challenge-cancel-request"
        val noticeKey = if (incoming) "controller.denied" else "controller.cancelled"
        val challenge = resolveCandidate(player, challengeId, incoming) ?: return
        DuelLog.info(requestLog, MatchId(challenge.id.value), player, "player={}", player.name)
        runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), status) }
            .onSuccess { resolved ->
                cancelChallengeExpiry(resolved.id)
                when {
                    resolved.status == ChallengeStatus.EXPIRED -> announceExpired(resolved)
                    participants(resolved).size == 2 || challengeBus == null ->
                        participants(resolved).forEach { it.sendMessage(locales.notice(it, noticeKey)) }
                    !publishResolution(resolved, null) ->
                        player.sendMessage(locales.notice(player, "controller.network-unavailable"))
                }
            }
            .onFailure { player.sendMessage(locales.notice(player, "controller.failed")) }
    }

    fun leave(player: Player) {
        DuelLog.info("leave-request", sessions.matchFor(player)?.id, player, "player={}", player.name)
        if (sessions.handleForfeit(player)) {
            player.sendMessage(locales.notice(player, "controller.forfeit"))
        } else {
            player.sendMessage(locales.notice(player, "controller.not-fighting"))
        }
    }

    fun returnToOrigin(player: Player) {
        val playerId = PlayerId(player.uniqueId)
        val offer = returnOffers[playerId]
        if (offer == null) {
            player.sendMessage(locales.notice(player, "controller.no-return-offer"))
            return
        }
        if (offer.destination == localServer) {
            if (sessions.requestRecovery(player)) {
                returnOffers.remove(playerId, offer)
            } else {
                player.sendMessage(locales.notice(player, "controller.no-return-offer"))
            }
            return
        }
        locales.optionalNotice(
            player,
            "controller.network-return",
            LocaleService.component("server", serverNames.display(offer.destination)),
        )?.let {
            player.sendMessage(it)
        }
        if (isSuccessfulBackendTransfer(transferPlayer(player, offer.destination, offer.matchId, "return-transfer-failed"))) {
            returnOffers.remove(playerId, offer)
        }
    }

    fun handleQuit(player: Player) {
        returnOffers.remove(PlayerId(player.uniqueId))
    }

    fun showStatistics(
        viewer: Player,
        target: DuelTarget,
    ) {
        statistics.find(PlayerId(target.uniqueId)).whenComplete { stats, failure ->
            runSync {
                if (failure != null) {
                    viewer.sendMessage(locales.notice(viewer, "controller.stats-failed"))
                    return@runSync
                }
                viewer.sendMessage(
                    locales.notice(
                        viewer,
                        "controller.stats",
                        LocaleService.component(
                            "player",
                            playerComponents.component(viewer, target.uniqueId, target.name, stats),
                        ),
                        LocaleService.text("rating", stats.rating),
                        LocaleService.text("wins", stats.wins),
                        LocaleService.text("losses", stats.losses),
                        LocaleService.text("winrate", "%.1f".format(java.util.Locale.ROOT, stats.winRate * 100)),
                        LocaleService.text("streak", stats.currentWinStreak),
                        LocaleService.text("best", stats.bestWinStreak),
                    ),
                )
            }
        }
    }

    fun rematch(
        player: Player,
        matchId: MatchId? = null,
    ) {
        val playerId = PlayerId(player.uniqueId)
        val lookup =
            matchId?.let(statistics::findMatch)
                ?: statistics.recentMatches(playerId, 1).thenApply { matches -> matches.firstOrNull() }
        lookup.whenComplete { recorded, failure ->
            runSync {
                if (!player.isOnline) return@runSync
                if (failure != null || recorded == null) {
                    DuelLog.warn("rematch-lookup-failed", matchId, player, "error={}", failure?.message ?: "match-not-found")
                    player.sendMessage(locales.notice(player, "controller.rematch-unavailable"))
                    return@runSync
                }
                val outcome = recorded.outcome
                if (playerId != outcome.winner && playerId != outcome.loser) {
                    DuelLog.warn("rematch-access-denied", outcome.matchId, player, "player={}", player.name)
                    player.sendMessage(locales.notice(player, "controller.rematch-unavailable"))
                    return@runSync
                }
                val effectiveRematchWindow = runtimeSettings()?.rematchWindow ?: rematchWindow
                if (clock.instant().isAfter(outcome.completedAt.plus(effectiveRematchWindow))) {
                    player.sendMessage(locales.notice(player, "controller.rematch-expired"))
                    return@runSync
                }
                if (outcome.rules.kitId?.let { !kitAvailable(it) } == true) {
                    DuelLog.warn(
                        "rematch-kit-unavailable",
                        outcome.matchId,
                        player,
                        "kit={}",
                        outcome.rules.kitId?.value,
                    )
                    player.sendMessage(locales.notice(player, "controller.rematch-unavailable"))
                    return@runSync
                }
                val opponentId = recorded.opponentOf(playerId)
                val target = targets.find(opponentId.value)
                if (target == null) {
                    player.sendMessage(locales.notice(player, "error.player-left"))
                    return@runSync
                }
                val arenaId = outcome.arenaId
                if (arenaId == null) {
                    player.sendMessage(locales.notice(player, "controller.rematch-arena-unavailable"))
                    return@runSync
                }
                val selection = ArenaSelection(outcome.serverId, arenaId)
                if (arenaChoices?.invoke(outcome.rules)?.none { it.selection == selection } == true) {
                    val choose =
                        locales.component(player, "controller.rematch-choose")
                            .clickEvent(ClickEvent.runCommand("/duel ${target.name}"))
                            .hoverEvent(HoverEvent.showText(locales.component(player, "controller.rematch-choose-hover")))
                    player.sendMessage(
                        locales.notice(
                            player,
                            "controller.rematch-arena-missing",
                            LocaleService.component("action", choose),
                        ),
                    )
                    return@runSync
                }
                val pending =
                    challenges.pendingFor(playerId).firstOrNull { challenge ->
                        setOf(challenge.challenger, challenge.target) == setOf(playerId, opponentId) &&
                            challenge.rules == outcome.rules && challenge.arenaSelection == selection
                    }
                if (pending != null) {
                    if (pending.target == playerId) {
                        DuelLog.info("rematch-mutual-confirm", outcome.matchId, player, "challenge={}", pending.id)
                        accept(player, pending.id)
                    } else {
                        player.sendMessage(locales.notice(player, "controller.rematch-pending"))
                    }
                    return@runSync
                }
                DuelLog.info(
                    "rematch-request",
                    outcome.matchId,
                    player,
                    "player={} opponent={} arena={}:{}",
                    player.name,
                    target.name,
                    selection.serverId.value,
                    selection.arenaId.value,
                )
                val recoveryMatchId =
                    continuationRecoveryMatchId(
                        outcome.matchId,
                        returnOffers[playerId],
                        returnOffers[opponentId],
                    )
                challengeInternal(player, target, outcome.rules, selection, recoveryMatchId)
            }
        }
    }

    private fun continuationRecoveryMatchId(
        completedMatchId: MatchId,
        first: ReturnOffer?,
        second: ReturnOffer?,
    ): MatchId? =
        continuationRecoveryMatchId(
            completedMatchId,
            first?.matchId,
            first?.recoveryMatchId,
            second?.matchId,
            second?.recoveryMatchId,
        )

    override fun close() {
        networkSubscription?.close()
        completionSubscription.close()
        acceptedTasks.values.forEach(BukkitTask::cancel)
        challengeExpiryTasks.values.forEach(BukkitTask::cancel)
        returnTasks.values.forEach(BukkitTask::cancel)
        acceptedTasks.clear()
        challengeExpiryTasks.clear()
        expiredNotificationPlayers.clear()
        returnTasks.clear()
        acceptedMatches.clear()
        transferRequests.clear()
        transferFailureNotifications.clear()
        originSnapshots.clear()
        networkPendingPlayers.clear()
        returnRequests.clear()
        returnFailureNotifications.clear()
        returnOffers.clear()
        returnRoutes.clear()
        contexts.clear()
    }

    private fun onNetworkMessage(message: CrossServerChallengeMessage) {
        runSync {
            when (message.type) {
                ChallengeMessageType.OFFER -> receiveOffer(message)
                ChallengeMessageType.RESOLUTION -> receiveResolution(message)
            }
        }
    }

    private fun receiveOffer(message: CrossServerChallengeMessage) {
        val target = plugin.server.getPlayer(message.challenge.target.value) ?: return
        if (!clock.instant().isBefore(message.challenge.expiresAt)) return
        DuelLog.debug(
            "challenge-network-offer",
            MatchId(message.challenge.id.value),
            target,
            "source={} challenger={} target={}",
            message.sourceServer.value,
            message.challengerName,
            message.targetName,
        )
        rememberContext(
            message.challenge.id,
            ChallengeContext(
                challengerName = message.challengerName,
                targetName = message.targetName,
                challengerRoute = ParticipantRoute(message.challengerCurrentServer, message.challengerServer),
                targetRoute = participantRoute(localServer, returnOffers[message.challenge.target]?.destination ?: message.targetServer),
                expiresAtMillis = message.challenge.expiresAt.toEpochMilli(),
                recoveryMatchId = message.recoveryMatchId,
                kitFingerprint = message.kitFingerprint,
            ),
        )
        if (sessions.isEngaged(target) || sessions.isStateLocked(target) || PlayerId(target.uniqueId) in networkPendingPlayers) {
            if (!publishResolution(message.challenge.resolve(ChallengeStatus.DENIED, clock.instant()), null)) {
                target.sendMessage(locales.notice(target, "controller.network-unavailable"))
            }
            return
        }
        runCatching { challenges.register(message.challenge) }
            .onSuccess { registered ->
                notifyChallenge(target, message.challengerName, registered)
                scheduleChallengeExpiry(registered, broadcast = false)
            }
            .onFailure {
                plugin.logger.warning("Rejected network challenge ${message.challenge.id}: ${it.message}")
                if (!publishResolution(message.challenge.resolve(ChallengeStatus.DENIED, clock.instant()), null)) {
                    target.sendMessage(locales.notice(target, "controller.network-unavailable"))
                }
            }
    }

    private fun receiveResolution(message: CrossServerChallengeMessage) {
        DuelLog.debug(
            "challenge-network-resolution",
            MatchId(message.challenge.id.value),
            "source={} status={} match_server={}",
            message.sourceServer.value,
            message.challenge.status,
            message.matchServer?.value,
        )
        runCatching { challenges.registerResolution(message.challenge) }
            .getOrElse {
                plugin.logger.warning("Rejected network challenge resolution ${message.challenge.id}: ${it.message}")
                return
            }
        cancelChallengeExpiry(message.challenge.id)
        rememberContext(
            message.challenge.id,
            ChallengeContext(
                challengerName = message.challengerName,
                targetName = message.targetName,
                challengerRoute = ParticipantRoute(message.challengerCurrentServer, message.challengerServer),
                targetRoute = ParticipantRoute(message.targetCurrentServer, message.targetServer),
                expiresAtMillis = message.challenge.expiresAt.toEpochMilli(),
                recoveryMatchId = message.recoveryMatchId,
                kitFingerprint = message.kitFingerprint,
            ),
        )
        when (message.challenge.status) {
            ChallengeStatus.ACCEPTED -> acceptNetworkMatch(message)
            ChallengeStatus.DENIED -> participants(message.challenge).forEach { it.sendMessage(locales.notice(it, "controller.denied")) }
            ChallengeStatus.CANCELLED -> participants(message.challenge).forEach { it.sendMessage(locales.notice(it, "controller.cancelled")) }
            ChallengeStatus.EXPIRED -> notifyExpiredParticipants(message.challenge)
            ChallengeStatus.PENDING -> error("A resolution cannot be pending")
        }
    }

    private fun publishResolution(challenge: DuelChallenge, matchServer: ServerId?): Boolean {
        val bus = challengeBus ?: return false
        val existing = contexts[challenge.id]
        val observedChallenger = targets.find(challenge.challenger.value)?.server
        val observedTarget = targets.find(challenge.target.value)?.server
        val context =
            ChallengeContext(
                challengerName = existing?.challengerName ?: resolveName(challenge.challenger),
                targetName = existing?.targetName ?: resolveName(challenge.target),
                challengerRoute =
                    participantRoute(
                        existing?.challengerRoute?.currentServer ?: observedChallenger ?: localServer,
                        returnOffers[challenge.challenger]?.destination
                            ?: selectOriginServer(existing?.challengerRoute?.originServer, observedChallenger, localServer),
                    ),
                targetRoute =
                    participantRoute(
                        existing?.targetRoute?.currentServer ?: observedTarget ?: localServer,
                        returnOffers[challenge.target]?.destination
                            ?: selectOriginServer(existing?.targetRoute?.originServer, observedTarget, localServer),
                    ),
                expiresAtMillis = challenge.expiresAt.toEpochMilli(),
                recoveryMatchId = existing?.recoveryMatchId,
                kitFingerprint = existing?.kitFingerprint ?: challenge.rules.kitId?.let(kitFingerprint),
            )
        return publishMessage(
            bus,
            CrossServerChallengeMessage(
                messageId = "${challenge.id}:${challenge.status.name.lowercase()}:${localServer.value}",
                sourceServer = localServer,
                type = ChallengeMessageType.RESOLUTION,
                challenge = challenge,
                kitFingerprint = context.kitFingerprint,
                challengerName = context.challengerName,
                targetName = context.targetName,
                challengerServer = context.challengerRoute.originServer,
                targetServer = context.targetRoute.originServer,
                matchServer = matchServer,
                challengerCurrentServer = context.challengerRoute.currentServer,
                targetCurrentServer = context.targetRoute.currentServer,
                recoveryMatchId = context.recoveryMatchId,
            ),
        )
    }

    private fun publishMessage(
        bus: CrossServerChallengeBus,
        message: CrossServerChallengeMessage,
    ): Boolean =
        runCatching {
            bus.publish(message)
            true
        }.getOrElse { failure ->
            plugin.logger.warning(
                "Could not publish ArcDuels challenge ${message.messageId}: ${unwrap(failure).javaClass.simpleName}: ${unwrap(failure).message}",
            )
            false
        }

    private fun acceptNetworkMatch(message: CrossServerChallengeMessage) {
        val host = requireNotNull(message.matchServer)
        if (message.recoveryMatchId == null) {
            if (!returnLobbyPlayersForNewMatch(message.challenge)) return
        } else if (localServer == host) {
            participants(message.challenge).forEach { player ->
                player.sendMessage(locales.notice(player, "controller.rematch-return"))
            }
        }
        scheduleAcceptedMatch(message.challenge, host, message)
    }

    private fun returnLobbyPlayersForNewMatch(challenge: DuelChallenge): Boolean {
        var returned = true
        participants(challenge).forEach { player ->
            val playerId = PlayerId(player.uniqueId)
            val offer = returnOffers[playerId] ?: return@forEach
            player.sendMessage(
                locales.notice(
                    player,
                    "controller.rematch-return",
                    LocaleService.component("server", serverNames.display(offer.destination)),
                ),
            )
            if (offer.destination == localServer) {
                if (sessions.requestRecovery(player)) returnOffers.remove(playerId, offer)
            } else {
                val result = transferPlayer(player, offer.destination, offer.matchId, "rematch-return-transfer-failed")
                if (!isSuccessfulBackendTransfer(result)) returned = false
            }
        }
        return returned
    }

    private fun scheduleAcceptedMatch(
        challenge: DuelChallenge,
        host: ServerId,
        networkMessage: CrossServerChallengeMessage? = null,
    ) {
        if (networkMessage?.recoveryMatchId != null && localServer != host) return
        if (networkMessage != null && localServer != host &&
            localServer != networkMessage.challengerServer && localServer != networkMessage.targetServer
        ) {
            return
        }
        val effectiveTransferTimeout = runtimeSettings()?.transferTimeout ?: transferTimeout
        val accepted = AcceptedMatch(challenge, host, networkMessage, clock.millis() + effectiveTransferTimeout.toMillis())
        DuelLog.info(
            "match-routing-start",
            MatchId(challenge.id.value),
            "local_server={} host={} network={} timeout_ms={}",
            localServer.value,
            host.value,
            networkMessage != null,
            effectiveTransferTimeout.toMillis(),
        )
        acceptedMatches.putIfAbsent(challenge.id, accepted)
        // Only the arena host must suppress join-time recovery while transferred
        // participants arrive. Origin nodes must remain ready to recover a player
        // who returns quickly after a short or cancelled match.
        if (networkMessage != null && localServer == host) sessions.expectNetworkMatch(challenge)
        networkPendingPlayers += challenge.challenger
        networkPendingPlayers += challenge.target
        if (acceptedTasks.containsKey(challenge.id)) return
        val task =
            plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable { tickAcceptedMatch(challenge.id) },
                1L,
                NETWORK_MATCH_POLL_TICKS,
            )
        acceptedTasks.putIfAbsent(challenge.id, task)?.let { task.cancel() }
    }

    private fun tickAcceptedMatch(challengeId: ChallengeId) {
        val accepted = acceptedMatches[challengeId] ?: return stopAcceptedMatch(challengeId)
        val challenge = accepted.challenge
        if (clock.millis() >= accepted.expiresAtMillis) {
            participants(challenge).forEach { it.sendMessage(locales.notice(it, "controller.network-timeout")) }
            returnAcceptedPlayers(accepted)
            stopAcceptedMatch(challengeId)
            return
        }
        val localParticipants = participants(challenge)
        if (accepted.networkMessage != null) {
            val originPreparation = prepareLocalOriginSnapshots(accepted, localParticipants)
            if (originPreparation == OriginPreparation.WAIT) return
            if (originPreparation == OriginPreparation.FAILED) {
                returnAcceptedPlayers(accepted)
                stopAcceptedMatch(challengeId)
                return
            }
        }
        if (localServer != accepted.host) {
            if (localParticipants.isEmpty() && transferRequests.any { it.first == challengeId }) {
                stopAcceptedMatch(challengeId)
                return
            }
            localParticipants.forEach { player ->
                val request = challengeId to PlayerId(player.uniqueId)
                if (transferRequests.add(request)) {
                    val reportFailure = request !in transferFailureNotifications
                    if (reportFailure) {
                        DuelLog.info(
                            "player-transfer-request",
                            MatchId(challenge.id.value),
                            player,
                            "player={} from={} to={}",
                            player.name,
                            localServer.value,
                            accepted.host.value,
                        )
                        player.sendMessage(
                            locales.notice(
                                player,
                                "controller.network-transfer",
                                LocaleService.component("server", serverNames.display(accepted.host)),
                            ),
                        )
                    }
                    val result = transferPlayer(
                        player,
                        accepted.host,
                        MatchId(challenge.id.value),
                        "match-transfer-failed",
                        reportFailure,
                    )
                    if (!retainSuccessfulTransferRequest(transferRequests, request, result)) {
                        transferFailureNotifications += request
                    }
                }
            }
            return
        }
        if (localParticipants.size != 2) return
        val decision =
            acceptedMatchDecision(
                localParticipants.map { participant ->
                    AcceptedParticipantReadiness(
                        stateLocked =
                            returnOffers[PlayerId(participant.uniqueId)]
                                ?.takeUnless { offer -> offer.recoveryMatchId == accepted.networkMessage?.recoveryMatchId } != null ||
                                (sessions.isStateLocked(participant) &&
                                    !sessions.hasOriginSnapshot(participant, challenge)),
                        playerDataReady = playerDataReady(participant),
                        engaged = sessions.isEngaged(participant),
                    )
                },
            )
        when (decision) {
            AcceptedMatchDecision.WAIT -> return
            AcceptedMatchDecision.CANCEL_BUSY -> {
                stopAcceptedMatch(challengeId)
                return
            }
            AcceptedMatchDecision.START -> Unit
        }
        stopAcceptedMatch(challengeId)
        startLocal(challenge, accepted.networkMessage)
    }

    private fun stopAcceptedMatch(challengeId: ChallengeId) {
        acceptedMatches.remove(challengeId)?.challenge?.let { challenge ->
            networkPendingPlayers -= challenge.challenger
            networkPendingPlayers -= challenge.target
            sessions.stopExpectingNetworkMatch(challenge)
        }
        acceptedTasks.remove(challengeId)?.cancel()
        transferRequests.removeIf { it.first == challengeId }
        transferFailureNotifications.removeIf { it.first == challengeId }
        originSnapshots.keys.removeIf { it.first == challengeId }
    }

    private fun startLocal(
        challenge: DuelChallenge,
        networkMessage: CrossServerChallengeMessage? = null,
    ) {
        // Capture alongside DuelSessionManager.start/startNetwork. Completion may wait on durable
        // storage, player transfer, or teleports while a newer config generation is published.
        val capturedSettings = runtimeSettings()
        val capturedReturnPolicy = capturedSettings?.defaultPostMatchReturnPolicy ?: returnPolicy
        val capturedAutomaticReturnTimeout = capturedSettings?.automaticReturnTimeout ?: automaticReturnTimeout
        DuelLog.info(
            "match-start-request",
            MatchId(challenge.id.value),
            "server={} network={} challenger={} target={}",
            localServer.value,
            networkMessage != null,
            challenge.challenger.value,
            challenge.target.value,
        )
        val start =
            if (networkMessage == null) {
                runCatching { sessions.start(challenge) }
            } else {
                runCatching {
                    challenge.rules.kitId?.let { kitId ->
                        check(kitFingerprint(kitId) == networkMessage.kitFingerprint) {
                            "The selected arena host no longer has the captured kit generation"
                        }
                    }
                    sessions.startNetwork(
                        challenge,
                        mapOf(
                            challenge.challenger to networkMessage.challengerServer,
                            challenge.target to networkMessage.targetServer,
                        ),
                        networkMessage.recoveryMatchId,
                    )
                }
            }
        start
            .getOrElse { failure -> java.util.concurrent.CompletableFuture.failedFuture(failure) }
            .whenComplete { match, failure ->
                runSync {
                    if (failure != null) {
                        val cause = unwrap(failure)
                        DuelLog.warn(
                            "match-start-failed",
                            MatchId(challenge.id.value),
                            "error_type={} error={}",
                            cause.javaClass.simpleName,
                            cause.message,
                        )
                        participants(challenge).forEach {
                            val reasonKey = if (cause is CancellationException) "controller.wait-cancelled" else "controller.start-internal"
                            it.sendMessage(locales.notice(it, "controller.start-failed", LocaleService.component("reason", locales.component(it, reasonKey))))
                        }
                        networkMessage?.let {
                            returnAcceptedPlayers(
                                AcceptedMatch(challenge, requireNotNull(it.matchServer), it, clock.millis()),
                            )
                        }
                    } else if (networkMessage != null) {
                        DuelLog.info("match-started", requireNotNull(match).id, "network=true server={}", localServer.value)
                        returnRoutes[requireNotNull(match).id] =
                            ReturnRoutes(
                                byPlayer =
                                    mapOf(
                                        networkMessage.challenge.challenger to networkMessage.challengerServer,
                                        networkMessage.challenge.target to networkMessage.targetServer,
                                    ),
                                recoveryMatchId = networkMessage.recoveryMatchId ?: requireNotNull(match).id,
                                returnPolicy = capturedReturnPolicy,
                                automaticReturnTimeout = capturedAutomaticReturnTimeout,
                            )
                        listOf(challenge.challenger, challenge.target).forEach { playerId ->
                            returnOffers[playerId]?.takeIf { it.recoveryMatchId == networkMessage.recoveryMatchId }
                                ?.let { returnOffers.remove(playerId, it) }
                        }
                    }
                }
            }
    }

    private fun prepareLocalOriginSnapshots(
        accepted: AcceptedMatch,
        localParticipants: List<Player>,
    ): OriginPreparation {
        val message = requireNotNull(accepted.networkMessage)
        if (message.recoveryMatchId != null) return OriginPreparation.READY
        val localOrigins =
            localParticipants.filter { player ->
                val playerId = PlayerId(player.uniqueId)
                originFor(message, playerId) == localServer
            }
        if (localOrigins.any { !playerDataReady(it) || (sessions.isStateLocked(it) && !sessions.hasOriginSnapshot(it, accepted.challenge)) }) {
            return OriginPreparation.WAIT
        }
        localOrigins.forEach { player ->
            val playerId = PlayerId(player.uniqueId)
            val key = accepted.challenge.id to playerId
            originSnapshots.computeIfAbsent(key) {
                if (sessions.hasOriginSnapshot(player, accepted.challenge)) {
                    java.util.concurrent.CompletableFuture.completedFuture(Unit)
                } else {
                    runCatching { sessions.storeOriginSnapshot(accepted.challenge, player).thenApply { Unit } }
                        .getOrElse { java.util.concurrent.CompletableFuture.failedFuture(it) }
                }
            }
        }
        val required = localOrigins.map { accepted.challenge.id to PlayerId(it.uniqueId) }
        if (required.any { originSnapshots[it]?.isCompletedExceptionally == true }) {
            localParticipants.forEach { player ->
                player.sendMessage(
                    locales.notice(
                        player,
                        "controller.start-failed",
                        LocaleService.component("reason", locales.component(player, "controller.start-internal")),
                    ),
                )
            }
            plugin.logger.severe("Origin inventory snapshot failed for network challenge ${accepted.challenge.id}; no affected player will be transferred")
            return OriginPreparation.FAILED
        }
        return if (required.all { originSnapshots[it]?.isDone == true }) OriginPreparation.READY else OriginPreparation.WAIT
    }

    private fun returnAcceptedPlayers(accepted: AcceptedMatch) {
        val message = accepted.networkMessage ?: return
        if (message.recoveryMatchId != null) return
        participants(accepted.challenge).forEach { player ->
            val destination = originFor(message, PlayerId(player.uniqueId))
            if (destination == localServer) {
                sessions.requestRecovery(player)
            } else {
                val result =
                    transferPlayer(
                        player,
                        destination,
                        MatchId(accepted.challenge.id.value),
                        "cancel-return-transfer-failed",
                    )
                if (!isSuccessfulBackendTransfer(result)) sessions.requestRecovery(player)
            }
        }
    }

    private fun transferPlayer(
        player: Player,
        destination: ServerId,
        matchId: MatchId,
        action: String,
        reportFailure: Boolean = true,
    ): BackendTransferResult? {
        val result = transfer?.connect(player, destination)
        if (result == BackendTransferResult.SENT) return result
        if (reportFailure) {
            DuelLog.warn(
                action,
                matchId,
                player,
                "player={} destination={} result={}",
                player.name,
                destination.value,
                result?.name ?: "UNAVAILABLE",
            )
            player.sendMessage(locales.notice(player, "controller.network-unavailable"))
        }
        return result
    }

    private fun originFor(
        message: CrossServerChallengeMessage,
        playerId: PlayerId,
    ): ServerId =
        when (playerId) {
            message.challenge.challenger -> message.challengerServer
            message.challenge.target -> message.targetServer
            else -> error("Player $playerId is not part of challenge ${message.challenge.id}")
        }

    private fun onMatchCompleted(match: DuelMatch) {
        val routes = returnRoutes.remove(match.id)
        val effectiveReturnPolicy =
            arenaReturnPolicy(match.arenaId) ?: routes?.returnPolicy
                ?: runtimeSettings()?.defaultPostMatchReturnPolicy ?: returnPolicy
        val promptedRoutes = routes?.takeIf { effectiveReturnPolicy == PostMatchReturnPolicy.PROMPT }
        promptedRoutes?.byPlayer?.forEach { (playerId, destination) ->
            returnOffers[playerId] = ReturnOffer(match.id, destination, promptedRoutes.recoveryMatchId)
        }
        offerMatchSummary(match, promptedRoutes)
        if (routes == null) return
        if (effectiveReturnPolicy == PostMatchReturnPolicy.PROMPT) {
            return
        }
        val deadline = clock.millis() + routes.automaticReturnTimeout.toMillis()
        val task =
            plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable { tickReturn(match, routes, deadline) },
                RETURN_DELAY_TICKS,
                NETWORK_MATCH_POLL_TICKS,
            )
        returnTasks.putIfAbsent(match.id, task)?.let { task.cancel() }
    }

    private fun offerMatchSummary(match: DuelMatch, routes: ReturnRoutes?) {
        val effectiveRematchWindow = runtimeSettings()?.rematchWindow ?: rematchWindow
        listOf(match.firstPlayer, match.secondPlayer).forEach { playerId ->
            val player = plugin.server.getPlayer(playerId.value) ?: return@forEach
            val opponentId = match.opponentOf(playerId)
            val opponentName = resolveName(opponentId)
            playerComponents.load(player, opponentId.value, opponentName).whenComplete { opponent, _ ->
                runSync {
                    if (!player.isOnline) return@runSync
                    val action =
                        locales.component(player, "controller.rematch-action")
                            .clickEvent(ClickEvent.runCommand("/duel rematch ${match.id}"))
                            .hoverEvent(
                                HoverEvent.showText(
                                    locales.component(
                                        player,
                                        "controller.rematch-hover",
                                        LocaleService.text("seconds", effectiveRematchWindow.seconds.coerceAtLeast(1L)),
                                    ),
                                ),
                            )
                    val actions =
                        routes?.forPlayer(playerId)?.let { destination ->
                            val returnAction =
                                locales.component(
                                    player,
                                    "controller.return-action",
                                    LocaleService.component("server", serverNames.display(destination)),
                                ).clickEvent(ClickEvent.runCommand("/duel return"))
                                    .hoverEvent(HoverEvent.showText(locales.component(player, "controller.return-hover")))
                            action.append(Component.newline()).append(returnAction)
                        } ?: action
                    player.sendMessage(
                        locales.notice(
                            player,
                            if (match.rules.bestOf == 1) "controller.match-summary-single" else "controller.match-summary-series",
                            LocaleService.component(
                                "result",
                                locales.component(
                                    player,
                                    if (playerId == match.winner) "controller.result-win" else "controller.result-loss",
                                    LocaleService.component("player", requireNotNull(opponent)),
                                ),
                            ),
                            LocaleService.text("score", viewerScore(match, playerId)),
                            LocaleService.component("actions", actions),
                            LocaleService.text("seconds", effectiveRematchWindow.seconds.coerceAtLeast(1L)),
                        ),
                    )
                }
            }
        }
    }

    private fun tickReturn(match: DuelMatch, routes: ReturnRoutes, deadline: Long) {
        if (clock.millis() >= deadline) {
            stopReturn(match.id)
            return
        }
        val destinations =
            mapOf(
                match.firstPlayer to routes.forPlayer(match.firstPlayer),
                match.secondPlayer to routes.forPlayer(match.secondPlayer),
            )
        destinations.forEach { (playerId, destination) ->
            if (destination == localServer) {
                returnRequests += match.id to playerId
                return@forEach
            }
            val player = plugin.server.getPlayer(playerId.value) ?: return@forEach
            if (sessions.isStateLocked(player)) return@forEach
            val request = match.id to playerId
            if (returnRequests.add(request)) {
                val reportFailure = request !in returnFailureNotifications
                if (reportFailure) {
                    locales.optionalNotice(
                        player,
                        "controller.network-return",
                        LocaleService.component("server", serverNames.display(destination)),
                    )?.let { player.sendMessage(it) }
                }
                val result =
                    transferPlayer(
                        player,
                        destination,
                        match.id,
                        "automatic-return-transfer-failed",
                        reportFailure,
                    )
                if (!retainSuccessfulTransferRequest(returnRequests, request, result)) {
                    returnFailureNotifications += request
                }
            }
        }
        if (destinations.keys.all { (match.id to it) in returnRequests }) stopReturn(match.id)
    }

    private fun stopReturn(matchId: MatchId) {
        returnTasks.remove(matchId)?.cancel()
        returnRequests.removeIf { it.first == matchId }
        returnFailureNotifications.removeIf { it.first == matchId }
    }

    private fun resolveCandidate(player: Player, challengeId: ChallengeId?, incoming: Boolean): DuelChallenge? {
        val playerId = PlayerId(player.uniqueId)
        val pending = challenges.pendingFor(playerId)
        val candidate = challengeId?.let(challenges::find)
            ?: pending.lastOrNull { if (incoming) it.target == playerId else it.challenger == playerId }
        if (candidate != null && if (incoming) candidate.target != playerId else candidate.challenger != playerId) {
            player.sendMessage(locales.notice(player, if (incoming) "controller.no-incoming" else "controller.no-outgoing"))
            return null
        }
        if (candidate?.status == ChallengeStatus.EXPIRED) {
            sendExpiredNotice(player, candidate, once = false)
            return null
        }
        if (candidate == null || candidate.status != ChallengeStatus.PENDING) {
            player.sendMessage(locales.notice(player, if (incoming) "controller.no-incoming" else "controller.no-outgoing"))
            return null
        }
        return candidate
    }

    private fun scheduleChallengeExpiry(challenge: DuelChallenge, broadcast: Boolean = true) {
        val delay = challengeExpiryDelayTicks(clock.millis(), challenge.expiresAt.toEpochMilli())
        DuelLog.debug(
            "challenge-expiry-scheduled",
            MatchId(challenge.id.value),
            "delay_ticks={} broadcast={}",
            delay,
            broadcast,
        )
        val task =
            plugin.server.scheduler.runTaskLater(
                plugin,
                Runnable {
                    challengeExpiryTasks.remove(challenge.id)
                    val expired = challenges.expireIfDue(challenge.id) ?: return@Runnable
                    when (expired.status) {
                        ChallengeStatus.PENDING -> scheduleChallengeExpiry(expired, broadcast)
                        ChallengeStatus.EXPIRED -> {
                            DuelLog.info(
                                "challenge-expired",
                                MatchId(expired.id.value),
                                "challenger={} target={} broadcast={}",
                                expired.challenger.value,
                                expired.target.value,
                                broadcast,
                            )
                            announceExpired(expired, broadcast)
                        }
                        else -> Unit
                    }
                },
                delay,
            )
        challengeExpiryTasks.put(challenge.id, task)?.cancel()
    }

    private fun cancelChallengeExpiry(challengeId: ChallengeId) {
        challengeExpiryTasks.remove(challengeId)?.cancel()
    }

    private fun announceExpired(challenge: DuelChallenge, broadcast: Boolean = true) {
        if (challengeBus == null || !broadcast) {
            notifyExpiredParticipants(challenge)
        } else if (!publishResolution(challenge, null)) {
            notifyExpiredParticipants(challenge)
        }
    }

    private fun notifyExpiredParticipants(challenge: DuelChallenge) {
        participants(challenge).forEach { sendExpiredNotice(it, challenge) }
    }

    private fun sendExpiredNotice(player: Player, challenge: DuelChallenge, once: Boolean = true) {
        if (once && !expiredNotificationPlayers.add(challenge.id to player.uniqueId)) return
        val opponent =
            when (PlayerId(player.uniqueId)) {
                challenge.challenger -> challenge.target
                challenge.target -> challenge.challenger
                else -> return
            }
        val name = resolveName(opponent)
        sendPlayerNotice(player, opponent.value, name, "controller.expired")
    }

    private fun notifyChallenge(recipient: Player, challengerName: String, challenge: DuelChallenge) {
        playerComponents.load(recipient, challenge.challenger.value, challengerName).whenComplete { challenger, _ ->
            runSync {
                if (!recipient.isOnline || challenges.find(challenge.id)?.status != ChallengeStatus.PENDING) return@runSync
                recipient.sendMessage(challengeMessage(recipient, requireNotNull(challenger), challenge))
                recipient.playSound(recipient.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f)
            }
        }
    }

    private fun challengeMessage(recipient: Player, challenger: Component, challenge: DuelChallenge): Component {
        val kitId = challenge.rules.kitId
        fun withHelp(component: Component, key: String): Component =
            component.hoverEvent(HoverEvent.showText(locales.component(recipient, key)))
        val loadout =
            if (kitId != null) {
                val key = "kit.${kitId.value}.name"
                val kitName =
                    if (locales.hasKey(locales.language(recipient), key)) locales.component(recipient, key)
                    else Component.text(kitId.value)
                withHelp(
                    locales.component(recipient, "controller.loadout-kit", LocaleService.component("kit", kitName)),
                    "controller.loadout-kit-hover",
                )
            } else {
                withHelp(locales.component(recipient, "controller.loadout-own"), "controller.loadout-own-hover")
            }
        val objectiveKey =
            when (challenge.rules.objective) {
                ru.ruscrafting.duels.domain.DuelObjectiveType.ELIMINATION -> "controller.objective-elimination"
                ru.ruscrafting.duels.domain.DuelObjectiveType.KING_OF_THE_HILL -> "controller.objective-koth"
                ru.ruscrafting.duels.domain.DuelObjectiveType.SUMO -> "controller.objective-sumo"
                ru.ruscrafting.duels.domain.DuelObjectiveType.BOXING -> "controller.objective-boxing"
                ru.ruscrafting.duels.domain.DuelObjectiveType.COMBO -> "controller.objective-combo"
            }
        val objective =
            withHelp(
                locales.component(recipient, objectiveKey),
                "$objectiveKey-hover",
            )
        val ranked =
            withHelp(
                locales.component(recipient, if (challenge.rules.ranked) "controller.ranked" else "controller.unranked"),
                if (challenge.rules.ranked) "controller.ranked-hover" else "controller.unranked-hover",
            )
        val bestOf =
            withHelp(
                locales.component(recipient, "controller.best-of", LocaleService.text("bestof", challenge.rules.bestOf)),
                "controller.best-of-hover",
            )
        fun state(value: Boolean) = locales.component(recipient, if (value) "controller.state-on" else "controller.state-off")
        fun toggleParameter(key: String, enabled: Boolean): Component =
            withHelp(
                locales.component(recipient, "controller.$key", LocaleService.component("state", state(enabled))),
                "controller.$key-hover",
            )
        val arena =
            challenge.arenaSelection?.let { selection ->
                val displayName =
                    arenaDirectory?.choices(challenge.rules, contexts[challenge.id]?.kitFingerprint)
                        ?.firstOrNull { it.selection == selection }
                        ?.displayName
                        ?: selection.arenaId.value
                withHelp(
                    locales.component(
                        recipient,
                        "controller.arena-selected",
                        LocaleService.text("arena", displayName),
                        LocaleService.component("server", serverNames.display(selection.serverId)),
                    ),
                    "controller.arena-selected-hover",
                )
            } ?: withHelp(locales.component(recipient, "controller.arena-auto"), "controller.arena-auto-hover")
        val ruleSummary =
            if (challenge.rules.objective.isHitRace) {
                val target =
                    if (challenge.rules.objective == ru.ruscrafting.duels.domain.DuelObjectiveType.BOXING) {
                        challenge.rules.modifiers.boxingHitsToWin
                    } else {
                        challenge.rules.modifiers.comboHitsToWin
                    }
                locales.component(
                    recipient,
                    "controller.hit-race-summary",
                    LocaleService.component(
                        "target",
                        withHelp(
                            locales.component(recipient, "controller.hit-target", LocaleService.text("hits", target)),
                            "controller.hit-target-hover",
                        ),
                    ),
                )
            } else {
                locales.component(
                    recipient,
                    "controller.combat-summary",
                    LocaleService.component(
                        "sudden",
                        withHelp(
                            locales.component(
                                recipient,
                                "controller.sudden-death",
                                LocaleService.text("seconds", challenge.rules.modifiers.suddenDeathAfterSeconds),
                            ),
                            "controller.sudden-death-hover",
                        ),
                    ),
                    LocaleService.component("projectiles", toggleParameter("projectiles", challenge.rules.modifiers.projectiles)),
                    LocaleService.component("consumables", toggleParameter("consumables", challenge.rules.modifiers.consumables)),
                    LocaleService.component("pearls", toggleParameter("pearls", challenge.rules.modifiers.enderPearls)),
                    LocaleService.component("regeneration", toggleParameter("regeneration", challenge.rules.modifiers.naturalRegeneration)),
                )
            }
        val prefix =
            locales.component(
                recipient,
                "controller.received",
                LocaleService.component("player", challenger),
                LocaleService.component("objective", objective),
                LocaleService.component("loadout", loadout),
                LocaleService.component("bestof", bestOf),
                LocaleService.component("ranked", ranked),
                LocaleService.component("rules", ruleSummary),
                LocaleService.component("arena", arena),
            )
        val accept =
            locales.component(recipient, "controller.accept")
                .clickEvent(ClickEvent.runCommand("/duel accept ${challenge.id}"))
                .hoverEvent(HoverEvent.showText(locales.component(recipient, "controller.accept-hover")))
        val deny =
            locales.component(recipient, "controller.deny")
                .clickEvent(ClickEvent.runCommand("/duel deny ${challenge.id}"))
                .hoverEvent(HoverEvent.showText(locales.component(recipient, "controller.deny-hover")))
        return locales.frameNotice(recipient, prefix.append(accept).append(deny))
    }

    private fun resolveName(playerId: PlayerId): String =
        targets.find(playerId.value)?.name ?: plugin.server.getOfflinePlayer(playerId.value).name ?: playerId.toString().take(8)

    private fun sendPlayerNotice(
        viewer: Player,
        playerId: UUID,
        playerName: String,
        key: String,
        pendingChallenge: ChallengeId? = null,
    ) {
        playerComponents.load(viewer, playerId, playerName).whenComplete { playerComponent, _ ->
            runSync {
                if (viewer.isOnline && (pendingChallenge == null || challenges.find(pendingChallenge)?.status == ChallengeStatus.PENDING)) {
                    viewer.sendMessage(
                        locales.notice(
                            viewer,
                            key,
                            LocaleService.component("player", requireNotNull(playerComponent)),
                        ),
                    )
                }
            }
        }
    }

    private fun rememberContext(challengeId: ChallengeId, context: ChallengeContext) {
        val cutoff = clock.millis() - CONTEXT_RETENTION.toMillis()
        contexts.entries.removeIf { it.value.expiresAtMillis < cutoff }
        if (contexts.size >= MAX_CONTEXTS) {
            contexts.entries.minByOrNull { it.value.expiresAtMillis }?.key?.let(contexts::remove)
        }
        contexts[challengeId] = context
        expiredNotificationPlayers.removeIf { (expiredChallengeId, _) -> !contexts.containsKey(expiredChallengeId) }
    }

    private fun participants(challenge: DuelChallenge): List<Player> =
        listOfNotNull(plugin.server.getPlayer(challenge.challenger.value), plugin.server.getPlayer(challenge.target.value))

    private fun runSync(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (plugin.server.isPrimaryThread) block() else plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private fun unwrap(failure: Throwable): Throwable =
        if (failure is CompletionException && failure.cause != null) requireNotNull(failure.cause) else failure.cause ?: failure

    private data class ChallengeContext(
        val challengerName: String,
        val targetName: String,
        val challengerRoute: ParticipantRoute,
        val targetRoute: ParticipantRoute,
        val expiresAtMillis: Long,
        val recoveryMatchId: MatchId?,
        val kitFingerprint: String?,
    )

    private data class AcceptedMatch(
        val challenge: DuelChallenge,
        val host: ServerId,
        val networkMessage: CrossServerChallengeMessage?,
        val expiresAtMillis: Long,
    )

    private data class ReturnRoutes(
        val byPlayer: Map<PlayerId, ServerId>,
        val recoveryMatchId: MatchId,
        val returnPolicy: PostMatchReturnPolicy,
        val automaticReturnTimeout: Duration,
    ) {
        init {
            require(byPlayer.size == 2) { "Return routes require exactly two players" }
        }

        fun forPlayer(player: PlayerId): ServerId = requireNotNull(byPlayer[player]) { "Missing return route for $player" }
    }

    private data class ReturnOffer(
        val matchId: MatchId,
        val destination: ServerId,
        val recoveryMatchId: MatchId,
    )

    private enum class OriginPreparation {
        WAIT,
        READY,
        FAILED,
    }

    private companion object {
        const val NETWORK_MATCH_POLL_TICKS = 10L
        const val RETURN_DELAY_TICKS = 1L
        const val MAX_CONTEXTS = 4_096
        val CONTEXT_RETENTION: Duration = Duration.ofMinutes(10)
    }
}

internal fun selectOriginServer(
    recorded: ServerId?,
    observed: ServerId?,
    fallback: ServerId,
): ServerId = recorded ?: observed ?: fallback

internal data class ParticipantRoute(
    val currentServer: ServerId,
    val originServer: ServerId,
)

internal fun participantRoute(currentServer: ServerId, originServer: ServerId?): ParticipantRoute =
    ParticipantRoute(currentServer, originServer ?: currentServer)

internal fun viewerScore(match: DuelMatch, viewer: PlayerId): String =
    when (viewer) {
        match.firstPlayer -> "${match.score.first}:${match.score.second}"
        match.secondPlayer -> "${match.score.second}:${match.score.first}"
        else -> error("Player is not a match participant")
    }

internal fun shouldStartDirectLocalMatch(
    bothPlayersLocal: Boolean,
    continuesFromArenaLobby: Boolean,
    matchServer: ServerId?,
    localServer: ServerId,
): Boolean = bothPlayersLocal && !continuesFromArenaLobby && (matchServer == null || matchServer == localServer)

internal fun challengeExpiryDelayTicks(nowMillis: Long, expiresAtMillis: Long): Long {
    val remainingMillis = (expiresAtMillis - nowMillis).coerceAtLeast(1L)
    return ((remainingMillis + 49L) / 50L).coerceAtLeast(1L)
}

internal fun continuationRecoveryMatchId(
    completedMatchId: MatchId,
    firstOfferMatchId: MatchId?,
    firstRecoveryMatchId: MatchId?,
    secondOfferMatchId: MatchId?,
    secondRecoveryMatchId: MatchId?,
): MatchId? =
    firstRecoveryMatchId?.takeIf { recoveryMatchId ->
        firstOfferMatchId == completedMatchId &&
            secondOfferMatchId == completedMatchId &&
            secondRecoveryMatchId == recoveryMatchId
    }

internal data class AcceptedParticipantReadiness(
    val stateLocked: Boolean,
    val playerDataReady: Boolean,
    val engaged: Boolean,
)

internal enum class AcceptedMatchDecision {
    WAIT,
    CANCEL_BUSY,
    START,
}

internal fun acceptedMatchDecision(participants: List<AcceptedParticipantReadiness>): AcceptedMatchDecision {
    if (participants.size != 2) return AcceptedMatchDecision.WAIT
    if (participants.any(AcceptedParticipantReadiness::engaged)) return AcceptedMatchDecision.CANCEL_BUSY
    if (participants.any(AcceptedParticipantReadiness::stateLocked)) return AcceptedMatchDecision.WAIT
    if (participants.any { !it.playerDataReady }) return AcceptedMatchDecision.WAIT
    return AcceptedMatchDecision.START
}

internal fun <T> retainSuccessfulTransferRequest(
    requests: MutableSet<T>,
    request: T,
    result: BackendTransferResult?,
): Boolean {
    if (isSuccessfulBackendTransfer(result)) return true
    requests.remove(request)
    return false
}
