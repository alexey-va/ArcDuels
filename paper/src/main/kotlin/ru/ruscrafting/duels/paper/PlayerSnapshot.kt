package ru.ruscrafting.duels.paper

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect

data class PlayerSnapshot(
    val location: Location,
    val storage: Array<ItemStack?>,
    val armor: Array<ItemStack?>,
    val offHand: ItemStack?,
    val health: Double,
    val foodLevel: Int,
    val saturation: Float,
    val level: Int,
    val experience: Float,
    val gameMode: GameMode,
    val allowFlight: Boolean,
    val flying: Boolean,
    val potionEffects: Collection<PotionEffect>,
) {
    fun restore(player: Player) {
        player.inventory.storageContents = storage.clonedItems()
        player.inventory.armorContents = armor.clonedItems()
        player.inventory.setItemInOffHand(offHand?.clone())
        player.gameMode = gameMode
        player.allowFlight = allowFlight
        player.isFlying = flying && allowFlight
        player.foodLevel = foodLevel
        player.saturation = saturation
        player.level = level
        player.exp = experience
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.addPotionEffects(potionEffects)
        val maximumHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.health = health.coerceIn(0.1, maximumHealth)
        player.teleport(location)
    }

    companion object {
        fun capture(player: Player): PlayerSnapshot =
            PlayerSnapshot(
                location = player.location.clone(),
                storage = player.inventory.storageContents.clonedItems(),
                armor = player.inventory.armorContents.clonedItems(),
                offHand = player.inventory.itemInOffHand.clone(),
                health = player.health,
                foodLevel = player.foodLevel,
                saturation = player.saturation,
                level = player.level,
                experience = player.exp,
                gameMode = player.gameMode,
                allowFlight = player.allowFlight,
                flying = player.isFlying,
                potionEffects = player.activePotionEffects.map(PotionEffect::class.java::cast),
            )

        private fun Array<ItemStack?>.clonedItems(): Array<ItemStack?> = map { it?.clone() }.toTypedArray()
    }
}
