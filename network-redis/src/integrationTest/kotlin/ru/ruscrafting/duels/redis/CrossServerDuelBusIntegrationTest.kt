package ru.ruscrafting.duels.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.arc.testing.containers.RedisTestService
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.MatchCompletedEvent
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Duration
import java.time.Instant
import java.util.UUID

class CrossServerDuelBusIntegrationTest : StringSpec({
    "validated duel topic crosses a real Redis transport exactly once" {
        RedisTestService.start().use { redisService ->
            val endpoint = RedisConnection(redisService.endpoint.host, redisService.endpoint.port)
            RedisManager(endpoint, ServerIdentity { "spawn" }).use { spawnRedis ->
                RedisManager(endpoint, ServerIdentity { "survival" }).use { survivalRedis ->
                    CrossServerDuelBus(spawnRedis, ServerId("spawn")).use { spawnBus ->
                        CrossServerDuelBus(survivalRedis, ServerId("survival")).use { survivalBus ->
                            val received = mutableListOf<String>()
                            survivalBus.subscribe { received += it.eventId }
                            spawnRedis.init()
                            survivalRedis.init()
                            await(Duration.ofSeconds(10)) { spawnRedis.isSubscriptionActive() && survivalRedis.isSubscriptionActive() }
                            val event = MatchCompletedEvent(
                                eventId = "match:redis-integration",
                                occurredAt = Instant.parse("2026-08-30T12:00:00Z"),
                                sourceServer = ServerId("spawn"),
                                matchId = MatchId(UUID(0, 1)),
                                winner = PlayerId(UUID(0, 2)),
                                loser = PlayerId(UUID(0, 3)),
                                mode = DuelMode.OWN_INVENTORY,
                                kitId = null,
                                ranked = false,
                                winnerRating = 1_000,
                            )

                            spawnBus.publish(event).get()
                            await(Duration.ofSeconds(10)) { received.size == 1 }
                            received shouldBe listOf(event.eventId)
                        }
                    }
                }
            }
        }
    }
})

private fun await(timeout: Duration, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for Redis integration condition" }
        Thread.sleep(25)
    }
}
