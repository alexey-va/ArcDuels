package ru.ruscrafting.duels.paper

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small viewer-scoped native effects for the magic duel's temporary return point. */
internal object MagicDuelEchoEffects {
    private const val AUDIENCE_RADIUS = 48.0
    private const val AUDIENCE_RADIUS_SQUARED = AUDIENCE_RADIUS * AUDIENCE_RADIUS
    private const val OWNER_HIDE_RADIUS_SQUARED = 2.5 * 2.5
    private const val ANCHOR_RING_STEPS = 14
    private const val PUBLIC_SOUND_VOLUME = 3.0f // Extends the positional sound range to about 48 blocks.

    private val cyan = Particle.DustOptions(Color.fromRGB(75, 235, 255), 0.78f)
    private val violet = Particle.DustOptions(Color.fromRGB(174, 112, 255), 0.72f)
    private val brightCyan = Particle.DustOptions(Color.fromRGB(125, 250, 255), 0.92f)

    /** At most 44 particles per viewer per call: 15 for the foot ring and 29 for the silhouette. */
    fun anchor(owner: Player, location: Location, remaining: Double) {
        val world = location.world ?: return
        val progress = remaining.coerceIn(0.0, 1.0)
        val radius = 0.62 - (1.0 - progress) * 0.08
        val markerAngle = (1.0 - progress) * 2.0 * PI

        forNearby(world, location) { viewer ->
            val ownerClose = viewer.uniqueId == owner.uniqueId &&
                viewer.location.distanceSquared(location) <= OWNER_HIDE_RADIUS_SQUARED
            drawFootRing(viewer, location, radius, markerAngle)
            if (!ownerClose) drawSilhouette(viewer, location)
        }
    }

