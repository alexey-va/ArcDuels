package ru.ruscrafting.duels.paper

import kotlin.math.abs
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.util.Vector
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.playerstate.PaperPlayerStateSnapshot
import ru.arc.paper.playerstate.PaperLocationSnapshot
import ru.arc.paper.teleport.TeleportMatchTolerance

internal interface RestorablePlayerSnapshot {
    val storage: Array<ItemStack?>

    fun inventoryMatches(player: Player): Boolean

    fun inventoryMismatches(player: Player): List<String>

    fun locationMatches(player: Player): Boolean

    fun nonInventoryStateMatches(player: Player): Boolean

    fun nonInventoryStateMismatches(player: Player): List<String>

    fun restoreInventory(player: Player)

    fun restoreState(player: Player)

    fun restoreStateWithoutInventory(player: Player)

    fun restoreWithoutInventory(player: Player, teleport: (Player, Location) -> Boolean)

    fun restore(player: Player, teleport: (Player, Location) -> Boolean)
}

/** Thin ArcDuels adapter around the shared complete Paper state primitive. */
internal class PlayerSnapshot private constructor(
    internal val core: PaperPlayerStateSnapshot,
    private val fallbackWorld: World? = null,
) : RestorablePlayerSnapshot {
    val location: Location
        get() {
            val world = Bukkit.getWorld(core.location.worldId) ?: Bukkit.getWorld(core.location.worldName) ?: fallbackWorld
            return Location(requireNotNull(world), core.location.x, core.location.y, core.location.z, core.location.yaw, core.location.pitch)
        }
    override val storage: Array<ItemStack?> get() = core.storage.map { it?.clone() }.toTypedArray()
    val armor: Array<ItemStack?> get() = core.armor.map { it?.clone() }.toTypedArray()
    val offHand: ItemStack? get() = core.offHand?.clone()
    val cursor: ItemStack get() = core.cursor?.clone() ?: ItemStack.empty()
    val heldItemSlot: Int get() = core.selectedSlot
    val health: Double get() = core.health
    val foodLevel: Int get() = core.foodLevel
    val saturation: Float get() = core.saturation
    val exhaustion: Float get() = core.exhaustion
    val level: Int get() = core.level
    val experience: Float get() = core.experienceProgress
    val totalExperience: Int get() = core.totalExperience
    val gameMode: GameMode get() = core.gameMode
    val allowFlight: Boolean get() = core.allowFlight
    val flying: Boolean get() = core.flying
    val fireTicks: Int get() = core.fireTicks
    val fallDistance: Float get() = core.fallDistance
    val remainingAir: Int get() = core.remainingAir
    val noDamageTicks: Int get() = core.noDamageTicks
    val absorptionAmount: Double get() = core.absorption
    val velocity: Vector get() = Vector(core.velocity.x, core.velocity.y, core.velocity.z)
    val potionEffects: Collection<PotionEffect> get() = core.potionEffects.map { it.toPotionEffect() }

    override fun inventoryMatches(player: Player): Boolean = inventoryMismatches(player).isEmpty()

    override fun inventoryMismatches(player: Player): List<String> = SERVICE.inventoryMismatches(player, core)

    override fun locationMatches(player: Player): Boolean =
        if (fallbackWorld == null || player.server.getWorld(core.location.worldId) != null || player.server.getWorld(core.location.worldName) != null) {
            SERVICE.locationMatches(player, core)
        } else {
            val current = player.location
            current.world?.uid == fallbackWorld.uid &&
                abs(current.x - core.location.x) <= COORDINATE_EPSILON &&
                abs(current.y - core.location.y) <= COORDINATE_EPSILON &&
                abs(current.z - core.location.z) <= COORDINATE_EPSILON &&
                abs(current.yaw - core.location.yaw) <= ANGLE_EPSILON &&
                abs(current.pitch - core.location.pitch) <= ANGLE_EPSILON
        }

    override fun nonInventoryStateMatches(player: Player): Boolean = nonInventoryStateMismatches(player).isEmpty()

    override fun nonInventoryStateMismatches(player: Player): List<String> = SERVICE.nonInventoryStateMismatches(player, core)

    override fun restoreInventory(player: Player) = SERVICE.restoreInventoryAndVerify(player, core)

    override fun restoreState(player: Player) = SERVICE.restoreStateAtCurrentLocationAndVerify(player, core, fallbackWorld)

    override fun restoreStateWithoutInventory(player: Player) =
        SERVICE.restoreNonInventoryStateAtCurrentLocationAndVerify(player, core, fallbackWorld)

    override fun restoreWithoutInventory(player: Player, teleport: (Player, Location) -> Boolean) =
        SERVICE.restoreWithoutInventoryAndVerify(player, core, fallbackWorld, teleport)

    override fun restore(player: Player, teleport: (Player, Location) -> Boolean) =
        SERVICE.restoreAndVerify(player, core, fallbackWorld, teleport)

    fun copy(location: Location = this.location): PlayerSnapshot {
        val world = requireNotNull(location.world) { "Snapshot location must have a world" }
        return PlayerSnapshot(
            core.copy(
                location =
                    PaperLocationSnapshot(
                        world.uid,
                        world.name,
                        location.x,
                        location.y,
                        location.z,
                        location.yaw,
                        location.pitch,
                    ),
            ),
            fallbackWorld,
        )
    }

    companion object {
        private const val COORDINATE_EPSILON = 1.0e-7
        private const val ANGLE_EPSILON = 1.0e-4f
        private val SERVICE =
            PaperPlayerStateService(
                persistPlayerData = {},
                locationTolerance = TeleportMatchTolerance(COORDINATE_EPSILON, ANGLE_EPSILON),
            )

        fun capture(player: Player, capturedAtMillis: Long = System.currentTimeMillis().coerceAtLeast(1L)): PlayerSnapshot =
            PlayerSnapshot(SERVICE.capture(player, capturedAtMillis))

        fun fromCore(core: PaperPlayerStateSnapshot, fallbackWorld: World? = null): PlayerSnapshot = PlayerSnapshot(core, fallbackWorld)
    }
}

