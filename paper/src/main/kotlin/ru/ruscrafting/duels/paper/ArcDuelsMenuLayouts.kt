package ru.ruscrafting.duels.paper

import org.bukkit.inventory.Inventory
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.Config
import ru.arc.menu.MenuCatalog
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayoutParser
import ru.arc.menu.MenuRegionId
import java.nio.file.Files
import java.nio.file.Path

internal enum class ArcDuelsMenuScreen(val id: MenuId) {
    MAIN(MenuId.of("duel-main")),
    TARGETS(MenuId.of("duel-targets")),
    OBJECTIVES(MenuId.of("duel-objectives")),
    LOADOUTS(MenuId.of("duel-loadouts")),
    RULES(MenuId.of("duel-rules")),
    ARENAS(MenuId.of("duel-arenas")),
    LEADERBOARD(MenuId.of("duel-leaderboard")),
    HISTORY(MenuId.of("duel-history")),
    HEAD_TO_HEAD(MenuId.of("duel-head-to-head")),
    PRESETS(MenuId.of("duel-presets")),
    CATALOG_MODES(MenuId.of("duel-catalog-modes")),
    CATALOG_KITS(MenuId.of("duel-catalog-kits")),
    QUEUE(MenuId.of("duel-queue")),
    ADMIN(MenuId.of("duel-admin")),
    ADMIN_ARENAS(MenuId.of("duel-admin-arenas")),
    ADMIN_ARENA(MenuId.of("duel-admin-arena")),
    ADMIN_OBJECTIVES(MenuId.of("duel-admin-objectives")),
    RECOVERY(MenuId.of("duel-recovery")),
    MULTIPLAYER_SETUP(MenuId.of("multiplayer-setup")),
    MULTIPLAYER_LOBBY(MenuId.of("multiplayer-lobby")),
    MULTIPLAYER_REMOTE_LOBBY(MenuId.of("multiplayer-remote-lobby")),
}

/**
 * Compatibility layout adapter for the stateful duel inventories.
 *
 * Existing controllers keep logical slot numbers for their state machines. The
 * ordered YAML grid maps those logical slots to physical inventory slots, so an
 * operator can rearrange every screen without changing click semantics.
 */
internal class ArcDuelsMenuLayouts private constructor(private val catalog: MenuCatalog) {
    fun rows(screen: ArcDuelsMenuScreen): Int = catalog.require(screen.id).rows

    fun physical(screen: ArcDuelsMenuScreen, logicalSlot: Int): Int =
        grid(screen).getOrNull(logicalSlot)?.index
            ?: throw IllegalArgumentException("Logical slot $logicalSlot is outside ${screen.id}")

    fun logical(screen: ArcDuelsMenuScreen, physicalSlot: Int): Int? =
        grid(screen).indexOfFirst { it.index == physicalSlot }.takeIf { it >= 0 }

    fun arrange(screen: ArcDuelsMenuScreen, inventory: Inventory) {
        val slots = grid(screen)
        require(inventory.size == slots.size) {
            "Menu ${screen.id} inventory has ${inventory.size} slots, configured grid has ${slots.size}"
        }
        val logicalContents = inventory.contents.map { it?.clone() }
        inventory.clear()
        logicalContents.forEachIndexed { logical, item ->
            if (item != null) inventory.setItem(slots[logical].index, item)
        }
    }

    private fun grid(screen: ArcDuelsMenuScreen) = catalog.require(screen.id).region(GRID)

    companion object {
        const val RESOURCE = "gui-layouts.yml"
        private val GRID = MenuRegionId.of("grid")
        private val CONTRACTS = ArcDuelsMenuScreen.entries.associate { screen ->
            screen.id to MenuContract(requiredRegions = setOf(GRID))
        }

        fun load(plugin: JavaPlugin): ArcDuelsMenuLayouts {
            val config = Config(plugin.dataFolder.toPath(), RESOURCE)
            config.mergeMissingFromBundled(RESOURCE)
            return parse(config)
        }

        internal fun loadResource(classLoader: ClassLoader): ArcDuelsMenuLayouts {
            val root = Files.createTempDirectory("arc-duels-menu-layouts")
            val target = root.resolve(RESOURCE)
            classLoader.getResourceAsStream(RESOURCE).use { input ->
                requireNotNull(input) { "Missing bundled $RESOURCE" }
                Files.copy(input, target)
            }
            return parse(Config(root, RESOURCE))
        }

        internal fun load(root: Path): ArcDuelsMenuLayouts = parse(Config(root, RESOURCE))

        private fun parse(config: Config): ArcDuelsMenuLayouts {
            val catalog = MenuLayoutParser.require(config, "menus.layouts", CONTRACTS)
            catalog.layouts.values.forEach { layout ->
                val grid = layout.region(GRID)
                require(grid.size == layout.rows * 9) {
                    "Menu ${layout.id} grid must map every one of its ${layout.rows * 9} logical slots"
                }
                require(grid.map { it.index }.toSet().size == grid.size) {
                    "Menu ${layout.id} grid must not contain duplicate physical slots"
                }
            }
            return ArcDuelsMenuLayouts(catalog)
        }
    }
}
