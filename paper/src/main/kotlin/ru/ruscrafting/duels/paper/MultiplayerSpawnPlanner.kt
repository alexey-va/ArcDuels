package ru.ruscrafting.duels.paper

import ru.arc.core.BudgetedTaskQueue
import java.util.concurrent.CompletableFuture
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.MIN_MULTIPLAYER_PARTICIPANTS
import ru.ruscrafting.duels.domain.MAX_MULTIPLAYER_PARTICIPANTS
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

    fun shape(roster: MultiplayerRoster): List<Int> = groups(roster).map(List<PlayerId>::size)

    /** All legal team-size permutations are finite under the existing 3..12-player contract. */
    fun prepare(arena: PaperArena, queue: BudgetedTaskQueue): CompletableFuture<Map<List<Int>, List<Location>>> {
        val preparation = Preparation(arena)
        return queue.submit({ true }, preparation::step).thenApply { preparation.result() }
    }

    /** Startup/manual reload runs the same planner to completion before catalog publication. */
    fun prepareForMaintenance(arena: PaperArena): Map<List<Int>, List<Location>> = Preparation(arena).let { preparation ->
        while (!preparation.step()) { /* Explicit maintenance may block. */ }
        preparation.result()
    }

    private class Preparation(private val arena: PaperArena) {
        private val shapes = buildSet {
            for (size in MIN_MULTIPLAYER_PARTICIPANTS..MAX_MULTIPLAYER_PARTICIPANTS) {
                add(List(size) { 1 })
                for (teams in 2..3) for (mask in 0 until (1 shl teams)) {
                    if (Integer.bitCount(mask) == size % teams) {
                        add(List(teams) { team -> size / teams + if (mask and (1 shl team) == 0) 0 else 1 })
                    }
                }
            }
        }.iterator()
        private val prepared = linkedMapOf<List<Int>, List<Location>>()
        private var current: List<Int>? = null
        private var search: Search? = null

        fun step(): Boolean {
            if (search == null && shapes.hasNext()) {
                current = shapes.next()
                search = Search(arena, requireNotNull(current))
            }
            val active = search
            if (active != null && active.step()) {
                active.result?.let { prepared[requireNotNull(current)] = it }
                search = null
            }
            return search == null && !shapes.hasNext()
        }

        fun result(): Map<List<Int>, List<Location>> = prepared.toMap()
    }

    fun bind(roster: MultiplayerRoster, locations: List<Location>): Map<PlayerId, Location> =
        groups(roster).flatten().zip(locations.map(Location::clone)).toMap()

    /** Synchronous compatibility for offline catalog fixtures; runtime uses prepare before publication. */
    fun plan(
        arena: PaperArena,
        roster: MultiplayerRoster,
    ): Map<PlayerId, Location>? = Search(arena, shape(roster)).let { search ->
        while (!search.step()) { /* Offline fixture only. */ }
        search.result?.let { bind(roster, it) }
    }

    /** One candidate snap (405 cached cells and three native block checks) per shared queue step. */
    private class Search(private val arena: PaperArena, private val sizes: List<Int>) {
        private var attempt = 0
        private var planned: List<Location> = emptyList()
        private val navigable = mutableListOf<Location>()
        var result: List<Location>? = null
            private set

        fun step(): Boolean {
            if (attempt >= radiusAttempts.size) return true
            if (planned.isEmpty()) {
                planned = candidates(radiusAttempts[attempt]) ?: return true
            }
            val spawn = arena.multiplayerNavigation.snap(planned[navigable.size])
            if (spawn != null) navigable += spawn
            if (spawn == null || navigable.size == planned.size) {
                if (spawn != null && isValid(arena, navigable)) {
                    result = navigable.toList()
                    return true
                }
                attempt++
                planned = emptyList()
                navigable.clear()
            }
            return attempt >= radiusAttempts.size
        }

        private fun candidates(attempt: Double): List<Location>? {
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
            val radius = configuredRadius * attempt
            return buildList {
                sizes.forEachIndexed { groupIndex, size ->
                    val angle = baseAngle + FULL_CIRCLE * groupIndex / sizes.size
                    val rows = formationRows(size)
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
                            add(Location(world, x, y, z, yaw, 0.0f))
                        }
                    }
                }
            }
        }
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
    fun prepare(queue: BudgetedTaskQueue): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
    fun prepareForMaintenance() = Unit
    fun isSafe(location: Location): Boolean = true

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
    private var reachable: Set<Cell> = emptySet()
    private var preparation: CompletableFuture<Unit>? = null

    override fun prepare(queue: BudgetedTaskQueue): CompletableFuture<Unit> {
        preparation?.let { return it }
        val search = Search()
        return queue.submit({ true }, search::step).also { future ->
            preparation = future
            future.whenComplete { _, failure -> if (failure == null) reachable = search.visited }
        }
    }

    override fun prepareForMaintenance() {
        preparation?.let { previous ->
            check(previous.isDone && !previous.isCompletedExceptionally) { "Navigation preparation is unfinished or failed" }
            return
        }
        val search = Search(maintenance = true)
        while (!search.step()) { /* Explicit maintenance may load chunks and finish native capture inline. */ }
        reachable = search.visited
        preparation = CompletableFuture.completedFuture(Unit)
    }

    override fun snap(candidate: Location): Location? {
        if (candidate.world?.uid != world.uid) return null
        val target = Cell(candidate.blockX, candidate.blockY, candidate.blockZ)
        val candidates = sequence {
            for (x in target.x - MAX_HORIZONTAL_SNAP..target.x + MAX_HORIZONTAL_SNAP)
                for (z in target.z - MAX_HORIZONTAL_SNAP..target.z + MAX_HORIZONTAL_SNAP)
                    for (y in target.y - MAX_VERTICAL_SNAP..target.y + MAX_VERTICAL_SNAP) {
                        val cell = Cell(x, y, z)
                        if (cell in reachable) yield(cell)
                    }
        }
        val cell = candidates.minWithOrNull(compareBy<Cell> { cell ->
            val dx = candidate.x - (cell.x + 0.5)
            val dy = candidate.y - cell.y
            val dz = candidate.z - (cell.z + 0.5)
            dx * dx + dy * dy + dz * dz
        }.thenBy { it.y }.thenBy { it.x }.thenBy { it.z }) ?: return null
        if (!isStandable(cell)) return null
        if (cell == target) return candidate.clone()
        return Location(world, cell.x + 0.5, cell.y.toDouble(), cell.z + 0.5, candidate.yaw, candidate.pitch)
    }

    override fun isSafe(location: Location): Boolean =
        location.world?.uid == world.uid && isStandable(Cell(location.blockX, location.blockY, location.blockZ))

    /** One fixed node expansion per shared budget step; cold chunks are acquired asynchronously. */
    private inner class Search(private val maintenance: Boolean = false) {
        val visited = linkedSetOf<Cell>()
        private val pending = ArrayDeque<Cell>()
        private val enqueued = HashSet<Cell>()
        private var started = false
        private var loading: CompletableFuture<*>? = null

        fun step(): Boolean {
            loading?.let { load ->
                if (!load.isDone) return false
                if (load.isCompletedExceptionally) load.join() // Already complete: propagate, never wait.
                loading = null
            }
            val current = pending.firstOrNull() ?: Cell(firstSpawn.blockX, firstSpawn.blockY, firstSpawn.blockZ)
            for (x in (current.x - 1 shr 4)..(current.x + 1 shr 4)) {
                for (z in (current.z - 1 shr 4)..(current.z + 1 shr 4)) {
                    if (!world.isChunkLoaded(x, z)) {
                        if (maintenance) world.getChunkAt(x, z) else {
                            loading = world.getChunkAtAsync(x, z)
                            return false
                        }
                    }
                }
            }
            if (!started) {
                started = true
                val origin = nearestStandable(firstSpawn) ?: return true
                pending.add(origin)
                enqueued.add(origin)
            }
            if (pending.isEmpty() || visited.size >= MAX_REACHABLE_CELLS) return true
            val node = pending.removeFirst()
            visited.add(node)
            for ((dx, dz) in CARDINAL_DIRECTIONS) {
                for (dy in STEP_HEIGHTS) {
                    val next = Cell(node.x + dx, node.y + dy, node.z + dz)
                    if (next !in enqueued && limits.contains(next) && isStandable(next)) {
                        pending.addLast(next)
                        enqueued.add(next)
                        break
                    }
                }
            }
            return pending.isEmpty() || visited.size >= MAX_REACHABLE_CELLS
        }
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
        if (!world.isChunkLoaded(cell.x shr 4, cell.z shr 4)) return false
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
