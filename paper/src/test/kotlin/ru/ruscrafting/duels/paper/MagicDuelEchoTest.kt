package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.loadPlugin
import ru.arc.staffspells.api.StaffSpellDuelContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class MagicDuelEchoTest : StringSpec({
    "places an anchor and returns from a moved location without restoring health" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcDuelsPlugin>()
            val harness = echoHarness(paper, plugin)
            try {
                val start = Location(harness.world, 0.5, 70.0, 0.5, 15f, 5f)
                harness.player.teleport(start, PlayerTeleportEvent.TeleportCause.PLUGIN)

                harness.drop().isCancelled shouldBe true
                harness.anchors.map { it.location } shouldBe listOf(start)
                harness.teleports shouldBe emptyList()
                harness.cues shouldBe listOf(MagicDuelEchoCue.PLACED)

                val moved = Location(harness.world, 5.5, 70.0, 0.5, 135f, 20f)
                harness.player.teleport(moved, PlayerTeleportEvent.TeleportCause.PLUGIN)
                harness.player.health = 10.0
                harness.now.set(3_999_999_999L)

                harness.drop().isCancelled shouldBe true
                harness.teleports.size shouldBe 1
                harness.teleports.single().x shouldBe start.x
                harness.teleports.single().y shouldBe start.y
                harness.teleports.single().yaw shouldBe moved.yaw
                harness.teleports.single().pitch shouldBe moved.pitch
                harness.player.location.x shouldBe start.x
                harness.player.health shouldBe 10.0
                harness.cues shouldBe listOf(
                    MagicDuelEchoCue.PLACED,
                    MagicDuelEchoCue.DEPARTED,
                    MagicDuelEchoCue.RETURNED,
                )
            } finally {
                harness.close()
            }
        }
    }

    "expires at four seconds and starts cooldown at placement" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcDuelsPlugin>()
            val harness = echoHarness(paper, plugin)
            try {
                harness.drop().isCancelled shouldBe true
                harness.now.set(4_000_000_000L)
                harness.drop().isCancelled shouldBe true
                harness.anchors.size shouldBe 1
                harness.teleports shouldBe emptyList()
                harness.cues.contains(MagicDuelEchoCue.EXPIRED) shouldBe true

                harness.now.set(17_999_999_999L)
                harness.drop().isCancelled shouldBe true
                harness.anchors.size shouldBe 1
                harness.teleports shouldBe emptyList()

                harness.now.set(18_000_000_000L)
                harness.drop().isCancelled shouldBe true
                harness.anchors.size shouldBe 2
                harness.teleports shouldBe emptyList()
            } finally {
                harness.close()
            }
        }
    }

    "blocked and failed returns retain the anchor; round change and reset clear prior state" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcDuelsPlugin>()
            val harness = echoHarness(paper, plugin)
            try {
                val start = Location(harness.world, 0.5, 70.0, 0.5)
                harness.player.teleport(start, PlayerTeleportEvent.TeleportCause.PLUGIN)
                harness.drop()
                harness.player.teleport(Location(harness.world, 5.5, 70.0, 0.5), PlayerTeleportEvent.TeleportCause.PLUGIN)

                harness.safeLanding = { it.x != start.x }
                harness.now.set(1_000_000_000L)
                harness.drop().isCancelled shouldBe true
                harness.teleports shouldBe emptyList()

                harness.safeLanding = { true }
                harness.teleportSucceeds = false
                harness.now.set(2_000_000_000L)
                harness.drop().isCancelled shouldBe true
                harness.player.location.x shouldBe 5.5
                harness.teleports.size shouldBe 1

                harness.teleportSucceeds = true
                harness.now.set(3_000_000_000L)
                harness.drop().isCancelled shouldBe true
                harness.player.location.x shouldBe start.x
                harness.teleports.size shouldBe 2

                val nextRound = StaffSpellDuelContext(harness.context.matchId(), 2, 1.0)
                harness.contexts[harness.player.uniqueId] = nextRound
                harness.player.teleport(Location(harness.world, 6.5, 70.0, 0.5), PlayerTeleportEvent.TeleportCause.PLUGIN)
                harness.now.set(4_000_000_000L)
                harness.drop().isCancelled shouldBe true
                harness.teleports.size shouldBe 2
                harness.anchors.last().location.x shouldBe 6.5

                harness.echo.reset(listOf(harness.player.uniqueId))
                harness.now.set(5_000_000_000L)
                harness.drop().isCancelled shouldBe true
                harness.anchors.size shouldBe 3
                harness.teleports.size shouldBe 2
            } finally {
                harness.close()
            }
        }
    }

    "nonmagic and sneaking Q do not cast; cancelled input and inventory drops never cast" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                val plugin = paper.loadPlugin<ArcDuelsPlugin>()
                val harness = echoHarness(paper, plugin)
                try {
                    harness.contexts.remove(harness.player.uniqueId)
                    harness.drop().isCancelled shouldBe false
                    harness.anchors shouldBe emptyList()

                    harness.contexts[harness.player.uniqueId] = harness.context
                    harness.player.isSneaking = true
                    harness.drop().isCancelled shouldBe true
                    harness.anchors shouldBe emptyList()
                    harness.player.isSneaking = false

                    harness.drop(cancelled = true).isCancelled shouldBe true
                    harness.anchors shouldBe emptyList()

                    val inventory = Bukkit.createInventory(null, 9).apply {
                        setItem(0, ItemStack(Material.DIAMOND))
                    }
                    val view = requireNotNull(harness.player.openInventory(inventory))
                    val click = InventoryClickEvent(
                        view,
                        InventoryType.SlotType.CONTAINER,
                        0,
                        ClickType.DROP,
                        InventoryAction.DROP_ONE_SLOT,
                    )
                    paper.callEvent(click)
                    click.isCancelled shouldBe true
                    harness.anchors shouldBe emptyList()
                    harness.teleports shouldBe emptyList()
                } finally {
                    harness.close()
                }
            }
        }
    }

    "tick redraws the expiring anchor and death or quit clears it" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                val plugin = paper.loadPlugin<ArcDuelsPlugin>()
                val harness = echoHarness(paper, plugin)
                try {
                    harness.drop().isCancelled shouldBe true
                    harness.scheduler.tick(3)
                    harness.anchors.size shouldBe 2

                    harness.now.set(2_000_000_000L)
                    harness.scheduler.tick(4)
                    harness.anchors.last().progress shouldBe 0.5

                    paper.callEvent(
                        PlayerDeathEvent(
                            harness.player,
                            DamageSource.builder(DamageType.GENERIC).build(),
                            mutableListOf(),
                            0,
                            Component.empty(),
                            true,
                        ),
                    )
                    harness.now.set(2_500_000_000L)
                    harness.drop().isCancelled shouldBe true
                    harness.teleports shouldBe emptyList()

                    paper.callEvent(
                        PlayerQuitEvent(
                            harness.player,
                            Component.empty(),
                            PlayerQuitEvent.QuitReason.DISCONNECTED,
                        ),
                    )
                    harness.now.set(3_000_000_000L)
                    harness.drop().isCancelled shouldBe true
                    harness.teleports shouldBe emptyList()
                    harness.anchors.size shouldBe 5
                } finally {
                    harness.close()
                }
            }
        }
    }

    "live teleport safety rejects an anchor that becomes occupied" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                val plugin = paper.loadPlugin<ArcDuelsPlugin>()
                val harness = echoHarness(paper, plugin)
                try {
                    harness.world.getChunkAt(0, 0).load()
                    for (x in 0..6) harness.world.getBlockAt(x, 69, 0).type = Material.STONE
                    val safety = MagicDuelTeleportSafety(
                        isInsideArena = { _, location ->
                            location.world?.uid == harness.world.uid && location.x in -10.0..10.0
                        },
                        localCollisionBoxes = { block ->
                            if (block.type.isSolid) listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
                            else emptyList()
                        },
                    )
                    harness.safeLanding = { location -> safety.isSafe(harness.player, location) }
                    val anchor = Location(harness.world, 0.5, 70.0, 0.5)
                    harness.player.teleport(anchor, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    harness.drop().isCancelled shouldBe true

                    harness.world.getBlockAt(0, 70, 0).type = Material.STONE
                    harness.player.teleport(
                        Location(harness.world, 5.5, 70.0, 0.5),
                        PlayerTeleportEvent.TeleportCause.PLUGIN,
                    )
                    harness.now.set(1_000_000_000L)
                    harness.drop().isCancelled shouldBe true
                    harness.teleports shouldBe emptyList()

                    harness.world.getBlockAt(0, 70, 0).type = Material.AIR
                    harness.now.set(2_000_000_000L)
                    harness.drop().isCancelled shouldBe true
                    harness.teleports.size shouldBe 1
                    harness.player.location.x shouldBe anchor.x
                } finally {
                    harness.close()
                }
            }
        }
    }
})

