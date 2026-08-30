package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.duels.domain.MatchEndReason
import ru.ruscrafting.duels.domain.MultiplayerMatchState
import ru.ruscrafting.duels.domain.PlayerId

class MultiplayerSessionManagerTest : StringSpec({
    "completed groups remain locked through durable retention and ignore duplicate elimination" {
        MockBukkitTestRuntime.open().use { paper ->
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
        MockBukkitTestRuntime.open().use { paper ->
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
                    player.location.blockX shouldBe index
                }
                harness.results.writes shouldHaveSize 0
            } finally {
                harness.close()
            }
        }
    }

    "active disconnect eliminates and restores only the departing participant" {
        MockBukkitTestRuntime.open().use { paper ->
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
        MockBukkitTestRuntime.open().use { paper ->
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
                    player.location.blockX shouldBe index
                }
                harness.results.writes shouldHaveSize 0
            }
        }
    }

    "departure after result persistence restores one player while the post-match hold continues" {
        MockBukkitTestRuntime.open().use { paper ->
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
        MockBukkitTestRuntime.open().use { paper ->
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
                    player.location.blockX shouldBe index
                }
            }
        }
    }
})
