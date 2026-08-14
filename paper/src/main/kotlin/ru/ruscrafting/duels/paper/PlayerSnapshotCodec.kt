package ru.ruscrafting.duels.paper

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.util.Vector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal class PlayerSnapshotCodec(
    private val server: Server,
) {
    fun encode(snapshot: PlayerSnapshot): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(FORMAT_VERSION)
            val world = requireNotNull(snapshot.location.world) { "Snapshot location has no world" }
            data.writeLong(world.uid.mostSignificantBits)
            data.writeLong(world.uid.leastSignificantBits)
            data.writeUTF(world.name)
            data.writeDouble(snapshot.location.x)
            data.writeDouble(snapshot.location.y)
            data.writeDouble(snapshot.location.z)
            data.writeFloat(snapshot.location.yaw)
            data.writeFloat(snapshot.location.pitch)
            data.writeItemArray(snapshot.storage)
            data.writeItemArray(snapshot.armor)
            data.writeItem(snapshot.offHand)
            data.writeItem(snapshot.cursor)
            data.writeInt(snapshot.heldItemSlot)
            data.writeDouble(snapshot.health)
            data.writeInt(snapshot.foodLevel)
            data.writeFloat(snapshot.saturation)
            data.writeFloat(snapshot.exhaustion)
            data.writeInt(snapshot.level)
            data.writeFloat(snapshot.experience)
            data.writeInt(snapshot.totalExperience)
            data.writeUTF(snapshot.gameMode.name)
            data.writeBoolean(snapshot.allowFlight)
            data.writeBoolean(snapshot.flying)
            data.writeInt(snapshot.fireTicks)
            data.writeFloat(snapshot.fallDistance)
            data.writeInt(snapshot.remainingAir)
            data.writeInt(snapshot.noDamageTicks)
            data.writeDouble(snapshot.absorptionAmount)
            data.writeDouble(snapshot.velocity.x)
            data.writeDouble(snapshot.velocity.y)
            data.writeDouble(snapshot.velocity.z)
            check(snapshot.potionEffects.size <= MAX_POTION_EFFECTS) { "Too many potion effects in snapshot" }
            data.writeInt(snapshot.potionEffects.size)
            snapshot.potionEffects.forEach { data.writePotionEffect(it, 0) }
        }
        return output.toByteArray().also {
            check(it.size <= MAX_PAYLOAD_BYTES) { "Encoded player snapshot exceeds 8 MiB" }
        }
    }

    fun decode(
        payload: ByteArray,
        fallbackWorld: World? = null,
    ): PlayerSnapshot {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) { "Invalid player snapshot size" }
        return DataInputStream(ByteArrayInputStream(payload)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid player snapshot magic" }
            require(data.readInt() == FORMAT_VERSION) { "Unsupported player snapshot format" }
            val worldId = java.util.UUID(data.readLong(), data.readLong())
            val worldName = data.readUTF()
            val world = server.getWorld(worldId) ?: server.getWorld(worldName) ?: fallbackWorld
            requireNotNull(world) { "Snapshot world '$worldName' ($worldId) is not loaded" }
            val location = Location(world, data.readDouble(), data.readDouble(), data.readDouble(), data.readFloat(), data.readFloat())
            require(listOf(location.x, location.y, location.z).all(Double::isFinite)) { "Snapshot location is not finite" }
            val storage = data.readItemArray(EXPECTED_STORAGE_SLOTS)
            val armor = data.readItemArray(EXPECTED_ARMOR_SLOTS)
            val offHand = data.readItem()
            val cursor = data.readItem() ?: ItemStack.empty()
            val heldItemSlot = data.readInt().also { require(it in 0..8) { "Invalid held item slot" } }
            val health = data.readDouble().also { require(it.isFinite() && it > 0.0) { "Invalid health" } }
            val foodLevel = data.readInt().also { require(it in 0..20) { "Invalid food level" } }
            val saturation = data.readFloat().also { require(it.isFinite() && it >= 0f) { "Invalid saturation" } }
            val exhaustion = data.readFloat().also { require(it.isFinite() && it >= 0f) { "Invalid exhaustion" } }
            val level = data.readInt().also { require(it >= 0) { "Invalid experience level" } }
            val experience = data.readFloat().also { require(it.isFinite() && it in 0f..1f) { "Invalid experience progress" } }
            val totalExperience = data.readInt().also { require(it >= 0) { "Invalid total experience" } }
            val gameMode = runCatching { GameMode.valueOf(data.readUTF()) }.getOrElse { error("Invalid game mode") }
            val allowFlight = data.readBoolean()
            val flying = data.readBoolean()
            val fireTicks = data.readInt()
            val fallDistance = data.readFloat().also { require(it.isFinite()) { "Invalid fall distance" } }
            val remainingAir = data.readInt()
            val noDamageTicks = data.readInt()
            val absorptionAmount = data.readDouble().also { require(it.isFinite() && it >= 0.0) { "Invalid absorption" } }
            val velocity = Vector(data.readDouble(), data.readDouble(), data.readDouble())
            require(listOf(velocity.x, velocity.y, velocity.z).all(Double::isFinite)) { "Snapshot velocity is not finite" }
            val effectCount = data.readInt().also { require(it in 0..MAX_POTION_EFFECTS) { "Invalid potion effect count" } }
            val effects = List(effectCount) { data.readPotionEffect(0) }
            require(data.read() == -1) { "Trailing bytes in player snapshot" }
            PlayerSnapshot(
                location,
                storage,
                armor,
                offHand,
                cursor,
                heldItemSlot,
                health,
                foodLevel,
                saturation,
                exhaustion,
                level,
                experience,
                totalExperience,
                gameMode,
                allowFlight,
                flying,
                fireTicks,
                fallDistance,
                remainingAir,
                noDamageTicks,
                absorptionAmount,
                velocity,
                effects,
            )
        }
    }

    private fun DataOutputStream.writeItemArray(items: Array<ItemStack?>) =
        writeBlob(ItemStack.serializeItemsAsBytes(items))

    private fun DataInputStream.readItemArray(expectedSize: Int): Array<ItemStack?> {
        val items = ItemStack.deserializeItemsFromBytes(readBlob()).map { it.takeUnless(ItemStack::isEmpty) }.toTypedArray()
        require(items.size == expectedSize) { "Invalid inventory slot count" }
        return items
    }

    private fun DataOutputStream.writeItem(item: ItemStack?) = writeBlob(ItemStack.serializeItemsAsBytes(arrayOf(item)))

    private fun DataInputStream.readItem(): ItemStack? {
        val items = ItemStack.deserializeItemsFromBytes(readBlob())
        require(items.size == 1) { "Invalid single-item payload" }
        return items.single().takeUnless(ItemStack::isEmpty)
    }

    private fun DataOutputStream.writeBlob(bytes: ByteArray) {
        require(bytes.size <= MAX_ITEM_BLOB_BYTES) { "Serialized item data is too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBlob(): ByteArray {
        val length = readInt()
        require(length in 1..MAX_ITEM_BLOB_BYTES) { "Invalid serialized item data length" }
        return ByteArray(length).also(::readFully)
    }

    private fun DataOutputStream.writePotionEffect(
        effect: PotionEffect,
        depth: Int,
    ) {
        require(depth <= MAX_HIDDEN_EFFECT_DEPTH) { "Potion effect chain is too deep" }
        writeUTF(effect.type.key.toString())
        writeInt(effect.duration)
        writeInt(effect.amplifier)
        writeBoolean(effect.isAmbient)
        writeBoolean(effect.hasParticles())
        writeBoolean(effect.hasIcon())
        val hidden = effect.hiddenPotionEffect
        writeBoolean(hidden != null)
        if (hidden != null) writePotionEffect(hidden, depth + 1)
    }

    private fun DataInputStream.readPotionEffect(depth: Int): PotionEffect {
        require(depth <= MAX_HIDDEN_EFFECT_DEPTH) { "Potion effect chain is too deep" }
        val key = requireNotNull(NamespacedKey.fromString(readUTF())) { "Invalid potion effect key" }
        val type = requireNotNull(Registry.MOB_EFFECT[key]) { "Unknown potion effect $key" }
        val duration = readInt()
        val amplifier = readInt()
        val ambient = readBoolean()
        val particles = readBoolean()
        val icon = readBoolean()
        val hidden = if (readBoolean()) readPotionEffect(depth + 1) else null
        return PotionEffect(type, duration, amplifier, ambient, particles, icon, hidden)
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
        private const val MAGIC = 0x41524455
        private const val EXPECTED_STORAGE_SLOTS = 36
        private const val EXPECTED_ARMOR_SLOTS = 4
        private const val MAX_ITEM_BLOB_BYTES = 4 * 1024 * 1024
        private const val MAX_POTION_EFFECTS = 128
        private const val MAX_HIDDEN_EFFECT_DEPTH = 16
    }
}
