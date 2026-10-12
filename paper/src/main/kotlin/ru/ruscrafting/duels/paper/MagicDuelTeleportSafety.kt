package ru.ruscrafting.duels.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import kotlin.math.floor

/** Shared landing checks for arena mobility. Call on the Paper thread with loaded chunks only. */
internal class MagicDuelTeleportSafety(
    private val isInsideArena: (Player, Location) -> Boolean,
    private val localCollisionBoxes: (Block) -> Collection<BoundingBox> = { block ->
        block.blockData.getCollisionShape(block.location).boundingBoxes
    },
) {
    fun isSafe(player: Player, candidate: Location): Boolean {
        val origin = player.location
        val world = origin.world ?: return false
        if (candidate.world?.uid != world.uid || !isInsideArena(player, candidate)) return false
        val box = player.boundingBox.clone().shift(
            candidate.x - origin.x, candidate.y - origin.y, candidate.z - origin.z,
        )
        if (!chunksLoaded(world, box) || !standable(world, candidate)) return false
        if (hasHazardOrBlockCollision(world, box)) return false
        return world.getNearbyEntities(box).none { entity ->
            entity is Player && entity.uniqueId != player.uniqueId && entity.boundingBox.overlaps(box)
        }
    }

    fun isExactDestination(actual: Location, expected: Location): Boolean {
        val world = actual.world ?: return false
        val expectedWorld = expected.world ?: return false
        return world.uid == expectedWorld.uid &&
            kotlin.math.abs(actual.x - expected.x) <= TELEPORT_COORDINATE_TOLERANCE &&
            kotlin.math.abs(actual.y - expected.y) <= TELEPORT_COORDINATE_TOLERANCE &&
            kotlin.math.abs(actual.z - expected.z) <= TELEPORT_COORDINATE_TOLERANCE &&
            kotlin.math.abs(actual.yaw - expected.yaw) <= TELEPORT_ANGLE_TOLERANCE &&
            kotlin.math.abs(actual.pitch - expected.pitch) <= TELEPORT_ANGLE_TOLERANCE
    }

    private fun chunksLoaded(world: org.bukkit.World, box: BoundingBox): Boolean {
        val minChunkX = floor(box.minX).toInt() shr 4
        val maxChunkX = floor(box.maxX - BOX_EDGE_EPSILON).toInt() shr 4
        val minChunkZ = floor(box.minZ).toInt() shr 4
        val maxChunkZ = floor(box.maxZ - BOX_EDGE_EPSILON).toInt() shr 4
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) return false
            }
        }
        return true
    }

    private fun standable(world: org.bukkit.World, location: Location): Boolean {
        val x = location.blockX
        val z = location.blockZ
        val floorBlock = world.getBlockAt(x, floor(location.y - BOX_EDGE_EPSILON).toInt(), z)
        if (!floorBlock.type.isSolid || floorBlock.isLiquid || isHazard(floorBlock.type)) return false
        return MagicDuelBlinkBlockShapes.supports(location, worldCollisionBoxes(floorBlock), SUPPORT_HEIGHT_EPSILON)
    }

    private fun hasHazardOrBlockCollision(world: org.bukkit.World, box: BoundingBox): Boolean {
        val minX = floor(box.minX).toInt()
        val maxX = floor(box.maxX - BOX_EDGE_EPSILON).toInt()
        val minY = floor(box.minY).toInt()
        val maxY = floor(box.maxY - BOX_EDGE_EPSILON).toInt()
        val minZ = floor(box.minZ).toInt()
        val maxZ = floor(box.maxZ - BOX_EDGE_EPSILON).toInt()
        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val block = world.getBlockAt(x, y, z)
            if (block.isLiquid || isHazard(block.type)) return true
            if (MagicDuelBlinkBlockShapes.overlaps(box, worldCollisionBoxes(block))) return true
        }
        return false
    }

    private fun worldCollisionBoxes(block: Block): List<BoundingBox> =
        MagicDuelBlinkBlockShapes.toWorld(block.x, block.y, block.z, localCollisionBoxes(block))

    private fun isHazard(material: Material): Boolean = material in HAZARDS

    private companion object {
        const val TELEPORT_COORDINATE_TOLERANCE = 1.0e-7
        const val TELEPORT_ANGLE_TOLERANCE = 1.0e-4f
        const val BOX_EDGE_EPSILON = 1.0e-7
        const val SUPPORT_HEIGHT_EPSILON = 1.0e-4
        val HAZARDS = setOf(
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.FIRE,
            Material.MAGMA_BLOCK,
            Material.POINTED_DRIPSTONE,
            Material.POWDER_SNOW,
            Material.SOUL_CAMPFIRE,
            Material.SOUL_FIRE,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE,
        )
    }
}
