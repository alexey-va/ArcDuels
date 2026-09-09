package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.MultiplayerParticipant
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.MultiplayerRules
import ru.ruscrafting.duels.domain.PlayerId
import java.util.UUID

class MultiplayerSpawnNavigationMockBukkitTest : StringSpec({
    "sumo platform automatically fits every supported free for all group size" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcDuelsPlugin>()
            val world = paper.server.addSimpleWorld("sumo-platform")
            // Surface of the deployed octagonal platform, world-content/duels/sumo-platform.atelier.json.
            listOf(intArrayOf(6, 14, 0, 20), intArrayOf(4, 16, 1, 19),
                intArrayOf(3, 17, 2, 18), intArrayOf(2, 18, 3, 17)).forEach { box ->
                for (x in box[0]..box[1]) for (z in box[2]..box[3]) {
                    world.getBlockAt(x, 67, z).type = Material.STONE
                }
            }
            configureArena(plugin, world.name)
            val path = "arenas.barrier-test"
            plugin.config.set("$path.allowed-objectives", listOf("SUMO"))
            configureLocation(plugin, "$path.first-spawn", world.name, 4.5, 68.0, 10.5)
            configureLocation(plugin, "$path.second-spawn", world.name, 16.5, 68.0, 10.5)
            plugin.config.set("$path.bounds.min.x", -4.0)
            plugin.config.set("$path.bounds.min.y", 63.0)
            plugin.config.set("$path.bounds.min.z", -4.0)
            plugin.config.set("$path.bounds.max.x", 25.0)
            plugin.config.set("$path.bounds.max.z", 25.0)
            val catalog = PaperArenaCatalog.load(plugin)
            for (size in 3..12) {
                val roster = MultiplayerRoster(
                    MultiplayerRules(MultiplayerLayout.FREE_FOR_ALL, MultiplayerKitPolicy.SHARED,
                        KitId("sumo"), objective = ru.ruscrafting.duels.domain.DuelObjectiveType.SUMO),
                    (1..size).map { MultiplayerParticipant(PlayerId(UUID(0L, it.toLong())), kitId = KitId("sumo")) },
                )
                catalog.reserveMultiplayer(roster).get().use { reservation ->
                    reservation.spawns.size shouldBe size
                    reservation.spawns.values.all { it.blockY == 68 && world.getBlockAt(it.blockX, 67, it.blockZ).type.isSolid } shouldBe true
                }
            }
        }
    }

    "procedural placement keeps every player in the duel spawn walkable component" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadPlugin<ArcDuelsPlugin>()
            val world = paper.server.addSimpleWorld("barrier-arena")
            prepareArena(world)
            configureArena(plugin, world.name)
            val catalog = PaperArenaCatalog.load(plugin)
            val roster =
                MultiplayerRoster(
                    MultiplayerRules(MultiplayerLayout.FREE_FOR_ALL, MultiplayerKitPolicy.SHARED, KitId("classic")),
                    (1L..3L).map { index ->
                        MultiplayerParticipant(PlayerId(UUID(0L, index)), kitId = KitId("classic"))
                    },
                )

            val reservation = catalog.reserveMultiplayer(roster).get()
            val arena = catalog.get(reservation.arenaId)

            reservation.spawns.values.all { destination ->
                isReachable(arena.firstSpawn, destination, arena.bounds)
            } shouldBe true
        }
    }
})

private fun prepareArena(world: World) {
    for (x in -10..10) {
        for (z in -10..10) world.getBlockAt(x, 69, z).type = Material.STONE
        for (y in 70..72) world.getBlockAt(x, y, 2).type = Material.BARRIER
    }
}

private fun configureArena(
    plugin: ArcDuelsPlugin,
    worldName: String,
) {
    plugin.config.set("arenas.example.enabled", false)
    val path = "arenas.barrier-test"
    plugin.config.set("$path.enabled", true)
    plugin.config.set("$path.display-name", "Barrier test")
    plugin.config.set("$path.allowed-loadouts", listOf("KIT"))
    plugin.config.set("$path.allowed-objectives", listOf("ELIMINATION"))
    configureLocation(plugin, "$path.first-spawn", worldName, -5.0, 70.0, 0.0)
    configureLocation(plugin, "$path.second-spawn", worldName, 5.0, 70.0, 0.0)
    plugin.config.set("$path.bounds.min.x", -10.0)
    plugin.config.set("$path.bounds.min.y", 60.0)
    plugin.config.set("$path.bounds.min.z", -10.0)
    plugin.config.set("$path.bounds.max.x", 10.0)
    plugin.config.set("$path.bounds.max.y", 90.0)
    plugin.config.set("$path.bounds.max.z", 10.0)
}

private fun configureLocation(
    plugin: ArcDuelsPlugin,
    path: String,
    worldName: String,
    x: Double,
    y: Double,
    z: Double,
) {
    plugin.config.set("$path.world", worldName)
    plugin.config.set("$path.x", x)
    plugin.config.set("$path.y", y)
    plugin.config.set("$path.z", z)
    plugin.config.set("$path.yaw", 0.0)
    plugin.config.set("$path.pitch", 0.0)
}

private fun isReachable(
    origin: Location,
    destination: Location,
    bounds: ArenaBounds,
): Boolean {
    data class Cell(val x: Int, val y: Int, val z: Int)

    val world = requireNotNull(origin.world)
    val target = Cell(destination.blockX, destination.blockY, destination.blockZ)
    val queue = ArrayDeque<Cell>()
    val visited = mutableSetOf<Cell>()
    queue += Cell(origin.blockX, origin.blockY, origin.blockZ)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current)) continue
        if (current == target) return true
        listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).forEach { (dx, dz) ->
            for (dy in -1..1) {
                val next = Cell(current.x + dx, current.y + dy, current.z + dz)
                if (next in visited || !bounds.contains(world.uid, next.x + 0.5, next.y.toDouble(), next.z + 0.5)) continue
                val feet = world.getBlockAt(next.x, next.y, next.z)
                val head = world.getBlockAt(next.x, next.y + 1, next.z)
                val floor = world.getBlockAt(next.x, next.y - 1, next.z)
                if (feet.isPassable && !feet.isLiquid && head.isPassable && !head.isLiquid && floor.type.isSolid) {
                    queue += next
                    break
                }
            }
        }
    }
    return false
}
