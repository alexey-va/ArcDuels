package ru.ruscrafting.duels.redis

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.MessageClaimResult
import ru.arc.redis.safety.OriginBoundRedisBus
import ru.arc.redis.safety.RecentMessageDeduplicator
import ru.arc.redis.safety.RedisMessageRejection
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

enum class ChallengeMessageType {
    OFFER,
    RESOLUTION,
}

data class CrossServerChallengeMessage(
    val messageId: String,
    val sourceServer: ServerId,
    val type: ChallengeMessageType,
    val challenge: DuelChallenge,
    val kitFingerprint: String?,
    val challengerName: String,
    val targetName: String,
    val challengerServer: ServerId,
    val targetServer: ServerId,
    val matchServer: ServerId?,
    val challengerCurrentServer: ServerId = challengerServer,
    val targetCurrentServer: ServerId = targetServer,
    val recoveryMatchId: MatchId? = null,
) {
    init {
        require(messageId.matches(Regex("[A-Za-z0-9:._-]{1,160}"))) { "Unsafe challenge message id" }
        require(isSafeNetworkPlayerName(challengerName) && isSafeNetworkPlayerName(targetName)) { "Unsafe challenge player name" }
        requireKitFingerprintForMode(challenge.rules.mode, kitFingerprint)
        when (type) {
            ChallengeMessageType.OFFER -> {
                require(sourceServer == challengerCurrentServer) { "A challenge offer must come from the current challenger server" }
                require(challenge.status == ChallengeStatus.PENDING) { "An offer must be pending" }
                require(matchServer == null) { "An offer cannot select an arena server before acceptance" }
            }
            ChallengeMessageType.RESOLUTION -> {
                val selectedArena = challenge.arenaSelection
                require(challenge.status != ChallengeStatus.PENDING) { "A resolution must be terminal" }
                val expectedSource =
                    when (challenge.status) {
                        ChallengeStatus.ACCEPTED, ChallengeStatus.DENIED -> targetCurrentServer
                        ChallengeStatus.CANCELLED, ChallengeStatus.EXPIRED -> challengerCurrentServer
                        ChallengeStatus.PENDING -> error("A resolution cannot be pending")
                    }
                require(sourceServer == expectedSource) { "Challenge resolution came from the wrong participant server" }
                require(challenge.status != ChallengeStatus.ACCEPTED || matchServer != null) {
                    "An accepted challenge must select an arena server"
                }
                require(challenge.status != ChallengeStatus.ACCEPTED || selectedArena == null || matchServer == selectedArena.serverId) {
                    "An accepted challenge must route to its explicitly selected arena server"
                }
            }
        }
    }
}

class CrossServerChallengeBus(
    private val redis: RedisOperations,
    private val localServer: ServerId,
    private val logger: Logger = LoggerFactory.getLogger(CrossServerChallengeBus::class.java),
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val codec = ChallengeMessageCodec()
    private val listeners = CopyOnWriteArrayList<(CrossServerChallengeMessage) -> Unit>()
    private val deduplicator = RecentMessageDeduplicator(SEEN_TTL_MILLIS, MAX_SEEN_MESSAGES)
    private val bus =
        OriginBoundRedisBus(
            redis = redis,
            channel = CHANNEL,
            codec = codec,
            originAllowed = { origin -> origin != localServer.value },
            embeddedOrigin = { message -> message.sourceServer.value },
            messageId = { message -> replayKey(message.sourceServer, message.messageId) },
            deduplicator = deduplicator,
            clockMillis = clock::millis,
            onMessage = { message, _ -> deliver(message) },
            onRejected = { reason -> logRejection(reason) },
        )

    init {
        bus.register()
    }

    fun publish(message: CrossServerChallengeMessage) {
        require(message.sourceServer == localServer) { "Cannot publish a challenge message owned by another server" }
        bus.publish(message)
        if (claimFirstDelivery(message.sourceServer, message.messageId)) deliver(message)
    }

    fun subscribe(listener: (CrossServerChallengeMessage) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun close() {
        bus.close()
        listeners.clear()
    }

    private fun deliver(message: CrossServerChallengeMessage) {
        listeners.forEach { listener ->
            runCatching { listener(message) }
                .onFailure { failure -> logger.warn("ArcDuels challenge listener rejected {}", message.messageId, failure) }
        }
    }

    private fun claimFirstDelivery(sourceServer: ServerId, messageId: String): Boolean =
        when (deduplicator.claim(replayKey(sourceServer, messageId), clock.millis())) {
            MessageClaimResult.ACCEPTED -> true
            MessageClaimResult.DUPLICATE -> false
            MessageClaimResult.CAPACITY_EXCEEDED -> {
                logRejection(RedisMessageRejection.REPLAY_GUARD_FULL)
                false
            }
        }

    private fun logRejection(reason: RedisMessageRejection) {
        logger.warn("Rejected ArcDuels challenge message: {}", reason)
    }

    companion object {
        const val CHANNEL = "arcduels:v1:challenges"
        private const val MAX_SEEN_MESSAGES = 10_000
        private const val SEEN_TTL_MILLIS = 60L * 60L * 1_000L

        private fun replayKey(sourceServer: ServerId, messageId: String): String = "${sourceServer.value}:$messageId"
    }
}
