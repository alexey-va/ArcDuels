package ru.ruscrafting.duels.paper

import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.PlayerId
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
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
            if (isValid(arena, planned.values.toList())) return planned
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
