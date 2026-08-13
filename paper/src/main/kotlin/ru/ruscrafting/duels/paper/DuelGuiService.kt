package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ResolvableProfile
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
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.StatisticsRepository
import java.util.UUID

class DuelGuiService(
    private val plugin: JavaPlugin,
    private val kits: KitRegistry,
    private val statistics: StatisticsRepository,
    private val challengeAction: (Player, Player, DuelRules) -> Unit,
) : Listener {
    private val miniMessage = MiniMessage.miniMessage()

    fun openTargets(
        player: Player,
        requestedPage: Int = 0,
    ) {
        val targets =
            plugin.server.onlinePlayers
                .filter { it.uniqueId != player.uniqueId }
                .sortedWith { first, second -> String.CASE_INSENSITIVE_ORDER.compare(first.name, second.name) }
        val page = pageWindow(targets, requestedPage, PAGED_CONTENT_SIZE)
        val holder = TargetMenuHolder(page.index, page.hasPrevious, page.hasNext)
        val inventory =
            create(
                holder,
                54,
                "<gradient:#55ffff:#5555ff><bold>СОПЕРНИК</bold></gradient> <gray>${page.index + 1}/${page.totalPages}</gray>",
            )
        decorateFooter(inventory)
        page.items.forEachIndexed { slot, target ->
            holder.targets[slot] = target.uniqueId
            inventory.setItem(slot, playerHead(target, "<aqua><bold>${target.name}</bold></aqua>", listOf("<gray>Нажми, чтобы выбрать режим</gray>")))
        }
        if (targets.isEmpty()) inventory.setItem(22, item(Material.BARRIER, "<red>Нет доступных игроков</red>"))
        addNavigation(inventory, page)
        player.openInventory(inventory)
    }

    fun openMode(
        player: Player,
        target: Player,
        requestedPage: Int = 0,
    ) {
        val page = pageWindow(kits.all(), requestedPage, MODE_CONTENT_SLOTS.size)
        val holder = ModeMenuHolder(target.uniqueId, page.index, page.hasPrevious, page.hasNext)
        val inventory =
            create(
                holder,
                54,
                "<gradient:#ffaa00:#ff5555><bold>РЕЖИМ</bold></gradient> <gray>${page.index + 1}/${page.totalPages}</gray>",
            )
        decorate(inventory)
        page.items.forEachIndexed { index, kit ->
            val slot = MODE_CONTENT_SLOTS[index]
            holder.rules[slot] = DuelRules(DuelMode.KIT, kit.id)
            inventory.setItem(
                slot,
                item(
                    kit.icon,
                    componentToMiniMessage(kit.displayName),
                    listOf(
                        "<gray>Одинаковый набор для обоих</gray>",
                        "",
                        "<yellow>ЛКМ:</yellow> <white>обычная, BO1</white>",
                        "<yellow>ПКМ:</yellow> <white>обычная, BO3</white>",
                        "<aqua>Shift + ЛКМ:</aqua> <white>рейтинг, BO1</white>",
                        "<aqua>Shift + ПКМ:</aqua> <white>рейтинг, BO3</white>",
                    ),
                ),
            )
        }
        holder.rules[OWN_INVENTORY_SLOT] = DuelRules(DuelMode.OWN_INVENTORY)
        inventory.setItem(
            OWN_INVENTORY_SLOT,
            item(
                Material.ENDER_CHEST,
                "<light_purple><bold>СВОЁ СНАРЯЖЕНИЕ</bold></light_purple>",
                listOf(
                    "<gray>Каждый сражается своими вещами</gray>",
                    "<dark_gray>Исходное состояние сначала сохраняется в MySQL</dark_gray>",
                    "<dark_gray>Инвентарь восстановится после матча</dark_gray>",
                    "",
                    "<yellow>ЛКМ:</yellow> <white>BO1</white>",
                    "<yellow>ПКМ:</yellow> <white>BO3</white>",
                    "<dark_gray>Рейтинг для своего снаряжения выключен</dark_gray>",
                ),
            ),
        )
        addNavigation(inventory, page, MODE_PAGE_INFO_SLOT)
        player.openInventory(inventory)
    }

    fun openLeaderboard(
        player: Player,
        requestedPage: Int = 0,
    ) {
        player.sendActionBar(miniMessage.deserialize("<gray>Загружаю глобальный рейтинг…</gray>"))
        statistics.leaderboard(MAX_LEADERBOARD_ENTRIES).whenComplete { entries, failure ->
            if (!plugin.isEnabled) return@whenComplete
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                if (failure != null) {
                    player.sendMessage(miniMessage.deserialize("<red>Не удалось загрузить рейтинг.</red>"))
                    return@Runnable
                }
                val page = pageWindow(entries, requestedPage, PAGED_CONTENT_SIZE)
                val holder = LeaderboardMenuHolder(page.index, page.hasPrevious, page.hasNext)
                val inventory =
                    create(
                        holder,
                        54,
                        "<gradient:#ffd700:#ff8c00><bold>ЛИДЕРЫ</bold></gradient> <gray>${page.index + 1}/${page.totalPages}</gray>",
                    )
                decorateFooter(inventory)
                page.items.forEachIndexed { slot, entry ->
                    val profileName = entry.playerName ?: Bukkit.getOfflinePlayer(entry.playerId.value).name
                    val stack = ItemStack(Material.PLAYER_HEAD)
                    applyHeadProfile(stack, entry.playerId.value, profileName)
                    val meta = stack.itemMeta
                    meta.displayName(
                        miniMessage.deserialize("<gold><bold>#${entry.position}</bold></gold> ")
                            .append(Component.text(profileName ?: entry.playerId.toString())),
                    )
                    meta.lore(
                        listOf(
                            miniMessage.deserialize("<gray>Рейтинг:</gray> <aqua><bold>${entry.rating}</bold></aqua>"),
                            miniMessage.deserialize("<gray>Победы:</gray> <green>${entry.wins}</green>"),
                            miniMessage.deserialize("<gray>Поражения:</gray> <red>${entry.losses}</red>"),
                        ),
                    )
                    stack.itemMeta = meta
                    inventory.setItem(slot, stack)
                }
                if (entries.isEmpty()) inventory.setItem(22, item(Material.PAPER, "<gray>Матчей пока нет</gray>"))
                addNavigation(inventory, page)
                player.openInventory(inventory)
            })
        }
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        when (val holder = event.view.topInventory.holder) {
            is TargetMenuHolder -> {
                event.isCancelled = true
                if (event.clickedInventory != event.view.topInventory) return
                when (event.rawSlot) {
                    PREVIOUS_SLOT -> if (holder.hasPrevious) openTargets(player, holder.page - 1)
                    NEXT_SLOT -> if (holder.hasNext) openTargets(player, holder.page + 1)
                }
                if (event.rawSlot == PREVIOUS_SLOT || event.rawSlot == NEXT_SLOT) return
                val targetId = holder.targets[event.rawSlot] ?: return
                val target = plugin.server.getPlayer(targetId)
                if (target == null) {
                    player.closeInventory()
                    player.sendMessage(miniMessage.deserialize("<red>Игрок уже вышел.</red>"))
                } else {
                    openMode(player, target)
                }
            }
            is ModeMenuHolder -> {
                event.isCancelled = true
                if (event.clickedInventory != event.view.topInventory) return
                if (event.rawSlot == PREVIOUS_SLOT || event.rawSlot == NEXT_SLOT) {
                    val target = plugin.server.getPlayer(holder.target)
                    if (target == null) {
                        player.closeInventory()
                        player.sendMessage(miniMessage.deserialize("<red>Игрок уже вышел.</red>"))
                    } else if (event.rawSlot == PREVIOUS_SLOT && holder.hasPrevious) {
                        openMode(player, target, holder.page - 1)
                    } else if (event.rawSlot == NEXT_SLOT && holder.hasNext) {
                        openMode(player, target, holder.page + 1)
                    }
                    return
                }
                val baseRules = holder.rules[event.rawSlot] ?: return
                val rules = rulesForSelection(baseRules, event.isShiftClick, event.isRightClick)
                val target = plugin.server.getPlayer(holder.target)
                player.closeInventory()
                if (target == null) {
                    player.sendMessage(miniMessage.deserialize("<red>Игрок уже вышел.</red>"))
                } else {
                    challengeAction(player, target, rules)
                }
            }
            is LeaderboardMenuHolder -> {
                event.isCancelled = true
                if (event.clickedInventory != event.view.topInventory) return
                if (event.rawSlot == PREVIOUS_SLOT && holder.hasPrevious) openLeaderboard(player, holder.page - 1)
                if (event.rawSlot == NEXT_SLOT && holder.hasNext) openLeaderboard(player, holder.page + 1)
            }
        }
    }

    private fun create(
        holder: MenuHolder,
        size: Int,
        title: String,
    ): Inventory =
        Bukkit.createInventory(holder, size, miniMessage.deserialize(title)).also(holder::attach)

    private fun decorate(inventory: Inventory) {
        val border = item(Material.BLACK_STAINED_GLASS_PANE, " ")
        for (slot in 0 until inventory.size) {
            val row = slot / 9
            val column = slot % 9
            if (row == 0 || row == inventory.size / 9 - 1 || column == 0 || column == 8) inventory.setItem(slot, border)
        }
    }

    private fun decorateFooter(inventory: Inventory) {
        val footer = item(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (slot in 45 until 54) inventory.setItem(slot, footer)
    }

    private fun <T> addNavigation(
        inventory: Inventory,
        page: PageWindow<T>,
        infoSlot: Int = PAGE_INFO_SLOT,
    ) {
        if (page.hasPrevious) {
            inventory.setItem(PREVIOUS_SLOT, item(Material.ARROW, "<yellow><bold>НАЗАД</bold></yellow>"))
        }
        inventory.setItem(
            infoSlot,
            item(Material.CLOCK, "<aqua>Страница ${page.index + 1}/${page.totalPages}</aqua>"),
        )
        if (page.hasNext) {
            inventory.setItem(NEXT_SLOT, item(Material.SPECTRAL_ARROW, "<yellow><bold>ВПЕРЁД</bold></yellow>"))
        }
    }

    private fun playerHead(
        player: Player,
        name: String,
        lore: List<String>,
    ): ItemStack {
        val stack = ItemStack(Material.PLAYER_HEAD)
        applyHeadProfile(stack, player.uniqueId, player.name)
        val meta = stack.itemMeta
        meta.displayName(miniMessage.deserialize(name))
        meta.lore(lore.map(miniMessage::deserialize))
        stack.itemMeta = meta
        return stack
    }

    private fun applyHeadProfile(
        stack: ItemStack,
        uuid: UUID,
        name: String?,
    ) {
        val profile = ResolvableProfile.resolvableProfile().uuid(uuid)
        if (name != null) profile.name(name)
        stack.setData(DataComponentTypes.PROFILE, profile)
    }

    private fun item(
        material: Material,
        name: String,
        lore: List<String> = emptyList(),
    ): ItemStack =
        ItemStack(material).apply {
            itemMeta = itemMeta.apply {
                displayName(miniMessage.deserialize(name))
                lore(lore.map(miniMessage::deserialize))
                addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }

    private fun componentToMiniMessage(component: Component): String = miniMessage.serialize(component)

    private abstract class MenuHolder : InventoryHolder {
        private lateinit var inventory: Inventory

        fun attach(inventory: Inventory) {
            this.inventory = inventory
        }

        override fun getInventory(): Inventory = inventory
    }

    private class TargetMenuHolder(
        val page: Int,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
    ) : MenuHolder() {
        val targets = mutableMapOf<Int, UUID>()
    }

    private class ModeMenuHolder(
        val target: UUID,
        val page: Int,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
    ) : MenuHolder() {
        val rules = mutableMapOf<Int, DuelRules>()
    }

    private class LeaderboardMenuHolder(
        val page: Int,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
    ) : MenuHolder()

    private companion object {
        const val PAGED_CONTENT_SIZE = 45
        const val MAX_LEADERBOARD_ENTRIES = 100
        const val PREVIOUS_SLOT = 45
        const val MODE_PAGE_INFO_SLOT = 48
        const val OWN_INVENTORY_SLOT = 49
        const val PAGE_INFO_SLOT = 49
        const val NEXT_SLOT = 53
        val MODE_CONTENT_SLOTS =
            (0 until 54).filter { slot ->
                val row = slot / 9
                val column = slot % 9
                row in 1..4 && column in 1..7
            }
    }
}

internal data class PageWindow<T>(
    val index: Int,
    val totalPages: Int,
    val items: List<T>,
) {
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index + 1 < totalPages
}

internal fun <T> pageWindow(
    items: List<T>,
    requestedPage: Int,
    pageSize: Int,
): PageWindow<T> {
    require(pageSize > 0) { "Page size must be positive" }
    val totalPages = maxOf(1, (items.size + pageSize - 1) / pageSize)
    val index = requestedPage.coerceIn(0, totalPages - 1)
    val fromIndex = index * pageSize
    return PageWindow(index, totalPages, items.subList(fromIndex, minOf(items.size, fromIndex + pageSize)))
}

internal fun rulesForSelection(
    baseRules: DuelRules,
    shiftClick: Boolean,
    rightClick: Boolean,
): DuelRules =
    baseRules.copy(
        ranked = shiftClick && baseRules.mode == DuelMode.KIT,
        bestOf = if (rightClick) 3 else 1,
    )
