package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import com.google.gson.JsonParser
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
        kitFingerprints: Map<KitId, String> = emptyMap(),
        queuedPairs: Int = 0,
    ): String =
        Gson().toJson(
            mapOf(
                "version" to 5,
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
                "kitFingerprints" to
                    kitFingerprints.entries.sortedBy { it.key.value }.map { (kitId, fingerprint) ->
                        mapOf("kitId" to kitId.value, "fingerprint" to fingerprint)
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
                kitFingerprints = emptyMap(),
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
            statusJson(
                "parkour",
                parkourArenas,
                mapOf(KitId("classic") to CLASSIC_FINGERPRINT, KitId("boxing") to BOXING_FINGERPRINT),
            ),
            "parkour",
        )

        val kitRules = DuelRules(DuelMode.KIT, KitId("classic"))
        directory.select(kitRules, CLASSIC_FINGERPRINT) shouldBe ServerId("parkour")
        directory.select(DuelRules(DuelMode.OWN_INVENTORY), null) shouldBe ServerId("spawn")
        directory.select(
            DuelRules(
                DuelMode.KIT,
                KitId("boxing"),
                objective = DuelObjectiveType.BOXING,
                modifiers = CombatModifiers(false, false, false, false),
            ),
            BOXING_FINGERPRINT,
        ) shouldBe ServerId("parkour")
        directory.choices(kitRules, CLASSIC_FINGERPRINT) shouldContainExactly
            listOf(ArenaChoice(ArenaSelection(ServerId("parkour"), ArenaId("kit-one")), "kit one", true, 0))
        directory.select(
            kitRules,
            CLASSIC_FINGERPRINT,
            ArenaSelection(ServerId("parkour"), ArenaId("kit-one")),
        ) shouldBe ServerId("parkour")
        directory.select(
            kitRules,
            CLASSIC_FINGERPRINT,
            ArenaSelection(ServerId("parkour"), ArenaId("boxing")),
        ) shouldBe null
        directory.supports(ServerId("parkour"), kitRules, CLASSIC_FINGERPRINT) shouldBe true
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
                kitFingerprints = mapOf(KitId("classic") to CLASSIC_FINGERPRINT),
                queuedPairs = 4,
            ),
        )
        redis.simulateExternalMessage(
            NetworkArenaDirectory.CHANNEL,
            statusJson(
                "parkour",
                listOf(arena("free", setOf(DuelMode.KIT), setOf(DuelObjectiveType.ELIMINATION), available = true)),
                mapOf(KitId("classic") to CLASSIC_FINGERPRINT),
            ),
            "parkour",
        )

        directory.select(rules, CLASSIC_FINGERPRINT) shouldBe ServerId("parkour")
        directory.select(
            rules,
            CLASSIC_FINGERPRINT,
            ArenaSelection(ServerId("spawn"), ArenaId("chosen")),
        ) shouldBe ServerId("spawn")
        directory.close()
    }

    "rejects spoofed nodes and stops routing to stale heartbeats" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        val status =
            statusJson(
                "parkour",
                listOf(arena("kit", setOf(DuelMode.KIT), setOf(DuelObjectiveType.ELIMINATION), available = true)),
                mapOf(KitId("classic") to CLASSIC_FINGERPRINT),
            )
        val rules = DuelRules(DuelMode.KIT, KitId("classic"))

        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, status, "spoofed")
        directory.select(rules, CLASSIC_FINGERPRINT) shouldBe null
        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, status, "parkour")
        clock.advance(Duration.ofSeconds(6))

        directory.select(rules, CLASSIC_FINGERPRINT) shouldBe null
        directory.close()
    }

    "rejects heartbeats from the removed previous schema" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val logger = mockk<Logger>(relaxed = true)
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock, logger = logger)
        val oldStatus =
            """{"version":3,"server":"legacy","ownInventory":{"objectives":{}},"kit":{"objectives":{}},"queuedPairs":0}"""

        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, oldStatus, "legacy")

        directory.select(DuelRules(DuelMode.OWN_INVENTORY), null) shouldBe null
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
            directory.publish(ArenaNodeStatus(ServerId("spawn"), arenas, emptyMap(), queuedPairs = 0))
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
                kitFingerprints = mapOf(KitId("classic") to CLASSIC_FINGERPRINT),
                queuedPairs = 0,
            ),
        )

        clock.advance(Duration.ofSeconds(-1))

        directory.select(rules, CLASSIC_FINGERPRINT) shouldBe null
        directory.close()
    }

    "kit routing rejects a mismatched catalog and stops immediately after removal" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        val rules = DuelRules(DuelMode.KIT, KitId("classic"))
        val compatibleArena =
            listOf(arena("kit", setOf(DuelMode.KIT), setOf(DuelObjectiveType.ELIMINATION), available = true))

        redis.simulateExternalMessage(
            NetworkArenaDirectory.CHANNEL,
            statusJson("parkour", compatibleArena, mapOf(KitId("classic") to CLASSIC_FINGERPRINT)),
            "parkour",
        )
        redis.simulateExternalMessage(
            NetworkArenaDirectory.CHANNEL,
            statusJson("survival", compatibleArena, mapOf(KitId("classic") to OTHER_CLASSIC_FINGERPRINT)),
            "survival",
        )

        directory.select(rules, CLASSIC_FINGERPRINT) shouldBe ServerId("parkour")
        directory.choices(rules, CLASSIC_FINGERPRINT).map { it.selection.serverId } shouldContainExactly
            listOf(ServerId("parkour"))
        directory.supports(ServerId("parkour"), rules, CLASSIC_FINGERPRINT) shouldBe true
        directory.supports(ServerId("survival"), rules, CLASSIC_FINGERPRINT) shouldBe false

        redis.simulateExternalMessage(
            NetworkArenaDirectory.CHANNEL,
            statusJson("parkour", compatibleArena, emptyMap()),
            "parkour",
        )

        directory.select(rules, CLASSIC_FINGERPRINT) shouldBe null
        directory.select(
            rules,
            CLASSIC_FINGERPRINT,
            ArenaSelection(ServerId("parkour"), ArenaId("kit")),
        ) shouldBe null
        directory.choices(rules, CLASSIC_FINGERPRINT) shouldBe emptyList()
        directory.supports(ServerId("parkour"), rules, CLASSIC_FINGERPRINT) shouldBe false
        directory.close()
    }

    "routing requires a mode-correct lowercase SHA-256 kit fingerprint" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)
        val kitRules = DuelRules(DuelMode.KIT, KitId("classic"))

        shouldThrow<IllegalArgumentException> { directory.select(kitRules, null) }
        shouldThrow<IllegalArgumentException> { directory.choices(kitRules, "A".repeat(64)) }
        shouldThrow<IllegalArgumentException> {
            directory.supports(ServerId("spawn"), DuelRules(DuelMode.OWN_INVENTORY), CLASSIC_FINGERPRINT)
        }
        shouldThrow<IllegalArgumentException> {
            ArenaNodeStatus(ServerId("spawn"), emptyList(), mapOf(KitId("classic") to "0".repeat(63)), 0)
        }
        directory.close()
    }

    "arena heartbeat publishes the current catalog in deterministic kit-id order" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock)

        directory.publish(
            ArenaNodeStatus(
                server = ServerId("spawn"),
                arenas = emptyList(),
                kitFingerprints =
                    linkedMapOf(
                        KitId("zulu") to OTHER_CLASSIC_FINGERPRINT,
                        KitId("alpha") to CLASSIC_FINGERPRINT,
                    ),
                queuedPairs = 0,
            ),
        )

        val payload = JsonParser.parseString(redis.getPublishedMessages().single().message).asJsonObject
        payload.get("version").asInt shouldBe 5
        payload.getAsJsonArray("kitFingerprints").map { entry -> entry.asJsonObject.get("kitId").asString } shouldContainExactly
            listOf("alpha", "zulu")
        directory.activeNodes().single().kitFingerprints shouldBe
            mapOf(
                KitId("alpha") to CLASSIC_FINGERPRINT,
                KitId("zulu") to OTHER_CLASSIC_FINGERPRINT,
            )
        directory.close()
    }

    "arena heartbeat rejects malformed catalog fingerprints" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val logger = mockk<Logger>(relaxed = true)
        val directory = NetworkArenaDirectory(redis, ServerId("spawn"), clock, logger = logger)
        val malformed =
            statusJson(
                "parkour",
                emptyList(),
                mapOf(KitId("classic") to "A".repeat(64)),
            )

        redis.simulateExternalMessage(NetworkArenaDirectory.CHANNEL, malformed, "parkour")

        directory.activeNodes() shouldBe emptyList()
        verify(exactly = 1) { logger.warn("Rejected ArcDuels arena status: {}", RedisMessageRejection.MALFORMED_PAYLOAD) }
        directory.close()
    }
})

private val CLASSIC_FINGERPRINT = "1".repeat(64)
private val OTHER_CLASSIC_FINGERPRINT = "2".repeat(64)
private val BOXING_FINGERPRINT = "3".repeat(64)
