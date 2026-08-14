package ru.ruscrafting.duels.paper

import kotlin.math.abs
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
    fun inventoryMatches(player: Player): Boolean =
        player.inventory.storageContents.sameItems(storage) &&
            player.inventory.armorContents.sameItems(armor) &&
            player.inventory.itemInOffHand.sameItem(offHand) &&
            player.itemOnCursor.sameItem(cursor) &&
            player.inventory.heldItemSlot == heldItemSlot

    fun locationMatches(player: Player): Boolean = player.location.sameLocation(location)

    fun nonInventoryStateMatches(player: Player): Boolean = nonInventoryStateMismatches(player).isEmpty()

    fun nonInventoryStateMismatches(player: Player): List<String> =
        buildList {
            if (player.gameMode != gameMode) add("gameMode")
            if (player.allowFlight != allowFlight || player.isFlying != (flying && allowFlight)) add("flight")
            if (player.foodLevel != foodLevel || player.saturation != saturation || player.exhaustion != exhaustion) add("food")
            if (player.totalExperience != totalExperience || player.level != level || player.exp != experience) add("experience")
            if (player.fireTicks != fireTicks || player.fallDistance != fallDistance || player.remainingAir != remainingAir) add("movement")
            if (player.noDamageTicks != noDamageTicks || player.absorptionAmount != absorptionAmount) add("damage")
            if (!healthMatches(player)) add("health")
            if (player.activePotionEffects.toSet() != potionEffects.toSet()) add("potionEffects")
            if (player.velocity != velocity) add("velocity")
        }

    fun restoreLocation(
        player: Player,
        teleport: (Player, Location) -> Boolean,
    ) {
        if (locationMatches(player)) return
        check(teleport(player, location.clone())) { "Could not restore ${player.uniqueId} to their saved location" }
        check(locationMatches(player)) { "Location verification failed" }
    }

    fun restoreInventory(player: Player) {
        player.inventory.storageContents = storage.clonedItems()
        player.inventory.armorContents = armor.clonedItems()
        player.inventory.setItemInOffHand(offHand?.clone())
        player.setItemOnCursor(cursor.clone())
        player.inventory.heldItemSlot = heldItemSlot
        player.updateInventory()
        check(inventoryMatches(player)) { "Inventory verification failed" }
    }

    fun restoreState(player: Player) {
        restoreInventory(player)
        restoreNonInventoryState(player)
    }

    fun restoreWithoutInventory(
        player: Player,
        teleport: (Player, Location) -> Boolean,
    ) {
        restoreLocation(player, teleport)
        // Teleport listeners may normalize health, movement, game mode, or
        // effects. Apply the saved state only after every teleport callback.
        restoreNonInventoryState(player)
        player.velocity = velocity.clone()
        verifyNonInventoryState(player)
        check(locationMatches(player)) { "Location verification failed" }
    }

    private fun restoreNonInventoryState(player: Player) {
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
        restoreHealth(player)
    }

    fun restore(
        player: Player,
        teleport: (Player, Location) -> Boolean,
    ) {
        restoreInventory(player)
        restoreLocation(player, teleport)
        restoreNonInventoryState(player)
        player.velocity = velocity.clone()
        verifyRestored(player)
    }

    fun verifyRestored(player: Player) {
        check(player.inventory.storageContents.sameItems(storage)) { "Storage inventory verification failed" }
        check(player.inventory.armorContents.sameItems(armor)) { "Armor inventory verification failed" }
        check(player.inventory.itemInOffHand.sameItem(offHand)) { "Off-hand verification failed" }
        check(player.itemOnCursor.sameItem(cursor)) { "Cursor item verification failed" }
        check(player.inventory.heldItemSlot == heldItemSlot) { "Held item slot verification failed" }
        check(player.gameMode == gameMode) { "Game mode verification failed" }
        check(player.allowFlight == allowFlight && player.isFlying == (flying && allowFlight)) { "Flight state verification failed" }
        check(player.foodLevel == foodLevel && player.saturation == saturation && player.exhaustion == exhaustion) {
            "Food state verification failed"
        }
        check(player.totalExperience == totalExperience && player.level == level && player.exp == experience) {
            "Experience verification failed"
        }
        check(player.fireTicks == fireTicks && player.fallDistance == fallDistance && player.remainingAir == remainingAir) {
            "Movement state verification failed"
        }
        check(player.noDamageTicks == noDamageTicks && player.absorptionAmount == absorptionAmount) {
            "Damage state verification failed"
        }
        check(healthMatches(player)) {
            "Health verification failed"
        }
        check(player.activePotionEffects.toSet() == potionEffects.toSet()) { "Potion effect verification failed" }
        check(locationMatches(player)) { "Location verification failed" }
        check(player.velocity == velocity) { "Velocity verification failed" }
    }

    private fun verifyNonInventoryState(player: Player) {
        val mismatches = nonInventoryStateMismatches(player)
        check(mismatches.isEmpty()) { "Non-inventory state verification failed: ${mismatches.joinToString()}" }
    }

    private fun restoreHealth(player: Player) {
        player.health = restoredHealth(player)
    }

    private fun healthMatches(player: Player): Boolean =
        abs(player.health - restoredHealth(player)) <= HEALTH_EPSILON

    private fun restoredHealth(player: Player): Double {
        val maximumHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        return health.coerceIn(0.1, maximumHealth)
    }

    companion object {
        private const val HEALTH_EPSILON = 0.00001

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

        private fun Array<ItemStack?>.sameItems(other: Array<ItemStack?>): Boolean =
            size == other.size && indices.all { this[it].sameItem(other[it]) }

        private fun ItemStack?.sameItem(other: ItemStack?): Boolean {
            val first = this?.takeUnless(ItemStack::isEmpty)
            val second = other?.takeUnless(ItemStack::isEmpty)
            return first == second
        }

        private fun Location.sameLocation(other: Location): Boolean =
            world?.uid == other.world?.uid &&
                x == other.x && y == other.y && z == other.z && yaw == other.yaw && pitch == other.pitch
    }
}
