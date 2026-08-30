package ru.ruscrafting.duels.paper

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.kyori.adventure.text.Component
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

/** Inventory-only multiplayer setup, invitation, kit selection, and readiness flow. */
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
    private val backAction: (Player) -> Unit,
) : Listener, AutoCloseable {
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
    private val guiItems = GuiItemCatalog.load(plugin)
    private val networkSubscription = groupBus?.subscribe { message -> tasks.runSync { onNetworkMessage(message) } }

    fun open(player: Player) {
        if (busy(player)) {
            player.sendMessage(locales.notice(player, "multiplayer.busy"))
            return
        }
        remoteInvites[player.uniqueId]?.let { return openRemoteLobby(player, it) }
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
        lobbyByPlayer[event.player.uniqueId]?.let(lobbies::get)?.takeIf { it.phase == LobbyPhase.INVITING }
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
        val total = draft.selected.size + 1
        if (total !in MIN_MULTIPLAYER_PARTICIPANTS..MAX_MULTIPLAYER_PARTICIPANTS || !teamsValid(draft, total)) {
            host.sendMessage(locales.notice(host, "multiplayer.invalid-size"))
            return openSetup(host, draft)
        }
        val selected = draft.selected.mapNotNull(targets::find)
        val localMembers = selected.mapNotNull { target -> plugin.server.getPlayer(target.uniqueId) }
        if (selected.size != draft.selected.size || localMembers.any(::busy) || selected.any { it.uniqueId in lobbyByPlayer }) {
            host.sendMessage(locales.notice(host, "multiplayer.player-left"))
            return openSetup(host, draft)
        }
        if (selected.any { !it.local } && (groupBus == null || transfer == null)) {
            host.sendMessage(locales.notice(host, "multiplayer.network-unavailable"))
            return openSetup(host, draft)
        }
        val hostProfile = NetworkGroupParticipant(PlayerId(host.uniqueId), host.name, localServer)
        val memberProfiles = selected.map { NetworkGroupParticipant(PlayerId(it.uniqueId), it.name, it.server) }
        val lobby = Lobby(
            id = UUID.randomUUID(),
            host = hostProfile,
            members = memberProfiles,
            layout = draft.layout,
            kitPolicy = draft.kitPolicy,
            sharedKit = draft.sharedKit.takeIf { draft.kitPolicy == MultiplayerKitPolicy.SHARED },
            acceptedKits = mutableMapOf(host.uniqueId to if (draft.kitPolicy == MultiplayerKitPolicy.SHARED) draft.sharedKit else draft.hostKit),
            expiresAtEpochMillis = clock.millis() + INVITE_TIMEOUT_MILLIS,
        )
        lobbies[lobby.id] = lobby
        lobby.participantIds.forEach { lobbyByPlayer[it] = lobby.id }
        drafts.remove(host.uniqueId)
        localMembers.forEach { member ->
            member.sendMessage(locales.notice(member, "multiplayer.invited", LocaleService.text("player", host.name)))
            openLobby(member, lobby)
        }
        val offersPublished = lobby.members.filter { it.originServer != localServer }.all { member ->
            publish(groupMessage(lobby, GroupLobbyMessageType.OFFER, targetId = member.playerId))
        }
        if (!offersPublished) {
            cancelLobby(lobby, "multiplayer.network-unavailable")
            return
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
        when (slot) {
            32 -> if (lobby.kitPolicy == MultiplayerKitPolicy.PER_PLAYER && player.uniqueId != lobby.hostId && !lobby.accepted(player.uniqueId)) {
                provisionalKits[player.uniqueId] = nextKit(provisionalKits[player.uniqueId] ?: kits.all().first().id)
                openLobby(player, lobby)
            }
            34 -> if (player.uniqueId != lobby.hostId && !lobby.accepted(player.uniqueId)) {
                lobby.acceptedKits[player.uniqueId] = lobby.sharedKit ?: provisionalKits.remove(player.uniqueId) ?: kits.all().first().id
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
        online.forEach(Player::closeInventory)
        sessions.start(roster, online.associateBy { PlayerId(it.uniqueId) }).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) online.filter(Player::isOnline).forEach { player ->
                player.sendMessage(locales.notice(player, "multiplayer.start-failed", LocaleService.text("reason", failure.message ?: "unknown")))
            }
        }
    }

    private fun cancelLobby(lobby: Lobby, key: String, publishNetwork: Boolean = true) {
        if (publishNetwork && lobby.networked && groupBus != null) {
            publish(groupMessage(lobby, GroupLobbyMessageType.CANCEL))
        }
        lobby.participantIds.mapNotNull(plugin.server::getPlayer).forEach { player ->
            player.closeInventory()
            player.sendMessage(locales.notice(player, key))
        }
        removeLobby(lobby)
        if (lobby.phase != LobbyPhase.INVITING) recoverNetworkParticipants(lobby)
    }

    private fun beginNetworkPreparation(lobby: Lobby) {
        if (lobby.phase != LobbyPhase.INVITING) return
        lobby.phase = LobbyPhase.PREPARING
        lobby.participantIds.mapNotNull(plugin.server::getPlayer).forEach(Player::closeInventory)
        duelSessions.expectNetworkPlayers(lobby.participants.filter { it.originServer != localServer }.map(NetworkGroupParticipant::playerId))
        publish(groupMessage(lobby, GroupLobbyMessageType.PREPARE))
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
        sessions.startNetwork(MatchId(lobby.id), roster, online.associateBy { PlayerId(it.uniqueId) }, origins)
            .whenCompleteSync(tasks) { _, failure ->
                duelSessions.stopExpectingNetworkPlayers(origins.keys)
                if (failure != null) {
                    publish(groupMessage(lobby, GroupLobbyMessageType.CANCEL))
                    online.filter(Player::isOnline).forEach { player ->
                        player.sendMessage(locales.notice(player, "multiplayer.start-failed", LocaleService.text("reason", failure.message ?: "unknown")))
                    }
                    recoverNetworkParticipants(lobby)
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
        if (clock.millis() >= message.expiresAtEpochMillis) {
            publishResponse(message, targetId.value, GroupLobbyResponse.FAILED, null)
            return
        }
        val player = plugin.server.getPlayer(targetId.value)
        if (player == null || busy(player) || player.uniqueId in lobbyByPlayer || player.uniqueId in remoteInvites) {
            publishResponse(message, targetId.value, GroupLobbyResponse.FAILED, null)
            return
        }
        val initialKit = message.sharedKitId ?: kits.all().first().id
        val invite = RemoteInvite(message, initialKit)
        remoteInvites[player.uniqueId] = invite
        player.sendMessage(locales.notice(player, "multiplayer.invited", LocaleService.text("player", message.participants.first().name)))
        openRemoteLobby(player, invite)
        val remainingTicks = ((message.expiresAtEpochMillis - clock.millis() + 49L) / 50L).coerceIn(1L, INVITE_TIMEOUT_TICKS)
        tasks.runLater(remainingTicks) {
            if (remoteInvites.remove(player.uniqueId, invite)) {
                player.takeIf(Player::isOnline)?.let { online ->
                    online.closeInventory()
                    online.sendMessage(locales.notice(online, "multiplayer.expired"))
                }
            }
        }
    }

    private fun receiveResponse(message: CrossServerGroupMessage) {
        if (message.hostServer != localServer) return
        val lobby = lobbies[message.lobbyId] ?: return
        if (lobby.phase != LobbyPhase.INVITING || lobby.participants.map(NetworkGroupParticipant::playerId) != message.participants.map(NetworkGroupParticipant::playerId)) return
        val targetId = requireNotNull(message.targetId).value
        when (message.response) {
            GroupLobbyResponse.ACCEPTED -> {
                val kitId = requireNotNull(message.kitId)
                if (kits.all().none { it.id == kitId } || (lobby.sharedKit != null && lobby.sharedKit != kitId)) {
                    cancelLobby(lobby, "multiplayer.player-left")
                    return
                }
                lobby.acceptedKits[targetId] = kitId
                plugin.server.getPlayer(lobby.hostId)?.let { openLobby(it, lobby) }
                if (lobby.acceptedKits.size == lobby.participantIds.size) prepareOrStartLobby(lobby)
            }
            GroupLobbyResponse.DECLINED -> cancelLobby(lobby, "multiplayer.declined")
            GroupLobbyResponse.FAILED -> cancelLobby(lobby, "multiplayer.player-left")
            null -> Unit
        }
    }

    private fun receivePreparation(message: CrossServerGroupMessage) {
        if (clock.millis() >= message.expiresAtEpochMillis) return
        val localParticipants = message.participants.filter { it.originServer == localServer }
        if (localParticipants.isEmpty()) return
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
            if (player == null || !playerDataReady(player)) {
                publishResponse(message, participant.playerId.value, GroupLobbyResponse.FAILED, null)
                return@forEach
            }
            remoteInvites[player.uniqueId]?.let { invite ->
                if (!invite.accepted) {
                    publishResponse(message, participant.playerId.value, GroupLobbyResponse.FAILED, null)
                    return@forEach
                }
                invite.preparing = true
                player.closeInventory()
            }
            val key = message.lobbyId to player.uniqueId
            if (!preparationInFlight.add(key)) return@forEach
            duelSessions.storeOriginSnapshot(MatchId(message.lobbyId), player, inventoryReplaced = true)
                .whenCompleteSync(tasks) { _, failure ->
                    preparationInFlight.remove(key)
                    if (failure != null) {
                        publishResponse(message, player.uniqueId, GroupLobbyResponse.FAILED, null)
                        return@whenCompleteSync
                    }
                    publish(
                        message.copy(
                            messageId = "${message.lobbyId}:ready:${player.uniqueId}:${localServer.value}",
                            sourceServer = localServer,
                            type = GroupLobbyMessageType.READY,
                            targetId = PlayerId(player.uniqueId),
                        ),
                    )
                    if (localServer != message.hostServer) transfer?.connect(player, message.hostServer)
                }
        }
    }

    private fun receiveReady(message: CrossServerGroupMessage) {
        if (message.hostServer != localServer) return
        val lobby = lobbies[message.lobbyId] ?: return
        if (lobby.phase != LobbyPhase.PREPARING) return
        lobby.ready += requireNotNull(message.targetId).value
    }

    private fun receiveCancellation(message: CrossServerGroupMessage) {
        if (message.sourceServer == localServer) return
        lobbies[message.lobbyId]?.let { cancelLobby(it, "multiplayer.cancelled", publishNetwork = false) }
        val affected = remoteInvites.filterValues { it.message.lobbyId == message.lobbyId }
        affected.forEach { (playerId, invite) ->
            remoteInvites.remove(playerId, invite)
            plugin.server.getPlayer(playerId)?.let { player ->
                player.closeInventory()
                player.sendMessage(locales.notice(player, "multiplayer.cancelled"))
                if (invite.preparing && !duelSessions.requestRecovery(player)) duelSessions.handleJoin(player)
            }
        }
    }

    private fun openRemoteLobby(player: Player, invite: RemoteInvite) {
        val message = invite.message
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
        inventory.setItem(32, kitButton(player, kits.get(invite.kitId), message.kitPolicy, interactive = message.kitPolicy == MultiplayerKitPolicy.PER_PLAYER && !invite.accepted))
        inventory.setItem(34, item(player, if (invite.accepted) Material.LIME_DYE else Material.LIME_CONCRETE, if (invite.accepted) "multiplayer.menu.accepted" else "multiplayer.menu.accept", if (invite.accepted) "multiplayer.menu.accepted-lore" else "multiplayer.menu.accept-lore"))
        inventory.setItem(36, item(player, Material.RED_CONCRETE, "multiplayer.menu.decline", "multiplayer.menu.cancel-lore"))
        player.openInventory(inventory)
    }

    private fun handleRemoteLobbyClick(player: Player, holder: RemoteLobbyHolder, slot: Int) {
        val invite = remoteInvites[player.uniqueId]?.takeIf { it.message.lobbyId == holder.lobbyId } ?: return open(player)
        when (slot) {
            32 -> if (invite.message.kitPolicy == MultiplayerKitPolicy.PER_PLAYER && !invite.accepted) {
                invite.kitId = nextKit(invite.kitId)
                openRemoteLobby(player, invite)
            }
            34 -> if (!invite.accepted) {
                invite.accepted = true
                publishResponse(invite.message, player.uniqueId, GroupLobbyResponse.ACCEPTED, invite.kitId)
                openRemoteLobby(player, invite)
            }
            36 -> {
                publishResponse(invite.message, player.uniqueId, GroupLobbyResponse.DECLINED, null)
                remoteInvites.remove(player.uniqueId, invite)
                player.closeInventory()
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
    ) {
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
        const val INVITE_TIMEOUT_MILLIS = INVITE_TIMEOUT_TICKS * 50L
        const val NETWORK_START_POLL_TICKS = 5L
        val PLAYER_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22)
        val PARTICIPANT_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23)
    }
}
