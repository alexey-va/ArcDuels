package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.NamespacedKey
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.configuration.Configuration
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.Config
import ru.ruscrafting.duels.domain.KitId
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Locale

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
    val magic: Boolean = false,
    val magicDamageMultiplier: Double = 0.4,
    val staffSpells: Map<Int, String> = emptyMap(),
    val staffProviderFingerprint: String? = null,
)

internal data class StaffSpellItemProvider(
    val create: (String) -> ItemStack,
    val fingerprint: String,
)

class KitRegistry private constructor(
    kits: Map<KitId, DuelKit>,
    defaultKit: KitId,
) {
    @Volatile
    private var state = State.of(kits, defaultKit)

    fun get(id: KitId): DuelKit = state.kits[id] ?: error("Unknown duel kit $id")

    fun contains(id: KitId): Boolean = id in state.kits

    fun fingerprint(id: KitId): String? = state.fingerprints[id]

    fun fingerprints(): Map<KitId, String> = state.fingerprints

    fun defaultId(): KitId = state.defaultKit

    fun all(): List<DuelKit> = state.kits.values.sortedBy { it.id.value }

    /** Replaces the catalog only after [replacement] has been parsed and validated completely. */
    internal fun replaceWith(replacement: KitRegistry): Int {
        state = replacement.state
        return state.kits.size
    }

    private data class State(
        val kits: Map<KitId, DuelKit>,
        val fingerprints: Map<KitId, String>,
        val defaultKit: KitId,
    ) {
        companion object {
            fun of(kits: Map<KitId, DuelKit>, defaultKit: KitId): State =
                State(
                    kits = kits.toMap(),
                    fingerprints = kits.mapValues { (_, kit) -> kit.loadoutFingerprint() },
                    defaultKit = defaultKit,
                )
        }
    }

    companion object {
        private val miniMessage = MiniMessage.miniMessage()

        fun load(plugin: JavaPlugin): KitRegistry = load(
            plugin,
            plugin.config,
            prepareFiles = true,
            staffItems = loadProviderItems(plugin),
        )

        internal fun loadCandidate(
            plugin: JavaPlugin,
            configuration: Configuration,
            capturedLoadouts: YamlConfiguration? = null,
            staffItems: StaffSpellItemProvider? = loadProviderItems(plugin),
        ): KitRegistry = load(
            plugin,
            configuration,
            prepareFiles = false,
            capturedLoadouts = capturedLoadouts,
            staffItems = staffItems,
        )

        private fun load(
            plugin: JavaPlugin,
            configuration: Configuration,
            prepareFiles: Boolean,
            capturedLoadouts: YamlConfiguration? = null,
            staffItems: StaffSpellItemProvider? = null,
        ): KitRegistry {
            val loadoutsFile = File(plugin.dataFolder, LOADOUTS_RESOURCE)
            if (capturedLoadouts == null && !loadoutsFile.isFile) {
                require(prepareFiles) { "Missing ArcDuels loadouts.yml" }
                plugin.saveResource(LOADOUTS_RESOURCE, false)
            }
            // arc-core's merge-forward helper intentionally repairs an unreadable document from
            // bundled defaults. Bootstrap keeps that migration behavior, while live-reload
            // preparation is read-only and merges bundled values only in memory.
            if (prepareFiles) Config(plugin.dataFolder.toPath(), LOADOUTS_RESOURCE).mergeMissingFromBundled(LOADOUTS_RESOURCE)
            val loadouts = capturedLoadouts ?: YamlConfiguration().apply { load(loadoutsFile) }
            val bundled =
                requireNotNull(plugin.getResource(LOADOUTS_RESOURCE)) { "Missing bundled ArcDuels loadouts.yml" }
                    .use { input -> YamlConfiguration().apply { load(InputStreamReader(input, Charsets.UTF_8)) } }
            loadouts.setDefaults(bundled)
            val sections = linkedMapOf<String, ConfigurationSection>()
            loadouts.strictConfigurationSection("kits")?.let { root ->
                require(root.getKeys(false).map(String::lowercase).distinct().size == root.getKeys(false).size) {
                    "Kit ids in loadouts.yml must be unique after lowercase normalization"
                }
                root.getKeys(false).forEach { rawId ->
                    sections[rawId.lowercase()] = root.getConfigurationSection(rawId) ?: error("Invalid kit section $rawId")
                }
            }
            // Existing operator-owned kit values remain authoritative during the split from config.yml.
            configuration.strictConfigurationSection("kits")?.let { legacy ->
                require(legacy.getKeys(false).map(String::lowercase).distinct().size == legacy.getKeys(false).size) {
                    "Kit ids in config.yml must be unique after lowercase normalization"
                }
                legacy.getKeys(false).forEach { rawId ->
                    sections[rawId.lowercase()] = legacy.getConfigurationSection(rawId) ?: error("Invalid kit section $rawId")
                }
            }
            val skippedMagicKits = mutableListOf<String>()
            val parsed = sections.mapNotNull { (rawId, section) ->
                val kit = section.readKit(KitId(rawId), staffItems)
                if (kit == null) {
                    skippedMagicKits += rawId
                    null
                } else {
                    KitId(rawId) to kit
                }
            }.toMap()
            if (skippedMagicKits.isNotEmpty()) {
                plugin.logger.warning(
                    "Skipped magic duel kit(s) ${skippedMagicKits.joinToString(", ")} because ARC StaffSpells API is unavailable; ordinary kits remain enabled",
                )
            }
            val defaultKit =
                try {
                    KitId(configuration.strictString("multiplayer.defaults.kit", "classic").trim().lowercase(Locale.ROOT))
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException("multiplayer.defaults.kit must be a safe kit id", failure)
                }
            require(defaultKit in parsed) {
                if (defaultKit.value in skippedMagicKits) {
                    "multiplayer.defaults.kit '${defaultKit.value}' requires the ARC StaffSpells duel API, which is unavailable"
                } else {
                    "multiplayer.defaults.kit references unknown kit '${defaultKit.value}'"
                }
            }
            return KitRegistry(parsed, defaultKit)
        }

        private fun ConfigurationSection.readKit(id: KitId, staffItems: StaffSpellItemProvider?): DuelKit? {
            val displayName = miniMessage.deserialize(strictString("display-name", "<gold>${id.value}</gold>"))
            val icon = material(strictString("icon", "IRON_SWORD"))
            val magic = strictBoolean("magic", false)
            val damageMultiplier = strictNumber("magic-damage-multiplier", 0.4)
            require(damageMultiplier in 0.05..2.0) {
                "Kit ${id.value} magic-damage-multiplier must be between 0.05 and 2"
            }
            val itemSection = strictConfigurationSection("items")
            val spells = linkedMapOf<Int, String>()
            val items = linkedMapOf<Int, ItemStack>()
            itemSection?.getKeys(false)?.forEach { rawSlot ->
                val slot = rawSlot.toIntOrNull() ?: error("Kit ${id.value} has invalid slot '$rawSlot'")
                require(slot in 0..35) { "Kit ${id.value} slot must be between 0 and 35" }
                val section =
                    if (itemSection.isConfigurationSection(rawSlot)) {
                        itemSection.getConfigurationSection(rawSlot)
                    } else {
                        null
                    }
                if (section?.contains("staff-spell") == true) {
                    require(magic) { "Kit ${id.value} uses staff-spell but magic is not enabled" }
                    require(section.getKeys(false) == setOf("staff-spell")) {
                        "${section.currentPath} must contain only staff-spell"
                    }
                    val spell = section.strictString("staff-spell", "").trim().lowercase(Locale.ROOT)
                    require(spell in STAFF_SPELLS) { "Unknown ARC staff spell '$spell' at ${section.currentPath}" }
                    spells[slot] = spell
                    staffItems?.let { provider ->
                        val staff = provider.create(spell)
                        require(!staff.type.isAir && !staff.isEmpty) {
                            "ARC StaffSpells returned an empty item for '$spell'"
                        }
                        items[slot] = staff
                    }
                } else {
                    items[slot] = itemSection.readItem(rawSlot)
                }
            }
            if (magic) {
                require(spells.isNotEmpty()) { "Magic kit ${id.value} must define at least one staff-spell item" }
            } else {
                require(spells.isEmpty()) { "Non-magic kit ${id.value} cannot define staff-spell items" }
            }
            val armor = strictConfigurationSection("armor")
            val kit = DuelKit(
                id = id,
                displayName = displayName,
                icon = icon,
                items = items,
                helmet = armor?.readOptionalItem("helmet"),
                chestplate = armor?.readOptionalItem("chestplate"),
                leggings = armor?.readOptionalItem("leggings"),
                boots = armor?.readOptionalItem("boots"),
                offhand = readOptionalItem("offhand"),
                magic = magic,
                magicDamageMultiplier = damageMultiplier,
                staffSpells = spells,
                staffProviderFingerprint = if (magic) staffItems?.fingerprint else null,
            )
            return if (magic && staffItems == null) null else kit
        }

        private fun loadProviderItems(plugin: JavaPlugin): StaffSpellItemProvider? {
            if (!plugin.server.pluginManager.isPluginEnabled("ARC")) return null
            return try {
                MagicDuelCombatBridge.provider(plugin)?.let { service ->
                    val fingerprint = service.fingerprint()
                    require(fingerprint.isNotBlank()) { "ARC StaffSpells returned a blank provider fingerprint" }
                    StaffSpellItemProvider(service::createDuelStaff, fingerprint)
                }
            } catch (failure: LinkageError) {
                null
            }
        }

        private fun ConfigurationSection.readOptionalItem(path: String): ItemStack? =
            if (contains(path)) readItem(path) else null

        private fun ConfigurationSection.readItem(path: String): ItemStack {
            if (!isConfigurationSection(path)) {
                return parseItem(getString(path) ?: error("Missing item at $currentPath.$path"))
            }
            val section = requireNotNull(getConfigurationSection(path))
            val material = material(section.getString("material") ?: error("Missing material at ${section.currentPath}"))
            val amount = section.strictInteger("amount", 1)
            require(amount in 1..material.maxStackSize) {
                "Item amount at ${section.currentPath} must be between 1 and ${material.maxStackSize}"
            }
            return ItemStack(material, amount).apply {
                section.strictConfigurationSection("enchantments")?.let { enchantments ->
                    enchantments.getKeys(false).forEach { raw ->
                        val enchantment = RegistryAccess.registryAccess()
                            .getRegistry(RegistryKey.ENCHANTMENT)
                            .get(NamespacedKey.minecraft(raw.lowercase().replace('-', '_')))
                            ?: error("Unknown Bukkit enchantment '$raw' at ${enchantments.currentPath}")
                        val level = enchantments.strictInteger(raw)
                        require(level in 1..255) { "Enchantment level must be between 1 and 255" }
                        addUnsafeEnchantment(enchantment, level)
                    }
                }
                if (section.strictBoolean("unbreakable", false)) {
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
        private val STAFF_SPELLS = setOf("chain", "mark", "frost", "lance", "ember", "nova", "icicle", "fault")
    }
}

private fun DuelKit.loadoutFingerprint(): String {
    val canonical =
        buildString {
            append("arcduels-kit-v1\n")
            items.toSortedMap().forEach { (slot, item) ->
                append("slot:").append(slot).append('=')
                appendCanonical(item)
            }
            append("offhand=").appendCanonical(offhand)
            append("helmet=").appendCanonical(helmet)
            append("chestplate=").appendCanonical(chestplate)
            append("leggings=").appendCanonical(leggings)
            append("boots=").appendCanonical(boots)
            if (magic) {
                append("magic=true\n")
                append("magic-damage-multiplier=").append(magicDamageMultiplier).append('\n')
                append("staff-provider=").append(staffProviderFingerprint ?: "missing").append('\n')
                staffSpells.toSortedMap().forEach { (slot, spell) ->
                    append("staff-spell:").append(slot).append('=').append(spell).append('\n')
                }
            }
        }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun StringBuilder.appendCanonical(item: ItemStack?): StringBuilder {
    if (item == null) return append("-\n")
    append(item.type.key.asString())
        .append('@')
        .append(item.amount)
        .append(";unbreakable=")
        .append(item.itemMeta.isUnbreakable)
    item.enchantments.entries
        .sortedBy { (enchantment, _) -> enchantment.key.asString() }
        .forEach { (enchantment, level) ->
            append(";enchant=").append(enchantment.key.asString()).append('@').append(level)
        }
    return append('\n')
}
