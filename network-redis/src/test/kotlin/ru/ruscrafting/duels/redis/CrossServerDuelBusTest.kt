package ru.ruscrafting.duels.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.DuelEvent
import ru.ruscrafting.duels.domain.LeaderboardInvalidatedEvent
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Duration

class CrossServerDuelBusTest : StringSpec({
    "external events are delivered once and local echo is ignored" {
        val redis = InMemoryRedis(ServerIdentity { "duels-1" })
        val received = mutableListOf<DuelEvent>()
        val bus = CrossServerDuelBus(redis, ServerId("duels-1"))
        bus.subscribe(received::add)
        val external =
            LeaderboardInvalidatedEvent(
                eventId = "match-1:leaderboard",
                occurredAt = Instant.parse("2026-08-13T10:00:00Z"),
                sourceServer = ServerId("duels-2"),
                revision = 42,
            )
        val payload = DuelEventCodec().encode(external)

        redis.simulateExternalMessage(CrossServerDuelBus.CHANNEL, payload, "duels-2")
        redis.simulateExternalMessage(CrossServerDuelBus.CHANNEL, payload, "duels-2")

        received shouldContainExactly listOf(external)
        bus.close()
    }

    "published local events are delivered locally once" {
        val redis = InMemoryRedis(ServerIdentity { "duels-1" })
        val received = mutableListOf<DuelEvent>()
        val bus = CrossServerDuelBus(redis, ServerId("duels-1"))
        bus.subscribe(received::add)
        val local =
            LeaderboardInvalidatedEvent(
                eventId = "match-2:leaderboard",
                occurredAt = Instant.parse("2026-08-13T10:00:00Z"),
                sourceServer = ServerId("duels-1"),
                revision = 43,
            )

        bus.publish(local).get()

        received shouldContainExactly listOf(local)
        bus.close()
    }

    "dedupe expires after its TTL even when the cache never reaches capacity" {
        var now = Instant.parse("2026-08-13T10:00:00Z")
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC
                override fun withZone(zone: ZoneId): Clock = this
                override fun instant(): Instant = now
            }
        val redis = InMemoryRedis(ServerIdentity { "duels-1" })
        val received = mutableListOf<DuelEvent>()
        val bus = CrossServerDuelBus(redis, ServerId("duels-1"), clock = clock)
        bus.subscribe(received::add)
        val event =
            LeaderboardInvalidatedEvent(
                "expiring:leaderboard",
                now,
                ServerId("duels-2"),
                1,
            )
        val payload = DuelEventCodec().encode(event)

        redis.simulateExternalMessage(CrossServerDuelBus.CHANNEL, payload, "duels-2")
        now = now.plus(Duration.ofHours(1))
        redis.simulateExternalMessage(CrossServerDuelBus.CHANNEL, payload, "duels-2")

        received shouldContainExactly listOf(event, event)
        bus.close()
    }

    "the same event id from different authenticated origins does not collide" {
        val redis = InMemoryRedis(ServerIdentity { "duels-1" })
        val received = mutableListOf<DuelEvent>()
        val bus = CrossServerDuelBus(redis, ServerId("duels-1"))
        bus.subscribe(received::add)
        val first = LeaderboardInvalidatedEvent("shared:id", Instant.EPOCH, ServerId("duels-2"), 1)
        val second = first.copy(sourceServer = ServerId("duels-3"), revision = 2)

        redis.simulateExternalMessage(CrossServerDuelBus.CHANNEL, DuelEventCodec().encode(first), "duels-2")
        redis.simulateExternalMessage(CrossServerDuelBus.CHANNEL, DuelEventCodec().encode(second), "duels-3")

        received shouldContainExactly listOf(first, second)
        bus.close()
    }

    "origin spoofing and one broken subscriber cannot corrupt delivery" {
        val redis = InMemoryRedis(ServerIdentity { "duels-1" })
        val received = mutableListOf<DuelEvent>()
        val bus = CrossServerDuelBus(redis, ServerId("duels-1"))
        bus.subscribe { error("listener failure") }
        bus.subscribe(received::add)
        val event = LeaderboardInvalidatedEvent("safe:id", Instant.EPOCH, ServerId("duels-2"), 1)
        val payload = DuelEventCodec().encode(event)

        redis.simulateExternalMessage(CrossServerDuelBus.CHANNEL, payload, "spoofed-origin")
        received.isEmpty() shouldBe true
        redis.simulateExternalMessage(CrossServerDuelBus.CHANNEL, payload, "duels-2")

        received shouldContainExactly listOf(event)
        bus.close()
    }
})
