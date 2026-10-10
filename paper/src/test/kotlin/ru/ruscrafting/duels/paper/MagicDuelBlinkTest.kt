package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.type.Slab
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.arc.staffspells.api.StaffSpellDuelContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class MagicDuelBlinkTest : StringSpec({
    "two successful blinks consume two charges and the next attempt does not run" {
        val state = blinkState()
        var teleports = 0

        state.attemptTeleport(0L) { teleports++; true } shouldBe MagicDuelBlinkAttempt.TELEPORTED
        state.attemptTeleport(1L) { teleports++; true } shouldBe MagicDuelBlinkAttempt.TELEPORTED
        state.attemptTeleport(2L) { teleports++; true } shouldBe MagicDuelBlinkAttempt.EMPTY

        state.charges shouldBe 0
        teleports shouldBe 2
    }

    "failed teleport does not debit a charge" {
        val state = blinkState()

        state.attemptTeleport(0L) { false } shouldBe MagicDuelBlinkAttempt.FAILED
        state.charges shouldBe 2
        state.nextRechargeSeconds(0L) shouldBe null
    }

    "charges refill sequentially and delayed status refresh grants only elapsed intervals" {
        val state = blinkState()
        state.attemptTeleport(0L) { true } shouldBe MagicDuelBlinkAttempt.TELEPORTED
        state.attemptTeleport(1_000_000_000L) { true } shouldBe MagicDuelBlinkAttempt.TELEPORTED

        state.refreshCharges(8_000_000_000L)

        state.charges shouldBe 1
        state.nextRechargeSeconds(8_000_000_000L) shouldBe 8L
        state.refreshCharges(15_000_000_000L)
        state.charges shouldBe 1
        state.refreshCharges(16_000_000_000L)
        state.charges shouldBe 2

        val delayed = blinkState()
        delayed.attemptTeleport(0L) { true } shouldBe MagicDuelBlinkAttempt.TELEPORTED
        delayed.attemptTeleport(1_000_000_000L) { true } shouldBe MagicDuelBlinkAttempt.TELEPORTED
        delayed.refreshCharges(17_000_000_000L)
        delayed.charges shouldBe 2
    }

    "a new match round starts with two fresh charges" {
        val states = MagicDuelBlinkStateStore()
        val playerId = UUID(0L, 1L)
        val firstToken = MagicDuelBlinkRoundToken(UUID(0L, 2L), 1)
        val nextToken = firstToken.copy(round = 2)
        val settings = { MagicDuelBlinkSettings() }
        val first = states.stateFor(playerId, firstToken, settings)
        first.attemptTeleport(1L) { true } shouldBe MagicDuelBlinkAttempt.TELEPORTED
        first.charges shouldBe 1

        states.stateFor(playerId, firstToken, settings) shouldBe first
        val next = states.stateFor(playerId, nextToken, settings)

        next.token shouldBe nextToken
        (next === first) shouldBe false
        next.charges shouldBe 2
    }

    "settings parse defaults and reject values outside their configured bounds" {
        val config = YamlConfiguration()
        MagicDuelBlinkSettings.parse(config) shouldBe MagicDuelBlinkSettings()

        config.set("magic-duel.blink.distance", 17.0)
        shouldThrow<IllegalArgumentException> { MagicDuelBlinkSettings.parse(config) }
        config.set("magic-duel.blink.distance", Double.POSITIVE_INFINITY)
        shouldThrow<IllegalArgumentException> { MagicDuelBlinkSettings.parse(config) }
        config.set("magic-duel.blink.distance", 7.0)
        config.set("magic-duel.blink.recharge-seconds", "8")
        shouldThrow<IllegalArgumentException> { MagicDuelBlinkSettings.parse(config) }
    }

    "path stops before its first unsafe sample and rejects less than one block of movement" {
        val origin = Location(null, 0.5, 64.0, 0.5, 90f, 0f)
        val direction = Vector(1.0, 0.0, 0.0)

        val wallStop = MagicDuelBlinkPath.resolve(origin, direction, 7.0) { it.x <= 2.5 }
        wallStop?.x shouldBe 2.5
        MagicDuelBlinkPath.resolve(origin, direction, 7.0) { it.x <= 1.25 } shouldBe null
        MagicDuelBlinkPath.resolve(origin, direction, 7.0) { it.x <= 0.9 } shouldBe null
    }

    "local block shapes are translated before support and collision checks" {
        val slab = MagicDuelBlinkBlockShapes.toWorld(
            blockX = 5,
            blockY = 69,
            blockZ = -2,
            localBoxes = listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)),
        ).single()

        slab.minX shouldBe 5.0
        slab.minY shouldBe 69.0
        slab.minZ shouldBe -2.0
        slab.maxX shouldBe 6.0
        slab.maxY shouldBe 69.5
        slab.maxZ shouldBe -1.0
        MagicDuelBlinkBlockShapes.supports(Location(null, 5.5, 69.5, -1.5), listOf(slab), 1.0e-4) shouldBe true
        MagicDuelBlinkBlockShapes.supports(Location(null, 5.0, 69.5, -1.5), listOf(slab), 1.0e-4) shouldBe true
        MagicDuelBlinkBlockShapes.supports(Location(null, 5.5, 70.0, -1.5), listOf(slab), 1.0e-4) shouldBe false
        MagicDuelBlinkBlockShapes.overlaps(
            BoundingBox(5.2, 69.4, -1.8, 5.8, 70.2, -1.2),
            listOf(slab),
        ) shouldBe true
    }

    "swap hands blinks once in active magic, respects cancellation, and keeps safe bounds" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcDuelsPlugin>()
            val world = paper.server.getWorld("world") ?: paper.server.addSimpleWorld("world")
            for (x in -1..1) for (z in -1..1) world.getChunkAt(x, z).load()
            for (x in -4..12) for (z in -4..18) world.getBlockAt(x, 69, z).type = Material.STONE
            for (y in 70..72) for (z in -2..3) world.getBlockAt(3, y, z).type = Material.STONE
            val slab = world.getBlockAt(1, 69, 8)
            slab.type = Material.STONE_SLAB
            (slab.blockData as Slab).apply { type = Slab.Type.BOTTOM }.also { slab.blockData = it }
            world.getBlockAt(1, 70, 12).type = Material.CACTUS

            val wallPlayer = paper.server.addPlayer("BlinkWall")
            val boundedPlayer = paper.server.addPlayer("BlinkBound")
            val slabPlayer = paper.server.addPlayer("BlinkSlab")
            val hazardPlayer = paper.server.addPlayer("BlinkHazard")
            val shiftPlayer = paper.server.addPlayer("BlinkShift")
            val inactivePlayer = paper.server.addPlayer("BlinkFree")
            val players = listOf(
                wallPlayer to 0.5,
                boundedPlayer to 4.5,
                slabPlayer to 8.5,
                hazardPlayer to 12.5,
                shiftPlayer to 16.5,
            )
            players.forEach { (player, z) ->
                player.teleport(Location(world, 0.5, 70.0, z, -90f, 0f), PlayerTeleportEvent.TeleportCause.PLUGIN)
            }

            val contexts = players.associate { (player, _) ->
                player.uniqueId to StaffSpellDuelContext(UUID.randomUUID(), 1, 1.0)
            }
            val destinations = mutableMapOf<UUID, Location>()
            val collisionQueries = mutableListOf<String>()
            val time = AtomicLong()
            val tasks = LifecycleTaskScope(TestTaskScheduler())
            val blink = MagicDuelBlink(
                tasks = tasks,
                locales = LocaleService.load(plugin),
                settingsForNewRound = { MagicDuelBlinkSettings() },
                activeContext = { player -> contexts[player.uniqueId] },
                isInsideArena = { player, location ->
                    location.world?.uid == world.uid &&
                        location.x <= (if (player.uniqueId == boundedPlayer.uniqueId) 1.75 else 10.0)
                },
                teleportBlink = { player, destination ->
                    destinations[player.uniqueId] = destination.clone()
                    player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                },
                nowNanos = { time.getAndAdd(100_000_000L) },
                localCollisionBoxes = { block ->
                    collisionQueries += "${block.type}@${block.x},${block.y},${block.z}"
                    when (block.type) {
                        Material.STONE -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
                        Material.STONE_SLAB -> listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 0.5, 1.0))
                        else -> emptyList()
                    }
                },
            )
            try {
                paper.server.pluginManager.registerEvents(blink, plugin)
                fun swap(player: Player, cancelled: Boolean = false): PlayerSwapHandItemsEvent {
                    val event = PlayerSwapHandItemsEvent(
                        player,
                        player.inventory.itemInMainHand,
                        player.inventory.itemInOffHand,
                    ).apply { isCancelled = cancelled }
                    paper.server.pluginManager.callEvent(event)
                    return event
                }

                swap(wallPlayer, cancelled = true).isCancelled shouldBe true
                destinations.containsKey(wallPlayer.uniqueId) shouldBe false
                swap(wallPlayer).isCancelled shouldBe true
                val wallStop = requireNotNull(destinations[wallPlayer.uniqueId]) {
                    "Expected a wall-stop blink destination; origin=${wallPlayer.location}, " +
                        "direction=${wallPlayer.location.direction}, " +
                        "collisionQueries=${collisionQueries.takeLast(20)}"
                }
                (wallStop.x in 1.0..<3.0) shouldBe true

                swap(boundedPlayer).isCancelled shouldBe true
                val boundsStop = destinations[boundedPlayer.uniqueId]
                (boundsStop != null && boundsStop.x <= 1.75) shouldBe true

                swap(slabPlayer).isCancelled shouldBe true
                swap(hazardPlayer).isCancelled shouldBe true
                destinations.containsKey(slabPlayer.uniqueId) shouldBe false
                destinations.containsKey(hazardPlayer.uniqueId) shouldBe false

                shiftPlayer.isSneaking = true
                swap(shiftPlayer).isCancelled shouldBe true
                destinations.containsKey(shiftPlayer.uniqueId) shouldBe false

                swap(inactivePlayer).isCancelled shouldBe false
                destinations.containsKey(inactivePlayer.uniqueId) shouldBe false
            } finally {
                blink.close()
                tasks.close()
            }
        }
    }
})

private fun blinkState(): MagicDuelBlinkRoundState = MagicDuelBlinkRoundState(
    token = MagicDuelBlinkRoundToken(UUID(0L, 1L), 1),
    settings = MagicDuelBlinkSettings(),
)
