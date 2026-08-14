package ru.ruscrafting.duels.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class NetworkArenaDirectoryTest : StringSpec({
    var now = Instant.parse("2026-08-14T09:00:00Z")
    val clock =
        object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now
        }

    beforeTest { now = Instant.parse("2026-08-14T09:00:00Z") }

    "selects a live compatible node with free capacity before a queued node" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        directory.publish(ArenaNodeStatus(ServerId("spawn"), 2, 0, 1, 0, 1))
        redis.simulateExternalMessage(
            NetworkArenaDirectory.CHANNEL,
            """{"version":1,"server":"parkour","generalTotal":5,"generalFree":3,"kingOfTheHillTotal":0,"kingOfTheHillFree":0,"queuedPairs":0}""",
            "parkour",
        )

        directory.select(DuelObjectiveType.ELIMINATION) shouldBe ServerId("parkour")
        directory.select(DuelObjectiveType.KING_OF_THE_HILL) shouldBe ServerId("spawn")
        directory.close()
    }

    "rejects spoofed nodes and stops routing to stale heartbeats" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        val status = """{"version":1,"server":"parkour","generalTotal":5,"generalFree":5,"kingOfTheHillTotal":1,"kingOfTheHillFree":1,"queuedPairs":0}"""

        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, status, "spoofed")
        directory.select(DuelObjectiveType.ELIMINATION) shouldBe null
        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, status, "parkour")
        now = now.plusSeconds(6)

        directory.select(DuelObjectiveType.ELIMINATION) shouldBe null
        directory.close()
    }
})
