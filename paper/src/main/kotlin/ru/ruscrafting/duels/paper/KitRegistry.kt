package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.NamespacedKey
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.Config
import ru.ruscrafting.duels.domain.KitId
import java.io.File

data class DuelKit(
    val id: KitId,
    val displayName: Component,
    val icon: Material,
    val items: Map<Int, ItemStack>,
    val helmet: ItemStack?,
    val chestplate: ItemStack?,
    val leggings: ItemStack?,
    val boots: ItemStack?,
    val offhand: ItemStack? = null,
)

class KitRegistry private constructor(
    private val kits: Map<KitId, DuelKit>,
) {
    fun get(id: KitId): DuelKit = kits[id] ?: error("Unknown duel kit $id")

    fun all(): List<DuelKit> = kits.values.sortedBy { it.id.value }

    companion object {
        private val miniMessage = MiniMessage.miniMessage()

        fun load(plugin: JavaPlugin): KitRegistry {
            val loadoutsFile = File(plugin.dataFolder, LOADOUTS_RESOURCE)
            if (!loadoutsFile.isFile) plugin.saveResource(LOADOUTS_RESOURCE, false)
            Config(plugin.dataFolder.toPath(), LOADOUTS_RESOURCE).mergeMissingFromBundled(LOADOUTS_RESOURCE)
            val loadouts = YamlConfiguration.loadConfiguration(loadoutsFile)
            val sections = linkedMapOf<String, ConfigurationSection>()
            loadouts.getConfigurationSection("kits")?.let { root ->
                require(root.getKeys(false).map(String::lowercase).distinct().size == root.getKeys(false).size) {
                    "Kit ids in loadouts.yml must be unique after lowercase normalization"
                }
                root.getKeys(false).forEach { rawId ->
                    sections[rawId.lowercase()] = root.getConfigurationSection(rawId) ?: error("Invalid kit section $rawId")
                }
            }
            // Existing operator-owned kit values remain authoritative during the split from config.yml.
            plugin.config.getConfigurationSection("kits")?.let { legacy ->
                require(legacy.getKeys(false).map(String::lowercase).distinct().size == legacy.getKeys(false).size) {
                    "Kit ids in config.yml must be unique after lowercase normalization"
                }
                legacy.getKeys(false).forEach { rawId ->
                    sections[rawId.lowercase()] = legacy.getConfigurationSection(rawId) ?: error("Invalid kit section $rawId")
                }
            }
            return KitRegistry(sections.map { (rawId, section) -> KitId(rawId) to section.readKit(KitId(rawId)) }.toMap())
        }

        private fun ConfigurationSection.readKit(id: KitId): DuelKit {
            val displayName = miniMessage.deserialize(getString("display-name", "<gold>${id.value}</gold>")!!)
            val icon = material(getString("icon", "IRON_SWORD")!!)
            val itemSection = getConfigurationSection("items")
            val items =
                itemSection?.getKeys(false)?.associate { rawSlot ->
                    val slot = rawSlot.toIntOrNull() ?: error("Kit ${id.value} has invalid slot '$rawSlot'")
                    require(slot in 0..35) { "Kit ${id.value} slot must be between 0 and 35" }
                    slot to itemSection.readItem(rawSlot)
                }.orEmpty()
            val armor = getConfigurationSection("armor")
            return DuelKit(
                id = id,
                displayName = displayName,
                icon = icon,
                items = items,
                helmet = armor?.readOptionalItem("helmet"),
                chestplate = armor?.readOptionalItem("chestplate"),
                leggings = armor?.readOptionalItem("leggings"),
                boots = armor?.readOptionalItem("boots"),
                offhand = readOptionalItem("offhand"),
            )
        }

        private fun ConfigurationSection.readOptionalItem(path: String): ItemStack? =
            if (contains(path)) readItem(path) else null

        private fun ConfigurationSection.readItem(path: String): ItemStack {
            if (!isConfigurationSection(path)) {
                return parseItem(getString(path) ?: error("Missing item at $currentPath.$path"))
            }
            val section = requireNotNull(getConfigurationSection(path))
            val material = material(section.getString("material") ?: error("Missing material at ${section.currentPath}"))
            val amount = section.getInt("amount", 1)
            require(amount in 1..material.maxStackSize) {
                "Item amount at ${section.currentPath} must be between 1 and ${material.maxStackSize}"
            }
            return ItemStack(material, amount).apply {
                section.getConfigurationSection("enchantments")?.let { enchantments ->
                    enchantments.getKeys(false).forEach { raw ->
                        val enchantment = RegistryAccess.registryAccess()
                            .getRegistry(RegistryKey.ENCHANTMENT)
                            .get(NamespacedKey.minecraft(raw.lowercase().replace('-', '_')))
                            ?: error("Unknown Bukkit enchantment '$raw' at ${enchantments.currentPath}")
                        val level = enchantments.getInt(raw)
                        require(level in 1..255) { "Enchantment level must be between 1 and 255" }
                        addUnsafeEnchantment(enchantment, level)
                    }
                }
                if (section.getBoolean("unbreakable", false)) {
                    itemMeta = itemMeta.apply { isUnbreakable = true }
                }
            }
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

        private const val LOADOUTS_RESOURCE = "loadouts.yml"
    }
}
