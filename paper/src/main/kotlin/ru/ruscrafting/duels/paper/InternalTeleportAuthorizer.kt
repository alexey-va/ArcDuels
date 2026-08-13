package ru.ruscrafting.duels.paper

import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

internal class InternalTeleportAuthorizer {
    private val expectedByPlayer = ConcurrentHashMap<UUID, ExpectedTeleport>()

    fun <T> authorize(
        playerId: UUID,
        destination: Location,
        action: () -> T,
    ): T {
        val expected = ExpectedTeleport.from(destination)
        check(expectedByPlayer.putIfAbsent(playerId, expected) == null) { "A teleport is already authorized for $playerId" }
        return try {
            action()
        } finally {
            expectedByPlayer.remove(playerId, expected)
        }
    }

    fun isAuthorized(
        playerId: UUID,
        destination: Location?,
    ): Boolean = destination != null && expectedByPlayer[playerId]?.matches(destination) == true

    private data class ExpectedTeleport(
        val worldId: UUID,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
    ) {
        fun matches(location: Location): Boolean {
            val world = location.world ?: return false
            return world.uid == worldId &&
                abs(location.x - x) <= COORDINATE_EPSILON &&
                abs(location.y - y) <= COORDINATE_EPSILON &&
                abs(location.z - z) <= COORDINATE_EPSILON &&
                abs(location.yaw - yaw) <= ANGLE_EPSILON &&
                abs(location.pitch - pitch) <= ANGLE_EPSILON
        }

        companion object {
            fun from(location: Location): ExpectedTeleport {
                val world = requireNotNull(location.world) { "Teleport destination must have a world" }
                return ExpectedTeleport(world.uid, location.x, location.y, location.z, location.yaw, location.pitch)
            }
        }
    }

    private companion object {
        const val COORDINATE_EPSILON = 1.0e-7
        const val ANGLE_EPSILON = 1.0e-4f
    }
}
