package ru.ruscrafting.duels.paper

import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player
import kotlin.math.abs

internal class KitHealthIsolation(
    private val modifierKey: NamespacedKey,
) {
    fun enforce(player: Player): Boolean {
        val attribute = player.getAttribute(Attribute.MAX_HEALTH) ?: return false
        attribute.getModifier(modifierKey)?.let {
            if (abs(attribute.value - VANILLA_MAX_HEALTH) <= EPSILON) {
                player.absorptionAmount = 0.0
                if (player.health > VANILLA_MAX_HEALTH) player.health = VANILLA_MAX_HEALTH
                return true
            }
        }
        attribute.removeModifier(modifierKey)
        val externalMaximum = attribute.value
        if (!externalMaximum.isFinite() || externalMaximum <= 0.0) return false
        if (externalMaximum > VANILLA_MAX_HEALTH + EPSILON) {
            attribute.addTransientModifier(
                AttributeModifier(
                    modifierKey,
                    VANILLA_MAX_HEALTH / externalMaximum - 1.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                ),
            )
        }
        val expectedMaximum = minOf(externalMaximum, VANILLA_MAX_HEALTH)
        val isolatedMaximum = attribute.value
        if (abs(isolatedMaximum - expectedMaximum) > EPSILON) return false
        player.absorptionAmount = 0.0
        if (player.health > isolatedMaximum) player.health = isolatedMaximum
        return true
    }

    fun clear(player: Player) {
        player.getAttribute(Attribute.MAX_HEALTH)?.removeModifier(modifierKey)
    }

    companion object {
        const val VANILLA_MAX_HEALTH = 20.0
        private const val EPSILON = 0.001
    }
}
