package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Entity
import org.bukkit.entity.Firework
import org.bukkit.event.entity.EntityDamageByEntityEvent

class CelebrationEffectsTest : StringSpec({
    "only tagged celebration fireworks are treated as decorative" {
        val celebration = mockk<Firework> {
            every { scoreboardTags } returns setOf(CelebrationEffects.ENTITY_TAG)
        }
        val ordinary = mockk<Firework> {
            every { scoreboardTags } returns emptySet()
        }

        CelebrationEffects.isDecorativeFirework(celebration) shouldBe true
        CelebrationEffects.isDecorativeFirework(ordinary) shouldBe false
        CelebrationEffects.isDecorativeFirework(mockk<Entity>()) shouldBe false
    }

    "damage from a celebration firework is always cancelled" {
        val firework = mockk<Firework> {
            every { scoreboardTags } returns setOf(CelebrationEffects.ENTITY_TAG)
        }
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true) {
            every { damager } returns firework
        }
        val listener = DuelGameplayListener(mockk(relaxed = true))

        listener.onDecorativeFireworkDamage(event)

        verify(exactly = 1) { event.isCancelled = true }
    }
})
