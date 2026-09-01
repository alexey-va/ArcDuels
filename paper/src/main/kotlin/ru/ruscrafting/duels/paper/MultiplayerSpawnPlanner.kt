package ru.ruscrafting.duels.paper

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.PlayerId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class MultiplayerSpawnPlacementSettings(
    val radiusScale: Double = 1.0,
    val teammateSpacing: Double = 3.0,
    val minimumSeparation: Double = 2.0,
    val boundsInset: Double = 1.0,
) {
    init {
        require(radiusScale in 0.5..1.0) {
            "multiplayer.spawn-placement.radius-scale must be between 0.5 and 1.0"
        }
        require(teammateSpacing in 2.0..6.0) {
            "multiplayer.spawn-placement.teammate-spacing must be between 2 and 6"
        }
        require(minimumSeparation in 1.5..4.0) {
            "multiplayer.spawn-placement.minimum-separation must be between 1.5 and 4"
        }
        require(boundsInset in 0.0..8.0) {
            "multiplayer.spawn-placement.bounds-inset must be between 0 and 8"
        }
    }

    companion object {
        fun load(configuration: ConfigurationSection): MultiplayerSpawnPlacementSettings {
            val section = configuration.strictConfigurationSection("multiplayer.spawn-placement")
                ?: return MultiplayerSpawnPlacementSettings()
            return MultiplayerSpawnPlacementSettings(
                radiusScale = section.strictNumber("radius-scale", 1.0),
                teammateSpacing = section.strictNumber("teammate-spacing", 3.0),
                minimumSeparation = section.strictNumber("minimum-separation", 2.0),
                boundsInset = section.strictNumber("bounds-inset", 1.0),
            )
        }
    }
}

/** Deterministic placement around the axis established by the two verified duel spawns. */
internal object MultiplayerSpawnPlanner {
    private val radiusAttempts = listOf(1.0, 0.9, 0.8, 0.7, 0.6, 0.5)

    fun plan(
        arena: PaperArena,
        roster: MultiplayerRoster,
    ): Map<PlayerId, Location>? {
        val first = arena.firstSpawn
        val second = arena.secondSpawn
        val world = first.world ?: return null
        if (second.world?.uid != world.uid) return null
        val centerX = (first.x + second.x) / 2.0
        val centerY = (first.y + second.y) / 2.0
        val centerZ = (first.z + second.z) / 2.0
        val halfY = (first.y - second.y) / 2.0
        val firstDx = first.x - centerX
        val firstDz = first.z - centerZ
        val configuredRadius = hypot(firstDx, firstDz) * arena.multiplayerPlacement.radiusScale
        if (configuredRadius < arena.multiplayerPlacement.minimumSeparation) return null
        val baseAngle = atan2(firstDz, firstDx)
        val groups = groups(roster)

        radiusAttempts.forEach { attempt ->
            val radius = configuredRadius * attempt
            val planned = linkedMapOf<PlayerId, Location>()
            groups.forEachIndexed { groupIndex, participants ->
                val angle = baseAngle + FULL_CIRCLE * groupIndex / groups.size
                val rows = formationRows(participants.size)
                var participantIndex = 0
                rows.forEachIndexed { rowIndex, rowSize ->
                    repeat(rowSize) { columnIndex ->
                        val tangentOffset =
                            (columnIndex - (rowSize - 1) / 2.0) * arena.multiplayerPlacement.teammateSpacing
                        val radialOffset =
                            (rowIndex - (rows.size - 1) / 2.0) * arena.multiplayerPlacement.teammateSpacing
                        val effectiveRadius = radius - radialOffset
                        val x = centerX + cos(angle) * effectiveRadius - sin(angle) * tangentOffset
                        val z = centerZ + sin(angle) * effectiveRadius + cos(angle) * tangentOffset
                        val y = centerY + cos(angle - baseAngle) * halfY
                        val yaw = Math.toDegrees(atan2(-(centerX - x), centerZ - z)).toFloat()
                        planned[participants[participantIndex++]] = Location(world, x, y, z, yaw, 0.0f)
                    }
                }
            }
            val navigable = linkedMapOf<PlayerId, Location>()
            for ((playerId, candidate) in planned) {
                val spawn = arena.multiplayerNavigation.snap(candidate) ?: break
                navigable[playerId] = spawn
            }
            if (navigable.size == planned.size && isValid(arena, navigable.values.toList())) return navigable
        }
        return null
    }

