package ru.ruscrafting.duels.paper

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ResolvableProfile
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerQuitEvent
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
import java.util.concurrent.ConcurrentHashMap

class DuelGuiService internal constructor(
    private val plugin: JavaPlugin,
    private val kits: KitRegistry,
    private val statistics: StatisticsRepository,
    private val sessions: DuelSessionManager,
    private val locales: LocaleService,
    private val admin: DuelAdminCommand,
    private val targets: DuelTargetDirectory,
    private val challengeAction: (Player, DuelTarget, DuelRules) -> Unit,
    private val statisticsAction: (Player, DuelTarget) -> Unit,
) : Listener {
    private val pendingArenaNames = ConcurrentHashMap<UUID, Long>()
    private val guiItems = GuiItemCatalog.load(plugin)

    fun openChallenge(player: Player, target: DuelTarget) = openObjectives(player, target)

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
        if (sessions.hasPendingRecovery(player)) {
            inventory.setItem(42, item(player, Material.RECOVERY_COMPASS, "menu.main.recovery", "menu.main.recovery-lore"))
        }
        if (player.hasPermission(ADMIN_PERMISSION)) {
            inventory.setItem(44, item(player, Material.COMPARATOR, "menu.main.admin", "menu.main.admin-lore"))
        }
        player.openInventory(inventory)
    }

    fun openTargets(player: Player, requestedPage: Int = 0) {
        val availableTargets =
            targets.players()
                .filter { target ->
                    if (target.uniqueId == player.uniqueId) return@filter false
                    val local = plugin.server.getPlayer(target.uniqueId) ?: return@filter true
                    !sessions.isEngaged(local) && !sessions.isStateLocked(local)
                }
        val page = pageWindow(availableTargets, requestedPage, CONTENT_SLOTS.size)
        val holder = TargetMenuHolder(page.index, page.hasPrevious, page.hasNext)
        val inventory = create(holder, locales.component(player, "menu.targets.title", LocaleService.text("page", page.index + 1), LocaleService.text("pages", page.totalPages)))
        decorate(inventory)
        page.items.forEachIndexed { index, target ->
            val slot = CONTENT_SLOTS[index]
            holder.targets[slot] = target
            inventory.setItem(
                slot,
                playerHead(
                    target.uniqueId,
                    target.name,
                    locales.component(player, "menu.targets.player", LocaleService.text("player", target.name)),
                    locales.lines(player, "menu.targets.player-lore", LocaleService.text("server", target.server.value)),
                ),
            )
        }
        if (availableTargets.isEmpty()) inventory.setItem(22, item(player, Material.BARRIER, "menu.targets.empty"))
        navigation(player, inventory, page, MenuBack.MAIN)
        player.openInventory(inventory)
    }

    fun openAdmin(player: Player) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage(locales.notice(player, "admin.no-permission", LocaleService.text("permission", ADMIN_PERMISSION)))
            return
        }
        pendingArenaNames.remove(player.uniqueId)
        val inventory = create(AdminMenuHolder(), locales.component(player, "menu.admin.title"))
        decorate(inventory)
        val arenaIds = arenaIds()
        inventory.setItem(11, item(player, Material.FILLED_MAP, "menu.admin.arenas", "menu.admin.arenas-lore", LocaleService.text("arenas", arenaIds.size)))
        inventory.setItem(
            13,
            item(
                player,
                Material.CLOCK,
                "menu.admin.status",
                "menu.admin.status-lore",
                LocaleService.text("arenas", arenaIds.count { plugin.config.getBoolean("arenas.$it.enabled") }),
                LocaleService.text("active", sessions.activeArenaCount()),
                LocaleService.text("waiting", sessions.queueSize()),
            ),
        )
        inventory.setItem(15, item(player, Material.RECOVERY_COMPASS, "menu.admin.recovery", "menu.admin.recovery-lore", LocaleService.text("players", plugin.server.onlinePlayers.size)))
        inventory.setItem(29, item(player, Material.NAME_TAG, "menu.admin.create", "menu.admin.create-lore"))
        inventory.setItem(33, roleItem(player, "refresh", Material.REPEATER, "menu.admin.reload", "menu.admin.reload-lore"))
        inventory.setItem(BACK_SLOT, roleItem(player, "back", Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        player.openInventory(inventory)
    }

    private fun openAdminArenas(player: Player, requestedPage: Int = 0) {
        val ids = arenaIds()
        val page = pageWindow(ids, requestedPage, CONTENT_SLOTS.size)
        val holder = AdminArenaListHolder(page.index, page.hasPrevious, page.hasNext)
        val inventory = create(holder, locales.component(player, "menu.admin-arenas.title", LocaleService.text("page", page.index + 1), LocaleService.text("pages", page.totalPages)))
        decorate(inventory)
        page.items.forEachIndexed { index, id ->
            val slot = CONTENT_SLOTS[index]
            holder.arenas[slot] = id
            val path = "arenas.$id"
            val enabled = plugin.config.getBoolean("$path.enabled")
            val spawn1 = hasLocation("$path.first-spawn")
            val spawn2 = hasLocation("$path.second-spawn")
            val corner1 = hasCoordinates("$path.bounds.min")
            val corner2 = hasCoordinates("$path.bounds.max")
            val hill = hasLocation("$path.hill.center")
            val complete = spawn1 && spawn2 && corner1 && corner2
            val material = if (enabled) Material.LIME_BANNER else if (complete) Material.YELLOW_BANNER else Material.GRAY_BANNER
            inventory.setItem(
                slot,
                item(
                    player,
                    material,
                    "menu.admin-arenas.entry",
                    "menu.admin-arenas.entry-lore",
                    LocaleService.text("arena", id),
                    LocaleService.component("state", state(player, enabled)),
                    LocaleService.component("spawn1", configured(player, spawn1)),
                    LocaleService.component("spawn2", configured(player, spawn2)),
                    LocaleService.component("corner1", configured(player, corner1)),
                    LocaleService.component("corner2", configured(player, corner2)),
                    LocaleService.component("hill", configured(player, hill)),
                ),
            )
        }
        if (ids.isEmpty()) inventory.setItem(22, item(player, Material.PAPER, "menu.admin-arenas.empty", "menu.admin-arenas.empty-lore"))
        navigation(player, inventory, page, MenuBack.MAIN)
        player.openInventory(inventory)
    }

    private fun openAdminArena(player: Player, arenaId: String) {
        if (!plugin.config.isConfigurationSection("arenas.$arenaId")) return openAdminArenas(player)
        val path = "arenas.$arenaId"
        val enabled = plugin.config.getBoolean("$path.enabled")
        val inventory = create(AdminArenaHolder(arenaId), locales.component(player, "menu.admin-arena.title", LocaleService.text("arena", arenaId)))
        decorate(inventory)
        inventory.setItem(10, item(player, Material.MAP, "menu.admin-arena.overview", "menu.admin-arena.overview-lore", LocaleService.text("arena", arenaId), LocaleService.component("state", state(player, enabled))))
        inventory.setItem(12, item(player, Material.COMPASS, "menu.admin-arena.spawn1", "menu.admin-arena.point-lore", LocaleService.text("value", locationSummary("$path.first-spawn"))))
        inventory.setItem(14, item(player, Material.COMPASS, "menu.admin-arena.spawn2", "menu.admin-arena.point-lore", LocaleService.text("value", locationSummary("$path.second-spawn"))))
        inventory.setItem(16, item(player, Material.ENDER_EYE, "menu.admin-arena.lobby", "menu.admin-arena.point-lore", LocaleService.text("value", locationSummary("$path.lobby"))))
        inventory.setItem(19, item(player, Material.WOODEN_AXE, "menu.admin-arena.corner1", "menu.admin-arena.point-lore", LocaleService.text("value", coordinateSummary("$path.bounds.min"))))
        inventory.setItem(21, item(player, Material.GOLDEN_AXE, "menu.admin-arena.corner2", "menu.admin-arena.point-lore", LocaleService.text("value", coordinateSummary("$path.bounds.max"))))
        inventory.setItem(23, item(player, Material.BEACON, "menu.admin-arena.hill", "menu.admin-arena.hill-lore", LocaleService.text("value", locationSummary("$path.hill.center"))))
        val loadouts = arenaLoadoutSelection(path)
        inventory.setItem(
            25,
            item(
                player,
                when (loadouts) {
                    ArenaLoadoutSelection.ALL -> Material.CHEST
                    ArenaLoadoutSelection.OWN_INVENTORY -> Material.BUNDLE
                    ArenaLoadoutSelection.KIT -> Material.IRON_SWORD
                },
                "menu.admin-arena.loadouts",
                "menu.admin-arena.loadouts-lore",
                LocaleService.component("loadout", locales.component(player, "menu.admin-arena.loadouts-${loadouts.name.lowercase()}")),
            ),
        )
        inventory.setItem(
            27,
            roleItem(player, "info", Material.TARGET, "menu.admin-arena.objectives", "menu.admin-arena.objectives-lore"),
        )
        inventory.setItem(
            31,
            item(
                player,
                if (enabled) Material.RED_CONCRETE else Material.LIME_CONCRETE,
                if (enabled) "menu.admin-arena.disable" else "menu.admin-arena.enable",
                if (enabled) "menu.admin-arena.disable-lore" else "menu.admin-arena.enable-lore",
            ),
        )
        inventory.setItem(BACK_SLOT, roleItem(player, "back", Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        player.openInventory(inventory)
    }

    private fun openAdminArenaObjectives(player: Player, arenaId: String) {
        val section = plugin.config.getConfigurationSection("arenas.$arenaId") ?: return openAdminArenas(player)
        val enabled = readArenaAllowedObjectives(section)
        val holder = AdminArenaObjectivesHolder(arenaId)
        val inventory = create(holder, locales.component(player, "menu.admin-objectives.title", LocaleService.text("arena", arenaId)))
        decorate(inventory)
        OBJECTIVE_SLOTS.forEach { (slot, objective) ->
            holder.objectives[slot] = objective
            inventory.setItem(
                slot,
                item(
                    player,
                    objective.material,
                    "menu.admin-objectives.entry",
                    "menu.admin-objectives.entry-lore",
                    LocaleService.component("objective", locales.component(player, "objective.${objective.key}.name")),
                    LocaleService.component("state", state(player, objective in enabled)),
                ),
            )
        }
        inventory.setItem(BACK_SLOT, roleItem(player, "back", Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        player.openInventory(inventory)
    }

    private fun openRecoveryPlayers(player: Player, requestedPage: Int = 0) {
        val players = plugin.server.onlinePlayers.sortedWith { first, second -> String.CASE_INSENSITIVE_ORDER.compare(first.name, second.name) }
        val page = pageWindow(players, requestedPage, CONTENT_SLOTS.size)
        val holder = RecoveryMenuHolder(page.index, page.hasPrevious, page.hasNext)
        val inventory = create(holder, locales.component(player, "menu.admin-recovery.title", LocaleService.text("page", page.index + 1), LocaleService.text("pages", page.totalPages)))
        decorate(inventory)
        page.items.forEachIndexed { index, target ->
            val slot = CONTENT_SLOTS[index]
            holder.players[slot] = target.uniqueId
            inventory.setItem(slot, playerHead(target, locales.component(player, "menu.admin-recovery.player", LocaleService.text("player", target.name)), locales.lines(player, "menu.admin-recovery.player-lore")))
        }
        navigation(player, inventory, page, MenuBack.MAIN)
        player.openInventory(inventory)
    }

    fun openLeaderboard(player: Player, requestedPage: Int = 0) {
        player.sendActionBar(locales.component(player, "menu.leaderboard.loading"))
        statistics.leaderboard(MAX_LEADERBOARD_ENTRIES).whenComplete { entries, failure ->
            runSync {
                if (!player.isOnline) return@runSync
                if (failure != null) {
                    player.sendMessage(locales.notice(player, "error.leaderboard"))
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

    private fun openObjectives(player: Player, target: DuelTarget) {
        val holder = ObjectiveMenuHolder(target)
        val inventory = create(holder, locales.component(player, "menu.objectives.title", LocaleService.text("player", target.name)))
        decorate(inventory)
        objectiveItem(player, DuelObjectiveType.ELIMINATION, Material.DIAMOND_SWORD)?.let { inventory.setItem(11, it) }
        objectiveItem(player, DuelObjectiveType.KING_OF_THE_HILL, Material.BEACON)?.let { inventory.setItem(13, it) }
        objectiveItem(player, DuelObjectiveType.SUMO, Material.SLIME_BALL)?.let { inventory.setItem(15, it) }
        objectiveItem(player, DuelObjectiveType.BOXING, Material.LEATHER_BOOTS)?.let { inventory.setItem(29, it) }
        objectiveItem(player, DuelObjectiveType.COMBO, Material.BLAZE_POWDER)?.let { inventory.setItem(33, it) }
        inventory.setItem(BACK_SLOT, roleItem(player, "back", Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        player.openInventory(inventory)
    }

    private fun openLoadouts(player: Player, target: DuelTarget, objective: DuelObjectiveType, requestedPage: Int = 0) {
        val controlledKit =
            when {
                objective == DuelObjectiveType.SUMO -> "sumo"
                objective.isHitRace -> "boxing"
                else -> null
            }
        val availableKits = controlledKit?.let { required -> kits.all().filter { it.id.value == required } } ?: kits.all()
        val controlledOnly = controlledKit != null
        val kitSlots = if (controlledOnly) CONTENT_SLOTS else CONTENT_SLOTS.filter { it != 32 }
        val page = pageWindow(availableKits, requestedPage, kitSlots.size)
        val holder = LoadoutMenuHolder(target, objective, page.index, page.hasPrevious, page.hasNext)
        val inventory = create(holder, locales.component(player, "menu.loadouts.title"))
        decorate(inventory)
        page.items.forEachIndexed { index, kit ->
            val slot = kitSlots[index]
            holder.kits[slot] = kit.id
            inventory.setItem(slot, kitItem(player, kit))
        }
        if (!controlledOnly) {
            holder.ownInventorySlot = 32
            inventory.setItem(32, item(player, Material.BUNDLE, "menu.loadouts.own", "menu.loadouts.own-lore"))
        } else if (availableKits.isEmpty()) {
            inventory.setItem(22, item(player, Material.BARRIER, "menu.loadouts.controlled-missing", resolvers = arrayOf(LocaleService.text("kit", controlledKit))))
        }
        navigation(player, inventory, page, MenuBack.MAIN)
        player.openInventory(inventory)
    }

    private fun openRules(player: Player, draft: DuelDraft) {
        val target = targets.find(draft.target.uniqueId)
        if (target == null) {
            player.closeInventory()
            player.sendMessage(locales.notice(player, "error.player-left"))
            return
        }
        val holder = RulesMenuHolder(draft)
        val inventory = create(holder, locales.component(player, "menu.rules.title", LocaleService.text("player", target.name)))
        decorate(inventory)
        inventory.setItem(10, toggleItem(player, "menu.rules.ranked", draft.ranked, enabled = draft.mode == DuelMode.KIT))
        inventory.setItem(12, item(player, Material.REPEATER, "menu.rules.best-of", "menu.rules.best-of-lore", LocaleService.text("value", draft.bestOf)))
        if (draft.objective.isHitRace) {
            val target = if (draft.objective == DuelObjectiveType.BOXING) draft.modifiers.boxingHitsToWin else draft.modifiers.comboHitsToWin
            inventory.setItem(14, item(player, Material.TARGET, "menu.rules.hit-target", "menu.rules.hit-target-lore", LocaleService.text("hits", target)))
        } else {
            inventory.setItem(14, item(player, Material.WITHER_SKELETON_SKULL, "menu.rules.sudden-death", "menu.rules.sudden-death-lore", LocaleService.text("seconds", draft.modifiers.suddenDeathAfterSeconds)))
        }
        val combatTogglesEnabled = !draft.objective.isHitRace
        inventory.setItem(19, toggleItem(player, "menu.rules.projectiles", draft.modifiers.projectiles, combatTogglesEnabled))
        inventory.setItem(21, toggleItem(player, "menu.rules.consumables", draft.modifiers.consumables, combatTogglesEnabled))
        inventory.setItem(23, toggleItem(player, "menu.rules.pearls", draft.modifiers.enderPearls, combatTogglesEnabled))
        inventory.setItem(25, toggleItem(player, "menu.rules.regeneration", draft.modifiers.naturalRegeneration, combatTogglesEnabled))
        if (draft.objective == DuelObjectiveType.KING_OF_THE_HILL) {
            inventory.setItem(31, item(player, Material.BEACON, "menu.rules.capture", "menu.rules.capture-lore", LocaleService.text("seconds", draft.modifiers.kingOfTheHillCaptureSeconds)))
        }
        inventory.setItem(40, roleItem(player, "confirm", Material.LIME_CONCRETE, "menu.rules.confirm", "menu.rules.confirm-lore"))
        inventory.setItem(BACK_SLOT, roleItem(player, "back", Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        player.openInventory(inventory)
    }

    private fun openModes(player: Player) {
        val holder = CatalogMenuHolder(CatalogType.MODES)
        val inventory = create(holder, locales.component(player, "menu.modes.title"))
        decorate(inventory)
        inventory.setItem(11, requireNotNull(objectiveItem(player, DuelObjectiveType.ELIMINATION, Material.DIAMOND_SWORD)))
        inventory.setItem(13, requireNotNull(objectiveItem(player, DuelObjectiveType.KING_OF_THE_HILL, Material.BEACON)))
        inventory.setItem(15, requireNotNull(objectiveItem(player, DuelObjectiveType.SUMO, Material.SLIME_BALL)))
        inventory.setItem(29, requireNotNull(objectiveItem(player, DuelObjectiveType.BOXING, Material.LEATHER_BOOTS)))
        inventory.setItem(33, requireNotNull(objectiveItem(player, DuelObjectiveType.COMBO, Material.BLAZE_POWDER)))
        inventory.setItem(BACK_SLOT, roleItem(player, "back", Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
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
        inventory.setItem(BACK_SLOT, roleItem(player, "back", Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
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
                33 -> statisticsAction(player, targets.local(player))
                40 -> showHelp(player)
                42 -> if (sessions.hasPendingRecovery(player)) {
                    player.closeInventory()
                    if (sessions.requestRecovery(player)) {
                        player.sendMessage(locales.notice(player, "session.recovery-requested"))
                    }
                }
                44 -> if (player.hasPermission(ADMIN_PERMISSION)) openAdmin(player)
            }
            is TargetMenuHolder -> when (slot) {
                BACK_SLOT -> openMain(player)
                PREVIOUS_SLOT -> if (holder.hasPrevious) openTargets(player, holder.page - 1)
                NEXT_SLOT -> if (holder.hasNext) openTargets(player, holder.page + 1)
                else -> holder.targets[slot]?.let { selected ->
                    targets.find(selected.uniqueId)?.let { openObjectives(player, it) }
                        ?: player.sendMessage(locales.notice(player, "error.player-left"))
                }
            }
            is ObjectiveMenuHolder -> {
                if (slot == BACK_SLOT) return openTargets(player)
                val objective = OBJECTIVE_SLOTS[slot] ?: return
                targets.find(holder.target.uniqueId)?.let { openLoadouts(player, it, objective) }
                    ?: player.sendMessage(locales.notice(player, "error.player-left"))
            }
            is LoadoutMenuHolder -> {
                val target = targets.find(holder.target.uniqueId) ?: run { player.closeInventory(); player.sendMessage(locales.notice(player, "error.player-left")); return }
                if (slot == BACK_SLOT) return openObjectives(player, target)
                if (slot == PREVIOUS_SLOT && holder.hasPrevious) return openLoadouts(player, target, holder.objective, holder.page - 1)
                if (slot == NEXT_SLOT && holder.hasNext) return openLoadouts(player, target, holder.objective, holder.page + 1)
                val kit = holder.kits[slot]
                val mode = if (kit == null && slot == holder.ownInventorySlot) DuelMode.OWN_INVENTORY else if (kit != null) DuelMode.KIT else return
                val modifiers =
                    when {
                        holder.objective == DuelObjectiveType.SUMO -> SUMO_MODIFIERS
                        holder.objective.isHitRace -> HIT_RACE_MODIFIERS
                        kit?.value == "uhc" -> CombatModifiers(naturalRegeneration = false)
                        else -> CombatModifiers()
                    }
                openRules(player, DuelDraft(target, holder.objective, mode, kit, modifiers = modifiers))
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
            is AdminMenuHolder -> when (slot) {
                BACK_SLOT -> openMain(player)
                11 -> openAdminArenas(player)
                15 -> openRecoveryPlayers(player)
                29 -> promptArenaName(player)
                33 -> { admin.execute(player, listOf("arena", "reload")); openAdmin(player) }
            }
            is AdminArenaListHolder -> when (slot) {
                BACK_SLOT -> openAdmin(player)
                PREVIOUS_SLOT -> if (holder.hasPrevious) openAdminArenas(player, holder.page - 1)
                NEXT_SLOT -> if (holder.hasNext) openAdminArenas(player, holder.page + 1)
                else -> holder.arenas[slot]?.let { openAdminArena(player, it) }
            }
            is AdminArenaHolder -> when (slot) {
                BACK_SLOT -> openAdminArenas(player)
                12 -> executeArenaAction(player, holder.arenaId, "setspawn", "1")
                14 -> executeArenaAction(player, holder.arenaId, "setspawn", "2")
                16 -> executeArenaAction(player, holder.arenaId, "setlobby")
                19 -> executeArenaAction(player, holder.arenaId, "setcorner", "1")
                21 -> executeArenaAction(player, holder.arenaId, "setcorner", "2")
                23 -> executeArenaAction(player, holder.arenaId, "sethill")
                25 -> {
                    val path = "arenas.${holder.arenaId}"
                    executeArenaAction(player, holder.arenaId, "setloadouts", arenaLoadoutSelection(path).next().commandValue)
                }
                27 -> openAdminArenaObjectives(player, holder.arenaId)
                31 -> executeArenaAction(player, holder.arenaId, if (plugin.config.getBoolean("arenas.${holder.arenaId}.enabled")) "disable" else "enable")
            }
            is AdminArenaObjectivesHolder -> when (slot) {
                BACK_SLOT -> openAdminArena(player, holder.arenaId)
                else -> holder.objectives[slot]?.let { objective -> toggleArenaObjective(player, holder.arenaId, objective) }
            }
            is RecoveryMenuHolder -> when (slot) {
                BACK_SLOT -> openAdmin(player)
                PREVIOUS_SLOT -> if (holder.hasPrevious) openRecoveryPlayers(player, holder.page - 1)
                NEXT_SLOT -> if (holder.hasNext) openRecoveryPlayers(player, holder.page + 1)
                else -> holder.players[slot]?.let(plugin.server::getPlayer)?.let { target ->
                    admin.execute(player, listOf("recover", target.name))
                    openRecoveryPlayers(player, holder.page)
                }
            }
        }
    }

    @EventHandler
    fun onArenaName(event: AsyncChatEvent) {
        val player = event.player
        pendingArenaNames.remove(player.uniqueId) ?: return
        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
        runSync {
            if (!player.isOnline) return@runSync
            if (raw.equals("cancel", true) || raw.equals("отмена", true)) {
                player.sendMessage(locales.notice(player, "menu.admin.create-cancelled"))
                openAdmin(player)
                return@runSync
            }
            val id = raw.lowercase()
            val existed = plugin.config.isConfigurationSection("arenas.$id")
            admin.execute(player, listOf("arena", "create", raw))
            if (!existed && plugin.config.isConfigurationSection("arenas.$id")) openAdminArena(player, id) else openAdmin(player)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingArenaNames.remove(event.player.uniqueId)
    }

    private fun promptArenaName(player: Player) {
        player.closeInventory()
        val token = System.nanoTime()
        pendingArenaNames[player.uniqueId] = token
        player.sendMessage(locales.notice(player, "menu.admin.create-prompt"))
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                if (pendingArenaNames.remove(player.uniqueId, token) && player.isOnline) {
                    player.sendMessage(locales.notice(player, "menu.admin.create-timeout"))
                }
            },
            ARENA_NAME_TIMEOUT_TICKS,
        )
    }

    private fun executeArenaAction(player: Player, arenaId: String, operation: String, vararg arguments: String) {
        admin.execute(player, listOf("arena", operation, arenaId, *arguments))
        openAdminArena(player, arenaId)
    }

    private fun toggleArenaObjective(
        player: Player,
        arenaId: String,
        objective: DuelObjectiveType,
    ) {
        val section = plugin.config.getConfigurationSection("arenas.$arenaId") ?: return openAdminArenas(player)
        val selected = readArenaAllowedObjectives(section).toMutableSet()
        if (!selected.add(objective)) selected.remove(objective)
        if (selected.isEmpty()) {
            player.sendMessage(locales.notice(player, "menu.admin-objectives.required"))
            return openAdminArenaObjectives(player, arenaId)
        }
        admin.execute(player, listOf("arena", "setobjectives", arenaId, *selected.map { it.key }.toTypedArray()))
        openAdminArenaObjectives(player, arenaId)
    }

    private fun state(player: Player, enabled: Boolean): Component =
        locales.component(player, if (enabled) "menu.common.enabled" else "menu.common.disabled")

    private fun configured(player: Player, present: Boolean): Component =
        locales.component(player, if (present) "menu.common.configured" else "menu.common.missing")

    private fun arenaIds(): List<String> =
        plugin.config.getConfigurationSection("arenas")?.getKeys(false)?.sorted() ?: emptyList()

    private fun arenaLoadoutSelection(path: String): ArenaLoadoutSelection =
        plugin.config.getConfigurationSection(path)?.let(ArenaLoadoutSelection::from) ?: ArenaLoadoutSelection.ALL

    private fun locationSummary(path: String): String {
        if (!hasLocation(path)) return "—"
        val section = requireNotNull(plugin.config.getConfigurationSection(path))
        val world = section.getString("world") ?: return "—"
        return "$world  ${decimal(section.getDouble("x"))}, ${decimal(section.getDouble("y"))}, ${decimal(section.getDouble("z"))}"
    }

    private fun coordinateSummary(path: String): String {
        if (!hasCoordinates(path)) return "—"
        val section = requireNotNull(plugin.config.getConfigurationSection(path))
        return "${decimal(section.getDouble("x"))}, ${decimal(section.getDouble("y"))}, ${decimal(section.getDouble("z"))}"
    }

    private fun hasLocation(path: String): Boolean =
        plugin.config.getConfigurationSection(path)?.let { section ->
            !section.getString("world").isNullOrBlank() && COORDINATE_KEYS.all(section::contains)
        } == true

    private fun hasCoordinates(path: String): Boolean =
        plugin.config.getConfigurationSection(path)?.let { section -> COORDINATE_KEYS.all(section::contains) } == true

    private fun decimal(value: Double): String = "%.1f".format(java.util.Locale.ROOT, value)

    private fun handleRulesClick(player: Player, draft: DuelDraft, slot: Int) {
        when (slot) {
            BACK_SLOT -> targets.find(draft.target.uniqueId)?.let { openLoadouts(player, it, draft.objective) }
                ?: run { player.closeInventory(); player.sendMessage(locales.notice(player, "error.player-left")) }
            10 -> if (draft.mode == DuelMode.KIT) openRules(player, draft.copy(ranked = !draft.ranked))
            12 -> openRules(player, draft.copy(bestOf = when (draft.bestOf) { 1 -> 3; 3 -> 5; else -> 1 }))
            14 -> when (draft.objective) {
                DuelObjectiveType.BOXING -> openRules(player, draft.copy(modifiers = draft.modifiers.copy(boxingHitsToWin = nextOf(draft.modifiers.boxingHitsToWin, listOf(50, 100, 200)))))
                DuelObjectiveType.COMBO -> openRules(player, draft.copy(modifiers = draft.modifiers.copy(comboHitsToWin = nextOf(draft.modifiers.comboHitsToWin, listOf(5, 10, 15, 20)))))
                else -> openRules(player, draft.copy(modifiers = draft.modifiers.copy(suddenDeathAfterSeconds = nextOf(draft.modifiers.suddenDeathAfterSeconds, listOf(60, 180, 300, 600)))))
            }
            19 -> if (!draft.objective.isHitRace) openRules(player, draft.copy(modifiers = draft.modifiers.copy(projectiles = !draft.modifiers.projectiles)))
            21 -> if (!draft.objective.isHitRace) openRules(player, draft.copy(modifiers = draft.modifiers.copy(consumables = !draft.modifiers.consumables)))
            23 -> if (!draft.objective.isHitRace) openRules(player, draft.copy(modifiers = draft.modifiers.copy(enderPearls = !draft.modifiers.enderPearls)))
            25 -> if (!draft.objective.isHitRace) openRules(player, draft.copy(modifiers = draft.modifiers.copy(naturalRegeneration = !draft.modifiers.naturalRegeneration)))
            31 -> if (draft.objective == DuelObjectiveType.KING_OF_THE_HILL) openRules(player, draft.copy(modifiers = draft.modifiers.copy(kingOfTheHillCaptureSeconds = nextOf(draft.modifiers.kingOfTheHillCaptureSeconds, listOf(10, 15, 30, 45)))))
            40 -> {
                val target = targets.find(draft.target.uniqueId)
                player.closeInventory()
                if (target == null) player.sendMessage(locales.notice(player, "error.player-left"))
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
        val filler = roleItem("background", Material.GRAY_STAINED_GLASS_PANE, Component.text(" "))
        for (slot in 0 until inventory.size) inventory.setItem(slot, filler)
        CONTENT_SLOTS.forEach { inventory.setItem(it, null) }
    }

    private fun <T> navigation(player: Player, inventory: Inventory, page: PageWindow<T>, back: MenuBack) {
        if (back == MenuBack.MAIN) inventory.setItem(BACK_SLOT, roleItem(player, "back", Material.BLUE_STAINED_GLASS_PANE, "menu.common.back"))
        if (page.hasPrevious) inventory.setItem(PREVIOUS_SLOT, roleItem(player, "previous", Material.ARROW, "menu.common.previous"))
        inventory.setItem(PAGE_SLOT, roleItem(player, "info", Material.CLOCK, "menu.common.page", resolvers = arrayOf(LocaleService.text("page", page.index + 1), LocaleService.text("pages", page.totalPages))))
        if (page.hasNext) inventory.setItem(NEXT_SLOT, roleItem(player, "next", Material.SPECTRAL_ARROW, "menu.common.next"))
    }

    private fun item(player: Player, material: Material, nameKey: String, loreKey: String? = null, vararg resolvers: net.kyori.adventure.text.minimessage.tag.resolver.TagResolver): ItemStack =
        item(material, locales.component(player, nameKey, *resolvers), loreKey?.let { locales.lines(player, it, *resolvers) }.orEmpty())

    private fun item(player: Player, material: Material, nameKey: String, resolvers: Array<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver>): ItemStack =
        item(material, locales.component(player, nameKey, *resolvers))

    private fun roleItem(
        player: Player,
        role: String,
        fallback: Material,
        nameKey: String,
        loreKey: String? = null,
        vararg resolvers: net.kyori.adventure.text.minimessage.tag.resolver.TagResolver,
    ): ItemStack =
        roleItem(
            role,
            fallback,
            locales.component(player, nameKey, *resolvers),
            loreKey?.let { locales.lines(player, it, *resolvers) }.orEmpty(),
        )

    private fun roleItem(
        player: Player,
        role: String,
        fallback: Material,
        nameKey: String,
        resolvers: Array<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver>,
    ): ItemStack = roleItem(role, fallback, locales.component(player, nameKey, *resolvers))

    private fun roleItem(
        role: String,
        fallback: Material,
        name: Component,
        lore: List<Component> = emptyList(),
    ): ItemStack = style(guiItems.create(role, fallback), name, lore)

    private fun item(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack =
        style(ItemStack(material), name, lore)

    private fun style(stack: ItemStack, name: Component, lore: List<Component>): ItemStack =
        stack.apply {
            itemMeta = itemMeta.apply {
                displayName(nonItalic(name))
                lore(lore.map(::nonItalic))
                addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }

    private fun playerHead(player: Player, name: Component, lore: List<Component>): ItemStack = playerHead(player.uniqueId, player.name, name, lore)

    private fun playerHead(uuid: UUID, profileName: String?, name: Component, lore: List<Component>): ItemStack =
        ItemStack(Material.PLAYER_HEAD).apply {
            val profile = ResolvableProfile.resolvableProfile().uuid(uuid)
            if (profileName != null) profile.name(profileName)
            setData(DataComponentTypes.PROFILE, profile)
            itemMeta = itemMeta.apply { displayName(nonItalic(name)); lore(lore.map(::nonItalic)) }
        }

    private fun nonItalic(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)

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
    private class TargetMenuHolder(val page: Int, val hasPrevious: Boolean, val hasNext: Boolean) : MenuHolder() { val targets = mutableMapOf<Int, DuelTarget>() }
    private class ObjectiveMenuHolder(val target: DuelTarget) : MenuHolder()
    private class LoadoutMenuHolder(val target: DuelTarget, val objective: DuelObjectiveType, val page: Int, val hasPrevious: Boolean, val hasNext: Boolean) : MenuHolder() { val kits = mutableMapOf<Int, KitId>(); var ownInventorySlot = -1 }
    private class RulesMenuHolder(val draft: DuelDraft) : MenuHolder()
    private class LeaderboardMenuHolder(val page: Int, val hasPrevious: Boolean, val hasNext: Boolean) : MenuHolder()
    private class CatalogMenuHolder(val type: CatalogType, val page: Int = 0, val hasPrevious: Boolean = false, val hasNext: Boolean = false) : MenuHolder()
    private class AdminMenuHolder : MenuHolder()
    private class AdminArenaListHolder(val page: Int, val hasPrevious: Boolean, val hasNext: Boolean) : MenuHolder() { val arenas = mutableMapOf<Int, String>() }
    private class AdminArenaHolder(val arenaId: String) : MenuHolder()
    private class AdminArenaObjectivesHolder(val arenaId: String) : MenuHolder() { val objectives = mutableMapOf<Int, DuelObjectiveType>() }
    private class RecoveryMenuHolder(val page: Int, val hasPrevious: Boolean, val hasNext: Boolean) : MenuHolder() { val players = mutableMapOf<Int, UUID>() }
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
        const val ARENA_NAME_TIMEOUT_TICKS = 1_200L
        val COORDINATE_KEYS = listOf("x", "y", "z")
        val CONTENT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34)
        val SUMO_MODIFIERS =
            CombatModifiers(
                projectiles = false,
                consumables = false,
                enderPearls = false,
                naturalRegeneration = false,
                suddenDeathAfterSeconds = 180,
            )
        val HIT_RACE_MODIFIERS =
            CombatModifiers(
                projectiles = false,
                consumables = false,
                enderPearls = false,
                naturalRegeneration = false,
            )
        val OBJECTIVE_SLOTS =
            mapOf(
                11 to DuelObjectiveType.ELIMINATION,
                13 to DuelObjectiveType.KING_OF_THE_HILL,
                15 to DuelObjectiveType.SUMO,
                29 to DuelObjectiveType.BOXING,
                33 to DuelObjectiveType.COMBO,
            )
    }
}

private val DuelObjectiveType.key: String get() = name.lowercase().replace("king_of_the_hill", "koth")

private val DuelObjectiveType.material: Material
    get() =
        when (this) {
            DuelObjectiveType.ELIMINATION -> Material.DIAMOND_SWORD
            DuelObjectiveType.KING_OF_THE_HILL -> Material.BEACON
            DuelObjectiveType.SUMO -> Material.SLIME_BALL
            DuelObjectiveType.BOXING -> Material.LEATHER_BOOTS
            DuelObjectiveType.COMBO -> Material.BLAZE_POWDER
        }

private data class DuelDraft(
    val target: DuelTarget,
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
