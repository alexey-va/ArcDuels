package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import ru.ruscrafting.duels.domain.ChallengeId
import ru.ruscrafting.duels.domain.ChallengeRegistry
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.StatisticsRepository
import ru.ruscrafting.duels.redis.ChallengeMessageType
import ru.ruscrafting.duels.redis.CrossServerChallengeBus
import ru.ruscrafting.duels.redis.CrossServerChallengeMessage
import ru.ruscrafting.duels.redis.NetworkArenaDirectory
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
    private val challengeBus: CrossServerChallengeBus? = null,
    private val arenaDirectory: NetworkArenaDirectory? = null,
    private val transfer: PlayerTransfer? = null,
    private val playerDataReady: (Player) -> Boolean = { true },
    private val clock: Clock = Clock.systemUTC(),
    private val transferTimeout: Duration = Duration.ofSeconds(30),
    private val returnPolicy: PostMatchReturnPolicy = PostMatchReturnPolicy.PROMPT,
) : AutoCloseable {
    private val contexts = ConcurrentHashMap<ChallengeId, ChallengeContext>()
    private val acceptedMatches = ConcurrentHashMap<ChallengeId, AcceptedMatch>()
    private val acceptedTasks = ConcurrentHashMap<ChallengeId, BukkitTask>()
    private val challengeExpiryTasks = ConcurrentHashMap<ChallengeId, BukkitTask>()
    private val expiredNotificationPlayers = ConcurrentHashMap.newKeySet<Pair<ChallengeId, UUID>>()
    private val transferRequests = ConcurrentHashMap.newKeySet<Pair<ChallengeId, PlayerId>>()
    private val originSnapshots = ConcurrentHashMap<Pair<ChallengeId, PlayerId>, java.util.concurrent.CompletableFuture<Unit>>()
    private val networkPendingPlayers = ConcurrentHashMap.newKeySet<PlayerId>()
    private val returnRoutes = ConcurrentHashMap<MatchId, ReturnRoutes>()
    private val returnTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val returnRequests = ConcurrentHashMap.newKeySet<Pair<MatchId, PlayerId>>()
    private val returnOffers = ConcurrentHashMap<PlayerId, ReturnOffer>()
    private val networkSubscription = challengeBus?.subscribe(::onNetworkMessage)
    private val completionSubscription = sessions.onCompleted(::onMatchCompleted)

    init {
        require(!transferTimeout.isNegative && !transferTimeout.isZero) { "Network transfer timeout must be positive" }
        require(challengeBus == null || (arenaDirectory != null && transfer != null)) {
            "Cross-server challenges require arena discovery and a player transfer gateway"
        }
    }

    fun challenge(
        challenger: Player,
        requestedTarget: DuelTarget,
        rules: DuelRules,
    ) {
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
        runCatching { challenges.create(PlayerId(challenger.uniqueId), PlayerId(target.uniqueId), rules) }
            .onSuccess { challenge ->
                val context =
                    ChallengeContext(
                        challenger.name,
                        target.name,
                        returnOffers[challenge.challenger]?.destination ?: localServer,
                        returnOffers[challenge.target]?.destination ?: target.server,
                        challenge.expiresAt.toEpochMilli(),
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
                    bus.publish(
                        CrossServerChallengeMessage(
                            messageId = "${challenge.id}:offer",
                            sourceServer = localServer,
                            type = ChallengeMessageType.OFFER,
                            challenge = challenge,
                            challengerName = context.challengerName,
                            targetName = context.targetName,
                            challengerServer = context.challengerServer,
                            targetServer = context.targetServer,
                            matchServer = null,
                        ),
                    )
                }
                challenger.sendMessage(locales.notice(challenger, "controller.sent", LocaleService.text("player", target.name)))
                scheduleChallengeExpiry(challenge)
            }
            .onFailure { challenger.sendMessage(locales.notice(challenger, "controller.failed")) }
    }

    fun accept(
        player: Player,
        challengeId: ChallengeId? = null,
    ) {
        val challenge = resolveCandidate(player, challengeId, incoming = true) ?: return
        if (challenge.challenger in networkPendingPlayers || challenge.target in networkPendingPlayers) {
            player.sendMessage(locales.notice(player, "controller.busy"))
            return
        }
        val bothLocal = participants(challenge).size == 2
        if (!bothLocal && challengeBus == null) {
            player.sendMessage(locales.notice(player, "controller.network-unavailable"))
            return
        }
        val matchServer = arenaDirectory?.select(challenge.rules)
        if (arenaDirectory != null && matchServer == null) {
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
            announceExpired(accepted)
            return
        }
        val continuesFromArenaLobby =
            returnOffers.containsKey(challenge.challenger) || returnOffers.containsKey(challenge.target)
        if (shouldStartDirectLocalMatch(bothLocal, continuesFromArenaLobby, matchServer, localServer)) {
            scheduleAcceptedMatch(accepted, localServer)
        } else {
            publishResolution(accepted, requireNotNull(matchServer))
        }
    }

    fun deny(
        player: Player,
        challengeId: ChallengeId? = null,
    ) {
        val challenge = resolveCandidate(player, challengeId, incoming = true) ?: return
        runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), ChallengeStatus.DENIED) }
            .onSuccess { resolved ->
                cancelChallengeExpiry(resolved.id)
                if (resolved.status == ChallengeStatus.EXPIRED) {
                    announceExpired(resolved)
                } else if (participants(resolved).size == 2 || challengeBus == null) {
                    participants(resolved).forEach { it.sendMessage(locales.notice(it, "controller.denied")) }
                } else {
                    publishResolution(resolved, null)
                }
            }
            .onFailure { player.sendMessage(locales.notice(player, "controller.failed")) }
    }

    fun cancel(player: Player) {
        val challenge = resolveCandidate(player, null, incoming = false) ?: return
        runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), ChallengeStatus.CANCELLED) }
            .onSuccess { resolved ->
                cancelChallengeExpiry(resolved.id)
                if (resolved.status == ChallengeStatus.EXPIRED) {
                    announceExpired(resolved)
                } else if (participants(resolved).size == 2 || challengeBus == null) {
                    participants(resolved).forEach { it.sendMessage(locales.notice(it, "controller.cancelled")) }
                } else {
                    publishResolution(resolved, null)
                }
            }
            .onFailure { player.sendMessage(locales.notice(player, "controller.failed")) }
    }

    fun leave(player: Player) {
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
        player.sendMessage(locales.notice(player, "controller.network-return", LocaleService.text("server", offer.destination.value)))
        transfer?.connect(player, offer.destination)
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
                        LocaleService.text("player", target.name),
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
        originSnapshots.clear()
        networkPendingPlayers.clear()
        returnRequests.clear()
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
        rememberContext(
            message.challenge.id,
            ChallengeContext(
                message.challengerName,
                message.targetName,
                message.challengerServer,
                returnOffers[message.challenge.target]?.destination ?: localServer,
                message.challenge.expiresAt.toEpochMilli(),
            ),
        )
        if (sessions.isEngaged(target) || sessions.isStateLocked(target) || PlayerId(target.uniqueId) in networkPendingPlayers) {
            publishResolution(message.challenge.resolve(ChallengeStatus.DENIED, clock.instant()), null)
            return
        }
        runCatching { challenges.register(message.challenge) }
            .onSuccess { registered ->
                notifyChallenge(target, message.challengerName, registered)
                scheduleChallengeExpiry(registered, broadcast = false)
            }
            .onFailure {
                plugin.logger.warning("Rejected network challenge ${message.challenge.id}: ${it.message}")
                publishResolution(message.challenge.resolve(ChallengeStatus.DENIED, clock.instant()), null)
            }
    }

    private fun receiveResolution(message: CrossServerChallengeMessage) {
        runCatching { challenges.registerResolution(message.challenge) }
            .getOrElse {
                plugin.logger.warning("Rejected network challenge resolution ${message.challenge.id}: ${it.message}")
                return
            }
        cancelChallengeExpiry(message.challenge.id)
        rememberContext(
            message.challenge.id,
            ChallengeContext(
                message.challengerName,
                message.targetName,
                message.challengerServer,
                message.targetServer,
                message.challenge.expiresAt.toEpochMilli(),
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

    private fun publishResolution(challenge: DuelChallenge, matchServer: ServerId?) {
        val bus = challengeBus ?: return
        val existing = contexts[challenge.id]
        val context =
            ChallengeContext(
                challengerName = existing?.challengerName ?: resolveName(challenge.challenger),
                targetName = existing?.targetName ?: resolveName(challenge.target),
                challengerServer = returnOffers[challenge.challenger]?.destination
                    ?: selectOriginServer(existing?.challengerServer, targets.find(challenge.challenger.value)?.server, localServer),
                targetServer = returnOffers[challenge.target]?.destination
                    ?: selectOriginServer(existing?.targetServer, targets.find(challenge.target.value)?.server, localServer),
                expiresAtMillis = challenge.expiresAt.toEpochMilli(),
            )
        bus.publish(
            CrossServerChallengeMessage(
                messageId = "${challenge.id}:${challenge.status.name.lowercase()}:${localServer.value}",
                sourceServer = localServer,
                type = ChallengeMessageType.RESOLUTION,
                challenge = challenge,
                challengerName = context.challengerName,
                targetName = context.targetName,
                challengerServer = context.challengerServer,
                targetServer = context.targetServer,
                matchServer = matchServer,
            ),
        )
    }

    private fun acceptNetworkMatch(message: CrossServerChallengeMessage) {
        val host = requireNotNull(message.matchServer)
        returnLobbyPlayersForRematch(message.challenge)
        scheduleAcceptedMatch(message.challenge, host, message)
    }

    private fun returnLobbyPlayersForRematch(challenge: DuelChallenge) {
        participants(challenge).forEach { player ->
            val playerId = PlayerId(player.uniqueId)
            val offer = returnOffers[playerId] ?: return@forEach
            player.sendMessage(
                locales.notice(
                    player,
                    "controller.rematch-return",
                    LocaleService.text("server", offer.destination.value),
                ),
            )
            if (offer.destination == localServer) {
                if (sessions.requestRecovery(player)) returnOffers.remove(playerId, offer)
            } else {
                transfer?.connect(player, offer.destination)
            }
        }
    }

    private fun scheduleAcceptedMatch(
        challenge: DuelChallenge,
        host: ServerId,
        networkMessage: CrossServerChallengeMessage? = null,
    ) {
        if (networkMessage != null && localServer != host &&
            localServer != networkMessage.challengerServer && localServer != networkMessage.targetServer
        ) {
            return
        }
        val accepted = AcceptedMatch(challenge, host, networkMessage, clock.millis() + transferTimeout.toMillis())
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
                    player.sendMessage(locales.notice(player, "controller.network-transfer", LocaleService.text("server", accepted.host.value)))
                    transfer?.connect(player, accepted.host)
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
                            returnOffers.containsKey(PlayerId(participant.uniqueId)) ||
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
        originSnapshots.keys.removeIf { it.first == challengeId }
    }

    private fun startLocal(
        challenge: DuelChallenge,
        networkMessage: CrossServerChallengeMessage? = null,
    ) {
        val start =
            if (networkMessage == null) {
                runCatching { sessions.start(challenge) }
            } else {
                runCatching {
                    sessions.startNetwork(
                        challenge,
                        mapOf(
                            challenge.challenger to networkMessage.challengerServer,
                            challenge.target to networkMessage.targetServer,
                        ),
                    )
                }
            }
        start
            .getOrElse { failure -> java.util.concurrent.CompletableFuture.failedFuture(failure) }
            .whenComplete { match, failure ->
                runSync {
                    if (failure != null) {
                        val cause = unwrap(failure)
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
                        returnRoutes[requireNotNull(match).id] =
                            ReturnRoutes(
                                byPlayer =
                                    mapOf(
                                        networkMessage.challenge.challenger to networkMessage.challengerServer,
                                        networkMessage.challenge.target to networkMessage.targetServer,
                                    ),
                            )
                    }
                }
            }
    }

    private fun prepareLocalOriginSnapshots(
        accepted: AcceptedMatch,
        localParticipants: List<Player>,
    ): OriginPreparation {
        val message = requireNotNull(accepted.networkMessage)
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
        participants(accepted.challenge).forEach { player ->
            val destination = originFor(message, PlayerId(player.uniqueId))
            if (destination == localServer) {
                sessions.requestRecovery(player)
            } else {
                transfer?.connect(player, destination)
            }
        }
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
        val routes = returnRoutes.remove(match.id) ?: return
        if (returnPolicy == PostMatchReturnPolicy.PROMPT) {
            offerReturns(match, routes)
            return
        }
        val deadline = clock.millis() + RETURN_TIMEOUT.toMillis()
        val task =
            plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable { tickReturn(match, routes, deadline) },
                RETURN_DELAY_TICKS,
                NETWORK_MATCH_POLL_TICKS,
            )
        returnTasks.putIfAbsent(match.id, task)?.let { task.cancel() }
    }

    private fun offerReturns(
        match: DuelMatch,
        routes: ReturnRoutes,
    ) {
        listOf(match.firstPlayer, match.secondPlayer).forEach { playerId ->
            val destination = routes.forPlayer(playerId)
            returnOffers[playerId] = ReturnOffer(match.id, destination)
            plugin.server.getPlayer(playerId.value)?.let { player ->
                val action =
                    locales.component(player, "controller.return-action")
                        .clickEvent(ClickEvent.runCommand("/duel return"))
                        .hoverEvent(HoverEvent.showText(locales.component(player, "controller.return-hover")))
                player.sendMessage(
                    locales.notice(
                        player,
                        "controller.return-offer",
                        LocaleService.text("server", destination.value),
                        LocaleService.component("action", action),
                    ),
                )
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
                player.sendMessage(locales.notice(player, "controller.network-return", LocaleService.text("server", destination.value)))
                transfer?.connect(player, destination)
            }
        }
        if (destinations.keys.all { (match.id to it) in returnRequests }) stopReturn(match.id)
    }

    private fun stopReturn(matchId: MatchId) {
        returnTasks.remove(matchId)?.cancel()
        returnRequests.removeIf { it.first == matchId }
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
        val task =
            plugin.server.scheduler.runTaskLater(
                plugin,
                Runnable {
                    challengeExpiryTasks.remove(challenge.id)
                    val expired = challenges.expireIfDue(challenge.id) ?: return@Runnable
                    when (expired.status) {
                        ChallengeStatus.PENDING -> scheduleChallengeExpiry(expired, broadcast)
                        ChallengeStatus.EXPIRED -> announceExpired(expired, broadcast)
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
        } else {
            publishResolution(challenge, null)
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
        player.sendMessage(locales.notice(player, "controller.expired", LocaleService.text("player", resolveName(opponent))))
    }

    private fun notifyChallenge(recipient: Player, challengerName: String, challenge: DuelChallenge) {
        recipient.sendMessage(challengeMessage(recipient, challengerName, challenge))
        recipient.playSound(recipient.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f)
    }

    private fun challengeMessage(recipient: Player, challengerName: String, challenge: DuelChallenge): Component {
        val kitId = challenge.rules.kitId
        val mode =
            if (kitId != null) {
                val key = "kit.${kitId.value}.name"
                val kitName =
                    if (locales.hasKey(locales.language(recipient), key)) locales.component(recipient, key)
                    else Component.text(kitId.value)
                locales.component(recipient, "controller.loadout-kit", LocaleService.component("kit", kitName))
            } else {
                locales.component(recipient, "controller.loadout-own")
            }
        val objectiveKey =
            when (challenge.rules.objective) {
                ru.ruscrafting.duels.domain.DuelObjectiveType.ELIMINATION -> "controller.objective-elimination"
                ru.ruscrafting.duels.domain.DuelObjectiveType.KING_OF_THE_HILL -> "controller.objective-koth"
                ru.ruscrafting.duels.domain.DuelObjectiveType.SUMO -> "controller.objective-sumo"
                ru.ruscrafting.duels.domain.DuelObjectiveType.BOXING -> "controller.objective-boxing"
                ru.ruscrafting.duels.domain.DuelObjectiveType.COMBO -> "controller.objective-combo"
            }
        fun state(value: Boolean) = locales.component(recipient, if (value) "controller.state-on" else "controller.state-off")
        val ruleSummary =
            if (challenge.rules.objective.isHitRace) {
                val target =
                    if (challenge.rules.objective == ru.ruscrafting.duels.domain.DuelObjectiveType.BOXING) {
                        challenge.rules.modifiers.boxingHitsToWin
                    } else {
                        challenge.rules.modifiers.comboHitsToWin
                    }
                locales.component(recipient, "controller.hit-race-summary", LocaleService.text("hits", target))
            } else {
                locales.component(
                    recipient,
                    "controller.combat-summary",
                    LocaleService.text("sudden", challenge.rules.modifiers.suddenDeathAfterSeconds),
                    LocaleService.component("projectiles", state(challenge.rules.modifiers.projectiles)),
                    LocaleService.component("consumables", state(challenge.rules.modifiers.consumables)),
                    LocaleService.component("pearls", state(challenge.rules.modifiers.enderPearls)),
                    LocaleService.component("regeneration", state(challenge.rules.modifiers.naturalRegeneration)),
                )
            }
        val prefix =
            locales.component(
                recipient,
                "controller.received",
                LocaleService.text("player", challengerName),
                LocaleService.component("objective", locales.component(recipient, objectiveKey)),
                LocaleService.component("loadout", mode),
                LocaleService.text("bestof", challenge.rules.bestOf),
                LocaleService.component(
                    "ranked",
                    locales.component(recipient, if (challenge.rules.ranked) "controller.ranked" else "controller.unranked"),
                ),
                LocaleService.component("rules", ruleSummary),
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
        val challengerServer: ServerId,
        val targetServer: ServerId,
        val expiresAtMillis: Long,
    )

    private data class AcceptedMatch(
        val challenge: DuelChallenge,
        val host: ServerId,
        val networkMessage: CrossServerChallengeMessage?,
        val expiresAtMillis: Long,
    )

    private data class ReturnRoutes(
        val byPlayer: Map<PlayerId, ServerId>,
    ) {
        init {
            require(byPlayer.size == 2) { "Return routes require exactly two players" }
        }

        fun forPlayer(player: PlayerId): ServerId = requireNotNull(byPlayer[player]) { "Missing return route for $player" }
    }

    private data class ReturnOffer(
        val matchId: MatchId,
        val destination: ServerId,
    )

    private enum class OriginPreparation {
        WAIT,
        READY,
        FAILED,
    }

    private companion object {
        const val NETWORK_MATCH_POLL_TICKS = 10L
        const val RETURN_DELAY_TICKS = 60L
        const val MAX_CONTEXTS = 4_096
        val RETURN_TIMEOUT: Duration = Duration.ofMinutes(2)
        val CONTEXT_RETENTION: Duration = Duration.ofMinutes(10)
    }
}

internal fun selectOriginServer(
    recorded: ServerId?,
    observed: ServerId?,
    fallback: ServerId,
): ServerId = recorded ?: observed ?: fallback

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
