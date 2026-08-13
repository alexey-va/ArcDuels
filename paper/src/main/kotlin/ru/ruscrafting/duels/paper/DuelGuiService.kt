package ru.ruscrafting.duels.paper

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.CombatModifiers
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.StatisticsRepository
import java.util.UUID

class DuelGuiService(
    private val plugin: JavaPlugin,
    private val kits: KitRegistry,
    private val statistics: StatisticsRepository,
    private val sessions: DuelSessionManager,
    private val locales: LocaleService,
    private val challengeAction: (Player, Player, DuelRules) -> Unit,
    private val statisticsAction: (Player, Player) -> Unit,
) : Listener {
    fun openChallenge(player: Player, target: Player) = openObjectives(player, target)

    fun openMain(player: Player) {
        val holder = MainMenuHolder()
        val inventory = create(holder, locales.component(player, "menu.main.title"))
        decorate(inventory)
        inventory.setItem(11, item(player, Material.NETHERITE_SWORD, "menu.main.challenge", "menu.main.challenge-lore"))
        inventory.setItem(
            13,
            item(
                player,
                Material.CLOCK,
                "menu.main.queue",
                "menu.main.queue-lore",
                LocaleService.text("active", sessions.activeArenaCount()),
                LocaleService.text("waiting", sessions.queueSize()),
            ),
        )
        inventory.setItem(15, item(player, Material.GOLD_INGOT, "menu.main.leaderboard", "menu.main.leaderboard-lore"))
        inventory.setItem(29, item(player, Material.TARGET, "menu.main.modes", "menu.main.modes-lore"))
        inventory.setItem(31, item(player, Material.ENDER_CHEST, "menu.main.kits", "menu.main.kits-lore"))
        inventory.setItem(33, playerHead(player, locales.component(player, "menu.main.stats"), locales.lines(player, "menu.main.stats-lore")))
        inventory.setItem(40, item(player, Material.WRITABLE_BOOK, "menu.main.help", "menu.main.help-lore"))
        if (player.hasPermission(ADMIN_PERMISSION)) {
            inventory.setItem(44, item(player, Material.COMPARATOR, "menu.main.admin", "menu.main.admin-lore"))
        }
        player.openInventory(inventory)
    }

    fun openTargets(player: Player, requestedPage: Int = 0) {
        val targets =
            plugin.server.onlinePlayers
                .filter { it.uniqueId != player.uniqueId && !sessions.isEngaged(it) && !sessions.isStateLocked(it) }
                .sortedWith { first, second -> String.CASE_INSENSITIVE_ORDER.compare(first.name, second.name) }
        val page = pageWindow(targets, requestedPage, CONTENT_SLOTS.size)
        val holder = TargetMenuHolder(page.index, page.hasPrevious, page.hasNext)
        val inventory = create(holder, locales.component(player, "menu.targets.title", LocaleService.text("page", page.index + 1), LocaleService.text("pages", page.totalPages)))
        decorate(inventory)
        page.items.forEachIndexed { index, target ->
            val slot = CONTENT_SLOTS[index]
            holder.targets[slot] = target.uniqueId
            inventory.setItem(
                slot,
                playerHead(
                    target,
                    locales.component(player, "menu.targets.player", LocaleService.text("player", target.name)),
                    locales.lines(player, "menu.targets.player-lore"),
                ),
            )
        }
        if (targets.isEmpty()) inventory.setItem(22, item(player, Material.BARRIER, "menu.targets.empty"))
        navigation(player, inventory, page, MenuBack.MAIN)
        player.openInventory(inventory)
    }

    fun openLeaderboard(player: Player, requestedPage: Int = 0) {
        player.sendActionBar(locales.component(player, "menu.leaderboard.loading"))
        statistics.leaderboard(MAX_LEADERBOARD_ENTRIES).whenComplete { entries, failure ->
            runSync {
                if (!player.isOnline) return@runSync
                if (failure != null) {
                    player.sendMessage(locales.component(player, "error.leaderboard"))
                    return@runSync
                }
                val page = pageWindow(entries, requestedPage, CONTENT_SLOTS.size)
                val holder = LeaderboardMenuHolder(page.index, page.hasPrevious, page.hasNext)
                val inventory = create(holder, locales.component(player, "menu.leaderboard.title", LocaleService.text("page", page.index + 1), LocaleService.text("pages", page.totalPages)))
                decorate(inventory)
                page.items.forEachIndexed { index, entry ->
                    val slot = CONTENT_SLOTS[index]
                    val name = entry.playerName ?: Bukkit.getOfflinePlayer(entry.playerId.value).name ?: entry.playerId.toString().take(8)
                    inventory.setItem(
                        slot,
                        playerHead(
                            entry.playerId.value,
                            name,
                            locales.component(player, "menu.leaderboard.entry", LocaleService.text("position", entry.position), LocaleService.text("player", name)),
                            locales.lines(
                                player,
                                "menu.leaderboard.entry-lore",
                                LocaleService.text("rating", entry.rating),
                                LocaleService.text("wins", entry.wins),
                                LocaleService.text("losses", entry.losses),
                            ),
                        ),
                    )
                }
                if (entries.isEmpty()) inventory.setItem(22, item(player, Material.PAPER, "menu.leaderboard.empty"))
                navigation(player, inventory, page, MenuBack.MAIN)
                player.openInventory(inventory)
            }
        }
    }

    private fun openObjectives(player: Player, target: Player) {
        val holder = ObjectiveMenuHolder(target.uniqueId)
        val inventory = create(holder, locales.component(player, "menu.objectives.title", LocaleService.text("player", target.name)))
        decorate(inventory)
        objectiveItem(player, DuelObjectiveType.ELIMINATION, Material.DIAMOND_SWORD)?.let { inventory.setItem(11, it) }
        objectiveItem(player, DuelObjectiveType.KING_OF_THE_HILL, Material.BEACON)?.let { inventory.setItem(13, it) }
        objectiveItem(player, DuelObjectiveType.SUMO, Material.SLIME_BALL)?.let { inventory.setItem(15, it) }
        inventory.setItem(BACK_SLOT, item(player, Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        player.openInventory(inventory)
    }

    private fun openLoadouts(player: Player, target: Player, objective: DuelObjectiveType, requestedPage: Int = 0) {
        val availableKits = if (objective == DuelObjectiveType.SUMO) kits.all().filter { it.id.value == "sumo" } else kits.all()
        val kitSlots = if (objective == DuelObjectiveType.SUMO) CONTENT_SLOTS else CONTENT_SLOTS.filter { it != 32 }
        val page = pageWindow(availableKits, requestedPage, kitSlots.size)
        val holder = LoadoutMenuHolder(target.uniqueId, objective, page.index, page.hasPrevious, page.hasNext)
        val inventory = create(holder, locales.component(player, "menu.loadouts.title"))
        decorate(inventory)
        page.items.forEachIndexed { index, kit ->
            val slot = kitSlots[index]
            holder.kits[slot] = kit.id
            inventory.setItem(slot, kitItem(player, kit))
        }
        if (objective != DuelObjectiveType.SUMO) {
            holder.ownInventorySlot = 32
            inventory.setItem(32, item(player, Material.BUNDLE, "menu.loadouts.own", "menu.loadouts.own-lore"))
        } else if (availableKits.isEmpty()) {
            inventory.setItem(22, item(player, Material.BARRIER, "menu.loadouts.sumo-missing"))
        }
        navigation(player, inventory, page, MenuBack.MAIN)
        player.openInventory(inventory)
    }

    private fun openRules(player: Player, draft: DuelDraft) {
        val target = plugin.server.getPlayer(draft.target)
        if (target == null) {
            player.closeInventory()
            player.sendMessage(locales.component(player, "error.player-left"))
            return
        }
        val holder = RulesMenuHolder(draft)
        val inventory = create(holder, locales.component(player, "menu.rules.title", LocaleService.text("player", target.name)))
        decorate(inventory)
        inventory.setItem(10, toggleItem(player, "menu.rules.ranked", draft.ranked, enabled = draft.mode == DuelMode.KIT))
        inventory.setItem(12, item(player, Material.REPEATER, "menu.rules.best-of", "menu.rules.best-of-lore", LocaleService.text("value", draft.bestOf)))
        inventory.setItem(14, item(player, Material.WITHER_SKELETON_SKULL, "menu.rules.sudden-death", "menu.rules.sudden-death-lore", LocaleService.text("seconds", draft.modifiers.suddenDeathAfterSeconds)))
        inventory.setItem(19, toggleItem(player, "menu.rules.projectiles", draft.modifiers.projectiles))
        inventory.setItem(21, toggleItem(player, "menu.rules.consumables", draft.modifiers.consumables))
        inventory.setItem(23, toggleItem(player, "menu.rules.pearls", draft.modifiers.enderPearls))
        inventory.setItem(25, toggleItem(player, "menu.rules.regeneration", draft.modifiers.naturalRegeneration))
        if (draft.objective == DuelObjectiveType.KING_OF_THE_HILL) {
            inventory.setItem(31, item(player, Material.BEACON, "menu.rules.capture", "menu.rules.capture-lore", LocaleService.text("seconds", draft.modifiers.kingOfTheHillCaptureSeconds)))
        }
        inventory.setItem(40, item(player, Material.LIME_CONCRETE, "menu.rules.confirm", "menu.rules.confirm-lore"))
        inventory.setItem(BACK_SLOT, item(player, Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        player.openInventory(inventory)
    }

    private fun openModes(player: Player) {
        val holder = CatalogMenuHolder(CatalogType.MODES)
        val inventory = create(holder, locales.component(player, "menu.modes.title"))
        decorate(inventory)
        inventory.setItem(11, requireNotNull(objectiveItem(player, DuelObjectiveType.ELIMINATION, Material.DIAMOND_SWORD)))
        inventory.setItem(13, requireNotNull(objectiveItem(player, DuelObjectiveType.KING_OF_THE_HILL, Material.BEACON)))
        inventory.setItem(15, requireNotNull(objectiveItem(player, DuelObjectiveType.SUMO, Material.SLIME_BALL)))
        inventory.setItem(BACK_SLOT, item(player, Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        player.openInventory(inventory)
    }

    private fun openKits(player: Player, requestedPage: Int = 0) {
        val page = pageWindow(kits.all(), requestedPage, CONTENT_SLOTS.size)
        val holder = CatalogMenuHolder(CatalogType.KITS, page.index, page.hasPrevious, page.hasNext)
        val inventory = create(holder, locales.component(player, "menu.kits.title"))
        decorate(inventory)
        page.items.forEachIndexed { index, kit -> inventory.setItem(CONTENT_SLOTS[index], kitItem(player, kit)) }
        navigation(player, inventory, page, MenuBack.MAIN)
        player.openInventory(inventory)
    }

    private fun openQueue(player: Player) {
        val holder = CatalogMenuHolder(CatalogType.QUEUE)
        val inventory = create(holder, locales.component(player, "menu.queue.title"))
        decorate(inventory)
        inventory.setItem(12, item(player, Material.IRON_SWORD, "menu.queue.active", "menu.queue.active-lore", LocaleService.text("active", sessions.activeArenaCount())))
        inventory.setItem(14, item(player, Material.CLOCK, "menu.queue.waiting", "menu.queue.waiting-lore", LocaleService.text("waiting", sessions.queueSize())))
        inventory.setItem(BACK_SLOT, item(player, Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        player.openInventory(inventory)
    }

    private fun showHelp(player: Player) {
        player.closeInventory()
        locales.lines(player, "help.lines").forEach(player::sendMessage)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? MenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val slot = event.rawSlot
        when (holder) {
            is MainMenuHolder -> when (slot) {
                11 -> openTargets(player)
                13 -> openQueue(player)
                15 -> openLeaderboard(player)
                29 -> openModes(player)
                31 -> openKits(player)
                33 -> statisticsAction(player, player)
                40 -> showHelp(player)
                44 -> if (player.hasPermission(ADMIN_PERMISSION)) { player.closeInventory(); player.performCommand("duels admin") }
            }
            is TargetMenuHolder -> when (slot) {
                BACK_SLOT -> openMain(player)
                PREVIOUS_SLOT -> if (holder.hasPrevious) openTargets(player, holder.page - 1)
                NEXT_SLOT -> if (holder.hasNext) openTargets(player, holder.page + 1)
                else -> holder.targets[slot]?.let { id ->
                    plugin.server.getPlayer(id)?.let { openObjectives(player, it) } ?: player.sendMessage(locales.component(player, "error.player-left"))
                }
            }
            is ObjectiveMenuHolder -> {
                if (slot == BACK_SLOT) return openTargets(player)
                val objective = when (slot) { 11 -> DuelObjectiveType.ELIMINATION; 13 -> DuelObjectiveType.KING_OF_THE_HILL; 15 -> DuelObjectiveType.SUMO; else -> null } ?: return
                plugin.server.getPlayer(holder.target)?.let { openLoadouts(player, it, objective) } ?: player.sendMessage(locales.component(player, "error.player-left"))
            }
            is LoadoutMenuHolder -> {
                val target = plugin.server.getPlayer(holder.target) ?: run { player.closeInventory(); player.sendMessage(locales.component(player, "error.player-left")); return }
                if (slot == BACK_SLOT) return openObjectives(player, target)
                if (slot == PREVIOUS_SLOT && holder.hasPrevious) return openLoadouts(player, target, holder.objective, holder.page - 1)
                if (slot == NEXT_SLOT && holder.hasNext) return openLoadouts(player, target, holder.objective, holder.page + 1)
                val kit = holder.kits[slot]
                val mode = if (kit == null && slot == holder.ownInventorySlot) DuelMode.OWN_INVENTORY else if (kit != null) DuelMode.KIT else return
                val modifiers =
                    when {
                        holder.objective == DuelObjectiveType.SUMO -> SUMO_MODIFIERS
                        kit?.value == "uhc" -> CombatModifiers(naturalRegeneration = false)
                        else -> CombatModifiers()
                    }
                openRules(player, DuelDraft(holder.target, holder.objective, mode, kit, modifiers = modifiers))
            }
            is RulesMenuHolder -> handleRulesClick(player, holder.draft, slot)
            is LeaderboardMenuHolder -> when (slot) {
                BACK_SLOT -> openMain(player)
                PREVIOUS_SLOT -> if (holder.hasPrevious) openLeaderboard(player, holder.page - 1)
                NEXT_SLOT -> if (holder.hasNext) openLeaderboard(player, holder.page + 1)
            }
            is CatalogMenuHolder -> when {
                slot == BACK_SLOT -> openMain(player)
                holder.type == CatalogType.KITS && slot == PREVIOUS_SLOT && holder.hasPrevious -> openKits(player, holder.page - 1)
                holder.type == CatalogType.KITS && slot == NEXT_SLOT && holder.hasNext -> openKits(player, holder.page + 1)
            }
        }
    }

    private fun handleRulesClick(player: Player, draft: DuelDraft, slot: Int) {
        when (slot) {
            BACK_SLOT -> plugin.server.getPlayer(draft.target)?.let { openLoadouts(player, it, draft.objective) }
                ?: run { player.closeInventory(); player.sendMessage(locales.component(player, "error.player-left")) }
            10 -> if (draft.mode == DuelMode.KIT) openRules(player, draft.copy(ranked = !draft.ranked))
            12 -> openRules(player, draft.copy(bestOf = when (draft.bestOf) { 1 -> 3; 3 -> 5; else -> 1 }))
            14 -> openRules(player, draft.copy(modifiers = draft.modifiers.copy(suddenDeathAfterSeconds = nextOf(draft.modifiers.suddenDeathAfterSeconds, listOf(60, 180, 300, 600)))))
            19 -> openRules(player, draft.copy(modifiers = draft.modifiers.copy(projectiles = !draft.modifiers.projectiles)))
            21 -> openRules(player, draft.copy(modifiers = draft.modifiers.copy(consumables = !draft.modifiers.consumables)))
            23 -> openRules(player, draft.copy(modifiers = draft.modifiers.copy(enderPearls = !draft.modifiers.enderPearls)))
            25 -> openRules(player, draft.copy(modifiers = draft.modifiers.copy(naturalRegeneration = !draft.modifiers.naturalRegeneration)))
            31 -> if (draft.objective == DuelObjectiveType.KING_OF_THE_HILL) openRules(player, draft.copy(modifiers = draft.modifiers.copy(kingOfTheHillCaptureSeconds = nextOf(draft.modifiers.kingOfTheHillCaptureSeconds, listOf(10, 15, 30, 45)))))
            40 -> {
                val target = plugin.server.getPlayer(draft.target)
                player.closeInventory()
                if (target == null) player.sendMessage(locales.component(player, "error.player-left"))
                else challengeAction(player, target, DuelRules(draft.mode, draft.kitId, draft.ranked, draft.bestOf, draft.objective, draft.modifiers))
            }
        }
    }

    private fun objectiveItem(player: Player, objective: DuelObjectiveType, material: Material): ItemStack? =
        item(player, material, "objective.${objective.key}.name", "objective.${objective.key}.description")

    private fun kitItem(player: Player, kit: DuelKit): ItemStack {
        val key = "kit.${kit.id.value}"
        val name = if (locales.hasKey(locales.language(player), "$key.name")) locales.component(player, "$key.name") else kit.displayName
        val lore = if (locales.hasKey(locales.language(player), "$key.description")) locales.lines(player, "$key.description") else emptyList()
        return item(kit.icon, name, lore + locales.lines(player, "menu.loadouts.kit-hint"))
    }

    private fun toggleItem(player: Player, key: String, value: Boolean, enabled: Boolean = true): ItemStack {
        val state = when { !enabled -> locales.component(player, "menu.common.unavailable"); value -> locales.component(player, "menu.common.enabled"); else -> locales.component(player, "menu.common.disabled") }
        val material = when { !enabled -> Material.GRAY_DYE; value -> Material.LIME_DYE; else -> Material.RED_DYE }
        return item(player, material, key, "$key-lore", LocaleService.component("state", state))
    }

    private fun create(holder: MenuHolder, title: Component): Inventory =
        Bukkit.createInventory(holder, MENU_SIZE, title).also(holder::attach)

    private fun decorate(inventory: Inventory) {
        val filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "))
        for (slot in 0 until inventory.size) inventory.setItem(slot, filler)
        CONTENT_SLOTS.forEach { inventory.setItem(it, null) }
    }

    private fun <T> navigation(player: Player, inventory: Inventory, page: PageWindow<T>, back: MenuBack) {
        if (back == MenuBack.MAIN) inventory.setItem(BACK_SLOT, item(player, Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        if (page.hasPrevious) inventory.setItem(PREVIOUS_SLOT, item(player, Material.ARROW, "menu.common.previous"))
        inventory.setItem(PAGE_SLOT, item(player, Material.CLOCK, "menu.common.page", resolvers = arrayOf(LocaleService.text("page", page.index + 1), LocaleService.text("pages", page.totalPages))))
        if (page.hasNext) inventory.setItem(NEXT_SLOT, item(player, Material.SPECTRAL_ARROW, "menu.common.next"))
    }

    private fun item(player: Player, material: Material, nameKey: String, loreKey: String? = null, vararg resolvers: net.kyori.adventure.text.minimessage.tag.resolver.TagResolver): ItemStack =
        item(material, locales.component(player, nameKey, *resolvers), loreKey?.let { locales.lines(player, it, *resolvers) }.orEmpty())

    private fun item(player: Player, material: Material, nameKey: String, resolvers: Array<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver>): ItemStack =
        item(material, locales.component(player, nameKey, *resolvers))

    private fun item(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack =
        ItemStack(material).apply {
            itemMeta = itemMeta.apply {
                displayName(name)
                lore(lore)
                addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }

    private fun playerHead(player: Player, name: Component, lore: List<Component>): ItemStack = playerHead(player.uniqueId, player.name, name, lore)

    private fun playerHead(uuid: UUID, profileName: String?, name: Component, lore: List<Component>): ItemStack =
        ItemStack(Material.PLAYER_HEAD).apply {
            val profile = ResolvableProfile.resolvableProfile().uuid(uuid)
            if (profileName != null) profile.name(profileName)
            setData(DataComponentTypes.PROFILE, profile)
            itemMeta = itemMeta.apply { displayName(name); lore(lore) }
        }

    private fun runSync(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (plugin.server.isPrimaryThread) block() else plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private abstract class MenuHolder : InventoryHolder {
        private lateinit var inventory: Inventory
        fun attach(inventory: Inventory) { this.inventory = inventory }
        override fun getInventory(): Inventory = inventory
    }
    private class MainMenuHolder : MenuHolder()
    private class TargetMenuHolder(val page: Int, val hasPrevious: Boolean, val hasNext: Boolean) : MenuHolder() { val targets = mutableMapOf<Int, UUID>() }
    private class ObjectiveMenuHolder(val target: UUID) : MenuHolder()
    private class LoadoutMenuHolder(val target: UUID, val objective: DuelObjectiveType, val page: Int, val hasPrevious: Boolean, val hasNext: Boolean) : MenuHolder() { val kits = mutableMapOf<Int, KitId>(); var ownInventorySlot = -1 }
    private class RulesMenuHolder(val draft: DuelDraft) : MenuHolder()
    private class LeaderboardMenuHolder(val page: Int, val hasPrevious: Boolean, val hasNext: Boolean) : MenuHolder()
    private class CatalogMenuHolder(val type: CatalogType, val page: Int = 0, val hasPrevious: Boolean = false, val hasNext: Boolean = false) : MenuHolder()
    private enum class CatalogType { MODES, KITS, QUEUE }
    private enum class MenuBack { MAIN }

    private companion object {
        const val MENU_SIZE = 45
        const val MAX_LEADERBOARD_ENTRIES = 100
        const val BACK_SLOT = 36
        const val PREVIOUS_SLOT = 37
        const val PAGE_SLOT = 40
        const val NEXT_SLOT = 43
        const val ADMIN_PERMISSION = "arcduels.admin"
        val CONTENT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34)
        val SUMO_MODIFIERS = CombatModifiers(false, false, false, false, 180, 15)
    }
}

private val DuelObjectiveType.key: String get() = name.lowercase().replace("king_of_the_hill", "koth")

private data class DuelDraft(
    val target: UUID,
    val objective: DuelObjectiveType,
    val mode: DuelMode,
    val kitId: KitId?,
    val ranked: Boolean = false,
    val bestOf: Int = 1,
    val modifiers: CombatModifiers = CombatModifiers(),
)

private fun nextOf(current: Int, values: List<Int>): Int = values[(values.indexOf(current).takeIf { it >= 0 } ?: -1).let { (it + 1) % values.size }]

internal data class PageWindow<T>(val index: Int, val totalPages: Int, val items: List<T>) {
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index + 1 < totalPages
}

internal fun <T> pageWindow(items: List<T>, requestedPage: Int, pageSize: Int): PageWindow<T> {
    require(pageSize > 0) { "Page size must be positive" }
    val totalPages = maxOf(1, (items.size + pageSize - 1) / pageSize)
    val index = requestedPage.coerceIn(0, totalPages - 1)
    val fromIndex = index * pageSize
    return PageWindow(index, totalPages, items.subList(fromIndex, minOf(items.size, fromIndex + pageSize)))
}

internal fun rulesForSelection(baseRules: DuelRules, shiftClick: Boolean, rightClick: Boolean): DuelRules =
    baseRules.copy(ranked = shiftClick && baseRules.mode == DuelMode.KIT, bestOf = if (rightClick) 3 else 1)
