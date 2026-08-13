package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.KitId

data class DuelKit(
    val id: KitId,
    val displayName: Component,
    val icon: Material,
    val items: Map<Int, ItemStack>,
    val helmet: ItemStack?,
    val chestplate: ItemStack?,
    val leggings: ItemStack?,
    val boots: ItemStack?,
)

class KitRegistry private constructor(
    private val kits: Map<KitId, DuelKit>,
) {
    fun get(id: KitId): DuelKit = kits[id] ?: error("Unknown duel kit $id")

    fun all(): List<DuelKit> = kits.values.sortedBy { it.id.value }

    companion object {
        private val miniMessage = MiniMessage.miniMessage()

        fun load(plugin: JavaPlugin): KitRegistry {
            val root = plugin.config.getConfigurationSection("kits") ?: return KitRegistry(emptyMap())
            val loaded =
                root.getKeys(false).associate { rawId ->
                    val section = root.getConfigurationSection(rawId) ?: error("Invalid kit section $rawId")
                    val id = KitId(rawId.lowercase())
                    id to section.readKit(id)
                }
            return KitRegistry(loaded)
        }

        private fun ConfigurationSection.readKit(id: KitId): DuelKit {
            val displayName = miniMessage.deserialize(getString("display-name", "<gold>${id.value}</gold>")!!)
            val icon = material(getString("icon", "IRON_SWORD")!!)
            val itemSection = getConfigurationSection("items")
            val items =
                itemSection?.getKeys(false)?.associate { rawSlot ->
                    val slot = rawSlot.toIntOrNull() ?: error("Kit ${id.value} has invalid slot '$rawSlot'")
                    require(slot in 0..35) { "Kit ${id.value} slot must be between 0 and 35" }
                    slot to parseItem(itemSection.getString(rawSlot) ?: error("Missing item at kit slot $rawSlot"))
                }.orEmpty()
            val armor = getConfigurationSection("armor")
            return DuelKit(
                id = id,
                displayName = displayName,
                icon = icon,
                items = items,
                helmet = armor?.getString("helmet")?.let(::parseItem),
                chestplate = armor?.getString("chestplate")?.let(::parseItem),
                leggings = armor?.getString("leggings")?.let(::parseItem),
                boots = armor?.getString("boots")?.let(::parseItem),
            )
        }

        private fun parseItem(specification: String): ItemStack {
            val parts = specification.trim().split(Regex("\\s+"))
            require(parts.size in 1..2) { "Item must use 'MATERIAL [amount]' syntax" }
            val material = material(parts[0])
            val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
            require(amount in 1..material.maxStackSize) { "Item amount must be between 1 and ${material.maxStackSize}" }
            return ItemStack(material, amount)
        }

        private fun material(name: String): Material =
            Material.matchMaterial(name) ?: error("Unknown Bukkit material '$name'")
    }
}
