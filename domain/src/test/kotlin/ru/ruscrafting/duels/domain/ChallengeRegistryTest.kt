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

    "network challenge registration is idempotent but rejects conflicting payloads" {
        val clock = Clock.fixed(Instant.parse("2026-08-14T09:00:00Z"), ZoneOffset.UTC)
        val registry = ChallengeRegistry(clock)
        val first = PlayerId(UUID.randomUUID())
        val second = PlayerId(UUID.randomUUID())
        val challenge = DuelChallenge.create(first, second, DuelRules(DuelMode.OWN_INVENTORY), clock.instant(), Duration.ofSeconds(45))

        registry.register(challenge) shouldBe challenge
        registry.register(challenge) shouldBe challenge
        shouldThrow<IllegalArgumentException> {
            registry.register(challenge.copy(target = PlayerId(UUID.randomUUID())))
        }
    }

    "network resolution releases the pending player pair" {
        val clock = Clock.fixed(Instant.parse("2026-08-14T09:00:00Z"), ZoneOffset.UTC)
        val registry = ChallengeRegistry(clock)
        val first = PlayerId(UUID.randomUUID())
        val second = PlayerId(UUID.randomUUID())
        val challenge = DuelChallenge.create(first, second, DuelRules(DuelMode.OWN_INVENTORY), clock.instant(), Duration.ofSeconds(45))
        registry.register(challenge)

        registry.registerResolution(challenge.resolve(ChallengeStatus.DENIED, clock.instant()))

        shouldThrow<IllegalArgumentException> {
            registry.registerResolution(challenge.resolve(ChallengeStatus.ACCEPTED, clock.instant()))
        }

        registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY)).status shouldBe ChallengeStatus.PENDING
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

    "reverse duplicate is rejected and expiry releases the pair" {
        var now = Instant.parse("2026-08-13T10:00:00Z")
        val mutableClock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC
                override fun withZone(zone: ZoneId): Clock = this
                override fun instant(): Instant = now
            }
        val registry = ChallengeRegistry(mutableClock, Duration.ofSeconds(1))
        registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY))

        shouldThrow<IllegalStateException> {
            registry.create(second, first, DuelRules(DuelMode.OWN_INVENTORY))
        }

        now = now.plusSeconds(2)
        registry.create(second, first, DuelRules(DuelMode.OWN_INVENTORY)).status shouldBe ChallengeStatus.PENDING
    }

    "challenge TTL is bounded" {
        shouldThrow<IllegalArgumentException> {
            DuelChallenge.create(first, second, DuelRules(DuelMode.OWN_INVENTORY), clock.instant(), Duration.ZERO)
        }
        shouldThrow<IllegalArgumentException> {
            DuelChallenge.create(first, second, DuelRules(DuelMode.OWN_INVENTORY), clock.instant(), Duration.ofMinutes(11))
        }
    }

    "resolved challenge history remains bounded on a long-running server" {
        val registry = ChallengeRegistry(clock)
        repeat(2_000) {
            val challenge = registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY))
            registry.resolve(challenge.id, second, ChallengeStatus.DENIED)
        }

        registry.retainedChallengeCount() shouldBe 1_024
        registry.pendingFor(first) shouldBe emptyList()
    }
})
