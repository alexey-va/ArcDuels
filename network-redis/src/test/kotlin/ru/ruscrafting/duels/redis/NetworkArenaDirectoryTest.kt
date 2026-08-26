package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.slf4j.Logger
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.redis.safety.RedisMessageRejection
import ru.arc.testing.DeterministicClock
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.CombatModifiers
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Duration
import java.time.Instant

class NetworkArenaDirectoryTest : StringSpec({
    lateinit var clock: DeterministicClock

    beforeTest { clock = DeterministicClock.at(Instant.parse("2026-08-14T09:00:00Z")) }

    fun arena(
        id: String,
        loadouts: Set<DuelMode>,
        objectives: Set<DuelObjectiveType>,
        available: Boolean,
    ) = ArenaAdvertisement(ArenaId(id), id.replace('-', ' '), loadouts, objectives, available)

    fun statusJson(
        server: String,
        arenas: List<ArenaAdvertisement>,
        queuedPairs: Int = 0,
    ): String =
        Gson().toJson(
            mapOf(
                "version" to 4,
                "server" to server,
                "arenas" to
                    arenas.map { advertised ->
                        mapOf(
                            "id" to advertised.id.value,
                            "displayName" to advertised.displayName,
                            "loadouts" to advertised.loadouts.map { it.name },
                            "objectives" to advertised.objectives.map { it.name },
                            "available" to advertised.available,
                        )
                    },
                "queuedPairs" to queuedPairs,
            ),
        )

    "selects a live compatible node and exposes exact player-selectable arenas" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        directory.publish(
            ArenaNodeStatus(
                ServerId("spawn"),
                listOf(
                    arena(
                        "spawn-own",
                        setOf(DuelMode.OWN_INVENTORY),
                        setOf(DuelObjectiveType.ELIMINATION, DuelObjectiveType.KING_OF_THE_HILL),
                        available = false,
                    ),
                ),
                queuedPairs = 1,
            ),
        )
        val parkourArenas =
            listOf(
                arena("kit-one", setOf(DuelMode.KIT), setOf(DuelObjectiveType.ELIMINATION), available = true),
                arena("boxing", setOf(DuelMode.KIT), setOf(DuelObjectiveType.BOXING), available = true),
            )
        redis.simulateExternalMessage(
            NetworkArenaDirectory.CHANNEL,
            statusJson("parkour", parkourArenas),
            "parkour",
        )

        val kitRules = DuelRules(DuelMode.KIT, KitId("classic"))
        directory.select(kitRules) shouldBe ServerId("parkour")
        directory.select(DuelRules(DuelMode.OWN_INVENTORY)) shouldBe ServerId("spawn")
        directory.select(
            DuelRules(
                DuelMode.KIT,
                KitId("boxing"),
                objective = DuelObjectiveType.BOXING,
                modifiers = CombatModifiers(false, false, false, false),
            ),
        ) shouldBe ServerId("parkour")
        directory.choices(kitRules) shouldContainExactly
            listOf(ArenaChoice(ArenaSelection(ServerId("parkour"), ArenaId("kit-one")), "kit one", true, 0))
        directory.select(kitRules, ArenaSelection(ServerId("parkour"), ArenaId("kit-one"))) shouldBe ServerId("parkour")
        directory.select(kitRules, ArenaSelection(ServerId("parkour"), ArenaId("boxing"))) shouldBe null
        directory.close()
    }

    "an explicitly selected busy arena stays pinned instead of rerouting" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        val rules = DuelRules(DuelMode.KIT, KitId("classic"))
        directory.publish(
            ArenaNodeStatus(
                ServerId("spawn"),
                listOf(arena("chosen", setOf(DuelMode.KIT), setOf(DuelObjectiveType.ELIMINATION), available = false)),
                queuedPairs = 4,
            ),
        )
        redis.simulateExternalMessage(
            NetworkArenaDirectory.CHANNEL,
            statusJson(
                "parkour",
                listOf(arena("free", setOf(DuelMode.KIT), setOf(DuelObjectiveType.ELIMINATION), available = true)),
            ),
            "parkour",
        )

        directory.select(rules) shouldBe ServerId("parkour")
        directory.select(rules, ArenaSelection(ServerId("spawn"), ArenaId("chosen"))) shouldBe ServerId("spawn")
        directory.close()
    }

    "rejects spoofed nodes and stops routing to stale heartbeats" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        val status =
            statusJson(
                "parkour",
                listOf(arena("kit", setOf(DuelMode.KIT), setOf(DuelObjectiveType.ELIMINATION), available = true)),
            )
        val rules = DuelRules(DuelMode.KIT, KitId("classic"))

        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, status, "spoofed")
        directory.select(rules) shouldBe null
        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, status, "parkour")
        clock.advance(Duration.ofSeconds(6))

        directory.select(rules) shouldBe null
        directory.close()
    }

    "rejects heartbeats from the removed previous schema" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val logger = mockk<Logger>(relaxed = true)
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock, logger = logger)
        val oldStatus =
            """{"version":3,"server":"legacy","ownInventory":{"objectives":{}},"kit":{"objectives":{}},"queuedPairs":0}"""

        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, oldStatus, "legacy")

        directory.select(DuelRules(DuelMode.OWN_INVENTORY)) shouldBe null
        directory.activeNodes() shouldBe emptyList()
        verify(exactly = 1) { logger.warn("Rejected ArcDuels arena status: {}", RedisMessageRejection.MALFORMED_PAYLOAD) }
        directory.close()
    }

    "rejects an oversized outbound arena heartbeat before publishing it" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        val arenas =
            List(1_000) { index ->
                ArenaAdvertisement(
                    ArenaId("arena-$index"),
                    "x".repeat(64),
                    DuelMode.entries.toSet(),
                    DuelObjectiveType.entries.toSet(),
                    available = true,
                )
            }

        shouldThrow<IllegalArgumentException> {
            directory.publish(ArenaNodeStatus(ServerId("spawn"), arenas, queuedPairs = 0))
        }
        directory.activeNodes() shouldBe emptyList()
        directory.close()
    }

    "a backwards wall-clock jump cannot keep routing to an unverified arena heartbeat" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        val rules = DuelRules(DuelMode.KIT, KitId("classic"))
        directory.publish(
            ArenaNodeStatus(
                ServerId("spawn"),
                listOf(arena("kit", setOf(DuelMode.KIT), setOf(DuelObjectiveType.ELIMINATION), available = true)),
                queuedPairs = 0,
            ),
        )

        clock.advance(Duration.ofSeconds(-1))

        directory.select(rules) shouldBe null
        directory.close()
    }
})
