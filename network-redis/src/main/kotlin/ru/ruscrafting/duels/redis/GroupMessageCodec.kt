package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisWireCodec
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.util.UUID

internal class GroupMessageCodec(
    gson: Gson = Gson(),
) : RedisWireCodec<CrossServerGroupMessage> {
    private val wireCodec =
        BoundedJsonCodec(
            gson = gson,
            type = WireMessage::class.java,
            rootContract =
                JsonObjectContract(
                    allowedFields = WIRE_FIELDS,
                    requiredFields = REQUIRED_WIRE_FIELDS,
                    fieldContracts =
                        mapOf(
                            "participants" to
                                JsonArrayContract(
                                    minEntries = 3,
                                    maxEntries = 12,
                                    elementContract = JsonObjectContract(PARTICIPANT_FIELDS),
                                ),
                        ),
                ),
            bounds = JsonResourceBounds(MAX_MESSAGE_CHARACTERS, maxStringCharacters = 160),
            validate = { wire ->
                require(wire.version == WIRE_VERSION) { "Unsupported group lobby wire version ${wire.version}" }
                require(wire.messageId.matches(MESSAGE_ID_PATTERN)) { "Unsafe group message id" }
                ServerId(wire.sourceServer)
                UUID.fromString(wire.lobbyId)
                require(wire.participants.size <= 12) { "Too many group participants" }
            },
        )

    override fun encode(value: CrossServerGroupMessage): String =
        wireCodec.encode(
            WireMessage(
                type = value.type.name,
                messageId = value.messageId,
                sourceServer = value.sourceServer.value,
                lobbyId = value.lobbyId.toString(),
                hostId = value.hostId.toString(),
                hostServer = value.hostServer.value,
                participants = value.participants.map { WireParticipant(it.playerId.toString(), it.name, it.originServer.value) },
                layout = value.layout.name,
                kitPolicy = value.kitPolicy.name,
                sharedKitId = value.sharedKitId?.value,
                expiresAtEpochMillis = value.expiresAtEpochMillis,
                targetId = value.targetId?.toString(),
                response = value.response?.name,
                kitId = value.kitId?.value,
            ),
        )

    override fun decode(raw: String): CrossServerGroupMessage {
        val wire = wireCodec.decode(raw)
        return CrossServerGroupMessage(
            messageId = wire.messageId,
            sourceServer = ServerId(wire.sourceServer),
            type = GroupLobbyMessageType.valueOf(wire.type),
            lobbyId = UUID.fromString(wire.lobbyId),
            hostId = PlayerId(UUID.fromString(wire.hostId)),
            hostServer = ServerId(wire.hostServer),
            participants = wire.participants.map { NetworkGroupParticipant(PlayerId(UUID.fromString(it.playerId)), it.name, ServerId(it.originServer)) },
            layout = MultiplayerLayout.valueOf(wire.layout),
            kitPolicy = MultiplayerKitPolicy.valueOf(wire.kitPolicy),
            sharedKitId = wire.sharedKitId?.let(::KitId),
            expiresAtEpochMillis = wire.expiresAtEpochMillis,
            targetId = wire.targetId?.let { PlayerId(UUID.fromString(it)) },
            response = wire.response?.let(GroupLobbyResponse::valueOf),
            kitId = wire.kitId?.let(::KitId),
        )
    }

    private data class WireParticipant(
        val playerId: String,
        val name: String,
        val originServer: String,
    )

    private data class WireMessage(
        val version: Int = WIRE_VERSION,
        val type: String,
        val messageId: String,
        val sourceServer: String,
        val lobbyId: String,
        val hostId: String,
        val hostServer: String,
        val participants: List<WireParticipant>,
        val layout: String,
        val kitPolicy: String,
        val sharedKitId: String? = null,
        val expiresAtEpochMillis: Long,
        val targetId: String? = null,
        val response: String? = null,
        val kitId: String? = null,
    )

    private companion object {
        const val WIRE_VERSION = 1
        const val MAX_MESSAGE_CHARACTERS = 16_384
        val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9:._-]{1,160}")
        val WIRE_FIELDS =
            setOf(
                "version", "type", "messageId", "sourceServer", "lobbyId", "hostId", "hostServer", "participants",
                "layout", "kitPolicy", "sharedKitId", "expiresAtEpochMillis", "targetId", "response", "kitId",
            )
        val REQUIRED_WIRE_FIELDS = WIRE_FIELDS - setOf("sharedKitId", "targetId", "response", "kitId")
        val PARTICIPANT_FIELDS = setOf("playerId", "name", "originServer")
    }
}
