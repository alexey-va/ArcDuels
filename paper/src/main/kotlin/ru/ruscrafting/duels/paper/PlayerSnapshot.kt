package ru.ruscrafting.duels.paper

import kotlin.math.abs
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
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
