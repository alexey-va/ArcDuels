package ru.ruscrafting.duels.paper

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import org.bukkit.Material
import org.bukkit.configuration.Configuration
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

internal data class GuiItemSpec(
    val material: Material,
    val customModelData: Int? = null,
) {
    init {
        require(customModelData == null || customModelData in 1..MAX_CUSTOM_MODEL_DATA) {
            "GUI custom-model-data must be between 1 and $MAX_CUSTOM_MODEL_DATA"
        }
    }

    fun create(): ItemStack =
        ItemStack(material).apply {
            customModelData?.let { value ->
                setData(
                    DataComponentTypes.CUSTOM_MODEL_DATA,
                    CustomModelData.customModelData().addFloat(value.toFloat()),
                )
            }
        }

    private companion object {
        const val MAX_CUSTOM_MODEL_DATA = 16_777_216
    }
}

/** Resource-pack-neutral GUI roles. Production can overlay ItemsAdder material/CMD pairs. */
internal class GuiItemCatalog private constructor(
    configured: Map<String, GuiItemSpec>,
) {
    @Volatile
    private var configured: Map<String, GuiItemSpec> = configured

    fun create(
        role: String,
        fallback: Material,
    ): ItemStack = (configured[role] ?: GuiItemSpec(fallback)).create()

    internal fun replaceWith(replacement: GuiItemCatalog) {
        configured = replacement.configured
    }

    companion object {
        fun load(plugin: JavaPlugin): GuiItemCatalog = load(plugin.config)

        internal fun load(configuration: Configuration): GuiItemCatalog {
            val root = configuration.strictConfigurationSection("gui.items") ?: return GuiItemCatalog(emptyMap())
            val specs =
                root.getKeys(false).associateWith { role ->
                    val section = requireNotNull(root.strictConfigurationSection(role))
                    val rawMaterial = section.strictString("material", "")
                    require(rawMaterial.isNotBlank()) { "GUI item '$role' is missing material" }
                    val material = Material.matchMaterial(rawMaterial)
                        ?: error("GUI item '$role' has unknown material '$rawMaterial'")
                    val customModelData =
                        if (section.contains("custom-model-data")) section.strictInteger("custom-model-data") else null
                    GuiItemSpec(material, customModelData)
                }
            return GuiItemCatalog(specs)
        }
    }
}
