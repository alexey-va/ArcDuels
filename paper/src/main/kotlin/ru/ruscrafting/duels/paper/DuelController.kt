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
) : AutoCloseable {
    private val contexts = ConcurrentHashMap<ChallengeId, ChallengeContext>()
    private val acceptedMatches = ConcurrentHashMap<ChallengeId, AcceptedMatch>()
    private val acceptedTasks = ConcurrentHashMap<ChallengeId, BukkitTask>()
    private val transferRequests = ConcurrentHashMap.newKeySet<Pair<ChallengeId, PlayerId>>()
    private val networkPendingPlayers = ConcurrentHashMap.newKeySet<PlayerId>()
    private val returnRoutes = ConcurrentHashMap<MatchId, ReturnRoutes>()
    private val returnTasks = ConcurrentHashMap<MatchId, BukkitTask>()
    private val returnRequests = ConcurrentHashMap.newKeySet<Pair<MatchId, PlayerId>>()
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
            challenger.sendMessage(locales.component(challenger, "error.player-left"))
            return
        }
        val localTarget = plugin.server.getPlayer(target.uniqueId)
        if (sessions.isEngaged(challenger) || sessions.isStateLocked(challenger) || PlayerId(challenger.uniqueId) in networkPendingPlayers ||
            (localTarget != null &&
                (sessions.isEngaged(localTarget) || sessions.isStateLocked(localTarget) || PlayerId(localTarget.uniqueId) in networkPendingPlayers))
        ) {
            challenger.sendMessage(locales.component(challenger, "controller.busy"))
            return
        }
        runCatching { challenges.create(PlayerId(challenger.uniqueId), PlayerId(target.uniqueId), rules) }
            .onSuccess { challenge ->
                val context = ChallengeContext(challenger.name, target.name, localServer, target.server, challenge.expiresAt.toEpochMilli())
                rememberContext(challenge.id, context)
                if (localTarget != null) {
                    notifyChallenge(localTarget, challenger.name, challenge)
                } else {
                    val bus = challengeBus ?: run {
                        challenges.registerResolution(challenge.resolve(ChallengeStatus.CANCELLED, clock.instant()))
                        challenger.sendMessage(locales.component(challenger, "controller.network-unavailable"))
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
                challenger.sendMessage(locales.component(challenger, "controller.sent", LocaleService.text("player", target.name)))
            }
            .onFailure { challenger.sendMessage(locales.component(challenger, "controller.failed")) }
    }

    fun accept(
        player: Player,
        challengeId: ChallengeId? = null,
    ) {
        val challenge = resolveCandidate(player, challengeId, incoming = true) ?: return
        if (challenge.challenger in networkPendingPlayers || challenge.target in networkPendingPlayers) {
            player.sendMessage(locales.component(player, "controller.busy"))
            return
        }
        val bothLocal = participants(challenge).size == 2
        if (!bothLocal && challengeBus == null) {
            player.sendMessage(locales.component(player, "controller.network-unavailable"))
            return
        }
        val matchServer = arenaDirectory?.select(challenge.rules)
        if (arenaDirectory != null && matchServer == null) {
            player.sendMessage(locales.component(player, "controller.no-network-arena"))
            return
        }
        val accepted =
            runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), ChallengeStatus.ACCEPTED) }
                .getOrElse {
                    player.sendMessage(locales.component(player, "controller.failed"))
                    return
                }
        if (accepted.status != ChallengeStatus.ACCEPTED) {
            player.sendMessage(locales.component(player, "controller.expired"))
            return
        }
        if (bothLocal && (matchServer == null || matchServer == localServer)) {
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
                if (participants(resolved).size == 2 || challengeBus == null) {
                    participants(resolved).forEach { it.sendMessage(locales.component(it, "controller.denied")) }
                } else {
                    publishResolution(resolved, null)
                }
            }
            .onFailure { player.sendMessage(locales.component(player, "controller.failed")) }
    }

    fun cancel(player: Player) {
        val challenge = resolveCandidate(player, null, incoming = false) ?: return
        runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), ChallengeStatus.CANCELLED) }
            .onSuccess { resolved ->
                if (participants(resolved).size == 2 || challengeBus == null) {
                    participants(resolved).forEach { it.sendMessage(locales.component(it, "controller.cancelled")) }
                } else {
                    publishResolution(resolved, null)
                }
            }
            .onFailure { player.sendMessage(locales.component(player, "controller.failed")) }
    }

    fun leave(player: Player) {
        if (sessions.handleForfeit(player)) {
            player.sendMessage(locales.component(player, "controller.forfeit"))
        } else {
            player.sendMessage(locales.component(player, "controller.not-fighting"))
        }
    }

    fun showStatistics(
        viewer: Player,
        target: DuelTarget,
    ) {
        statistics.find(PlayerId(target.uniqueId)).whenComplete { stats, failure ->
            runSync {
                if (failure != null) {
                    viewer.sendMessage(locales.component(viewer, "controller.stats-failed"))
                    return@runSync
                }
                viewer.sendMessage(
                    locales.component(
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
        returnTasks.values.forEach(BukkitTask::cancel)
        acceptedTasks.clear()
        returnTasks.clear()
        acceptedMatches.clear()
        transferRequests.clear()
        networkPendingPlayers.clear()
        returnRequests.clear()
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
                localServer,
                message.challenge.expiresAt.toEpochMilli(),
            ),
        )
        if (sessions.isEngaged(target) || sessions.isStateLocked(target) || PlayerId(target.uniqueId) in networkPendingPlayers) {
            publishResolution(message.challenge.resolve(ChallengeStatus.DENIED, clock.instant()), null)
            return
        }
        runCatching { challenges.register(message.challenge) }
            .onSuccess { notifyChallenge(target, message.challengerName, message.challenge) }
            .onFailure {
                plugin.logger.warning("Rejected network challenge ${message.challenge.id}: ${it.message}")
                publishResolution(message.challenge.resolve(ChallengeStatus.DENIED, clock.instant()), null)
            }
    }

    private fun receiveResolution(message: CrossServerChallengeMessage) {
        runCatching { challenges.registerResolution(message.challenge) }
            .onFailure {
                plugin.logger.warning("Rejected network challenge resolution ${message.challenge.id}: ${it.message}")
                return
            }
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
            ChallengeStatus.DENIED -> participants(message.challenge).forEach { it.sendMessage(locales.component(it, "controller.denied")) }
            ChallengeStatus.CANCELLED -> participants(message.challenge).forEach { it.sendMessage(locales.component(it, "controller.cancelled")) }
            ChallengeStatus.EXPIRED -> participants(message.challenge).forEach { it.sendMessage(locales.component(it, "controller.expired")) }
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
                challengerServer = targets.find(challenge.challenger.value)?.server ?: existing?.challengerServer ?: localServer,
                targetServer = targets.find(challenge.target.value)?.server ?: existing?.targetServer ?: localServer,
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
        scheduleAcceptedMatch(message.challenge, host, message)
    }

    private fun scheduleAcceptedMatch(
        challenge: DuelChallenge,
        host: ServerId,
        networkMessage: CrossServerChallengeMessage? = null,
    ) {
        val accepted = AcceptedMatch(challenge, host, networkMessage, clock.millis() + transferTimeout.toMillis())
        acceptedMatches.putIfAbsent(challenge.id, accepted)
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
            participants(challenge).forEach { it.sendMessage(locales.component(it, "controller.network-timeout")) }
            stopAcceptedMatch(challengeId)
            return
        }
        val localParticipants = participants(challenge)
        if (localServer != accepted.host) {
            localParticipants.forEach { player ->
                val request = challengeId to PlayerId(player.uniqueId)
                if (transferRequests.add(request)) {
                    player.sendMessage(locales.component(player, "controller.network-transfer", LocaleService.text("server", accepted.host.value)))
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
                        stateLocked = sessions.isStateLocked(participant),
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
        }
        acceptedTasks.remove(challengeId)?.cancel()
        transferRequests.removeIf { it.first == challengeId }
    }

    private fun startLocal(
        challenge: DuelChallenge,
        networkMessage: CrossServerChallengeMessage? = null,
    ) {
        runCatching { sessions.start(challenge) }
            .getOrElse { failure -> java.util.concurrent.CompletableFuture.failedFuture(failure) }
            .whenComplete { match, failure ->
                runSync {
                    if (failure != null) {
                        val cause = unwrap(failure)
                        participants(challenge).forEach {
                            val reasonKey = if (cause is CancellationException) "controller.wait-cancelled" else "controller.start-internal"
                            it.sendMessage(locales.component(it, "controller.start-failed", LocaleService.component("reason", locales.component(it, reasonKey))))
                        }
                    } else if (networkMessage != null) {
                        returnRoutes[requireNotNull(match).id] =
                            ReturnRoutes(
                                challenger = networkMessage.challengerServer,
                                target = networkMessage.targetServer,
                            )
                    }
                }
            }
    }

    private fun onMatchCompleted(match: DuelMatch) {
        val routes = returnRoutes.remove(match.id) ?: return
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

    private fun tickReturn(match: DuelMatch, routes: ReturnRoutes, deadline: Long) {
        if (clock.millis() >= deadline) {
            stopReturn(match.id)
            return
        }
        val destinations =
            mapOf(
                match.firstPlayer to routes.forPlayer(match.firstPlayer, match),
                match.secondPlayer to routes.forPlayer(match.secondPlayer, match),
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
                player.sendMessage(locales.component(player, "controller.network-return", LocaleService.text("server", destination.value)))
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
        if (candidate == null) {
            player.sendMessage(locales.component(player, if (incoming) "controller.no-incoming" else "controller.no-outgoing"))
        }
        return candidate
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
                ru.ruscrafting.duels.domain.DuelObjectiveType.ELIMINATION -> "objective.elimination.name"
                ru.ruscrafting.duels.domain.DuelObjectiveType.KING_OF_THE_HILL -> "objective.koth.name"
                ru.ruscrafting.duels.domain.DuelObjectiveType.SUMO -> "objective.sumo.name"
            }
        fun state(value: Boolean) = locales.component(recipient, if (value) "menu.common.enabled" else "menu.common.disabled")
        val prefix =
            locales.component(
                recipient,
                "controller.received",
                LocaleService.text("player", challengerName),
                LocaleService.component("objective", locales.component(recipient, objectiveKey)),
                LocaleService.component("loadout", mode),
                LocaleService.text("bestof", challenge.rules.bestOf),
                LocaleService.component("ranked", state(challenge.rules.ranked)),
                LocaleService.text("sudden", challenge.rules.modifiers.suddenDeathAfterSeconds),
                LocaleService.component("projectiles", state(challenge.rules.modifiers.projectiles)),
                LocaleService.component("consumables", state(challenge.rules.modifiers.consumables)),
                LocaleService.component("pearls", state(challenge.rules.modifiers.enderPearls)),
                LocaleService.component("regeneration", state(challenge.rules.modifiers.naturalRegeneration)),
            )
        val accept =
            locales.component(recipient, "controller.accept")
                .clickEvent(ClickEvent.runCommand("/duel accept ${challenge.id}"))
                .hoverEvent(HoverEvent.showText(locales.component(recipient, "controller.accept-hover")))
        val deny =
            locales.component(recipient, "controller.deny")
                .clickEvent(ClickEvent.runCommand("/duel deny ${challenge.id}"))
                .hoverEvent(HoverEvent.showText(locales.component(recipient, "controller.deny-hover")))
        return prefix.append(accept).append(deny)
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
        val challenger: ServerId,
        val target: ServerId,
    ) {
        fun forPlayer(player: PlayerId, match: DuelMatch): ServerId =
            if (player == match.firstPlayer) challenger else target
    }

    private companion object {
        const val NETWORK_MATCH_POLL_TICKS = 10L
        const val RETURN_DELAY_TICKS = 60L
        const val MAX_CONTEXTS = 4_096
        val RETURN_TIMEOUT: Duration = Duration.ofMinutes(2)
        val CONTEXT_RETENTION: Duration = Duration.ofMinutes(10)
    }
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
