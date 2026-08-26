package ru.ruscrafting.duels.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.testing.DeterministicClock
import ru.ruscrafting.duels.domain.ServerId
import java.time.Duration
import java.time.Instant
import java.util.UUID

class NetworkPlayerDirectoryTest : StringSpec({
    lateinit var clock: DeterministicClock

    beforeTest { clock = DeterministicClock.at(Instant.parse("2026-08-14T09:00:00Z")) }

    "accepts the authenticated ProxyARC snapshot and expires stale players" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkPlayerDirectory(redis, clock = clock)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        redis.simulateExternalMessage(
            NetworkPlayerDirectory.CHANNEL,
            """[{"username":"Alice","server":"spawn","uuid":"$first","joinTime":1},{"username":"Bob","server":"survival","uuid":"$second","joinTime":2}]""",
            "proxy",
        )

        directory.players().map(NetworkPlayer::username) shouldContainExactly listOf("Alice", "Bob")
        directory.find("bOb")?.server shouldBe ServerId("survival")
        clock.advance(Duration.ofSeconds(5))
        directory.players() shouldBe emptyList()
        directory.close()
    }

    "ignores spoofed origins and preserves the last valid snapshot after malformed input" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkPlayerDirectory(redis, clock = clock)
        val playerId = UUID.randomUUID()
        val valid = """[{"username":"Alice","server":"spawn","uuid":"$playerId","joinTime":1}]"""

        redis.simulateExternalMessage(NetworkPlayerDirectory.CHANNEL, valid, "not-proxy")
        directory.players() shouldBe emptyList()
        redis.simulateExternalMessage(NetworkPlayerDirectory.CHANNEL, valid, "proxy")
        redis.simulateExternalMessage(NetworkPlayerDirectory.CHANNEL, "[{\"username\":\"bad name\"}]", "proxy")

        directory.players().map(NetworkPlayer::username) shouldContainExactly listOf("Alice")
        directory.close()
    }

    "skips players whose proxy backend is not assigned yet" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkPlayerDirectory(redis, clock = clock)
        val ready = UUID.randomUUID()
        val connecting = UUID.randomUUID()
        redis.simulateExternalMessage(
            NetworkPlayerDirectory.CHANNEL,
            """[{"username":"Ready","server":"spawn","uuid":"$ready","joinTime":1},{"username":"Connecting","server":"","uuid":"$connecting","joinTime":2}]""",
            "proxy",
        )

        directory.players().map(NetworkPlayer::username) shouldContainExactly listOf("Ready")
        directory.close()
    }

    "accepts the configured Floodgate dot prefix without rejecting the whole snapshot" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkPlayerDirectory(redis, clock = clock)
        val bedrock = UUID.randomUUID()
        val java = UUID.randomUUID()
        redis.simulateExternalMessage(
            NetworkPlayerDirectory.CHANNEL,
            """[{"username":".Bedrock_User","server":"spawn","uuid":"$bedrock","joinTime":1},{"username":"JavaUser","server":"survival","uuid":"$java","joinTime":2}]""",
            "proxy",
        )

        directory.players().map(NetworkPlayer::username) shouldContainExactly listOf(".Bedrock_User", "JavaUser")
        directory.close()
    }

    "a backwards wall-clock jump fails closed instead of keeping a stale proxy snapshot" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkPlayerDirectory(redis, clock = clock)
        val playerId = UUID.randomUUID()
        redis.simulateExternalMessage(
            NetworkPlayerDirectory.CHANNEL,
            """[{"username":"Alice","server":"spawn","uuid":"$playerId","joinTime":1}]""",
            "proxy",
        )

        clock.advance(Duration.ofSeconds(-1))

        directory.players() shouldBe emptyList()
        directory.close()
    }
})
