package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
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
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MAX_MULTIPLAYER_PARTICIPANTS
import ru.ruscrafting.duels.domain.MIN_MULTIPLAYER_PARTICIPANTS
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.MultiplayerParticipant
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.MultiplayerRules
import ru.ruscrafting.duels.domain.PlayerId
import java.util.UUID

/** Inventory-only multiplayer setup, invitation, kit selection, and readiness flow. */
internal class MultiplayerGuiService(
    private val plugin: JavaPlugin,
    private val kits: KitRegistry,
    private val sessions: MultiplayerSessionManager,
    private val duelSessions: DuelSessionManager,
    private val locales: LocaleService,
    private val tasks: LifecycleTaskScope,
    private val backAction: (Player) -> Unit,
) : Listener {
    private data class Draft(
        val hostId: UUID,
        val selected: LinkedHashSet<UUID> = linkedSetOf(),
        var layout: MultiplayerLayout = MultiplayerLayout.FREE_FOR_ALL,
        var kitPolicy: MultiplayerKitPolicy = MultiplayerKitPolicy.SHARED,
        var hostKit: KitId,
        var sharedKit: KitId,
        var page: Int = 0,
    )

    private data class Lobby(
        val id: UUID,
        val hostId: UUID,
        val members: List<UUID>,
        val layout: MultiplayerLayout,
        val kitPolicy: MultiplayerKitPolicy,
        val sharedKit: KitId?,
        val acceptedKits: MutableMap<UUID, KitId>,
    ) {
        val participantIds: List<UUID> get() = listOf(hostId) + members
        fun accepted(playerId: UUID): Boolean = playerId in acceptedKits
    }

    private val drafts = mutableMapOf<UUID, Draft>()
    private val lobbies = mutableMapOf<UUID, Lobby>()
    private val lobbyByPlayer = mutableMapOf<UUID, UUID>()
    private val provisionalKits = mutableMapOf<UUID, KitId>()
    private val guiItems = GuiItemCatalog.load(plugin)

    fun open(player: Player) {
        if (busy(player)) {
            player.sendMessage(locales.notice(player, "multiplayer.busy"))
            return
        }
        lobbyByPlayer[player.uniqueId]?.let(lobbies::get)?.let { return openLobby(player, it) }
        val firstKit = kits.all().firstOrNull()?.id ?: run {
            player.sendMessage(locales.notice(player, "multiplayer.no-kits"))
            return
        }
        val draft = drafts.getOrPut(player.uniqueId) { Draft(player.uniqueId, hostKit = firstKit, sharedKit = firstKit) }
        openSetup(player, draft)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val holder = event.view.topInventory.holder as? MultiplayerHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        when (holder) {
            is SetupHolder -> handleSetupClick(player, holder, event.rawSlot)
            is LobbyHolder -> handleLobbyClick(player, holder, event.rawSlot)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        drafts.remove(event.player.uniqueId)
        lobbyByPlayer[event.player.uniqueId]?.let(lobbies::get)?.let { cancelLobby(it, "multiplayer.player-left") }
    }

    private fun openSetup(player: Player, draft: Draft) {
        val available = plugin.server.onlinePlayers
            .filter { it.uniqueId != player.uniqueId && !busy(it) && it.uniqueId !in lobbyByPlayer }
            .sortedBy(Player::getName)
        val page = pageWindow(available, draft.page, PLAYER_SLOTS.size)
        draft.page = page.index
        val shown = page.items
        val holder = SetupHolder(draft.hostId, page.index, page.hasPrevious, page.hasNext)
        val inventory = create(holder, locales.component(player, "multiplayer.menu.setup-title"))
        decorate(inventory)
        shown.forEachIndexed { index, target ->
            val slot = PLAYER_SLOTS[index]
            holder.players[slot] = target.uniqueId
            inventory.setItem(slot, playerHead(player, target, target.uniqueId in draft.selected, assignedTeam(draft, target.uniqueId), interactive = true))
        }
        if (shown.isEmpty()) inventory.setItem(22, item(player, Material.BARRIER, "multiplayer.menu.no-players"))
        inventory.setItem(28, item(player, layoutMaterial(draft.layout), "multiplayer.menu.layout", "multiplayer.menu.layout-lore", value(player, layoutKey(draft.layout))))
        inventory.setItem(30, item(player, policyMaterial(draft.kitPolicy), "multiplayer.menu.kit-policy", "multiplayer.menu.kit-policy-lore", value(player, policyKey(draft.kitPolicy))))
        val selectedKit = if (draft.kitPolicy == MultiplayerKitPolicy.SHARED) draft.sharedKit else draft.hostKit
        inventory.setItem(32, kitButton(player, kits.get(selectedKit), draft.kitPolicy, interactive = true))
        val total = draft.selected.size + 1
        val ready = total in MIN_MULTIPLAYER_PARTICIPANTS..MAX_MULTIPLAYER_PARTICIPANTS && teamsValid(draft, total)
        inventory.setItem(
            34,
            item(
                player,
                if (ready) Material.LIME_CONCRETE else Material.GRAY_CONCRETE,
                if (ready) "multiplayer.menu.invite" else "multiplayer.menu.invite-disabled",
                if (ready) "multiplayer.menu.invite-lore" else "multiplayer.menu.invite-disabled-lore",
                LocaleService.text("players", total),
            ),
        )
        inventory.setItem(36, roleItem(player, "back", Material.BLUE_STAINED_GLASS_PANE, "menu.common.back", "multiplayer.menu.back-lore"))
        if (page.hasPrevious) inventory.setItem(37, roleItem(player, "previous", Material.ARROW, "menu.common.previous"))
        inventory.setItem(
            40,
            roleItem(
                player,
                "info",
                Material.CLOCK,
                "menu.common.page",
                values = arrayOf(LocaleService.text("page", page.index + 1), LocaleService.text("pages", page.totalPages)),
            ),
        )
        if (page.hasNext) inventory.setItem(43, roleItem(player, "next", Material.SPECTRAL_ARROW, "menu.common.next"))
        player.openInventory(inventory)
    }

    private fun handleSetupClick(player: Player, holder: SetupHolder, slot: Int) {
        val draft = drafts[holder.hostId] ?: return open(player)
        holder.players[slot]?.let { playerId ->
            if (!draft.selected.add(playerId)) draft.selected.remove(playerId)
            return openSetup(player, draft)
        }
        when (slot) {
            28 -> {
                draft.layout = when (draft.layout) {
                    MultiplayerLayout.FREE_FOR_ALL -> MultiplayerLayout.TWO_TEAMS
                    MultiplayerLayout.TWO_TEAMS -> MultiplayerLayout.THREE_TEAMS
                    MultiplayerLayout.THREE_TEAMS -> MultiplayerLayout.FREE_FOR_ALL
                }
                openSetup(player, draft)
            }
            30 -> {
                draft.kitPolicy = if (draft.kitPolicy == MultiplayerKitPolicy.SHARED) MultiplayerKitPolicy.PER_PLAYER else MultiplayerKitPolicy.SHARED
                openSetup(player, draft)
            }
            32 -> {
                val next = nextKit(if (draft.kitPolicy == MultiplayerKitPolicy.SHARED) draft.sharedKit else draft.hostKit)
                if (draft.kitPolicy == MultiplayerKitPolicy.SHARED) draft.sharedKit = next else draft.hostKit = next
                openSetup(player, draft)
            }
            34 -> createLobby(player, draft)
            36 -> {
                drafts.remove(player.uniqueId)
                backAction(player)
            }
            37 -> if (holder.hasPrevious) {
                draft.page = holder.page - 1
                openSetup(player, draft)
            }
            43 -> if (holder.hasNext) {
                draft.page = holder.page + 1
                openSetup(player, draft)
            }
        }
    }

    private fun createLobby(host: Player, draft: Draft) {
        val total = draft.selected.size + 1
        if (total !in MIN_MULTIPLAYER_PARTICIPANTS..MAX_MULTIPLAYER_PARTICIPANTS || !teamsValid(draft, total)) {
            host.sendMessage(locales.notice(host, "multiplayer.invalid-size"))
            return openSetup(host, draft)
        }
        val members = draft.selected.mapNotNull(plugin.server::getPlayer).filterNot(::busy)
        if (members.size != draft.selected.size || members.any { it.uniqueId in lobbyByPlayer }) {
            host.sendMessage(locales.notice(host, "multiplayer.player-left"))
            return openSetup(host, draft)
        }
        val lobby = Lobby(
            id = UUID.randomUUID(),
            hostId = host.uniqueId,
            members = members.map(Player::getUniqueId),
            layout = draft.layout,
            kitPolicy = draft.kitPolicy,
            sharedKit = draft.sharedKit.takeIf { draft.kitPolicy == MultiplayerKitPolicy.SHARED },
            acceptedKits = mutableMapOf(host.uniqueId to if (draft.kitPolicy == MultiplayerKitPolicy.SHARED) draft.sharedKit else draft.hostKit),
        )
        lobbies[lobby.id] = lobby
        lobby.participantIds.forEach { lobbyByPlayer[it] = lobby.id }
        drafts.remove(host.uniqueId)
        members.forEach { member ->
            member.sendMessage(locales.notice(member, "multiplayer.invited", LocaleService.text("player", host.name)))
            openLobby(member, lobby)
        }
        openLobby(host, lobby)
        tasks.runLater(INVITE_TIMEOUT_TICKS) {
            if (lobbies[lobby.id] === lobby) cancelLobby(lobby, "multiplayer.expired")
        }
    }

    private fun openLobby(player: Player, lobby: Lobby) {
        val holder = LobbyHolder(lobby.id)
        val inventory = create(holder, locales.component(player, "multiplayer.menu.lobby-title"))
        decorate(inventory)
        lobby.participantIds.forEachIndexed { index, playerId ->
            val member = plugin.server.getPlayer(playerId)
            val slot = PARTICIPANT_SLOTS[index]
            inventory.setItem(
                slot,
                member?.let {
                    playerHead(player, it, lobby.accepted(playerId), assignedTeam(lobby, playerId), interactive = false)
                } ?: item(player, Material.SKELETON_SKULL, "multiplayer.menu.left-player"),
            )
        }
        inventory.setItem(28, item(player, layoutMaterial(lobby.layout), "multiplayer.menu.layout", "multiplayer.menu.readonly-lore", value(player, layoutKey(lobby.layout))))
        inventory.setItem(30, item(player, policyMaterial(lobby.kitPolicy), "multiplayer.menu.kit-policy", "multiplayer.menu.readonly-lore", value(player, policyKey(lobby.kitPolicy))))
        val chosen = lobby.sharedKit ?: lobby.acceptedKits[player.uniqueId] ?: provisionalKits[player.uniqueId] ?: kits.all().first().id
        val canChooseKit =
            lobby.kitPolicy == MultiplayerKitPolicy.PER_PLAYER &&
                player.uniqueId != lobby.hostId &&
                !lobby.accepted(player.uniqueId)
        inventory.setItem(32, kitButton(player, kits.get(chosen), lobby.kitPolicy, interactive = canChooseKit))
        when {
            player.uniqueId == lobby.hostId -> inventory.setItem(34, item(player, Material.CLOCK, "multiplayer.menu.waiting", "multiplayer.menu.waiting-lore", LocaleService.text("ready", lobby.acceptedKits.size), LocaleService.text("players", lobby.participantIds.size)))
            lobby.accepted(player.uniqueId) -> inventory.setItem(34, item(player, Material.LIME_DYE, "multiplayer.menu.accepted", "multiplayer.menu.accepted-lore"))
            else -> inventory.setItem(34, item(player, Material.LIME_CONCRETE, "multiplayer.menu.accept", "multiplayer.menu.accept-lore"))
        }
        inventory.setItem(36, item(player, Material.RED_CONCRETE, if (player.uniqueId == lobby.hostId) "multiplayer.menu.cancel" else "multiplayer.menu.decline", "multiplayer.menu.cancel-lore"))
        player.openInventory(inventory)
    }

    private fun handleLobbyClick(player: Player, holder: LobbyHolder, slot: Int) {
        val lobby = lobbies[holder.lobbyId] ?: return open(player)
        when (slot) {
            32 -> if (lobby.kitPolicy == MultiplayerKitPolicy.PER_PLAYER && player.uniqueId != lobby.hostId && !lobby.accepted(player.uniqueId)) {
                provisionalKits[player.uniqueId] = nextKit(provisionalKits[player.uniqueId] ?: kits.all().first().id)
                openLobby(player, lobby)
            }
            34 -> if (player.uniqueId != lobby.hostId && !lobby.accepted(player.uniqueId)) {
                lobby.acceptedKits[player.uniqueId] = lobby.sharedKit ?: provisionalKits.remove(player.uniqueId) ?: kits.all().first().id
                if (lobby.acceptedKits.size == lobby.participantIds.size) startLobby(lobby) else {
                    openLobby(player, lobby)
                    plugin.server.getPlayer(lobby.hostId)?.let { openLobby(it, lobby) }
                }
            }
            36 -> cancelLobby(lobby, if (player.uniqueId == lobby.hostId) "multiplayer.cancelled" else "multiplayer.declined")
        }
    }

    private fun startLobby(lobby: Lobby) {
        val online = lobby.participantIds.mapNotNull(plugin.server::getPlayer)
        if (online.size != lobby.participantIds.size || online.any(::busy)) return cancelLobby(lobby, "multiplayer.player-left")
        val roster = MultiplayerRoster(
            MultiplayerRules(lobby.layout, lobby.kitPolicy, lobby.sharedKit),
            lobby.participantIds.mapIndexed { index, playerId ->
                MultiplayerParticipant(
                    playerId = PlayerId(playerId),
                    team = lobby.layout.teamCount?.let { index % it + 1 },
                    kitId = requireNotNull(lobby.acceptedKits[playerId]),
                )
            },
        )
        removeLobby(lobby)
        online.forEach(Player::closeInventory)
        sessions.start(roster, online.associateBy { PlayerId(it.uniqueId) }).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) online.filter(Player::isOnline).forEach { player ->
                player.sendMessage(locales.notice(player, "multiplayer.start-failed", LocaleService.text("reason", failure.message ?: "unknown")))
            }
        }
    }

    private fun cancelLobby(lobby: Lobby, key: String) {
        lobby.participantIds.mapNotNull(plugin.server::getPlayer).forEach { player ->
            player.closeInventory()
            player.sendMessage(locales.notice(player, key))
        }
        removeLobby(lobby)
    }

    private fun removeLobby(lobby: Lobby) {
        lobbies.remove(lobby.id, lobby)
        lobby.participantIds.forEach { playerId ->
            lobbyByPlayer.remove(playerId, lobby.id)
            provisionalKits.remove(playerId)
        }
    }

    private fun busy(player: Player): Boolean =
        duelSessions.isEngaged(player) || duelSessions.isStateLocked(player) || sessions.isEngaged(player)

    private fun nextKit(current: KitId): KitId {
        val all = kits.all()
        val index = all.indexOfFirst { it.id == current }
        return all[(index + 1).mod(all.size)].id
    }

    private fun teamsValid(draft: Draft, size: Int): Boolean = draft.layout.teamCount?.let { size >= it } ?: true

    private fun assignedTeam(draft: Draft, playerId: UUID): Int? {
        val order = listOf(draft.hostId) + draft.selected
        return draft.layout.teamCount?.let { teamCount -> order.indexOf(playerId).takeIf { it >= 0 }?.let { it % teamCount + 1 } }
    }

    private fun assignedTeam(lobby: Lobby, playerId: UUID): Int? =
        lobby.layout.teamCount?.let { lobby.participantIds.indexOf(playerId) % it + 1 }

    private fun openState(player: Player, selected: Boolean): Component =
        locales.component(player, if (selected) "multiplayer.menu.selected" else "multiplayer.menu.not-selected")

    private fun playerHead(
        player: Player,
        target: Player,
        selected: Boolean,
        team: Int?,
        interactive: Boolean,
    ): ItemStack =
        ItemStack(Material.PLAYER_HEAD).apply {
            itemMeta = (itemMeta as SkullMeta).apply {
                owningPlayer = target
                displayName(nonItalic(locales.component(player, "multiplayer.menu.player", LocaleService.text("player", target.name))))
                lore(
                    locales.lines(
                        player,
                        if (interactive) "multiplayer.menu.player-lore" else "multiplayer.menu.player-status-lore",
                        LocaleService.component("state", openState(player, selected)),
                        LocaleService.text("team", team?.toString() ?: "—"),
                    ).map(::nonItalic),
                )
            }
        }

    private fun kitButton(
        player: Player,
        kit: DuelKit,
        policy: MultiplayerKitPolicy,
        interactive: Boolean,
    ): ItemStack =
        style(
            ItemStack(kit.icon),
            kit.localizedName(player, locales),
            locales.lines(
                player,
                when {
                    policy == MultiplayerKitPolicy.PER_PLAYER && interactive -> "multiplayer.menu.personal-kit-lore"
                    policy == MultiplayerKitPolicy.PER_PLAYER -> "multiplayer.menu.personal-kit-readonly-lore"
                    interactive -> "multiplayer.menu.shared-kit-lore"
                    else -> "multiplayer.menu.shared-kit-readonly-lore"
                },
            ) + kit.contentLore(player, locales),
        )

    private fun create(holder: MultiplayerHolder, title: Component): Inventory =
        Bukkit.createInventory(holder, MENU_SIZE, nonItalic(title)).also(holder::attach)

    private fun decorate(inventory: Inventory) {
        val filler = style(guiItems.create("background", Material.GRAY_STAINED_GLASS_PANE), Component.text(" "), emptyList())
        repeat(inventory.size) { inventory.setItem(it, filler) }
        CONTENT_SLOTS.forEach { inventory.setItem(it, null) }
    }

    private fun item(player: Player, material: Material, nameKey: String, loreKey: String? = null, vararg values: LocaleValue): ItemStack =
        style(ItemStack(material), locales.component(player, nameKey, *values), loreKey?.let { locales.lines(player, it, *values) }.orEmpty())

    private fun roleItem(
        player: Player,
        role: String,
        fallback: Material,
        nameKey: String,
        loreKey: String? = null,
        values: Array<out LocaleValue> = emptyArray(),
    ): ItemStack =
        style(
            guiItems.create(role, fallback),
            locales.component(player, nameKey, *values),
            loreKey?.let { locales.lines(player, it, *values) }.orEmpty(),
        )

    private fun style(stack: ItemStack, name: Component, lore: List<Component>): ItemStack = stack.apply {
        itemMeta = itemMeta.apply {
            displayName(nonItalic(name))
            lore(lore.map(::nonItalic))
            addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
    }

    private fun nonItalic(component: Component): Component = component.decoration(TextDecoration.ITALIC, false)
    private fun value(player: Player, key: String): LocaleValue = LocaleService.component("value", locales.component(player, key))
    private fun layoutKey(layout: MultiplayerLayout): String = "multiplayer.layout.${layout.name.lowercase()}"
    private fun policyKey(policy: MultiplayerKitPolicy): String = "multiplayer.kit-policy.${policy.name.lowercase()}"
    private fun layoutMaterial(layout: MultiplayerLayout): Material = when (layout) {
        MultiplayerLayout.FREE_FOR_ALL -> Material.TNT
        MultiplayerLayout.TWO_TEAMS -> Material.RED_WOOL
        MultiplayerLayout.THREE_TEAMS -> Material.YELLOW_WOOL
    }
    private fun policyMaterial(policy: MultiplayerKitPolicy): Material =
        if (policy == MultiplayerKitPolicy.SHARED) Material.CHEST else Material.BUNDLE

    private abstract class MultiplayerHolder : InventoryHolder {
        private lateinit var inventory: Inventory
        fun attach(inventory: Inventory) { this.inventory = inventory }
        override fun getInventory(): Inventory = inventory
    }
    private class SetupHolder(
        val hostId: UUID,
        val page: Int,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
    ) : MultiplayerHolder() {
        val players = mutableMapOf<Int, UUID>()
    }
    private class LobbyHolder(val lobbyId: UUID) : MultiplayerHolder()

    private companion object {
        const val MENU_SIZE = 45
        const val INVITE_TIMEOUT_TICKS = 900L
        val CONTENT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 30, 32, 34, 36)
        val PLAYER_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22)
        val PARTICIPANT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23)
    }
}