    private fun groups(roster: MultiplayerRoster): List<List<PlayerId>> =
        when (roster.rules.layout) {
            MultiplayerLayout.FREE_FOR_ALL -> roster.participants.map { listOf(it.playerId) }
            MultiplayerLayout.TWO_TEAMS, MultiplayerLayout.THREE_TEAMS ->
                (1..requireNotNull(roster.rules.layout.teamCount)).map { team ->
                    roster.participants.filter { it.team == team }.map { it.playerId }
                }
        }

    private fun formationRows(size: Int): List<Int> {
        if (size == 1) return listOf(1)
        val columns = ceil(sqrt(size.toDouble())).toInt()
        val rows = mutableListOf<Int>()
        var remaining = size
        while (remaining > 0) {
            val rowSize = minOf(columns, remaining)
            rows += rowSize
            remaining -= rowSize
        }
        return rows
    }

    private fun isValid(
        arena: PaperArena,
        locations: List<Location>,
    ): Boolean {
        val inset = arena.multiplayerPlacement.boundsInset
        val bounds = arena.bounds
        if (bounds.minX + inset > bounds.maxX - inset || bounds.minZ + inset > bounds.maxZ - inset) return false
        if (locations.any { location ->
                location.x !in (bounds.minX + inset)..(bounds.maxX - inset) ||
                    location.z !in (bounds.minZ + inset)..(bounds.maxZ - inset) ||
                    !bounds.contains(location)
            }
        ) return false
        val minimumSquared = arena.multiplayerPlacement.minimumSeparation * arena.multiplayerPlacement.minimumSeparation
        return locations.indices.all { first ->
            ((first + 1) until locations.size).all { second ->
                val dx = locations[first].x - locations[second].x
                val dz = locations[first].z - locations[second].z
                dx * dx + dz * dz >= minimumSquared
            }
        }
    }

    private const val FULL_CIRCLE = PI * 2.0
}

internal fun interface MultiplayerSpawnNavigation {
    fun snap(candidate: Location): Location?

    companion object {
        val UNCHECKED = MultiplayerSpawnNavigation(Location::clone)

        fun create(
            firstSpawn: Location,
            secondSpawn: Location,
            bounds: ArenaBounds,
            settings: MultiplayerSpawnPlacementSettings,
        ): MultiplayerSpawnNavigation =
            BukkitMultiplayerSpawnNavigation(firstSpawn, secondSpawn, bounds, settings)
    }
}

/**
 * Caches the walkable component around the verified first duel spawn. A single
 * flood fill proves transitive reachability for every generated group spawn;
 * candidates behind barriers are snapped back to the nearest cell in that
 * component instead of being accepted merely because broad safety bounds fit.
 */
