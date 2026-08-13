package ru.ruscrafting.duels.paper

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.util.Vector

data class PlayerSnapshot(
    val location: Location,
    val storage: Array<ItemStack?>,
    val armor: Array<ItemStack?>,
    val offHand: ItemStack?,
    val cursor: ItemStack,
    val heldItemSlot: Int,
    val health: Double,
    val foodLevel: Int,
    val saturation: Float,
    val exhaustion: Float,
    val level: Int,
    val experience: Float,
    val totalExperience: Int,
    val gameMode: GameMode,
    val allowFlight: Boolean,
    val flying: Boolean,
    val fireTicks: Int,
    val fallDistance: Float,
    val remainingAir: Int,
    val noDamageTicks: Int,
    val absorptionAmount: Double,
    val velocity: Vector,
    val potionEffects: Collection<PotionEffect>,
) {
    fun restoreState(player: Player) {
        player.inventory.storageContents = storage.clonedItems()
        player.inventory.armorContents = armor.clonedItems()
        player.inventory.setItemInOffHand(offHand?.clone())
        player.setItemOnCursor(cursor.clone())
        player.inventory.heldItemSlot = heldItemSlot
        player.gameMode = gameMode
        player.allowFlight = allowFlight
        player.isFlying = flying && allowFlight
        player.foodLevel = foodLevel
        player.saturation = saturation
        player.exhaustion = exhaustion
        player.totalExperience = totalExperience
        player.level = level
        player.exp = experience
        player.fireTicks = fireTicks
        player.fallDistance = fallDistance
        player.remainingAir = remainingAir
        player.noDamageTicks = noDamageTicks
        player.absorptionAmount = absorptionAmount
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.addPotionEffects(potionEffects)
        val maximumHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.health = health.coerceIn(0.1, maximumHealth)
        player.updateInventory()
    }

    fun restore(
        player: Player,
        teleport: (Player, Location) -> Boolean,
    ) {
        restoreState(player)
        check(teleport(player, location.clone())) { "Could not restore ${player.uniqueId} to their saved location" }
        player.velocity = velocity.clone()
    }

    companion object {
        fun capture(player: Player): PlayerSnapshot =
            PlayerSnapshot(
                location = player.location.clone(),
                storage = player.inventory.storageContents.clonedItems(),
                armor = player.inventory.armorContents.clonedItems(),
                offHand = player.inventory.itemInOffHand.clone(),
                cursor = player.itemOnCursor.clone(),
                heldItemSlot = player.inventory.heldItemSlot,
                health = player.health,
                foodLevel = player.foodLevel,
                saturation = player.saturation,
                exhaustion = player.exhaustion,
                level = player.level,
                experience = player.exp,
                totalExperience = player.totalExperience,
                gameMode = player.gameMode,
                allowFlight = player.allowFlight,
                flying = player.isFlying,
                fireTicks = player.fireTicks,
                fallDistance = player.fallDistance,
                remainingAir = player.remainingAir,
                noDamageTicks = player.noDamageTicks,
                absorptionAmount = player.absorptionAmount,
                velocity = player.velocity.clone(),
                potionEffects = player.activePotionEffects.map(PotionEffect::class.java::cast),
            )

        private fun Array<ItemStack?>.clonedItems(): Array<ItemStack?> = map { it?.clone() }.toTypedArray()
    }
}
