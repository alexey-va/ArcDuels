package ru.ruscrafting.duels.redis

import com.google.gson.JsonParser
import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.redis.RedisOperations
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.CombatModifiers
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Duration
import java.time.Instant
import java.util.UUID

class CrossServerChallengeBusTest : StringSpec({
    val offer =
        CrossServerChallengeMessage(
            messageId = "challenge:offer:1",
            sourceServer = ServerId("spawn"),
            type = ChallengeMessageType.OFFER,
            challenge =
                DuelChallenge.create(
                    PlayerId(UUID.randomUUID()),
                    PlayerId(UUID.randomUUID()),
                    DuelRules(
                        mode = DuelMode.KIT,
                        kitId = KitId("uhc"),
                        ranked = true,
                        bestOf = 5,
                        objective = DuelObjectiveType.KING_OF_THE_HILL,
                        modifiers =
                            CombatModifiers(
                                projectiles = false,
                                consumables = true,
                                enderPearls = false,
                                naturalRegeneration = false,
                                suddenDeathAfterSeconds = 600,
                                kingOfTheHillCaptureSeconds = 45,
                                boxingHitsToWin = 250,
                                comboHitsToWin = 20,
                            ),
                    ),
                    Instant.parse("2026-08-14T09:00:00Z"),
                    Duration.ofSeconds(45),
                    ArenaSelection(ServerId("parkour"), ArenaId("kit-one")),
                ),
            kitFingerprint = KIT_FINGERPRINT,
            challengerName = "Alice",
            targetName = "Bob",
            challengerServer = ServerId("spawn"),
            targetServer = ServerId("survival"),
            matchServer = null,
        )

    "codec round trips offers and terminal resolutions" {
        val codec = ChallengeMessageCodec()

        codec.decode(codec.encode(offer)) shouldBe offer
        val resolution =
            offer.copy(
                messageId = "challenge:accepted:1",
                sourceServer = ServerId("survival"),
                type = ChallengeMessageType.RESOLUTION,
                challenge = offer.challenge.resolve(ChallengeStatus.ACCEPTED, offer.challenge.createdAt),
                matchServer = ServerId("parkour"),
            )
        codec.decode(codec.encode(resolution)) shouldBe resolution
        resolution.kitFingerprint shouldBe offer.kitFingerprint
    }

    "rematch resolution keeps current acceptance servers separate from inventory origins" {
        val resolution =
            offer.copy(
                messageId = "challenge:accepted:arena-lobby",
                sourceServer = ServerId("spawn"),
                type = ChallengeMessageType.RESOLUTION,
                challenge = offer.challenge.resolve(ChallengeStatus.ACCEPTED, offer.challenge.createdAt),
                challengerServer = ServerId("spawn"),
                targetServer = ServerId("survival"),
                matchServer = ServerId("parkour"),
                challengerCurrentServer = ServerId("spawn"),
                targetCurrentServer = ServerId("spawn"),
            )

        ChallengeMessageCodec().decode(ChallengeMessageCodec().encode(resolution)) shouldBe resolution
    }

    "codec rejects messages produced before current server routes were required" {
        val codec = ChallengeMessageCodec()
        val legacyPayload = JsonParser.parseString(codec.encode(offer)).asJsonObject
        legacyPayload.remove("challengerCurrentServer")
        legacyPayload.remove("targetCurrentServer")

        shouldThrow<IllegalArgumentException> { codec.decode(legacyPayload.toString()) }
    }

    "codec carries objective-specific hit targets and rejects the previous schema" {
        val codec = ChallengeMessageCodec()
        val boxing =
            offer.copy(
                challenge =
                    offer.challenge.copy(
                        rules =
                            DuelRules(
                                DuelMode.KIT,
                                KitId("boxing"),
                                objective = DuelObjectiveType.BOXING,
                                modifiers = CombatModifiers(false, false, false, false, boxingHitsToWin = 200),
                            ),
                    ),
            )

        val continuation = boxing.copy(recoveryMatchId = ru.ruscrafting.duels.domain.MatchId.random())
        codec.decode(codec.encode(continuation)) shouldBe continuation
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            codec.decode(codec.encode(boxing).replace("\"version\":5", "\"version\":4"))
        }
    }

    "codec requires an exact kit fingerprint and permits null only for own inventory" {
        val codec = ChallengeMessageCodec()
        val missing = JsonParser.parseString(codec.encode(offer)).asJsonObject.apply { remove("kitFingerprint") }
        val uppercase = JsonParser.parseString(codec.encode(offer)).asJsonObject.apply {
            addProperty("kitFingerprint", "A".repeat(64))
        }

        shouldThrow<IllegalArgumentException> { codec.decode(missing.toString()) }
        shouldThrow<IllegalArgumentException> { codec.decode(uppercase.toString()) }
        shouldThrow<IllegalArgumentException> { offer.copy(kitFingerprint = null) }

        val ownInventoryOffer =
            offer.copy(
                challenge = offer.challenge.copy(rules = DuelRules(DuelMode.OWN_INVENTORY)),
                kitFingerprint = null,
            )
        codec.decode(codec.encode(ownInventoryOffer)) shouldBe ownInventoryOffer
        shouldThrow<IllegalArgumentException> { ownInventoryOffer.copy(kitFingerprint = KIT_FINGERPRINT) }
    }

    "bus authenticates origin and deduplicates challenge messages" {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val bus = CrossServerChallengeBus(redis, ServerId("survival"))
        val received = mutableListOf<CrossServerChallengeMessage>()
        bus.subscribe(received::add)
        val payload = ChallengeMessageCodec().encode(offer)

        redis.simulateExternalMessage(CrossServerChallengeBus.CHANNEL, payload, "spoofed")
        received shouldBe emptyList()
        redis.simulateExternalMessage(CrossServerChallengeBus.CHANNEL, payload, "spawn")
        redis.simulateExternalMessage(CrossServerChallengeBus.CHANNEL, payload, "spawn")

        received shouldContainExactly listOf(offer)
        bus.close()
    }

    "challenge messages can only originate from the participant server authorized for that transition" {
        shouldThrow<IllegalArgumentException> {
            offer.copy(sourceServer = ServerId("parkour"))
        }
        shouldThrow<IllegalArgumentException> {
            offer.copy(
                messageId = "challenge:accepted:spoofed",
                type = ChallengeMessageType.RESOLUTION,
                challenge = offer.challenge.resolve(ChallengeStatus.ACCEPTED, offer.challenge.createdAt),
                matchServer = ServerId("parkour"),
            )
        }
    }

    "a failed Redis publication cannot advance the local challenge state" {
        val redis = mockk<RedisOperations>(relaxed = true)
        every { redis.publish(any(), any()) } throws IllegalStateException("redis unavailable")
        val bus = CrossServerChallengeBus(redis, ServerId("spawn"))
        val received = mutableListOf<CrossServerChallengeMessage>()
        bus.subscribe(received::add)

        shouldThrow<IllegalStateException> { bus.publish(offer) }

        received shouldBe emptyList()
        bus.close()
    }
})

private val KIT_FINGERPRINT = "a".repeat(64)
