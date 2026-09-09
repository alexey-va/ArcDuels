package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import ru.arc.paper.testing.MockBukkitTestRuntime

class SumoCombatTest : StringSpec({
    "sumo hit briefly slows running without replacing knockback velocity" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.server.addPlayer()
            player.isSprinting = true
            val knockback = Vector(0.4, 0.3, 0.1)
            player.velocity = knockback
            SumoCombat.afterHit(player)
            player.isSprinting shouldBe false
            player.getPotionEffect(PotionEffectType.SLOWNESS)?.duration shouldBe 6
            player.getPotionEffect(PotionEffectType.SLOWNESS)?.amplifier shouldBe 1
            player.velocity shouldBe knockback
        }
    }
})
