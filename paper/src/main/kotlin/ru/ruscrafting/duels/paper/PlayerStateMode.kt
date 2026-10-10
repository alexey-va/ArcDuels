package ru.ruscrafting.duels.paper

import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.util.Locale

/** Whether an arena server keeps a durable pre-match player state for later restoration. */
enum class PlayerStateMode {
    PRESERVE,
    DISPOSABLE,
    ;

    companion object {
        fun parse(configured: String): PlayerStateMode =
            entries.firstOrNull { it.name == configured.trim().uppercase(Locale.ROOT) }
                ?: throw IllegalArgumentException("player-state.mode must be PRESERVE or DISPOSABLE")
    }
}

/** Clears temporary kit/combat effects without reading or restoring a prior player state. */
internal fun clearDisposableCombatState(player: Player) {
    player.gameMode = GameMode.SURVIVAL
    player.isInvulnerable = false
    player.allowFlight = false
    player.isFlying = false
    player.fireTicks = 0
    player.fallDistance = 0f
    player.noDamageTicks = 0
    player.absorptionAmount = 0.0
    player.velocity = Vector()
    player.foodLevel = 20
    player.saturation = 5f
    player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
    player.inventory.clear()
    player.inventory.armorContents = arrayOfNulls(4)
    player.inventory.setItemInOffHand(null)
    player.setItemOnCursor(ItemStack.empty())
    player.health = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
    player.updateInventory()
}