private class BukkitMultiplayerSpawnNavigation(
    private val firstSpawn: Location,
    secondSpawn: Location,
    private val bounds: ArenaBounds,
    settings: MultiplayerSpawnPlacementSettings,
) : MultiplayerSpawnNavigation {
    private val world = requireNotNull(firstSpawn.world)
    private val limits = NavigationLimits.create(firstSpawn, secondSpawn, bounds, settings)
    private val reachable by lazy(LazyThreadSafetyMode.NONE, ::discoverReachable)

    override fun snap(candidate: Location): Location? {
        if (candidate.world?.uid != world.uid) return null
        val target = Cell(candidate.blockX, candidate.blockY, candidate.blockZ)
        val cell =
            reachable.asSequence()
                .filter { cell ->
                    abs(cell.x - target.x) <= MAX_HORIZONTAL_SNAP &&
                        abs(cell.z - target.z) <= MAX_HORIZONTAL_SNAP &&
                        abs(cell.y - target.y) <= MAX_VERTICAL_SNAP
                }
                .minWithOrNull(
                    compareBy<Cell> { cell ->
                        val dx = candidate.x - (cell.x + 0.5)
                        val dy = candidate.y - cell.y
                        val dz = candidate.z - (cell.z + 0.5)
                        dx * dx + dy * dy + dz * dz
                    }.thenBy { it.y }.thenBy { it.x }.thenBy { it.z },
                ) ?: return null
        if (cell == target && isStandable(cell)) return candidate.clone()
        return Location(world, cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5, candidate.yaw, candidate.pitch)
    }

    private fun discoverReachable(): Set<Cell> {
        val origin = nearestStandable(firstSpawn) ?: return emptySet()
        val queue = ArrayDeque<Cell>()
        val visited = linkedSetOf<Cell>()
        queue += origin
        while (queue.isNotEmpty() && visited.size < MAX_REACHABLE_CELLS) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            for ((dx, dz) in CARDINAL_DIRECTIONS) {
                for (dy in STEP_HEIGHTS) {
                    val next = Cell(current.x + dx, current.y + dy, current.z + dz)
                    if (next !in visited && limits.contains(next) && isStandable(next)) {
                        queue += next
                        break
                    }
                }
            }
        }
        return visited
    }

    private fun nearestStandable(location: Location): Cell? {
        val target = Cell(location.blockX, location.blockY, location.blockZ)
        for (radius in 0..1) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    for (dy in 0..MAX_VERTICAL_SNAP) {
                        listOf(dy, -dy).distinct().forEach { offsetY ->
                            val candidate = Cell(target.x + dx, target.y + offsetY, target.z + dz)
                            if (limits.contains(candidate) && isStandable(candidate)) return candidate
                        }
                    }
                }
            }
        }
        return null
    }

    private fun isStandable(cell: Cell): Boolean {
        val feet = world.getBlockAt(cell.x, cell.y, cell.z)
        val head = world.getBlockAt(cell.x, cell.y + 1, cell.z)
        val floor = world.getBlockAt(cell.x, cell.y - 1, cell.z)
        return feet.isPassable && !feet.isLiquid &&
            head.isPassable && !head.isLiquid &&
            floor.type.isSolid
    }

    private data class Cell(
        val x: Int,
        val y: Int,
        val z: Int,
    )

    private data class NavigationLimits(
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val maxX: Int,
        val maxY: Int,
        val maxZ: Int,
    ) {
        fun contains(cell: Cell): Boolean =
            cell.x in minX..maxX && cell.y in minY..maxY && cell.z in minZ..maxZ

        companion object {
            fun create(
                first: Location,
                second: Location,
                bounds: ArenaBounds,
                settings: MultiplayerSpawnPlacementSettings,
            ): NavigationLimits {
                val centerX = (first.x + second.x) / 2.0
                val centerZ = (first.z + second.z) / 2.0
                val formationRadius = hypot(first.x - centerX, first.z - centerZ) * settings.radiusScale
                val horizontalRadius = formationRadius + settings.teammateSpacing * 2.0 + NAVIGATION_MARGIN
                val inset = settings.boundsInset
                return NavigationLimits(
                    minX = ceil(maxOf(bounds.minX + inset, centerX - horizontalRadius) - 0.5).toInt(),
                    minY = maxOf(ceil(bounds.minY).toInt(), minOf(first.blockY, second.blockY) - VERTICAL_MARGIN),
                    minZ = ceil(maxOf(bounds.minZ + inset, centerZ - horizontalRadius) - 0.5).toInt(),
                    maxX = floor(minOf(bounds.maxX - inset, centerX + horizontalRadius) - 0.5).toInt(),
                    maxY = minOf(floor(bounds.maxY).toInt(), maxOf(first.blockY, second.blockY) + VERTICAL_MARGIN),
                    maxZ = floor(minOf(bounds.maxZ - inset, centerZ + horizontalRadius) - 0.5).toInt(),
                )
            }
        }
    }

    private companion object {
        val CARDINAL_DIRECTIONS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        val STEP_HEIGHTS = listOf(0, 1, -1)
        const val MAX_HORIZONTAL_SNAP = 4
        const val MAX_VERTICAL_SNAP = 2
        const val VERTICAL_MARGIN = 6
        const val NAVIGATION_MARGIN = 8.0
        const val MAX_REACHABLE_CELLS = 20_000
    }
}
