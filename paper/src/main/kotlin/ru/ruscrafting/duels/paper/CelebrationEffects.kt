package ru.ruscrafting.duels.paper

import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Entity
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal class CelebrationEffects(
    private val plugin: JavaPlugin,
    private val runtimeSettings: () -> ArcDuelsRuntimeSettings? = { null },
) {
    fun play(
        player: Player,
        settingsOverride: ArcDuelsRuntimeSettings? = null,
    ) {
        val center = player.location.add(0.0, 1.0, 0.0)
        player.world.spawnParticle(Particle.FIREWORK, center, 120, 1.2, 1.5, 1.2, 0.2)
        player.world.spawnParticle(Particle.FLASH, center, 8, 0.7, 0.8, 0.7, 0.0, Color.WHITE)
        player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 80, 0.8, 1.0, 0.8, 0.15)
        player.world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 0.8f, 1.0f)
        player.world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.2f)
        val settings = settingsOverride ?: runtimeSettings()
        val fireworksEnabled = settings?.celebrationFireworksEnabled
            ?: plugin.config.getBoolean("celebration.fireworks.enabled", true)
        val fireworkCount = settings?.celebrationFireworkCount
            ?: plugin.config.getInt("celebration.fireworks.count", 4).coerceIn(1, MAX_FIREWORKS)
        if (!fireworksEnabled) return

        repeat(fireworkCount) { index ->
            plugin.server.scheduler.runTaskLater(
                plugin,
                Runnable {
                    if (!plugin.isEnabled || !player.isOnline) return@Runnable
                    spawnFirework(player, index, fireworkCount)
                },
                index * FIREWORK_SPACING_TICKS,
            )
        }
    }

    private fun spawnFirework(
        player: Player,
        index: Int,
        fireworkCount: Int,
    ) {
        val angle = 2.0 * PI * index / fireworkCount
        val location = player.location.add(cos(angle) * 0.9, 0.4, sin(angle) * 0.9)
        player.world.spawn(location, Firework::class.java) { firework ->
            val meta = firework.fireworkMeta
            meta.addEffect(
                FireworkEffect.builder()
                    .with(FIREWORK_TYPES[index % FIREWORK_TYPES.size])
                    .withColor(FIREWORK_COLORS[index % FIREWORK_COLORS.size])
                    .withFade(FIREWORK_FADE_COLORS[index % FIREWORK_FADE_COLORS.size])
                    .withTrail()
                    .withFlicker()
                    .build(),
            )
            meta.power = 0
            firework.fireworkMeta = meta
            firework.addScoreboardTag(ENTITY_TAG)
            firework.setTicksToDetonate(FIREWORK_FUSE_TICKS)
        }
    }

    companion object {
        const val ENTITY_TAG = "arcduels_celebration"
        private const val MAX_FIREWORKS = 5
        private const val FIREWORK_SPACING_TICKS = 6L
        private const val FIREWORK_FUSE_TICKS = 12
        private val FIREWORK_TYPES =
            listOf(FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR, FireworkEffect.Type.BURST)
        private val FIREWORK_COLORS = listOf(Color.AQUA, Color.LIME, Color.FUCHSIA)
        private val FIREWORK_FADE_COLORS = listOf(Color.BLUE, Color.YELLOW, Color.PURPLE)

        fun isDecorativeFirework(entity: Entity): Boolean =
            entity is Firework && ENTITY_TAG in entity.scoreboardTags
    }
}
