package ru.ruscrafting.duels.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.Logger
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.KitId
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
        directory.publish(
            ArenaNodeStatus(
                ServerId("spawn"),
                ownInventory = ArenaModeCapacity(2, 0, 1, 0),
                kit = ArenaModeCapacity(0, 0, 0, 0),
                queuedPairs = 1,
            ),
        )
        redis.simulateExternalMessage(
            NetworkArenaDirectory.CHANNEL,
            """{"version":2,"server":"parkour","ownInventory":{"generalTotal":0,"generalFree":0,"kingOfTheHillTotal":0,"kingOfTheHillFree":0},"kit":{"generalTotal":5,"generalFree":3,"kingOfTheHillTotal":0,"kingOfTheHillFree":0},"queuedPairs":0}""",
            "parkour",
        )

        directory.select(DuelRules(DuelMode.KIT, KitId("classic"))) shouldBe ServerId("parkour")
        directory.select(DuelRules(DuelMode.OWN_INVENTORY)) shouldBe ServerId("spawn")
        directory.select(DuelRules(DuelMode.OWN_INVENTORY, objective = DuelObjectiveType.KING_OF_THE_HILL)) shouldBe
            ServerId("spawn")
        directory.select(DuelRules(DuelMode.KIT, KitId("classic"), objective = DuelObjectiveType.KING_OF_THE_HILL)) shouldBe null
        directory.close()
    }

    "rejects spoofed nodes and stops routing to stale heartbeats" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        val status = """{"version":2,"server":"parkour","ownInventory":{"generalTotal":0,"generalFree":0,"kingOfTheHillTotal":0,"kingOfTheHillFree":0},"kit":{"generalTotal":5,"generalFree":5,"kingOfTheHillTotal":1,"kingOfTheHillFree":1},"queuedPairs":0}"""
        val rules = DuelRules(DuelMode.KIT, KitId("classic"))

        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, status, "spoofed")
        directory.select(rules) shouldBe null
        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, status, "parkour")
        now = now.plusSeconds(6)

        directory.select(rules) shouldBe null
        directory.close()
    }

    "ignores rolling-deployment heartbeats from the previous schema" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val logger = mockk<Logger>(relaxed = true)
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock, logger = logger)
        val oldStatus =
            """{"version":1,"server":"legacy","generalTotal":5,"generalFree":5,"kingOfTheHillTotal":1,"kingOfTheHillFree":1,"queuedPairs":0}"""

        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, oldStatus, "legacy")

        directory.select(DuelRules(DuelMode.OWN_INVENTORY)) shouldBe null
        directory.activeNodes() shouldBe emptyList()
        verify(exactly = 1) { logger.debug("Ignored ArcDuels arena status version {} from {}", 1, "legacy") }
        verify(exactly = 0) { logger.warn(any<String>(), any<Any>(), any<Throwable>()) }
        directory.close()
    }
})