private data class EchoAnchor(val location: Location, val progress: Double)

private class EchoBehavior {
    var safeLanding: (Location) -> Boolean = { true }
    var teleportSucceeds = true
}

private class EchoHarness(
    val player: Player,
    val world: org.bukkit.World,
    val context: StaffSpellDuelContext,
    val contexts: MutableMap<UUID, StaffSpellDuelContext>,
    val now: AtomicLong,
    val anchors: MutableList<EchoAnchor>,
    val teleports: MutableList<Location>,
    val cues: MutableList<MagicDuelEchoCue>,
    val scheduler: TestTaskScheduler,
    val tasks: LifecycleTaskScope,
    val echo: MagicDuelEcho,
    private val behavior: EchoBehavior,
) : AutoCloseable {
    var safeLanding: (Location) -> Boolean
        get() = behavior.safeLanding
        set(value) { behavior.safeLanding = value }
    var teleportSucceeds: Boolean
        get() = behavior.teleportSucceeds
        set(value) { behavior.teleportSucceeds = value }

    fun drop(cancelled: Boolean = false): PlayerDropItemEvent {
        val item = world.dropItem(player.location, ItemStack(Material.DIAMOND))
        val event = PlayerDropItemEvent(player, item).apply { isCancelled = cancelled }
        player.server.pluginManager.callEvent(event)
        item.remove()
        return event
    }

    override fun close() {
        echo.close()
        tasks.close()
    }
}

