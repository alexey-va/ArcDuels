package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.duels.domain.MatchEndReason
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MultiplayerMatchState
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId

class MultiplayerSessionManagerTest : StringSpec({
    "completed groups remain locked through durable retention and ignore duplicate elimination" {
        withManagerScenario { paper ->
            val harness = multiplayerHarness(paper)
            try {
                val roster = ffaRoster(harness.players)
                harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                paper.server.scheduler.performTicks(4)
                harness.teleports.completeAll()
                paper.server.scheduler.performTicks(4)

                harness.manager.eliminate(harness.players[0])
                harness.manager.eliminate(harness.players[0])
                harness.manager.eliminate(harness.players[1])
                harness.manager.eliminate(harness.players[2], MatchEndReason.FORFEIT)
                harness.results.writes shouldHaveSize 1

                harness.results.completion.complete(true)
                paper.server.scheduler.performTicks(4)
                harness.manager.isLocked(harness.players[3]) shouldBe true

                paper.server.scheduler.performTicks(64)
                harness.manager.isEngaged(harness.players[3]) shouldBe false
                harness.players.forEachIndexed { index, player ->
                    player.inventory.getItem(index)?.type shouldBe Material.GOLDEN_APPLE
                }
            } finally {
                harness.close()
            }
        }
    }

    "cancelled reservation waits for late asynchronous teleports before restoring" {
        withManagerScenario { paper ->
            val harness = multiplayerHarness(paper)
            try {
                val roster = ffaRoster(harness.players)
                harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                paper.server.scheduler.performTicks(4)

                harness.manager.handleForfeit(harness.players.first()) shouldBe true
                harness.manager.isLocked(harness.players.last()) shouldBe true
                harness.teleports.completeAll()
                paper.server.scheduler.performTicks(8)

                harness.players.forEachIndexed { index, player ->
                    harness.manager.isEngaged(player) shouldBe false
                    player.inventory.getItem(index)?.type shouldBe Material.GOLDEN_APPLE
                    player.world.name shouldBe "world"
                }
                harness.results.writes shouldHaveSize 0
            } finally {
                harness.close()
            }
        }
    }

    "active disconnect eliminates and restores only the departing participant" {
        withManagerScenario { paper ->
            multiplayerHarness(paper).use { harness ->
                val roster = ffaRoster(harness.players)
                harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                paper.performTicks(4)
                harness.teleports.completeAll()
                paper.performTicks(4)
                val departing = harness.players.first()

                harness.manager.handleQuit(departing)
                paper.performTicks(4)

                harness.manager.isEngaged(departing) shouldBe false
                departing.inventory.getItem(0)?.type shouldBe Material.GOLDEN_APPLE
                harness.players.drop(1).forEach { survivor ->
                    harness.manager.isEngaged(survivor) shouldBe true
                    requireNotNull(harness.manager.matchFor(survivor)).state shouldBe MultiplayerMatchState.ACTIVE
                }
                harness.results.writes shouldHaveSize 0
            }
        }
    }

    "disconnect during countdown cancels and restores the complete group" {
        withManagerScenario { paper ->
            multiplayerHarness(paper, countdownSeconds = 2).use { harness ->
                val roster = ffaRoster(harness.players)
                harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                paper.performTicks(4)
                harness.teleports.completeAll()
                paper.performTicks(1)
                requireNotNull(harness.manager.matchFor(harness.players.first())).state shouldBe MultiplayerMatchState.COUNTDOWN

                harness.manager.handleQuit(harness.players.first())
                paper.performTicks(4)

                harness.manager.activeCount() shouldBe 0
                harness.players.forEachIndexed { index, player ->
                    harness.manager.isEngaged(player) shouldBe false
                    player.inventory.getItem(index)?.type shouldBe Material.GOLDEN_APPLE
                    player.world.name shouldBe "world"
                }
                harness.results.writes shouldHaveSize 0
            }
        }
    }

    "departure after result persistence restores one player while the post-match hold continues" {
        withManagerScenario { paper ->
            multiplayerHarness(paper).use { harness ->
                val roster = ffaRoster(harness.players)
                harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                paper.performTicks(4)
                harness.teleports.completeAll()
                paper.performTicks(4)
                harness.manager.eliminate(harness.players[0])
                harness.manager.eliminate(harness.players[1])
                harness.manager.eliminate(harness.players[2])
                harness.results.completion.complete(true)
                paper.performTicks(4)
                val departing = harness.players.first()

                harness.manager.handleQuit(departing)
                paper.performTicks(4)

                harness.manager.isEngaged(departing) shouldBe false
                departing.inventory.getItem(0)?.type shouldBe Material.GOLDEN_APPLE
                harness.manager.isLocked(harness.players.last()) shouldBe true

                paper.performTicks(64)
                harness.manager.activeCount() shouldBe 0
                harness.players.drop(1).forEachIndexed { offset, player ->
                    player.inventory.getItem(offset + 1)?.type shouldBe Material.GOLDEN_APPLE
                }
            }
        }
    }

    "shutdown restores every active participant and releases the session immediately" {
        withManagerScenario { paper ->
            multiplayerHarness(paper).use { harness ->
                val roster = ffaRoster(harness.players)
                harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                paper.performTicks(4)
                harness.teleports.completeAll()
                paper.performTicks(4)

                harness.manager.close()
                paper.performTicks(4)

                harness.manager.activeCount() shouldBe 0
                harness.players.forEachIndexed { index, player ->
                    harness.manager.isEngaged(player) shouldBe false
                    player.inventory.getItem(index)?.type shouldBe Material.GOLDEN_APPLE
                    player.world.name shouldBe "world"
                }
            }
        }
    }

    "network start consumes origin snapshots then restores and returns every remote participant once" {
        withManagerScenario { paper ->
            val repository = InMemoryEscrowRepository()
            val returned = mutableListOf<Pair<java.util.UUID, ServerId>>()
            multiplayerHarness(
                paper,
                escrowRepository = repository,
                networkReturn = { player, server -> returned += player.uniqueId to server },
            ).use { harness ->
                val matchId = MatchId.random()
                val origin = ServerId("spawn")
                val roster = ffaRoster(harness.players)
                val online = harness.players.associateBy { PlayerId(it.uniqueId) }
                val origins = roster.playerIds.associateWith { origin }
                DurablePlayerStateService(harness.plugin, origin, repository)
                    .storeAll(matchId, harness.players, inventoryReplaced = true)
                    .get()

                val started = harness.manager.startNetwork(matchId, roster, online, origins)
                paper.performTicks(4)
                harness.teleports.completeAll()
                paper.performTicks(4)

                started.get() shouldBe matchId
                harness.players.forEach { player ->
                    requireNotNull(harness.manager.matchFor(player)).state shouldBe MultiplayerMatchState.ACTIVE
                }

                harness.players.dropLast(1).forEach(harness.manager::eliminate)
                harness.results.writes shouldHaveSize 1
                harness.results.completion.complete(true)
                paper.performTicks(64)

                harness.manager.activeCount() shouldBe 0
                repository.pendingCount() shouldBe harness.players.size
                repository.retainedCount() shouldBe 0
                returned shouldHaveSize harness.players.size
                harness.players.forEach { player ->
                    returned.count { it == player.uniqueId to origin } shouldBe 1
                }
                harness.players.forEachIndexed { index, player ->
                    player.inventory.getItem(index)?.type shouldBe Material.GOLDEN_APPLE
                    player.world.name shouldBe "world"
                }
            }
        }
    }

    "network release returns only remote origins and one failed transfer cannot retain the session" {
        withManagerScenario { paper ->
            val repository = InMemoryEscrowRepository()
            val returned = mutableListOf<Pair<java.util.UUID, ServerId>>()
            multiplayerHarness(
                paper,
                escrowRepository = repository,
                networkReturn = { player, server ->
                    returned += player.uniqueId to server
                    if (returned.size == 1) error("planned network return failure")
                },
            ).use { harness ->
                val matchId = MatchId.random()
                val local = ServerId("group-test")
                val remote = ServerId("survival")
                val roster = ffaRoster(harness.players)
                val origins = roster.playerIds.mapIndexed { index, playerId ->
                    playerId to if (index < 2) local else remote
                }.toMap()
                harness.players.forEachIndexed { index, player ->
                    DurablePlayerStateService(harness.plugin, if (index < 2) local else remote, repository)
                        .store(matchId, player, inventoryReplaced = true)
                        .get()
                }

                harness.manager.startNetwork(
                    matchId,
                    roster,
                    harness.players.associateBy { PlayerId(it.uniqueId) },
                    origins,
                )
                paper.performTicks(4)
                harness.teleports.completeAll()
                paper.performTicks(4)
                harness.players.dropLast(1).forEach(harness.manager::eliminate)
                harness.results.completion.complete(true)
                paper.performTicks(64)

                harness.manager.activeCount() shouldBe 0
                val expectedRemotePlayers = harness.players.drop(2).map { it.uniqueId }
                returned shouldHaveSize expectedRemotePlayers.size
                expectedRemotePlayers.forEach { playerId ->
                    returned.count { it == playerId to remote } shouldBe 1
                }
                repository.pendingCount() shouldBe 2
                repository.retainedCount() shouldBe 2
            }
        }
    }

    "network arena restores its local baseline and never retains foreign escrow" {
        withManagerScenario { paper ->
            val repository = InMemoryEscrowRepository()
            val returned = mutableListOf<Pair<java.util.UUID, ServerId>>()
            multiplayerHarness(
                paper,
                escrowRepository = repository,
                networkReturn = { player, server -> returned += player.uniqueId to server },
            ).use { harness ->
                val matchId = MatchId.random()
                val local = ServerId("group-test")
                val remote = ServerId("survival")
                val roster = ffaRoster(harness.players)
                val origins = roster.playerIds.mapIndexed { index, playerId ->
                    playerId to if (index == 0) local else remote
                }.toMap()
                harness.players.forEachIndexed { index, player ->
                    DurablePlayerStateService(harness.plugin, if (index == 0) local else remote, repository)
                        .store(matchId, player, inventoryReplaced = true)
                        .get()
                    player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.GOLDEN_APPLE))
                }

                harness.manager.startNetwork(matchId, roster, harness.players.associateBy { PlayerId(it.uniqueId) }, origins)
                paper.performTicks(4)
                harness.teleports.completeAll()
                paper.performTicks(4)
                harness.players.dropLast(1).forEach(harness.manager::eliminate)
                harness.results.completion.complete(true)
                paper.performTicks(64)

                harness.manager.activeCount() shouldBe 0
                repository.retainedCount() shouldBe 1
                repository.pendingCount() shouldBe 3
                returned shouldHaveSize 3
                harness.players.drop(1).forEach { it.inventory.getItem(0)?.type shouldBe Material.GOLDEN_APPLE }
            }
        }
    }

    "network start with one missing origin snapshot releases the arena without touching players" {
        withManagerScenario { paper ->
            val repository = InMemoryEscrowRepository()
            multiplayerHarness(paper, escrowRepository = repository).use { harness ->
                val matchId = MatchId.random()
                val origin = ServerId("parkour")
                val roster = ffaRoster(harness.players)
                val online = harness.players.associateBy { PlayerId(it.uniqueId) }
                val origins = roster.playerIds.associateWith { origin }
                DurablePlayerStateService(harness.plugin, origin, repository)
                    .storeAll(matchId, harness.players.take(3), inventoryReplaced = true)
                    .get()

                val started = harness.manager.startNetwork(matchId, roster, online, origins)
                paper.performTicks(8)

                started.isCompletedExceptionally shouldBe true
                harness.arenas.reservedCount() shouldBe 0
                harness.manager.activeCount() shouldBe 0
                harness.teleports.pendingCount shouldBe 0
                harness.players.forEachIndexed { index, player ->
                    harness.manager.isEngaged(player) shouldBe false
                    player.inventory.getItem(index)?.type shouldBe Material.GOLDEN_APPLE
                    player.world.name shouldBe "world"
                }
            }
        }
    }

    "unavailable multiplayer arena fails before escrow or player mutation" {
        withManagerScenario { paper ->
            val repository = InMemoryEscrowRepository()
            multiplayerHarness(paper, escrowRepository = repository, enableArena = false).use { harness ->
                val roster = ffaRoster(harness.players)

                val started = harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                paper.performTicks(4)

                started.isCompletedExceptionally shouldBe true
                repository.pendingCount() shouldBe 0
                harness.manager.activeCount() shouldBe 0
                harness.players.forEachIndexed { index, player ->
                    player.inventory.getItem(index)?.type shouldBe Material.GOLDEN_APPLE
                    player.world.name shouldBe "world"
                }
            }
        }
    }

    "failed arena teleport restores every snapshot and releases arena and chunk tickets" {
        withManagerScenario { paper ->
            val chunks = RecordingChunkTickets()
            val repository = InMemoryEscrowRepository()
            multiplayerHarness(paper, escrowRepository = repository, chunkTickets = chunks).use { harness ->
                val roster = ffaRoster(harness.players)
                val started = harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                paper.performTicks(4)
                harness.teleports.completeAll(arrived = false)
                paper.performTicks(8)

                started.get()
                harness.manager.activeCount() shouldBe 0
                harness.arenas.reservedCount() shouldBe 0
                harness.tickets.activeLeaseCount shouldBe 0
                chunks.removed.toSet() shouldBe chunks.added.toSet()
                repository.pendingCount() shouldBe 0
                harness.players.forEachIndexed { index, player ->
                    player.inventory.getItem(index)?.type shouldBe Material.GOLDEN_APPLE
                    player.world.name shouldBe "world"
                }
            }
        }
    }

    "partial chunk ticket acquisition failure releases earlier leases and restores escrow" {
        withManagerScenario { paper ->
            val chunks = RecordingChunkTickets(failOnAddCall = 2)
            val repository = InMemoryEscrowRepository()
            multiplayerHarness(paper, escrowRepository = repository, chunkTickets = chunks).use { harness ->
                val roster = ffaRoster(harness.players)

                val started = harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                paper.performTicks(8)

                started.isCompletedExceptionally shouldBe true
                harness.manager.activeCount() shouldBe 0
                harness.arenas.reservedCount() shouldBe 0
                harness.tickets.activeLeaseCount shouldBe 0
                chunks.addCalls shouldBe 2
                chunks.removed shouldHaveSize 1
                repository.pendingCount() shouldBe 0
                repository.retainedCount() shouldBe harness.players.size
                harness.players.forEachIndexed { index, player ->
                    player.inventory.getItem(index)?.type shouldBe Material.GOLDEN_APPLE
                }
            }
        }
    }

    "network chunk failure restores the arena baseline without retaining foreign escrow" {
        withManagerScenario { paper ->
            val chunks = RecordingChunkTickets(failOnAddCall = 1)
            val repository = InMemoryEscrowRepository()
            val returned = mutableListOf<Pair<java.util.UUID, ServerId>>()
            multiplayerHarness(
                paper,
                escrowRepository = repository,
                chunkTickets = chunks,
                networkReturn = { player, server -> returned += player.uniqueId to server },
            ).use { harness ->
                val matchId = MatchId.random()
                val origin = ServerId("survival")
                val roster = ffaRoster(harness.players)
                val online = harness.players.associateBy { PlayerId(it.uniqueId) }
                val origins = roster.playerIds.associateWith { origin }
                DurablePlayerStateService(harness.plugin, origin, repository)
                    .storeAll(matchId, harness.players, inventoryReplaced = true)
                    .get()
                harness.players.forEach { player ->
                    player.inventory.setItem(0, org.bukkit.inventory.ItemStack(Material.DIRT))
                }

                val started = harness.manager.startNetwork(matchId, roster, online, origins)
                paper.performTicks(8)

                started.isCompletedExceptionally shouldBe true
                harness.manager.activeCount() shouldBe 0
                harness.arenas.reservedCount() shouldBe 0
                harness.tickets.activeLeaseCount shouldBe 0
                repository.pendingCount() shouldBe harness.players.size
                repository.retainedCount() shouldBe 0
                returned shouldHaveSize 0
                harness.players.forEach { player ->
                    player.inventory.getItem(0)?.type shouldBe Material.DIRT
                }
            }
        }
    }

    "result persistence failure retries without unlocking or duplicating the outcome" {
        withManagerScenario { paper ->
            val results = ControlledMultiplayerResults(failuresBeforeSuccess = 1)
            multiplayerHarness(paper, results = results).use { harness ->
                harness.startAndArrive(ffaRoster(harness.players))
                harness.players.dropLast(1).forEach(harness.manager::eliminate)
                // Process the failed completion on the primary thread; the
                // manager schedules the next persistence attempt 40 ticks later.
                paper.performTicks(1)

                results.writes shouldHaveSize 1
                harness.players.forEach { harness.manager.isLocked(it) shouldBe true }
                paper.performTicks(39)
                results.writes shouldHaveSize 1
                paper.performTicks(1)
                results.writes shouldHaveSize 2
                results.writes.distinctBy { it.matchId } shouldHaveSize 1

                results.completion.complete(true)
                paper.performTicks(64)
                harness.manager.activeCount() shouldBe 0
            }
        }
    }

    "playerdata restore failure keeps the match locked then retries the complete restore" {
        withManagerScenario { paper ->
            val repository = InMemoryEscrowRepository()
            var persistenceCalls = 0
            val playerData = PaperPlayerDataPersistence {
                persistenceCalls++
                if (persistenceCalls == 1) error("planned playerdata failure")
            }
            multiplayerHarness(paper, escrowRepository = repository, playerData = playerData).use { harness ->
                harness.startAndArrive(ffaRoster(harness.players))
                harness.players.dropLast(1).forEach(harness.manager::eliminate)
                harness.results.completion.complete(true)

                paper.performTicks(64)
                harness.manager.activeCount() shouldBe 1
                repository.retainedCount() shouldBe 0
                paper.performTicks(64)

                harness.manager.activeCount() shouldBe 0
                persistenceCalls shouldBe harness.players.size + 1
                repository.pendingCount() shouldBe 0
                repository.retainedCount() shouldBe harness.players.size
            }
        }
    }

    "retention failure keeps recovery pending then acknowledges only the missing player on retry" {
        withManagerScenario { paper ->
            val repository = InMemoryEscrowRepository(retainFailuresBeforeSuccess = 1)
            multiplayerHarness(paper, escrowRepository = repository).use { harness ->
                harness.startAndArrive(ffaRoster(harness.players))
                harness.players.dropLast(1).forEach(harness.manager::eliminate)
                harness.results.completion.complete(true)

                paper.performTicks(64)
                harness.manager.activeCount() shouldBe 1
                repository.pendingCount() shouldBe 1
                repository.retainedCount() shouldBe harness.players.size - 1
                paper.performTicks(64)

                harness.manager.activeCount() shouldBe 0
                repository.retainCalls shouldBe harness.players.size + 1
                repository.pendingCount() shouldBe 0
                repository.retainedCount() shouldBe harness.players.size
            }
        }
    }
})

private fun withManagerScenario(block: (MockBukkitTestRuntime) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation { block(paper) }
    }
}

private class RecordingChunkTickets(
    private val failOnAddCall: Int? = null,
) : PaperChunkTicketBackend {
    val added = mutableListOf<PaperChunkKey>()
    val removed = mutableListOf<PaperChunkKey>()
    var addCalls: Int = 0
        private set

    override fun add(key: PaperChunkKey): PaperChunkTicketAddResult {
        addCalls++
        if (addCalls == failOnAddCall) error("planned chunk ticket failure")
        added += key
        return PaperChunkTicketAddResult.ADDED
    }

    override fun remove(key: PaperChunkKey): Boolean {
        removed += key
        return true
    }
}
