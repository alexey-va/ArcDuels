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

    "explicit arena selection is immutable challenge state" {
        val registry = ChallengeRegistry(clock)
        val selection = ArenaSelection(ServerId("spawn"), ArenaId("kit-test"))

        val challenge = registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY), selection)

        challenge.arenaSelection shouldBe selection
        registry.resolve(challenge.id, second, ChallengeStatus.ACCEPTED).arenaSelection shouldBe selection
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

    "late offer delivery keeps an already received network resolution" {
        val registry = ChallengeRegistry(clock)
        val challenge = DuelChallenge.create(first, second, DuelRules(DuelMode.OWN_INVENTORY), clock.instant(), Duration.ofSeconds(45))
        val resolved = challenge.resolve(ChallengeStatus.ACCEPTED, clock.instant())

        registry.registerResolution(resolved) shouldBe resolved

        registry.register(challenge) shouldBe resolved
        registry.find(challenge.id) shouldBe resolved
    }

    "expired remote offer never claims its players" {
        var now = Instant.parse("2026-08-14T09:00:00Z")
        val mutableClock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = now
            }
        val registry = ChallengeRegistry(mutableClock)
        val challenge = DuelChallenge.create(first, second, DuelRules(DuelMode.OWN_INVENTORY), now, Duration.ofSeconds(1))
        now = now.plusSeconds(1)

        registry.register(challenge).status shouldBe ChallengeStatus.EXPIRED
        registry.pendingFor(first) shouldBe emptyList()
        registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY)).status shouldBe ChallengeStatus.PENDING
    }

    "network resolution cannot replace the arena selected in the offer" {
        val registry = ChallengeRegistry(clock)
        val selected = ArenaSelection(ServerId("spawn"), ArenaId("kit-test"))
        val challenge = registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY), selected)

        shouldThrow<IllegalArgumentException> {
            registry.registerResolution(
                challenge.resolve(ChallengeStatus.ACCEPTED, clock.instant())
                    .copy(arenaSelection = ArenaSelection(ServerId("parkour"), ArenaId("kit1"))),
            )
        }
        registry.find(challenge.id)?.status shouldBe ChallengeStatus.PENDING
        registry.find(challenge.id)?.arenaSelection shouldBe selected
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

    "scheduled expiry transition is idempotent and releases the pair" {
        var now = Instant.parse("2026-08-13T10:00:00Z")
        val mutableClock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC
                override fun withZone(zone: ZoneId): Clock = this
                override fun instant(): Instant = now
            }
        val registry = ChallengeRegistry(mutableClock, Duration.ofSeconds(1))
        val challenge = registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY))

        registry.expireIfDue(challenge.id)?.status shouldBe ChallengeStatus.PENDING
        now = now.plusSeconds(1)
        registry.expireIfDue(challenge.id)?.status shouldBe ChallengeStatus.EXPIRED
        registry.expireIfDue(challenge.id)?.status shouldBe ChallengeStatus.EXPIRED
        registry.create(second, first, DuelRules(DuelMode.OWN_INVENTORY)).status shouldBe ChallengeStatus.PENDING
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

    "one player cannot participate in two pending challenges" {
        val registry = ChallengeRegistry(clock)
        val third = PlayerId(UUID.randomUUID())
        val fourth = PlayerId(UUID.randomUUID())
        val pending = registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY))

        shouldThrow<IllegalStateException> {
            registry.create(first, third, DuelRules(DuelMode.OWN_INVENTORY))
        }
        shouldThrow<IllegalStateException> {
            registry.create(third, second, DuelRules(DuelMode.OWN_INVENTORY))
        }

        registry.resolve(pending.id, second, ChallengeStatus.DENIED)
        registry.create(first, third, DuelRules(DuelMode.OWN_INVENTORY)).status shouldBe ChallengeStatus.PENDING
        registry.create(second, fourth, DuelRules(DuelMode.OWN_INVENTORY)).status shouldBe ChallengeStatus.PENDING
    }

    "challenge TTL is bounded" {
        shouldThrow<IllegalArgumentException> {
            DuelChallenge.create(first, second, DuelRules(DuelMode.OWN_INVENTORY), clock.instant(), Duration.ZERO)
        }
        shouldThrow<IllegalArgumentException> {
            DuelChallenge.create(first, second, DuelRules(DuelMode.OWN_INVENTORY), clock.instant(), Duration.ofMinutes(11))
        }
    }

    "TTL update preserves existing challenge deadlines and pending entries" {
        val registry = ChallengeRegistry(clock, Duration.ofSeconds(45))
        val existing = registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY))
        val third = PlayerId(UUID.randomUUID())
        registry.pendingCount shouldBe 1

        registry.updateTtl(Duration.ofSeconds(90))

        registry.find(existing.id)?.expiresAt shouldBe clock.instant().plusSeconds(45)
        registry.pendingCount shouldBe 1
        registry.pendingFor(first).map(DuelChallenge::id) shouldBe listOf(existing.id)
        shouldThrow<IllegalStateException> {
            registry.create(first, third, DuelRules(DuelMode.OWN_INVENTORY))
        }
    }

    "new challenges use the latest TTL" {
        val registry = ChallengeRegistry(clock, Duration.ofSeconds(45))
        registry.updateTtl(Duration.ofSeconds(90))

        val challenge = registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY))

        challenge.expiresAt shouldBe clock.instant().plusSeconds(90)
        registry.pendingCount shouldBe 1
    }

    "invalid TTL update is rejected without replacing the previous value" {
        val registry = ChallengeRegistry(clock, Duration.ofSeconds(45))

        listOf(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMinutes(10).plusNanos(1)).forEach { invalidTtl ->
            shouldThrow<IllegalArgumentException> {
                registry.updateTtl(invalidTtl)
            }
        }

        val challenge = registry.create(first, second, DuelRules(DuelMode.OWN_INVENTORY))
        challenge.expiresAt shouldBe clock.instant().plusSeconds(45)
        registry.pendingCount shouldBe 1
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
