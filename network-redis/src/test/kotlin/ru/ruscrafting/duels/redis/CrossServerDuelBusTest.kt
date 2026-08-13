package ru.ruscrafting.duels.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.DuelEvent
import ru.ruscrafting.duels.domain.LeaderboardInvalidatedEvent
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant

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
})
