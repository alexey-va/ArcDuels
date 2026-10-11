package ru.ruscrafting.duels.paper

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Instant, bounded effects shared with nearby opponents, below the caster's eye line. */
internal object MagicDuelBlinkEffects {
    private val trail = Particle.DustTransition(Color.fromRGB(165, 95, 255), Color.fromRGB(80, 235, 255), 0.85f)
    private val arrival = Particle.DustOptions(Color.fromRGB(100, 245, 255), 1.1f)

    fun play(origin: Location, destination: Location) {
        val world = origin.world ?: return
        if (destination.world?.uid != world.uid) return
        val delta = destination.toVector().subtract(origin.toVector())
        val distance = delta.length()
        if (distance < 0.01) return
        val side = delta.clone().setY(0.0).normalize().let { org.bukkit.util.Vector(-it.z, 0.0, it.x) }
        val steps = ceil(distance * 2.0).toInt().coerceIn(1, 32)
        for (step in 0..steps) {
            val fraction = step.toDouble() / steps
            val phase = fraction * PI * 4.0
            for (sign in listOf(-1.0, 1.0)) {
                val point = origin.clone().add(delta.clone().multiply(fraction))
                    .add(side.clone().multiply(cos(phase) * 0.3 * sign))
                    .add(0.0, 0.8 + sin(phase) * 0.25 * sign, 0.0)
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point, 1, 0.02, 0.02, 0.02, 0.0, trail)
            }
        }
        world.spawnParticle(Particle.REVERSE_PORTAL, origin.clone().add(0.0, 0.7, 0.0), 24, 0.35, 0.45, 0.35, 0.06)
        for (step in 0 until 20) {
            val angle = step * PI * 2.0 / 20
            val point = destination.clone().add(cos(angle) * 0.7, 0.15, sin(angle) * 0.7)
            world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, arrival)
        }
        world.spawnParticle(Particle.END_ROD, destination.clone().add(0.0, 0.6, 0.0), 10, 0.4, 0.25, 0.4, 0.025)
        world.playSound(origin, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.65f, 1.35f)
        world.playSound(destination, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.5f)
    }
}
