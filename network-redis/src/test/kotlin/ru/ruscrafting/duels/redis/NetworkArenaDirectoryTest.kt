package ru.ruscrafting.duels.redis

import com.google.gson.Gson
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
import ru.ruscrafting.duels.domain.CombatModifiers
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

    fun capacity(vararg values: Pair<DuelObjectiveType, ObjectiveCapacity>): ArenaModeCapacity =
        ArenaModeCapacity(DuelObjectiveType.entries.associateWith { ObjectiveCapacity(0, 0) } + values.toMap())

    fun statusJson(
        server: String,
        ownInventory: ArenaModeCapacity,
        kit: ArenaModeCapacity,
        queuedPairs: Int = 0,
    ): String =
        Gson().toJson(
            mapOf(
                "version" to 3,
                "server" to server,
                "ownInventory" to mapOf("objectives" to ownInventory.objectives.mapKeys { it.key.name }),
                "kit" to mapOf("objectives" to kit.objectives.mapKeys { it.key.name }),
                "queuedPairs" to queuedPairs,
            ),
        )

    "selects a live compatible node with free capacity before a queued node" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        directory.publish(
            ArenaNodeStatus(
                ServerId("spawn"),
                ownInventory =
                    capacity(
                        DuelObjectiveType.ELIMINATION to ObjectiveCapacity(2, 0),
                        DuelObjectiveType.KING_OF_THE_HILL to ObjectiveCapacity(1, 0),
                    ),
                kit = capacity(),
                queuedPairs = 1,
            ),
        )
        redis.simulateExternalMessage(
            NetworkArenaDirectory.CHANNEL,
            statusJson(
                "parkour",
                capacity(),
                capacity(
                    DuelObjectiveType.ELIMINATION to ObjectiveCapacity(5, 3),
                    DuelObjectiveType.BOXING to ObjectiveCapacity(2, 2),
                ),
            ),
            "parkour",
        )

        directory.select(DuelRules(DuelMode.KIT, KitId("classic"))) shouldBe ServerId("parkour")
        directory.select(DuelRules(DuelMode.OWN_INVENTORY)) shouldBe ServerId("spawn")
        directory.select(DuelRules(DuelMode.OWN_INVENTORY, objective = DuelObjectiveType.KING_OF_THE_HILL)) shouldBe
            ServerId("spawn")
        directory.select(DuelRules(DuelMode.KIT, KitId("classic"), objective = DuelObjectiveType.KING_OF_THE_HILL)) shouldBe null
        directory.select(
            DuelRules(
                DuelMode.KIT,
                KitId("boxing"),
                objective = DuelObjectiveType.BOXING,
                modifiers = CombatModifiers(false, false, false, false),
            ),
        ) shouldBe ServerId("parkour")
        directory.close()
    }

    "rejects spoofed nodes and stops routing to stale heartbeats" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        val status =
            statusJson(
                "parkour",
                capacity(),
                capacity(DuelObjectiveType.ELIMINATION to ObjectiveCapacity(5, 5)),
            )
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
            """{"version":2,"server":"legacy","ownInventory":{"generalTotal":5,"generalFree":5,"kingOfTheHillTotal":1,"kingOfTheHillFree":1},"kit":{"generalTotal":0,"generalFree":0,"kingOfTheHillTotal":0,"kingOfTheHillFree":0},"queuedPairs":0}"""

        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, oldStatus, "legacy")

        directory.select(DuelRules(DuelMode.OWN_INVENTORY)) shouldBe null
        directory.activeNodes() shouldBe emptyList()
        verify(exactly = 1) { logger.debug("Ignored ArcDuels arena status version {} from {}", 2, "legacy") }
        verify(exactly = 0) { logger.warn(any<String>(), any<Any>(), any<Throwable>()) }
        directory.close()
    }
})
