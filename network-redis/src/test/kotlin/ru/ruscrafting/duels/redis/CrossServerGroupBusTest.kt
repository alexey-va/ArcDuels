package ru.ruscrafting.duels.redis

import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.util.UUID

class CrossServerGroupBusTest : StringSpec({
    val host = NetworkGroupParticipant(PlayerId(UUID.randomUUID()), "Host", ServerId("spawn"))
    val local = NetworkGroupParticipant(PlayerId(UUID.randomUUID()), "Local", ServerId("spawn"))
    val remote = NetworkGroupParticipant(PlayerId(UUID.randomUUID()), "Remote", ServerId("parkour"))
    val offer =
        CrossServerGroupMessage(
            messageId = "group:offer:remote",
            sourceServer = ServerId("spawn"),
            type = GroupLobbyMessageType.OFFER,
            lobbyId = UUID.randomUUID(),
            hostId = host.playerId,
            hostServer = ServerId("spawn"),
            participants = listOf(host, local, remote),
            layout = MultiplayerLayout.TWO_TEAMS,
            kitPolicy = MultiplayerKitPolicy.SHARED,
            sharedKitId = KitId("classic"),
            expiresAtEpochMillis = 1_800_000_000_000L,
            targetId = remote.playerId,
        )

    "codec round trips every cross-server group transition" {
        val codec = GroupMessageCodec()
        val accepted =
            offer.copy(
                messageId = "group:accepted:remote",
                sourceServer = ServerId("parkour"),
                type = GroupLobbyMessageType.RESPONSE,
                response = GroupLobbyResponse.ACCEPTED,
                kitId = KitId("classic"),
            )
        val prepare =
            offer.copy(
                messageId = "group:prepare",
                type = GroupLobbyMessageType.PREPARE,
                targetId = null,
            )
        val ready =
            prepare.copy(
                messageId = "group:ready:remote",
                sourceServer = ServerId("parkour"),
                type = GroupLobbyMessageType.READY,
                targetId = remote.playerId,
            )
        val cancel = prepare.copy(messageId = "group:cancel", type = GroupLobbyMessageType.CANCEL)

        listOf(offer, accepted, prepare, ready, cancel).forEach { message ->
            codec.decode(codec.encode(message)) shouldBe message
        }
    }

    "bus authenticates embedded origins and deduplicates group messages" {
        val redis = InMemoryRedis(ServerIdentity { "parkour" })
        val bus = CrossServerGroupBus(redis, ServerId("parkour"))
        val received = mutableListOf<CrossServerGroupMessage>()
        bus.subscribe(received::add)
        val payload = GroupMessageCodec().encode(offer)

        redis.simulateExternalMessage(CrossServerGroupBus.CHANNEL, payload, "spoofed")
        redis.simulateExternalMessage(CrossServerGroupBus.CHANNEL, payload, "spawn")
        redis.simulateExternalMessage(CrossServerGroupBus.CHANNEL, payload, "spawn")

        received shouldContainExactly listOf(offer)
        bus.close()
    }

    "group transitions reject unauthorized servers and incomplete acceptances" {
        shouldThrow<IllegalArgumentException> { offer.copy(sourceServer = ServerId("parkour")) }
        shouldThrow<IllegalArgumentException> {
            offer.copy(
                messageId = "group:bad-response",
                sourceServer = ServerId("parkour"),
                type = GroupLobbyMessageType.RESPONSE,
                response = GroupLobbyResponse.ACCEPTED,
            )
        }
        shouldThrow<IllegalArgumentException> {
            offer.copy(
                messageId = "group:spoofed-ready",
                type = GroupLobbyMessageType.READY,
            )
        }
    }

    "codec rejects unknown nested participant fields" {
        val codec = GroupMessageCodec()
        val payload = JsonParser.parseString(codec.encode(offer)).asJsonObject
        payload.getAsJsonArray("participants").first().asJsonObject.addProperty("admin", true)

        shouldThrow<IllegalArgumentException> { codec.decode(payload.toString()) }
    }
})