private fun echoHarness(
    paper: MockBukkitTestRuntime,
    plugin: ArcDuelsPlugin,
): EchoHarness {
    val world = paper.server.getWorld("world") ?: paper.server.addSimpleWorld("world")
    val player = paper.server.addPlayer("Echo")
    val context = StaffSpellDuelContext(UUID.randomUUID(), 1, 1.0)
    val contexts = mutableMapOf(player.uniqueId to context)
    val now = AtomicLong()
    val anchors = mutableListOf<EchoAnchor>()
    val teleports = mutableListOf<Location>()
    val cues = mutableListOf<MagicDuelEchoCue>()
    val scheduler = TestTaskScheduler()
    val tasks = LifecycleTaskScope(scheduler)
    val behavior = EchoBehavior()
    val harness = EchoHarness(
        player = player,
        world = world,
        context = context,
        contexts = contexts,
        now = now,
        anchors = anchors,
        teleports = teleports,
        cues = cues,
        scheduler = scheduler,
        tasks = tasks,
        behavior = behavior,
        echo = MagicDuelEcho(
            tasks = tasks,
            locales = LocaleService.load(plugin),
            settingsForNewRound = { MagicDuelEchoSettings() },
            activeContext = { contexts[it.uniqueId] },
            isInsideArena = { _, location -> location.world?.uid == world.uid && location.x in -10.0..10.0 },
            teleport = { movingPlayer, destination ->
                teleports += destination.clone()
                if (behavior.teleportSucceeds) movingPlayer.teleport(
                    destination,
                    PlayerTeleportEvent.TeleportCause.PLUGIN,
                ) else false
            },
            nowNanos = now::get,
            safeLanding = { _, location -> behavior.safeLanding(location) },
            drawAnchor = { _, location, progress -> anchors += EchoAnchor(location.clone(), progress) },
            playCue = { _, _, cue -> cues += cue },
        ),
    )
    paper.server.pluginManager.registerEvents(harness.echo, plugin)
    return harness
}
