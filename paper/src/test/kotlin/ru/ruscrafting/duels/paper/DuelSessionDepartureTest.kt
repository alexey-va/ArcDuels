package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.InMemoryStatisticsRepository
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchCoordinator
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchState
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class DuelSessionDepartureTest : StringSpec({
    "a wager leave before first combat refunds and holds the arena until native restoration saves" {
        withDepartureSession { scenario ->
            scenario.saveFailures += scenario.first.uniqueId

            scenario.manager.handleForfeit(scenario.second) shouldBe true

            scenario.observed.single().state shouldBe MatchState.CANCELLED
            scenario.observed.single().winner shouldBe null
            scenario.statistics.findMatch(scenario.match.id).join() shouldBe null
            scenario.arenas.reservedCount() shouldBe 1
            scenario.cancelled.size shouldBe 0
            scenario.manager.isStateLocked(scenario.first) shouldBe true
            scenario.saveFailures.clear()
            scenario.paper.performTicks(21)

            scenario.assertCancelledAndRestored()
        }
    }

    "a wager quit before first combat reports a refund instead of recording a winner" {
        withDepartureSession { scenario ->
            scenario.manager.handleQuit(scenario.second)

            scenario.assertCancelledAndRestored()
        }
    }

    "a wager participant escaping during the initial countdown cancels through the presence check" {
        withDepartureSession { scenario ->
            val bounds = scenario.arenas.get(scenario.match.arenaId).bounds
            scenario.second.teleport(Location(scenario.second.world, bounds.maxX + 5.0, 80.0, 0.0))

            scenario.manager.checkParticipantPresence(scenario.match) shouldBe false

            scenario.assertCancelledAndRestored()
        }
    }

    "leaving a wager during a later round countdown still records and pays the final winner" {
        withDepartureSession { scenario ->
            scenario.coordinator.activate(scenario.match.id)
            val nextRound = scenario.coordinator.recordRoundWinner(scenario.match.id, PlayerId(scenario.first.uniqueId)).join()
            nextRound.state shouldBe MatchState.COUNTDOWN
            (nextRound.startedAt != null) shouldBe true

            scenario.manager.handleForfeit(scenario.second) shouldBe true

            scenario.observed.single().winner shouldBe PlayerId(scenario.first.uniqueId)
            scenario.completed.single().state shouldBe MatchState.COMPLETED
            scenario.statistics.findMatch(scenario.match.id).join()?.outcome?.winner shouldBe PlayerId(scenario.first.uniqueId)
            scenario.cancelled.size shouldBe 0
            scenario.arenas.reservedCount() shouldBe 0
        }
    }

    "free duels retain countdown forfeit semantics" {
        withDepartureSession(stakeMinor = 0L) { scenario ->
            scenario.manager.handleForfeit(scenario.second) shouldBe true

            scenario.observed.single().winner shouldBe PlayerId(scenario.first.uniqueId)
            scenario.completed.single().state shouldBe MatchState.COMPLETED
            scenario.cancelled.size shouldBe 0
        }
    }

    "network cancellation normalizes host state before return callback and preserves the origin snapshots" {
        withDepartureSession(network = true) { scenario ->
            var normalizedAtReturn = false
            scenario.manager.onCancelled {
                normalizedAtReturn = scenario.arenas.reservedCount() == 0 &&
                    scenario.first.inventory.getItem(0)?.type == Material.GOLDEN_APPLE &&
                    scenario.second.inventory.getItem(0)?.type == Material.DIAMOND &&
                    scenario.first.location == scenario.arenas.get(scenario.match.arenaId).lobby &&
                    scenario.second.location == scenario.arenas.get(scenario.match.arenaId).lobby
            }

            scenario.manager.handleForfeit(scenario.second) shouldBe true

            scenario.observed.single().winner shouldBe null
            scenario.cancelled.single().state shouldBe MatchState.CANCELLED
            scenario.completed.size shouldBe 0
            normalizedAtReturn shouldBe true
            scenario.repository.pendingCount() shouldBe 2
            scenario.repository.retainedCount() shouldBe 0
        }
    }
})