/** Decoder target kept only for format-1 escrow rows written before arc-core migration. */
internal data class LegacyPlayerSnapshot(
    val location: Location,
    override val storage: Array<ItemStack?>,
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
) : RestorablePlayerSnapshot {
    override fun inventoryMatches(player: Player): Boolean =
        inventoryMismatches(player).isEmpty()

    /** Returns bounded field names only; inventory contents must never enter logs. */
    override fun inventoryMismatches(player: Player): List<String> =
        buildList {
            if (!player.inventory.storageContents.sameItems(storage)) add("storage")
            if (!player.inventory.armorContents.sameItems(armor)) add("armor")
            if (!player.inventory.itemInOffHand.sameItem(offHand)) add("offHand")
            if (!player.itemOnCursor.sameItem(cursor)) add("cursor")
            if (player.inventory.heldItemSlot != heldItemSlot) add("heldSlot")
        }

    override fun locationMatches(player: Player): Boolean = player.location.sameLocation(location)

    override fun nonInventoryStateMatches(player: Player): Boolean = nonInventoryStateMismatches(player).isEmpty()

    override fun nonInventoryStateMismatches(player: Player): List<String> =
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

    override fun restoreInventory(player: Player) {
        player.inventory.storageContents = storage.clonedItems()
        player.inventory.armorContents = armor.clonedItems()
        player.inventory.setItemInOffHand(offHand?.clone())
        player.setItemOnCursor(cursor.clone())
        player.inventory.heldItemSlot = heldItemSlot
        player.updateInventory()
        check(inventoryMatches(player)) { "Inventory verification failed" }
    }

    override fun restoreState(player: Player) {
        restoreInventory(player)
        restoreStateWithoutInventory(player)
    }

    /** Restores health, game mode and movement state without touching any item. */
    override fun restoreStateWithoutInventory(player: Player) {
        restoreNonInventoryState(player)
        player.velocity = velocity.clone()
        verifyNonInventoryState(player)
    }

    override fun restoreWithoutInventory(
        player: Player,
        teleport: (Player, Location) -> Boolean,
    ) {
        restoreLocation(player, teleport)
        // Teleport listeners may normalize health, movement, game mode, or
        // effects. Apply the saved state only after every teleport callback.
        restoreStateWithoutInventory(player)
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

    override fun restore(
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
