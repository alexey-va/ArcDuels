package ru.ruscrafting.duels.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class ChallengeRegistryTest : StringSpec({
    val first = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val second = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    val clock = Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC)

    "only target may accept a challenge" {
        val registry = ChallengeRegistry(clock)
        val challenge = registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY))

        shouldThrow<IllegalArgumentException> {
            registry.resolve(challenge.id, first, ChallengeStatus.ACCEPTED)
        }
        registry.resolve(challenge.id, second, ChallengeStatus.ACCEPTED).status shouldBe ChallengeStatus.ACCEPTED
    }

    "expired challenge cannot be accepted" {
        var now = Instant.parse("2026-08-13T10:00:00Z")
        val mutableClock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = now
            }
        val registry = ChallengeRegistry(mutableClock, Duration.ofSeconds(1))
        val challenge = registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY))
        now = now.plusSeconds(2)

        registry.find(challenge.id)?.status shouldBe ChallengeStatus.EXPIRED
    }
})
