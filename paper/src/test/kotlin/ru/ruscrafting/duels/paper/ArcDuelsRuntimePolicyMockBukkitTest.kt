package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.configuration.MemoryConfiguration
import org.bukkit.inventory.Inventory
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.duels.domain.MultiplayerMatchState
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.PlayerId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ArcDuelsRuntimePolicyMockBukkitTest : StringSpec({
    "existing and new group invitations retain the timeout generation captured at creation" {
        withRuntimePolicyScenario { paper ->
            var settings = defaultRuntimeSettings().copy(multiplayerInvitationTimeout = Duration.ofSeconds(5))
            val clock = MutablePolicyClock(Instant.parse("2026-08-31T12:00:00Z"))
            multiplayerHarness(
                paper,
                playerNames = listOf("TimeoutHost", "TimeoutAlpha", "TimeoutBravo"),
                runtimeSettings = { settings },
            ).use { harness ->
                val gui = harness.registerGui(clock = clock)
                val host = harness.players.first()

                gui.open(host)
                host.click(10)
                host.click(11)
                host.click(34)
                host.openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK

                // Changing the live value cannot move the already advertised deadline.
                settings = settings.copy(multiplayerInvitationTimeout = Duration.ofSeconds(6))
                paper.performTicks(99)
                host.openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK
                clock.advance(Duration.ofSeconds(5))
                paper.performTicks(1)
                (host.openInventory.topInventory as Inventory?) shouldBe null

                gui.open(host)
                host.click(10)
                host.click(11)
                host.click(34)
                paper.performTicks(100)
                host.openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK
                clock.advance(Duration.ofSeconds(6))
                paper.performTicks(20)
                (host.openInventory.topInventory as Inventory?) shouldBe null
                gui.close()
            }
        }
    }

    "active multiplayer session keeps countdown and finish delay while the next session uses new values" {
        withRuntimePolicyScenario { paper ->
            var settings =
                defaultRuntimeSettings().copy(
                    countdownSeconds = 2,
                    multiplayerFinishDelayTicks = 100L,
                )
            multiplayerHarness(
                paper,
                runtimeSettings = { settings },
            ).use { harness ->
                val roster = ffaRoster(harness.players)
                val start = harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                start.isDone shouldBe false
                // Reload before arena reservation/escrow callbacks create the
                // session. The match must retain the generation from start().
                settings = settings.copy(countdownSeconds = 0, multiplayerFinishDelayTicks = 0L)
                paper.performTicks(4)
                harness.teleports.completeAll()
                paper.performTicks(4)
                requireNotNull(harness.manager.matchFor(harness.players.first())).state shouldBe MultiplayerMatchState.COUNTDOWN

                paper.performTicks(1)
                requireNotNull(harness.manager.matchFor(harness.players.first())).state shouldBe MultiplayerMatchState.COUNTDOWN
                paper.performTicks(60)
                requireNotNull(harness.manager.matchFor(harness.players.first())).state shouldBe MultiplayerMatchState.ACTIVE

                harness.players.dropLast(1).forEach(harness.manager::eliminate)
                harness.results.completion.complete(true)
                paper.performTicks(4)
                harness.manager.isEngaged(harness.players.last()) shouldBe true
                paper.performTicks(100)
                harness.manager.activeCount() shouldBe 0

                harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                paper.performTicks(4)
                harness.teleports.completeAll()
                paper.performTicks(4)
                requireNotNull(harness.manager.matchFor(harness.players.first())).state shouldBe MultiplayerMatchState.ACTIVE

                harness.players.dropLast(1).forEach(harness.manager::eliminate)
                paper.performTicks(5)
                harness.manager.activeCount() shouldBe 0
            }
        }
    }

    "an open group draft keeps its defaults while the next draft observes the live generation" {
        withRuntimePolicyScenario { paper ->
            var settings =
                defaultRuntimeSettings().copy(
                    multiplayerDefaultLayout = MultiplayerLayout.THREE_TEAMS,
                    multiplayerDefaultKitPolicy = MultiplayerKitPolicy.PER_PLAYER,
                )
            multiplayerHarness(paper, runtimeSettings = { settings }).use { harness ->
                val gui = harness.registerGui()
                val host = harness.players.first()

                gui.open(host)
                host.openInventory.topInventory.getItem(28)?.type shouldBe Material.YELLOW_WOOL
                host.openInventory.topInventory.getItem(30)?.type shouldBe Material.BUNDLE
                host.openInventory.topInventory.getItem(32)?.type shouldBe Material.DIAMOND_SWORD

                settings =
                    settings.copy(
                        multiplayerDefaultLayout = MultiplayerLayout.FREE_FOR_ALL,
                        multiplayerDefaultKitPolicy = MultiplayerKitPolicy.SHARED,
                    )
                gui.open(host)
                host.openInventory.topInventory.getItem(28)?.type shouldBe Material.YELLOW_WOOL
                host.openInventory.topInventory.getItem(30)?.type shouldBe Material.BUNDLE
                host.openInventory.topInventory.getItem(32)?.type shouldBe Material.DIAMOND_SWORD

                host.click(36)
                harness.plugin.config.set("multiplayer.defaults.kit", "axe")
                harness.kits.replaceWith(KitRegistry.loadCandidate(harness.plugin, harness.plugin.config))
                gui.open(host)
                host.openInventory.topInventory.getItem(28)?.type shouldBe Material.TNT
                host.openInventory.topInventory.getItem(30)?.type shouldBe Material.CHEST
                host.openInventory.topInventory.getItem(32)?.type shouldBe Material.DIAMOND_AXE
                gui.close()
            }
        }
    }
})

private fun defaultRuntimeSettings(): ArcDuelsRuntimeSettings =
    ArcDuelsRuntimeSettings.parse(MemoryConfiguration()).settings

private fun withRuntimePolicyScenario(block: (MockBukkitTestRuntime) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation { block(paper) }
    }
}

private class MutablePolicyClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutablePolicyClock(current, zone)

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