    fun cue(player: Player, location: Location, cue: MagicDuelEchoCue) {
        val world = location.world ?: return
        when (cue) {
            MagicDuelEchoCue.PLACED -> {
                world.playSound(location, Sound.BLOCK_BEACON_POWER_SELECT, PUBLIC_SOUND_VOLUME, 1.35f)
                forNearby(world, location) { drawPlacementCue(it, location) }
            }
            MagicDuelEchoCue.DEPARTED -> {
                world.playSound(location, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, PUBLIC_SOUND_VOLUME, 1.25f)
                forNearby(world, location) { drawDepartureCue(it, location) }
            }
            MagicDuelEchoCue.RETURNED -> {
                world.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, PUBLIC_SOUND_VOLUME, 1.55f)
                forNearby(world, location) { drawReturnCue(it, location) }
            }
            MagicDuelEchoCue.EXPIRED -> {
                player.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.85f, 0.72f)
                drawPersonalDust(player, location, violet, count = 6, height = 0.18)
            }
            MagicDuelEchoCue.DENIED -> {
                player.playSound(location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.85f, 0.62f)
                drawPersonalDust(player, location, violet, count = 5, height = 0.14)
            }
        }
    }

    private fun drawFootRing(viewer: Player, location: Location, radius: Double, markerAngle: Double) {
        repeat(ANCHOR_RING_STEPS) { index ->
            val angle = index * 2.0 * PI / ANCHOR_RING_STEPS
            val point = location.clone().add(cos(angle) * radius, 0.07, sin(angle) * radius)
            dust(viewer, point, if (index % 2 == 0) cyan else violet)
        }
        endRod(viewer, location.clone().add(cos(markerAngle) * radius, 0.09, sin(markerAngle) * radius))
    }

    private fun drawSilhouette(viewer: Player, location: Location) {
        val yaw = Math.toRadians(location.yaw.toDouble())
        val sideX = cos(yaw)
        val sideZ = sin(yaw)
        val forwardX = -sin(yaw)
        val forwardZ = cos(yaw)

        fun mark(lateral: Double, height: Double, color: Particle.DustOptions = cyan) {
            dust(viewer, localPoint(location, sideX, sideZ, forwardX, forwardZ, lateral, 0.0, height), color)
        }

        // A thin head loop, shoulder line, chest, arms, waist and two leg contours.
        repeat(8) { index ->
            val angle = index * 2.0 * PI / 8
            mark(cos(angle) * 0.15, 1.72 + sin(angle) * 0.15, if (index % 2 == 0) brightCyan else violet)
        }
        mark(-0.24, 1.40, brightCyan)
        mark(0.0, 1.40, brightCyan)
        mark(0.24, 1.40, brightCyan)
        mark(0.0, 1.17)
        mark(0.0, 0.96, violet)
        mark(-0.37, 1.20, violet)
        mark(0.37, 1.20, violet)
        mark(-0.48, 1.00, brightCyan)
        mark(0.48, 1.00, brightCyan)
        mark(-0.19, 0.75, violet)
        mark(0.0, 0.75, violet)
        mark(0.19, 0.75, violet)
        mark(-0.13, 0.50)
        mark(0.13, 0.50)
        mark(-0.18, 0.28, violet)
        mark(0.18, 0.28, violet)
        mark(-0.20, 0.07, brightCyan)
        mark(0.20, 0.07, brightCyan)

        endRod(viewer, localPoint(location, sideX, sideZ, forwardX, forwardZ, 0.0, 0.0, 1.94))
        endRod(viewer, localPoint(location, sideX, sideZ, forwardX, forwardZ, -0.29, 0.0, 1.35))
        endRod(viewer, localPoint(location, sideX, sideZ, forwardX, forwardZ, 0.29, 0.0, 1.35))
    }

    private fun drawPlacementCue(viewer: Player, location: Location) {
        repeat(12) { index ->
            val angle = index * 2.0 * PI / 12
            dust(viewer, location.clone().add(cos(angle) * 0.46, 0.09, sin(angle) * 0.46), brightCyan)
        }
        endRod(viewer, location.clone().add(0.0, 0.30, 0.0))
        endRod(viewer, location.clone().add(0.0, 0.72, 0.0))
    }

    /** Endpoint-only cue: never draws a path that could appear to pass through cover. */
    private fun drawDepartureCue(viewer: Player, location: Location) {
        listOf(0.20, 0.46, 0.76, 1.06, 1.36, 1.62).forEachIndexed { index, height ->
            dust(viewer, location.clone().add(0.0, height, 0.0), if (index % 2 == 0) violet else cyan)
        }
        endRod(viewer, location.clone().add(0.0, 0.35, 0.0))
        endRod(viewer, location.clone().add(0.0, 0.85, 0.0))
        endRod(viewer, location.clone().add(0.0, 1.35, 0.0))
    }

    /** Endpoint-only cue: a compact ring and short vertical accent at the arrival point. */
    private fun drawReturnCue(viewer: Player, location: Location) {
        repeat(14) { index ->
            val angle = index * 2.0 * PI / 14
            dust(viewer, location.clone().add(cos(angle) * 0.54, 0.08, sin(angle) * 0.54),
                if (index % 2 == 0) brightCyan else violet)
        }
        listOf(0.35, 0.72, 1.09, 1.42).forEach { height ->
            endRod(viewer, location.clone().add(0.0, height, 0.0))
        }
    }

    private fun drawPersonalDust(
        player: Player,
        location: Location,
        color: Particle.DustOptions,
        count: Int,
        height: Double,
    ) {
        repeat(count) { index ->
            val angle = index * 2.0 * PI / count
            dust(player, location.clone().add(cos(angle) * 0.20, height, sin(angle) * 0.20), color)
        }
    }

    private fun forNearby(world: World, location: Location, effect: (Player) -> Unit) {
        world.players.forEach { viewer ->
            if (viewer.location.distanceSquared(location) <= AUDIENCE_RADIUS_SQUARED) effect(viewer)
        }
    }

    private fun localPoint(
        base: Location,
        sideX: Double,
        sideZ: Double,
        forwardX: Double,
        forwardZ: Double,
        lateral: Double,
        depth: Double,
        height: Double,
    ) = base.clone().add(sideX * lateral + forwardX * depth, height, sideZ * lateral + forwardZ * depth)

    private fun dust(viewer: Player, location: Location, options: Particle.DustOptions) {
        viewer.spawnParticle(Particle.DUST, location, 1, 0.0, 0.0, 0.0, 0.0, options)
    }

    private fun endRod(viewer: Player, location: Location) {
        viewer.spawnParticle(Particle.END_ROD, location, 1)
    }
}
