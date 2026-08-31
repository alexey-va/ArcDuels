package ru.ruscrafting.duels.paper

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.network.BackendTransferResult
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MAX_MULTIPLAYER_PARTICIPANTS
import ru.ruscrafting.duels.domain.MIN_MULTIPLAYER_PARTICIPANTS
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.MultiplayerParticipant
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.MultiplayerRules
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.redis.CrossServerGroupBus
import ru.ruscrafting.duels.redis.CrossServerGroupMessage
import ru.ruscrafting.duels.redis.GroupLobbyMessageType
import ru.ruscrafting.duels.redis.GroupLobbyResponse
import ru.ruscrafting.duels.redis.NetworkGroupParticipant
import java.time.Clock
import java.util.UUID

internal interface MultiplayerInvitationActions {
    fun openInvitation(player: Player, lobbyId: UUID)
    fun declineInvitation(player: Player, lobbyId: UUID)
}

/** Multiplayer setup, chat invitation, kit selection, and readiness flow. */
internal class MultiplayerGuiService(
    private val plugin: JavaPlugin,
    private val kits: KitRegistry,
    private val sessions: MultiplayerSessionManager,
    private val duelSessions: DuelSessionManager,
    private val locales: LocaleService,
    private val tasks: LifecycleTaskScope,
    private val targets: DuelTargetDirectory,
    private val localServer: ServerId,
    private val serverNames: ServerDisplayNames,
    private val groupBus: CrossServerGroupBus? = null,
    private val transfer: PlayerTransfer? = null,
    private val playerDataReady: (Player) -> Boolean = { true },
    private val clock: Clock = Clock.systemUTC(),
    private val runtimeSettings: () -> ArcDuelsRuntimeSettings? = { null },
    private val guiItems: GuiItemCatalog = GuiItemCatalog.load(plugin),
    private val backAction: (Player) -> Unit,
) : Listener, AutoCloseable, MultiplayerInvitationActions {
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
        val host: NetworkGroupParticipant,
        val members: List<NetworkGroupParticipant>,
        val layout: MultiplayerLayout,
        val kitPolicy: MultiplayerKitPolicy,
        val sharedKit: KitId?,
        val acceptedKits: MutableMap<UUID, KitId>,
        val expiresAtEpochMillis: Long,
        val ready: MutableSet<UUID> = mutableSetOf(),
        var phase: LobbyPhase = LobbyPhase.INVITING,
    ) {
        val hostId: UUID get() = host.playerId.value
        val participants: List<NetworkGroupParticipant> get() = listOf(host) + members
        val participantIds: List<UUID> get() = participants.map { it.playerId.value }
        val networked: Boolean get() = participants.any { it.originServer != host.originServer }
        fun accepted(playerId: UUID): Boolean = playerId in acceptedKits
    }

    private data class RemoteInvite(
        val message: CrossServerGroupMessage,
        var kitId: KitId,
        var accepted: Boolean = false,
        var preparing: Boolean = false,
    )

    private enum class LobbyPhase { INVITING, PREPARING, STARTING }

    private val drafts = mutableMapOf<UUID, Draft>()
    private val lobbies = mutableMapOf<UUID, Lobby>()
    private val lobbyByPlayer = mutableMapOf<UUID, UUID>()
    private val provisionalKits = mutableMapOf<UUID, KitId>()
    private val remoteInvites = mutableMapOf<UUID, RemoteInvite>()
    private val preparationInFlight = mutableSetOf<Pair<UUID, UUID>>()
    private val preparationRetryScheduled = mutableSetOf<Pair<UUID, UUID>>()
    private val networkSubscription = groupBus?.subscribe { message -> tasks.runSync { onNetworkMessage(message) } }

    internal fun activeFlowCount(): Int {
        val openDrafts =
            drafts.keys.count { playerId ->
                plugin.server.getPlayer(playerId)?.openInventory?.topInventory?.holder is SetupHolder
            }
        return openDrafts + lobbies.size + remoteInvites.size + preparationInFlight.size + preparationRetryScheduled.size
    }

    fun open(player: Player) {
        if (busy(player)) {
            player.sendMessage(locales.notice(player, "multiplayer.busy"))
            return
        }
        remoteInvites[player.uniqueId]?.let { return openRemoteLobby(player, it) }
        lobbyByPlayer[player.uniqueId]?.let(lobbies::get)?.let { return openLobby(player, it) }
        val availableKits = kits.all()
        val firstKit = availableKits.firstOrNull()?.id ?: run {
            player.sendMessage(locales.notice(player, "multiplayer.no-kits"))
            return
        }
        val settings = runtimeSettings()
        val defaultKit = kits.defaultId().takeIf(kits::contains) ?: firstKit
        val draft =
            drafts.getOrPut(player.uniqueId) {
                Draft(
                    hostId = player.uniqueId,
                    layout = settings?.multiplayerDefaultLayout ?: MultiplayerLayout.FREE_FOR_ALL,
                    kitPolicy = settings?.multiplayerDefaultKitPolicy ?: MultiplayerKitPolicy.SHARED,
                    hostKit = defaultKit,
                    sharedKit = defaultKit,
                )
            }
        val available = availableKits.mapTo(mutableSetOf(), DuelKit::id)
        if (draft.hostKit !in available) draft.hostKit = defaultKit
        if (draft.sharedKit !in available) draft.sharedKit = defaultKit
        openSetup(player, draft)
    }

    override fun openInvitation(player: Player, lobbyId: UUID) {
        val localLobby = lobbyByPlayer[player.uniqueId]?.let(lobbies::get)
        if (localLobby?.id == lobbyId && localLobby.phase == LobbyPhase.INVITING && player.uniqueId != localLobby.hostId) {
            if (expireLocalLobbyIfNeeded(localLobby)) return
            openLobby(player, localLobby)
            return
        }
        val remote = remoteInvites[player.uniqueId]
        if (remote?.message?.lobbyId == lobbyId && !remote.preparing) {
            if (expireRemoteInviteIfNeeded(player, remote)) return
            openRemoteLobby(player, remote)
            return
        }
        player.sendMessage(locales.notice(player, "multiplayer.invite-unavailable"))
    }

    override fun declineInvitation(player: Player, lobbyId: UUID) {
        val localLobby = lobbyByPlayer[player.uniqueId]?.let(lobbies::get)
        if (localLobby?.id == lobbyId && localLobby.phase == LobbyPhase.INVITING && player.uniqueId != localLobby.hostId) {
            if (expireLocalLobbyIfNeeded(localLobby)) return
            cancelLobby(localLobby, "multiplayer.declined")
            return
        }
        val remote = remoteInvites[player.uniqueId]
        if (remote?.message?.lobbyId == lobbyId && !remote.preparing) {
            if (expireRemoteInviteIfNeeded(player, remote)) return
            if (!publishResponse(remote.message, player.uniqueId, GroupLobbyResponse.DECLINED, null)) {
                player.sendMessage(locales.notice(player, "multiplayer.network-unavailable"))
                return
            }
            remoteInvites.remove(player.uniqueId, remote)
            closeLobbyInventory(player, lobbyId)
            player.sendMessage(locales.notice(player, "multiplayer.declined-self"))
            return
        }
        player.sendMessage(locales.notice(player, "multiplayer.invite-unavailable"))
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
            is RemoteLobbyHolder -> handleRemoteLobbyClick(player, holder, event.rawSlot)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        drafts.remove(event.player.uniqueId)
        remoteInvites[event.player.uniqueId]?.let { invite ->
            if (!invite.preparing) publishResponse(invite.message, event.player.uniqueId, GroupLobbyResponse.DECLINED, null)
            remoteInvites.remove(event.player.uniqueId, invite)
        }
        lobbyByPlayer[event.player.uniqueId]?.let(lobbies::get)?.takeIf { it.phase != LobbyPhase.STARTING }
            ?.let { cancelLobby(it, "multiplayer.player-left") }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        lobbies.values.firstOrNull { it.phase == LobbyPhase.PREPARING && event.player.uniqueId in it.participantIds }
            ?.let(::scheduleNetworkStartCheck)
    }

    private fun openSetup(player: Player, draft: Draft) {
        val available = targets.players()
            .filter { target ->
                if (target.uniqueId == player.uniqueId || target.uniqueId in lobbyByPlayer) return@filter false
                val local = plugin.server.getPlayer(target.uniqueId) ?: return@filter true
                !busy(local)
            }
        val page = pageWindow(available, draft.page, PLAYER_SLOTS.size)
        draft.page = page.index
        val shown = page.items
        val holder = SetupHolder(draft.hostId, page.index, page.hasPrevious, page.hasNext)
        val inventory = create(holder, locales.component(player, "multiplayer.menu.setup-title"))
        decorate(inventory)
        shown.forEachIndexed { index, target ->
            val slot = PLAYER_SLOTS[index]
            holder.players[slot] = target.uniqueId
            inventory.setItem(
                slot,
                playerHead(
                    player,
                    NetworkGroupParticipant(PlayerId(target.uniqueId), target.name, target.server),
                    target.uniqueId in draft.selected,
                    assignedTeam(draft, target.uniqueId),
                    interactive = true,
                ),
            )
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
        if (page.totalPages > 1) {
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
        }
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
        val invitationTimeoutMillis =
            runtimeSettings()?.multiplayerInvitationTimeout?.toMillis()
                ?: INVITE_TIMEOUT_TICKS * MILLIS_PER_TICK
        val selected = draft.selected.mapNotNull(targets::find)
        if (selected.size != draft.selected.size) {
            draft.selected.retainAll(selected.mapTo(mutableSetOf(), DuelTarget::uniqueId))
            host.sendMessage(locales.notice(host, "multiplayer.player-left"))
            return openSetup(host, draft)
        }
        val total = draft.selected.size + 1
        if (total !in MIN_MULTIPLAYER_PARTICIPANTS..MAX_MULTIPLAYER_PARTICIPANTS || !teamsValid(draft, total)) {
            host.sendMessage(locales.notice(host, "multiplayer.invalid-size"))
            return openSetup(host, draft)
        }
        val memberProfiles = selected.map { target ->
            val localPlayer = plugin.server.getPlayer(target.uniqueId)?.takeIf(Player::isOnline)
            NetworkGroupParticipant(
                PlayerId(target.uniqueId),
                localPlayer?.name ?: target.name,
                if (localPlayer != null) localServer else target.server,
            )
        }
        val localMembers = memberProfiles
            .filter { it.originServer == localServer }
            .mapNotNull { participant -> plugin.server.getPlayer(participant.playerId.value) }
        if (localMembers.any(::busy) || selected.any { it.uniqueId in lobbyByPlayer }) {
            host.sendMessage(locales.notice(host, "multiplayer.player-left"))
            return openSetup(host, draft)
        }
        if (memberProfiles.any { it.originServer != localServer } && (groupBus == null || transfer == null)) {
            host.sendMessage(locales.notice(host, "multiplayer.network-unavailable"))
            return openSetup(host, draft)
        }
        val hostProfile = NetworkGroupParticipant(PlayerId(host.uniqueId), host.name, localServer)
        val lobby = Lobby(
            id = UUID.randomUUID(),
            host = hostProfile,
            members = memberProfiles,
            layout = draft.layout,
            kitPolicy = draft.kitPolicy,
            sharedKit = draft.sharedKit.takeIf { draft.kitPolicy == MultiplayerKitPolicy.SHARED },
            acceptedKits = mutableMapOf(host.uniqueId to if (draft.kitPolicy == MultiplayerKitPolicy.SHARED) draft.sharedKit else draft.hostKit),
            expiresAtEpochMillis = clock.millis() + invitationTimeoutMillis,
        )
        lobbies[lobby.id] = lobby
        lobby.participantIds.forEach { lobbyByPlayer[it] = lobby.id }
        drafts.remove(host.uniqueId)
        localMembers.forEach { member ->
            sendInvitation(member, lobby.id, host.name, lobby.participantIds.size, lobby.layout)
        }
        DuelLog.info(
            "multiplayer-lobby-created",
            MatchId(lobby.id),
            "participants={} network={} layout={} kit_policy={}",
            lobby.participantIds.size,
            lobby.networked,
            lobby.layout,
            lobby.kitPolicy,
        )
        val offersPublished = lobby.members.filter { it.originServer != localServer }.all { member ->
            publish(groupMessage(lobby, GroupLobbyMessageType.OFFER, targetId = member.playerId))
        }
        if (!offersPublished) {
            cancelLobby(lobby, "multiplayer.network-unavailable")
            return
        }
        openLobby(host, lobby)
        scheduleLocalLobbyExpiry(lobby)
    }

    private fun sendInvitation(
        player: Player,
        lobbyId: UUID,
        hostName: String,
        participantCount: Int,
        layout: MultiplayerLayout,
    ) {
        val summary =
            locales.component(
                player,
                "multiplayer.invited",
                LocaleService.text("player", hostName),
                LocaleService.text("players", participantCount),
                LocaleService.component("layout", locales.component(player, layoutKey(layout))),
            )
        val open =
            locales.component(player, "multiplayer.invite-open")
                .clickEvent(ClickEvent.runCommand("/duel group open $lobbyId"))
                .hoverEvent(HoverEvent.showText(locales.component(player, "multiplayer.invite-open-hover")))
        val decline =
            locales.component(player, "multiplayer.invite-decline")
                .clickEvent(ClickEvent.runCommand("/duel group decline $lobbyId"))
                .hoverEvent(HoverEvent.showText(locales.component(player, "multiplayer.invite-decline-hover")))
        player.sendMessage(
            locales.frameNotice(
                player,
                summary.append(Component.newline()).append(open).append(Component.newline()).append(decline),
            ),
        )
    }

    private fun openLobby(player: Player, lobby: Lobby) {
        if (expireLocalLobbyIfNeeded(lobby)) return
        val holder = LobbyHolder(lobby.id)
        val inventory = create(holder, locales.component(player, "multiplayer.menu.lobby-title"))
        decorate(inventory)
        lobby.participants.forEachIndexed { index, participant ->
            val playerId = participant.playerId.value
            val slot = PARTICIPANT_SLOTS[index]
            inventory.setItem(
                slot,
                playerHead(player, participant, lobby.accepted(playerId), assignedTeam(lobby, playerId), interactive = false),
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
        if (expireLocalLobbyIfNeeded(lobby)) return
        when (slot) {
            32 -> if (lobby.kitPolicy == MultiplayerKitPolicy.PER_PLAYER && player.uniqueId != lobby.hostId && !lobby.accepted(player.uniqueId)) {
                provisionalKits[player.uniqueId] = nextKit(provisionalKits[player.uniqueId] ?: kits.all().first().id)
                openLobby(player, lobby)
            }
            34 -> if (player.uniqueId != lobby.hostId && !lobby.accepted(player.uniqueId)) {
                lobby.acceptedKits[player.uniqueId] = lobby.sharedKit ?: provisionalKits.remove(player.uniqueId) ?: kits.all().first().id
                DuelLog.info(
                    "multiplayer-participant-accepted",
                    MatchId(lobby.id),
                    player,
                    "ready={}/{}",
                    lobby.acceptedKits.size,
                    lobby.participantIds.size,
                )
                if (lobby.acceptedKits.size == lobby.participantIds.size) prepareOrStartLobby(lobby) else {
                    openLobby(player, lobby)
                    plugin.server.getPlayer(lobby.hostId)?.let { openLobby(it, lobby) }
                }
            }
            36 -> cancelLobby(lobby, if (player.uniqueId == lobby.hostId) "multiplayer.cancelled" else "multiplayer.declined")
        }
    }

    private fun prepareOrStartLobby(lobby: Lobby) {
        if (lobby.networked) return beginNetworkPreparation(lobby)
        startLocalLobby(lobby)
    }

    private fun startLocalLobby(lobby: Lobby) {
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
        online.forEach { closeLobbyInventory(it, lobby.id) }
        DuelLog.info("multiplayer-start-attempt", MatchId(lobby.id), "network=false participants={}", online.size)
        sessions.start(roster, online.associateBy { PlayerId(it.uniqueId) }).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) {
                DuelLog.warn(
                    "multiplayer-start-failed",
                    MatchId(lobby.id),
                    "network=false error_type={} error={}",
                    failure.javaClass.simpleName,
                    failure.message,
                )
                online.filter(Player::isOnline).forEach { player ->
                    player.sendMessage(locales.notice(player, "multiplayer.start-failed"))
                }
            } else {
                DuelLog.info("multiplayer-started", MatchId(lobby.id), "network=false participants={}", online.size)
            }
        }
    }

    private fun cancelLobby(lobby: Lobby, key: String, publishNetwork: Boolean = true) {
        if (publishNetwork && lobby.networked && groupBus != null) {
            publish(groupMessage(lobby, GroupLobbyMessageType.CANCEL))
        }
        lobby.participantIds.mapNotNull(plugin.server::getPlayer).forEach { player ->
            closeLobbyInventory(player, lobby.id)
            player.sendMessage(locales.notice(player, key))
        }
        removeLobby(lobby)
        if (lobby.phase != LobbyPhase.INVITING) recoverNetworkParticipants(lobby)
    }

    private fun beginNetworkPreparation(lobby: Lobby) {
        if (lobby.phase != LobbyPhase.INVITING) return
        lobby.phase = LobbyPhase.PREPARING
        lobby.participantIds.mapNotNull(plugin.server::getPlayer).forEach { player ->
            closeLobbyInventory(player, lobby.id)
            player.sendMessage(locales.notice(player, "multiplayer.preparing"))
        }
        DuelLog.info("multiplayer-preparing", MatchId(lobby.id), "participants={}", lobby.participantIds.size)
        duelSessions.expectNetworkPlayers(lobby.participants.filter { it.originServer != localServer }.map(NetworkGroupParticipant::playerId))
        if (!publish(groupMessage(lobby, GroupLobbyMessageType.PREPARE))) {
            cancelLobby(lobby, "multiplayer.network-unavailable")
            return
        }
        scheduleNetworkStartCheck(lobby)
    }

    private fun scheduleNetworkStartCheck(lobby: Lobby) {
        if (lobbies[lobby.id] !== lobby || lobby.phase != LobbyPhase.PREPARING) return
        tasks.runLater(NETWORK_START_POLL_TICKS) {
            if (lobbies[lobby.id] !== lobby || lobby.phase != LobbyPhase.PREPARING) return@runLater
            if (clock.millis() >= lobby.expiresAtEpochMillis) {
                cancelLobby(lobby, "multiplayer.expired")
                return@runLater
            }
            val online = lobby.participantIds.mapNotNull(plugin.server::getPlayer)
            if (lobby.ready.size == lobby.participantIds.size && online.size == lobby.participantIds.size && online.all(playerDataReady)) {
                startNetworkLobby(lobby, online)
            } else {
                scheduleNetworkStartCheck(lobby)
            }
        }
    }

    private fun startNetworkLobby(lobby: Lobby, online: List<Player>) {
        if (lobby.phase != LobbyPhase.PREPARING) return
        if (online.any { sessions.isEngaged(it) }) return cancelLobby(lobby, "multiplayer.player-left")
        lobby.phase = LobbyPhase.STARTING
        val roster = roster(lobby)
        val origins = lobby.participants.associate { it.playerId to it.originServer }
        removeLobby(lobby)
        DuelLog.info("multiplayer-start-attempt", MatchId(lobby.id), "network=true participants={}", online.size)
        sessions.startNetwork(MatchId(lobby.id), roster, online.associateBy { PlayerId(it.uniqueId) }, origins)
            .whenCompleteSync(tasks) { _, failure ->
                duelSessions.stopExpectingNetworkPlayers(origins.keys)
                if (failure != null) {
                    DuelLog.warn(
                        "multiplayer-start-failed",
                        MatchId(lobby.id),
                        "network=true error_type={} error={}",
                        failure.javaClass.simpleName,
                        failure.message,
                    )
                    publish(groupMessage(lobby, GroupLobbyMessageType.CANCEL))
                    online.filter(Player::isOnline).forEach { player ->
                        player.sendMessage(locales.notice(player, "multiplayer.start-failed"))
                    }
                    recoverNetworkParticipants(lobby)
                } else {
                    DuelLog.info("multiplayer-started", MatchId(lobby.id), "network=true participants={}", online.size)
                }
            }
    }

    private fun roster(lobby: Lobby): MultiplayerRoster =
        MultiplayerRoster(
            MultiplayerRules(lobby.layout, lobby.kitPolicy, lobby.sharedKit),
            lobby.participantIds.mapIndexed { index, playerId ->
                MultiplayerParticipant(
                    playerId = PlayerId(playerId),
                    team = lobby.layout.teamCount?.let { index % it + 1 },
                    kitId = requireNotNull(lobby.acceptedKits[playerId]),
                )
            },
        )

    private fun recoverNetworkParticipants(lobby: Lobby) {
        duelSessions.stopExpectingNetworkPlayers(lobby.participants.map(NetworkGroupParticipant::playerId))
        lobby.participants.forEach { participant ->
            val player = plugin.server.getPlayer(participant.playerId.value) ?: return@forEach
            if (!duelSessions.requestRecovery(player)) duelSessions.handleJoin(player)
        }
    }

    private fun onNetworkMessage(message: CrossServerGroupMessage) {
        when (message.type) {
            GroupLobbyMessageType.OFFER -> receiveOffer(message)
            GroupLobbyMessageType.RESPONSE -> receiveResponse(message)
            GroupLobbyMessageType.PREPARE -> receivePreparation(message)
            GroupLobbyMessageType.READY -> receiveReady(message)
            GroupLobbyMessageType.CANCEL -> receiveCancellation(message)
        }
    }

    private fun receiveOffer(message: CrossServerGroupMessage) {
        val targetId = requireNotNull(message.targetId)
        if (message.participant(targetId).originServer != localServer) return
        val now = clock.millis()
        if (now >= message.expiresAtEpochMillis || !message.hasAcceptableFutureDeadline(now)) {
            publishResponse(message, targetId.value, GroupLobbyResponse.FAILED, null)
            return
        }
        val player = plugin.server.getPlayer(targetId.value)
        if (player == null || busy(player) || player.uniqueId in lobbyByPlayer || player.uniqueId in remoteInvites) {
            publishResponse(message, targetId.value, GroupLobbyResponse.FAILED, null)
            return
        }
        val availableKits = kits.all()
        val initialKit = message.sharedKitId ?: availableKits.firstOrNull()?.id
        if (initialKit == null || availableKits.none { it.id == initialKit }) {
            DuelLog.warn(
                "multiplayer-offer-rejected",
                MatchId(message.lobbyId),
                player,
                "reason=unknown_kit policy={}",
                message.kitPolicy,
            )
            publishResponse(message, targetId.value, GroupLobbyResponse.FAILED, null)
            player.sendMessage(locales.notice(player, "multiplayer.invite-unavailable"))
            return
        }
        val invite = RemoteInvite(message, initialKit)
        remoteInvites[player.uniqueId] = invite
        sendInvitation(player, message.lobbyId, message.participants.first().name, message.participants.size, message.layout)
        val remainingTicks =
            millisToTicksCeil(message.expiresAtEpochMillis - clock.millis())
                .coerceIn(1L, MAX_INVITE_TIMEOUT_TICKS)
        scheduleRemoteInviteExpiry(player, invite, remainingTicks)
    }

    private fun receiveResponse(message: CrossServerGroupMessage) {
        if (message.hostServer != localServer) return
        val lobby = lobbies[message.lobbyId] ?: return
        if (expireLocalLobbyIfNeeded(lobby)) return
        if (lobby.phase == LobbyPhase.STARTING || !sameLobby(lobby, message)) return
        val targetId = requireNotNull(message.targetId).value
        when (message.response) {
            GroupLobbyResponse.ACCEPTED -> {
                if (lobby.phase != LobbyPhase.INVITING) return
                val kitId = requireNotNull(message.kitId)
                if (kits.all().none { it.id == kitId } || (lobby.sharedKit != null && lobby.sharedKit != kitId)) {
                    cancelLobby(lobby, "multiplayer.player-left")
                    return
                }
                lobby.acceptedKits[targetId] = kitId
                DuelLog.info(
                    "multiplayer-participant-accepted",
                    MatchId(lobby.id),
                    "ready={}/{} remote=true",
                    lobby.acceptedKits.size,
                    lobby.participantIds.size,
                )
                plugin.server.getPlayer(lobby.hostId)?.let { openLobby(it, lobby) }
                if (lobby.acceptedKits.size == lobby.participantIds.size) prepareOrStartLobby(lobby)
            }
            GroupLobbyResponse.DECLINED -> if (lobby.phase == LobbyPhase.INVITING) cancelLobby(lobby, "multiplayer.declined")
            GroupLobbyResponse.FAILED -> cancelLobby(lobby, "multiplayer.player-left")
            null -> Unit
        }
    }

    private fun receivePreparation(message: CrossServerGroupMessage) {
        val now = clock.millis()
        if (now >= message.expiresAtEpochMillis) return
        val localParticipants = message.participants.filter { it.originServer == localServer }
        if (localParticipants.isEmpty()) return
        if (!message.hasAcceptableFutureDeadline(now)) {
            localParticipants.forEach { participant ->
                plugin.server.getPlayer(participant.playerId.value)?.let {
                    publishResponse(message, participant.playerId.value, GroupLobbyResponse.FAILED, null)
                }
            }
            return
        }
        val authorized =
            if (localServer == message.hostServer) {
                lobbies[message.lobbyId]?.takeIf { lobby -> lobby.phase == LobbyPhase.PREPARING && sameLobby(lobby, message) } != null
            } else {
                localParticipants.all { participant ->
                    remoteInvites[participant.playerId.value]
                        ?.takeIf(RemoteInvite::accepted)
                        ?.message
                        ?.let { offer -> sameLobby(offer, message) } == true
                }
            }
        if (!authorized) {
            localParticipants.forEach { participant ->
                plugin.server.getPlayer(participant.playerId.value)?.let {
                    publishResponse(message, participant.playerId.value, GroupLobbyResponse.FAILED, null)
                }
            }
            return
        }
        localParticipants.forEach { participant ->
            val player = plugin.server.getPlayer(participant.playerId.value)
            if (player == null) {
                publishResponse(message, participant.playerId.value, GroupLobbyResponse.FAILED, null)
                return@forEach
            }
            val key = message.lobbyId to player.uniqueId
            remoteInvites[player.uniqueId]?.let { invite ->
                if (!invite.accepted) {
                    publishResponse(message, participant.playerId.value, GroupLobbyResponse.FAILED, null)
                    return@forEach
                }
                if (!invite.preparing) {
                    invite.preparing = true
                    closeLobbyInventory(player, message.lobbyId)
                    if (localServer != message.hostServer) player.sendMessage(locales.notice(player, "multiplayer.preparing"))
                }
            }
            if (!playerDataReady(player)) {
                if (preparationRetryScheduled.add(key)) {
                    tasks.runLater(NETWORK_START_POLL_TICKS) {
                        preparationRetryScheduled.remove(key)
                        if (clock.millis() < message.expiresAtEpochMillis) receivePreparation(message)
                    }
                }
                return@forEach
            }
            if (!preparationInFlight.add(key)) return@forEach
            duelSessions.storeOriginSnapshot(MatchId(message.lobbyId), player, inventoryReplaced = true)
                .whenCompleteSync(tasks) { _, failure ->
                    preparationInFlight.remove(key)
                    if (failure != null) {
                        DuelLog.warn(
                            "multiplayer-origin-snapshot-failed",
                            MatchId(message.lobbyId),
                            player,
                            "error_type={} error={}",
                            failure.javaClass.simpleName,
                            failure.message,
                        )
                        publishResponse(message, player.uniqueId, GroupLobbyResponse.FAILED, null)
                        return@whenCompleteSync
                    }
                    if (!preparationStillCurrent(message, player)) {
                        recoverLatePreparation(message, player)
                        return@whenCompleteSync
                    }
                    val readyPublished = publish(
                        message.copy(
                            messageId = "${message.lobbyId}:ready:${player.uniqueId}:${localServer.value}",
                            sourceServer = localServer,
                            type = GroupLobbyMessageType.READY,
                            targetId = PlayerId(player.uniqueId),
                        ),
                    )
                    if (!readyPublished) {
                        failPreparationAfterPublish(message, player)
                        return@whenCompleteSync
                    }
                    if (!preparationStillCurrent(message, player)) {
                        recoverLatePreparation(message, player)
                        return@whenCompleteSync
                    }
                    if (localServer != message.hostServer) {
                        val transferResult = transfer?.connect(player, message.hostServer)
                        if (transferResult != BackendTransferResult.SENT) {
                            DuelLog.warn(
                                "multiplayer-transfer-failed",
                                MatchId(message.lobbyId),
                                player,
                                "result={}",
                                transferResult?.name ?: "UNAVAILABLE",
                            )
                            publishResponse(message, player.uniqueId, GroupLobbyResponse.FAILED, null)
                            failPreparationAfterPublish(message, player)
                        }
                    }
                }
        }
    }

    private fun receiveReady(message: CrossServerGroupMessage) {
        if (message.hostServer != localServer) return
        val lobby = lobbies[message.lobbyId] ?: return
        if (expireLocalLobbyIfNeeded(lobby)) return
        if (lobby.phase != LobbyPhase.PREPARING || !sameLobby(lobby, message)) return
        lobby.ready += requireNotNull(message.targetId).value
        DuelLog.debug(
            "multiplayer-participant-ready",
            MatchId(lobby.id),
            "ready={}/{}",
            lobby.ready.size,
            lobby.participantIds.size,
        )
    }

    private fun receiveCancellation(message: CrossServerGroupMessage) {
        if (message.sourceServer == localServer) return
        lobbies[message.lobbyId]
            ?.takeIf { lobby -> sameLobby(lobby, message) }
            ?.let { cancelLobby(it, "multiplayer.cancelled", publishNetwork = false) }
        val affected = remoteInvites.filterValues { invite -> sameLobby(invite.message, message) }
        affected.forEach { (playerId, invite) ->
            remoteInvites.remove(playerId, invite)
            plugin.server.getPlayer(playerId)?.let { player ->
                closeLobbyInventory(player, message.lobbyId)
                player.sendMessage(locales.notice(player, "multiplayer.cancelled"))
                if (invite.preparing && !duelSessions.requestRecovery(player)) duelSessions.handleJoin(player)
            }
        }
    }

    private fun openRemoteLobby(player: Player, invite: RemoteInvite) {
        if (expireRemoteInviteIfNeeded(player, invite)) return
        val message = invite.message
        val selectedKit = kits.all().firstOrNull { it.id == invite.kitId }
        if (selectedKit == null) {
            if (remoteInvites.remove(player.uniqueId, invite)) {
                publishResponse(message, player.uniqueId, GroupLobbyResponse.FAILED, null)
                closeLobbyInventory(player, message.lobbyId)
                player.sendMessage(locales.notice(player, "multiplayer.invite-unavailable"))
            }
            return
        }
        val inventory = create(RemoteLobbyHolder(message.lobbyId), locales.component(player, "multiplayer.menu.lobby-title"))
        decorate(inventory)
        message.participants.forEachIndexed { index, participant ->
            inventory.setItem(
                PARTICIPANT_SLOTS[index],
                playerHead(
                    player,
                    participant,
                    selected = participant.playerId == message.hostId || (participant.playerId.value == player.uniqueId && invite.accepted),
                    team = message.layout.teamCount?.let { index % it + 1 },
                    interactive = false,
                ),
            )
        }
        inventory.setItem(28, item(player, layoutMaterial(message.layout), "multiplayer.menu.layout", "multiplayer.menu.readonly-lore", value(player, layoutKey(message.layout))))
        inventory.setItem(30, item(player, policyMaterial(message.kitPolicy), "multiplayer.menu.kit-policy", "multiplayer.menu.readonly-lore", value(player, policyKey(message.kitPolicy))))
        inventory.setItem(32, kitButton(player, selectedKit, message.kitPolicy, interactive = message.kitPolicy == MultiplayerKitPolicy.PER_PLAYER && !invite.accepted))
        inventory.setItem(34, item(player, if (invite.accepted) Material.LIME_DYE else Material.LIME_CONCRETE, if (invite.accepted) "multiplayer.menu.accepted" else "multiplayer.menu.accept", if (invite.accepted) "multiplayer.menu.accepted-lore" else "multiplayer.menu.accept-lore"))
        inventory.setItem(36, item(player, Material.RED_CONCRETE, "multiplayer.menu.decline", "multiplayer.menu.cancel-lore"))
        player.openInventory(inventory)
    }

    private fun handleRemoteLobbyClick(player: Player, holder: RemoteLobbyHolder, slot: Int) {
        val invite = remoteInvites[player.uniqueId]?.takeIf { it.message.lobbyId == holder.lobbyId } ?: return open(player)
        if (expireRemoteInviteIfNeeded(player, invite)) return
        when (slot) {
            32 -> if (invite.message.kitPolicy == MultiplayerKitPolicy.PER_PLAYER && !invite.accepted) {
                invite.kitId = nextKit(invite.kitId)
                openRemoteLobby(player, invite)
            }
            34 -> if (!invite.accepted) {
                if (publishResponse(invite.message, player.uniqueId, GroupLobbyResponse.ACCEPTED, invite.kitId)) {
                    invite.accepted = true
                    openRemoteLobby(player, invite)
                } else {
                    player.sendMessage(locales.notice(player, "multiplayer.network-unavailable"))
                }
            }
            36 -> {
                if (!publishResponse(invite.message, player.uniqueId, GroupLobbyResponse.DECLINED, null)) {
                    player.sendMessage(locales.notice(player, "multiplayer.network-unavailable"))
                    return
                }
                remoteInvites.remove(player.uniqueId, invite)
                closeLobbyInventory(player, invite.message.lobbyId)
                player.sendMessage(locales.notice(player, "multiplayer.declined-self"))
            }
        }
    }

    private fun groupMessage(
        lobby: Lobby,
        type: GroupLobbyMessageType,
        targetId: PlayerId? = null,
    ): CrossServerGroupMessage =
        CrossServerGroupMessage(
            messageId = "${lobby.id}:${type.name.lowercase()}:${targetId?.value ?: localServer.value}",
            sourceServer = localServer,
            type = type,
            lobbyId = lobby.id,
            hostId = lobby.host.playerId,
            hostServer = localServer,
            participants = lobby.participants,
            layout = lobby.layout,
            kitPolicy = lobby.kitPolicy,
            sharedKitId = lobby.sharedKit,
            expiresAtEpochMillis = lobby.expiresAtEpochMillis,
            targetId = targetId,
        )

    private fun publishResponse(
        source: CrossServerGroupMessage,
        playerId: UUID,
        response: GroupLobbyResponse,
        kitId: KitId?,
    ): Boolean =
        publish(
            source.copy(
                messageId = "${source.lobbyId}:response:${playerId}:${response.name.lowercase()}",
                sourceServer = localServer,
                type = GroupLobbyMessageType.RESPONSE,
                targetId = PlayerId(playerId),
                response = response,
                kitId = kitId,
            ),
        )

    private fun failPreparationAfterPublish(
        message: CrossServerGroupMessage,
        player: Player,
    ) {
        if (localServer == message.hostServer) {
            lobbies[message.lobbyId]
                ?.takeIf { lobby -> sameLobby(lobby, message) }
                ?.let { lobby -> cancelLobby(lobby, "multiplayer.network-unavailable") }
            return
        }
        val invite = remoteInvites[player.uniqueId]
            ?.takeIf { candidate -> sameLobby(candidate.message, message) }
            ?: return
        if (!remoteInvites.remove(player.uniqueId, invite)) return
        closeLobbyInventory(player, message.lobbyId)
        player.sendMessage(locales.notice(player, "multiplayer.network-unavailable"))
        if (!duelSessions.requestRecovery(player)) duelSessions.handleJoin(player)
    }

    private fun preparationStillCurrent(
        message: CrossServerGroupMessage,
        player: Player,
    ): Boolean {
        val now = clock.millis()
        if (!player.isOnline || now >= message.expiresAtEpochMillis || !message.hasAcceptableFutureDeadline(now)) return false
        return if (localServer == message.hostServer) {
            lobbies[message.lobbyId]
                ?.takeIf { lobby -> lobby.phase == LobbyPhase.PREPARING }
                ?.let { lobby -> sameLobby(lobby, message) } == true
        } else {
            remoteInvites[player.uniqueId]
                ?.takeIf { invite -> invite.accepted && invite.preparing }
                ?.let { invite -> sameLobby(invite.message, message) } == true
        }
    }

    private fun recoverLatePreparation(
        message: CrossServerGroupMessage,
        player: Player,
    ) {
        if (!player.isOnline) return
        if (clock.millis() >= message.expiresAtEpochMillis) {
            if (localServer == message.hostServer) {
                val lobby =
                    lobbies[message.lobbyId]
                        ?.takeIf { candidate -> candidate.phase == LobbyPhase.PREPARING && sameLobby(candidate, message) }
                if (lobby != null) {
                    cancelLobby(lobby, "multiplayer.expired")
                    return
                }
            } else {
                val invite =
                    remoteInvites[player.uniqueId]
                        ?.takeIf { candidate -> sameLobby(candidate.message, message) }
                if (invite != null && expireRemoteInviteIfNeeded(player, invite)) return
            }
        }
        if (!duelSessions.requestRecovery(player)) duelSessions.handleJoin(player)
    }

    private fun publish(message: CrossServerGroupMessage): Boolean =
        runCatching {
            requireNotNull(groupBus).publish(message)
            true
        }.getOrElse { failure ->
            plugin.logger.warning("Could not publish ArcDuels group message ${message.messageId}: ${failure.message}")
            false
        }

    private fun sameLobby(lobby: Lobby, message: CrossServerGroupMessage): Boolean =
        lobby.id == message.lobbyId &&
            lobby.host.playerId == message.hostId &&
            lobby.host.originServer == message.hostServer &&
            lobby.participants == message.participants &&
            lobby.layout == message.layout &&
            lobby.kitPolicy == message.kitPolicy &&
            lobby.sharedKit == message.sharedKitId &&
            lobby.expiresAtEpochMillis == message.expiresAtEpochMillis

    private fun sameLobby(offer: CrossServerGroupMessage, message: CrossServerGroupMessage): Boolean =
        offer.lobbyId == message.lobbyId &&
            offer.hostId == message.hostId &&
            offer.hostServer == message.hostServer &&
            offer.participants == message.participants &&
            offer.layout == message.layout &&
            offer.kitPolicy == message.kitPolicy &&
            offer.sharedKitId == message.sharedKitId &&
            offer.expiresAtEpochMillis == message.expiresAtEpochMillis

    private fun removeLobby(lobby: Lobby) {
        lobbies.remove(lobby.id, lobby)
        preparationInFlight.removeIf { (lobbyId, _) -> lobbyId == lobby.id }
        preparationRetryScheduled.removeIf { (lobbyId, _) -> lobbyId == lobby.id }
        lobby.participantIds.forEach { playerId ->
            lobbyByPlayer.remove(playerId, lobby.id)
            provisionalKits.remove(playerId)
        }
    }

    private fun closeLobbyInventory(player: Player, lobbyId: UUID) {
        val topInventory = runCatching { player.openInventory.topInventory }.getOrNull() ?: return
        val holder = topInventory.holder
        val belongsToLobby =
            when (holder) {
                is LobbyHolder -> holder.lobbyId == lobbyId
                is RemoteLobbyHolder -> holder.lobbyId == lobbyId
                else -> false
            }
        if (belongsToLobby) player.closeInventory()
    }

    private fun expireLocalLobbyIfNeeded(lobby: Lobby): Boolean {
        if (clock.millis() < lobby.expiresAtEpochMillis) return false
        if (lobbies[lobby.id] === lobby) cancelLobby(lobby, "multiplayer.expired")
        return true
    }

    /** Scheduler callbacks are cleanup hints; wall time remains the expiry authority. */
    private fun scheduleLocalLobbyExpiry(lobby: Lobby) {
        val remainingTicks =
            millisToTicksCeil(lobby.expiresAtEpochMillis - clock.millis())
                .coerceIn(1L, MAX_INVITE_TIMEOUT_TICKS)
        tasks.runLater(remainingTicks) {
            if (lobbies[lobby.id] !== lobby) return@runLater
            if (!expireLocalLobbyIfNeeded(lobby)) scheduleLocalLobbyExpiry(lobby)
        }
    }

    private fun expireRemoteInviteIfNeeded(player: Player, invite: RemoteInvite): Boolean {
        if (clock.millis() < invite.message.expiresAtEpochMillis) return false
        if (remoteInvites.remove(player.uniqueId, invite)) {
            publishResponse(invite.message, player.uniqueId, GroupLobbyResponse.FAILED, null)
            closeLobbyInventory(player, invite.message.lobbyId)
            player.sendMessage(locales.notice(player, "multiplayer.expired"))
            if (invite.preparing && !duelSessions.requestRecovery(player)) duelSessions.handleJoin(player)
        }
        return true
    }

    private fun scheduleRemoteInviteExpiry(
        player: Player,
        invite: RemoteInvite,
        delayTicks: Long = millisToTicksCeil(invite.message.expiresAtEpochMillis - clock.millis()),
    ) {
        tasks.runLater(delayTicks.coerceIn(1L, MAX_INVITE_TIMEOUT_TICKS)) {
            if (remoteInvites[player.uniqueId] !== invite) return@runLater
            if (!expireRemoteInviteIfNeeded(player, invite)) scheduleRemoteInviteExpiry(player, invite)
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
        target: NetworkGroupParticipant,
        selected: Boolean,
        team: Int?,
        interactive: Boolean,
    ): ItemStack =
        ItemStack(Material.PLAYER_HEAD).apply {
            val profile = ResolvableProfile.resolvableProfile().uuid(target.playerId.value).name(target.name)
            setData(DataComponentTypes.PROFILE, profile)
            itemMeta = itemMeta.apply {
                displayName(nonItalic(locales.component(player, "multiplayer.menu.player", LocaleService.text("player", target.name))))
                lore(
                    locales.lines(
                        player,
                        if (interactive) "multiplayer.menu.player-lore" else "multiplayer.menu.player-status-lore",
                        LocaleService.component("state", openState(player, selected)),
                        LocaleService.text("team", team?.toString() ?: "—"),
                        LocaleService.component("server", serverNames.display(target.originServer)),
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
    }

    override fun close() {
        networkSubscription?.close()
        remoteInvites.clear()
        preparationInFlight.clear()
        preparationRetryScheduled.clear()
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
    private class RemoteLobbyHolder(val lobbyId: UUID) : MultiplayerHolder()

    private companion object {
        const val MENU_SIZE = 45
        const val INVITE_TIMEOUT_TICKS = 900L
        const val MAX_INVITE_TIMEOUT_TICKS = 12_000L
        const val NETWORK_START_POLL_TICKS = 5L
        val PLAYER_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22)
        val PARTICIPANT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23)
    }
}

private fun millisToTicksCeil(millis: Long): Long =
    (millis.coerceAtLeast(1L) + MILLIS_PER_TICK - 1L) / MILLIS_PER_TICK

private const val MILLIS_PER_TICK = 50L