private class DepartureScenario(
    val paper: MockBukkitTestRuntime,
    val manager: DuelSessionManager,
    val coordinator: MatchCoordinator,
    val arenas: PaperArenaCatalog,
    val statistics: InMemoryStatisticsRepository,
    val repository: InMemoryEscrowRepository,
    val first: PlayerMock,
    val second: PlayerMock,
    val match: DuelMatch,
    val observed: MutableList<DuelMatch>,
    val completed: MutableList<DuelMatch>,
    val cancelled: MutableList<DuelMatch>,
    val saveFailures: MutableSet<UUID>,
) {
    fun assertCancelledAndRestored() {
        observed.single().state shouldBe MatchState.CANCELLED
        observed.single().winner shouldBe null
        cancelled.single().state shouldBe MatchState.CANCELLED
        completed.size shouldBe 0
        statistics.findMatch(match.id).join() shouldBe null
        arenas.reservedCount() shouldBe 0
        manager.matchFor(first) shouldBe null
        manager.matchFor(second) shouldBe null
        first.inventory.getItem(0)?.type shouldBe Material.GOLDEN_APPLE
        second.inventory.getItem(0)?.type shouldBe Material.DIAMOND
        first.location shouldBe Location(first.world, 130.0, 80.0, 130.0)
        second.location shouldBe Location(second.world, 135.0, 80.0, 130.0)
        repository.pendingCount() shouldBe 0
        repository.retainedCount() shouldBe 2
    }
}

private fun withDepartureSession(
    stakeMinor: Long = 10_000L,
    network: Boolean = false,
    block: (DepartureScenario) -> Unit,
) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation {
            val plugin = paper.loadReadyDuelsPlugin()
            val world = paper.server.getWorld("world") ?: paper.server.addSimpleWorld("world")
            plugin.config.set("arenas.example.enabled", true)
            plugin.config.set("arenas.example.allowed-loadouts", listOf("KIT"))
            plugin.config.set("arenas.example.allowed-objectives", listOf("ELIMINATION"))
            plugin.config.set("arenas.example.post-match-action", "LOCAL_LOBBY")
            val arenas = PaperArenaCatalog.load(plugin, ArenaEnvironmentInspector {})
            val serverId = ServerId("departure-host")
            val first = paper.addReadyDuelsPlayer(plugin, "DepartureFirst")
            val second = paper.addReadyDuelsPlayer(plugin, "DepartureSecond")
            first.teleport(Location(world, 130.0, 80.0, 130.0))
            second.teleport(Location(world, 135.0, 80.0, 130.0))
            first.inventory.setItem(0, ItemStack(Material.GOLDEN_APPLE))
            second.inventory.setItem(0, ItemStack(Material.DIAMOND))
            val statistics = InMemoryStatisticsRepository()
            val observed = mutableListOf<DuelMatch>()
            val completed = mutableListOf<DuelMatch>()
            val cancelled = mutableListOf<DuelMatch>()
            val coordinator = MatchCoordinator(serverId, arenas, statistics, onTerminated = { observed += it })
            val repository = InMemoryEscrowRepository()
            val saveFailures = mutableSetOf<UUID>()
            val manager = DuelSessionManager(
                plugin = plugin,
                coordinator = coordinator,
                arenas = arenas,
                kits = KitRegistry.load(plugin),
                playerStates = DurablePlayerStateService(plugin, serverId, repository),
                locales = LocaleService.load(plugin),
                countdownSeconds = 3,
                teleportStabilizationTicks = 0L,
                playerDataSaver = { check(it.uniqueId !in saveFailures) { "Planned native save failure" } },
                celebrationPlay = { _, _ -> },
                celebrationDurationTicks = 0L,
                seriesRoundIntermissionTicks = 0L,
                defaultPostMatchReturnPolicy = PostMatchReturnPolicy.PROMPT,
                localServerId = serverId,
            )
            manager.onCompleted { completed += it }
            manager.onCancelled { cancelled += it }
            val now = Instant.now()
            val challenge = DuelChallenge.create(
                PlayerId(first.uniqueId),
                PlayerId(second.uniqueId),
                DuelRules(DuelMode.KIT, KitId("classic"), bestOf = 3, stakeMinor = stakeMinor),
                now,
                Duration.ofSeconds(60),
                ArenaSelection(serverId, ArenaId("example")),
            ).resolve(ChallengeStatus.ACCEPTED, now)
            val starting = if (network) {
                val origin = ServerId("departure-origin")
                DurablePlayerStateService(plugin, origin, repository)
                    .storePair(MatchId(challenge.id.value), first, second, inventoryReplaced = true).join()
                manager.startNetwork(challenge, mapOf(PlayerId(first.uniqueId) to origin, PlayerId(second.uniqueId) to origin))
            } else {
                manager.start(challenge)
            }
            paper.performTicks(1)
            val match = starting.get(5, TimeUnit.SECONDS)
            match.state shouldBe MatchState.COUNTDOWN
            match.startedAt shouldBe null
            try {
                block(DepartureScenario(paper, manager, coordinator, arenas, statistics, repository, first, second, match,
                    observed, completed, cancelled, saveFailures))
            } finally {
                saveFailures.clear()
                manager.shutdown()
            }
        }
    }
}
