package ru.ruscrafting.duels.redis

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.time.Duration
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
    private val seenMessages = HashMap<String, Long>()
    private val seenMessagesLock = Any()
    private val redisListener = ChannelListener(::consume)

    init {
        redis.registerChannelUnique(CHANNEL, redisListener)
    }

    fun publish(message: CrossServerChallengeMessage) {
        require(message.sourceServer == localServer) { "Cannot publish a challenge message owned by another server" }
        redis.publish(CHANNEL, codec.encode(message))
        if (markFirstDelivery(message.sourceServer, message.messageId)) deliver(message)
    }

    fun subscribe(listener: (CrossServerChallengeMessage) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun close() {
        redis.unregisterChannel(CHANNEL, redisListener)
        listeners.clear()
        synchronized(seenMessagesLock) { seenMessages.clear() }
    }

    private fun consume(channel: String, message: String, originServer: String) {
        if (channel != CHANNEL || originServer == localServer.value) return
        runCatching {
            val decoded = codec.decode(message)
            require(decoded.sourceServer.value == originServer) { "Redis origin does not match challenge source" }
            if (markFirstDelivery(decoded.sourceServer, decoded.messageId)) deliver(decoded)
        }.onFailure { failure -> logger.warn("Rejected ArcDuels challenge message from {}", originServer, failure) }
    }

    private fun deliver(message: CrossServerChallengeMessage) {
        listeners.forEach { listener ->
            runCatching { listener(message) }
                .onFailure { failure -> logger.warn("ArcDuels challenge listener rejected {}", message.messageId, failure) }
        }
    }

    private fun markFirstDelivery(sourceServer: ServerId, messageId: String): Boolean = synchronized(seenMessagesLock) {
        val now = clock.millis()
        val key = "${sourceServer.value}:$messageId"
        val previous = seenMessages[key]
        if (previous != null && now - previous < SEEN_TTL.toMillis()) return@synchronized false
        if (seenMessages.size >= MAX_SEEN_MESSAGES) {
            val cutoff = now - SEEN_TTL.toMillis()
            seenMessages.entries.removeIf { it.value < cutoff }
            if (seenMessages.size >= MAX_SEEN_MESSAGES) seenMessages.entries.minByOrNull { it.value }?.key?.let(seenMessages::remove)
        }
        seenMessages[key] = now
        true
    }

    companion object {
        const val CHANNEL = "arcduels:v1:challenges"
        private const val MAX_SEEN_MESSAGES = 10_000
        private val SEEN_TTL = Duration.ofHours(1)
    }
}
