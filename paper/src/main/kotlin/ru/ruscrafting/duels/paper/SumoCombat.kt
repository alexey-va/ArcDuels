package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/** Briefly limits running after a landed punch without cancelling native knockback. */
internal object SumoCombat {
    fun afterHit(victim: Player) {
        victim.isSprinting = false
        victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 6, 1, false, false, false))
    }
}
