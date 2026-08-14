package ru.ruscrafting.duels.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.ChallengeStatus
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
                ),
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

        codec.decode(codec.encode(boxing)) shouldBe boxing
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            codec.decode(codec.encode(boxing).replace("\"version\":2", "\"version\":1"))
        }
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
})
