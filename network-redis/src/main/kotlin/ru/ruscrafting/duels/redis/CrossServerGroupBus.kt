package ru.ruscrafting.duels.redis

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.MessageClaimResult
import ru.arc.redis.safety.OriginBoundRedisBus
import ru.arc.redis.safety.RecentMessageDeduplicator
import ru.arc.redis.safety.RedisMessageRejection
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MAX_MULTIPLAYER_PARTICIPANTS
import ru.ruscrafting.duels.domain.MIN_MULTIPLAYER_PARTICIPANTS
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class GroupLobbyMessageType {
    OFFER,
    RESPONSE,
    PREPARE,
    READY,
    CANCEL,
}

enum class GroupLobbyResponse {
    ACCEPTED,
    DECLINED,
    FAILED,
}

data class NetworkGroupParticipant(
    val playerId: PlayerId,
    val name: String,
    val originServer: ServerId,
) {
    init {
        require(isSafeNetworkPlayerName(name)) { "Unsafe group participant name" }
    }
}

data class CrossServerGroupMessage(
    val messageId: String,
    val sourceServer: ServerId,
    val type: GroupLobbyMessageType,
    val lobbyId: UUID,
    val hostId: PlayerId,
    val hostServer: ServerId,
    val participants: List<NetworkGroupParticipant>,
    val layout: MultiplayerLayout,
    val kitPolicy: MultiplayerKitPolicy,
    val sharedKitId: KitId?,
    val expiresAtEpochMillis: Long,
    val targetId: PlayerId? = null,
    val response: GroupLobbyResponse? = null,
    val kitId: KitId? = null,
) {
    init {
        require(messageId.matches(MESSAGE_ID_PATTERN)) { "Unsafe group message id" }
        require(participants.size in MIN_MULTIPLAYER_PARTICIPANTS..MAX_MULTIPLAYER_PARTICIPANTS) {
            "A network group lobby requires $MIN_MULTIPLAYER_PARTICIPANTS..$MAX_MULTIPLAYER_PARTICIPANTS participants"
        }
        require(participants.map(NetworkGroupParticipant::playerId).distinct().size == participants.size) {
            "A network group lobby cannot contain duplicate participants"
        }
        require(participants.first().playerId == hostId) { "The group host must be the first participant" }
        require(participants.first().originServer == hostServer) { "The group host must originate on the host server" }
        require((kitPolicy == MultiplayerKitPolicy.SHARED) == (sharedKitId != null)) {
            "A shared-kit group lobby requires exactly one shared kit"
        }
        require(expiresAtEpochMillis > 0L) { "A group lobby expiry must be positive" }
        when (type) {
            GroupLobbyMessageType.OFFER -> {
                require(sourceServer == hostServer) { "A group offer must come from the host server" }
                require(targetId != null && participant(targetId).originServer != hostServer) {
                    "A network group offer must target a remote participant"
                }
                require(response == null && kitId == null) { "A group offer cannot contain a response" }
            }
            GroupLobbyMessageType.RESPONSE -> {
                val target = requireNotNull(targetId) { "A group response requires a participant" }
                require(sourceServer == participant(target).originServer) {
                    "A group response must come from the participant origin server"
                }
                require(response != null) { "A group response requires a decision" }
                require((response == GroupLobbyResponse.ACCEPTED) == (kitId != null)) {
                    "Only an accepted group response carries a kit"
                }
            }
            GroupLobbyMessageType.PREPARE -> {
                require(sourceServer == hostServer) { "Group preparation must come from the host server" }
                require(targetId == null && response == null && kitId == null) {
                    "Group preparation cannot target one participant"
                }
            }
            GroupLobbyMessageType.READY -> {
                val target = requireNotNull(targetId) { "A ready message requires a participant" }
                require(sourceServer == participant(target).originServer) {
                    "A ready message must come from the participant origin server"
                }
                require(response == null && kitId == null) { "A ready message cannot contain a response" }
            }
            GroupLobbyMessageType.CANCEL -> {
                require(sourceServer == hostServer) { "Only the group host can cancel a network lobby" }
                require(targetId == null && response == null && kitId == null) {
                    "A group cancellation cannot target one participant"
                }
            }
        }
    }

    fun participant(playerId: PlayerId): NetworkGroupParticipant =
        participants.firstOrNull { it.playerId == playerId }
            ?: throw IllegalArgumentException("Player $playerId is not part of group lobby $lobbyId")

    private companion object {
        val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9:._-]{1,160}")
    }
}

class CrossServerGroupBus(
    private val redis: RedisOperations,
    private val localServer: ServerId,
    private val logger: Logger = LoggerFactory.getLogger(CrossServerGroupBus::class.java),
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val listeners = CopyOnWriteArrayList<(CrossServerGroupMessage) -> Unit>()
    private val deduplicator = RecentMessageDeduplicator(SEEN_TTL_MILLIS, MAX_SEEN_MESSAGES)
    private val bus =
        OriginBoundRedisBus(
            redis = redis,
            channel = CHANNEL,
            codec = GroupMessageCodec(),
            originAllowed = { origin -> origin != localServer.value },
            embeddedOrigin = { message -> message.sourceServer.value },
            messageId = { message -> replayKey(message.sourceServer, message.messageId) },
            deduplicator = deduplicator,
            clockMillis = clock::millis,
            onMessage = { message, _ -> deliver(message) },
            onRejected = ::logRejection,
        )

    init {
        bus.register()
    }

    fun publish(message: CrossServerGroupMessage) {
        require(message.sourceServer == localServer) { "Cannot publish a group message owned by another server" }
        bus.publish(message)
        if (claimFirstDelivery(message.sourceServer, message.messageId)) deliver(message)
    }

    fun subscribe(listener: (CrossServerGroupMessage) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun close() {
        bus.close()
        listeners.clear()
    }

    private fun deliver(message: CrossServerGroupMessage) {
        listeners.forEach { listener ->
            runCatching { listener(message) }
                .onFailure { failure -> logger.warn("ArcDuels group listener rejected {}", message.messageId, failure) }
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
        // Redis may echo this node's own publish back to its subscription. The
        // service already delivers local messages explicitly in publish().
        if (reason == RedisMessageRejection.ORIGIN_REJECTED) return
        logger.warn("Rejected ArcDuels group message: {}", reason)
    }

    companion object {
        const val CHANNEL = "arcduels:v1:group-lobbies"
        private const val MAX_SEEN_MESSAGES = 10_000
        private const val SEEN_TTL_MILLIS = 60L * 60L * 1_000L

        private fun replayKey(sourceServer: ServerId, messageId: String): String = "${sourceServer.value}:$messageId"
    }
}
