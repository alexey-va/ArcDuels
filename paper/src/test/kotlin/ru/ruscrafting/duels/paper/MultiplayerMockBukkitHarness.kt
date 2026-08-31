package ru.ruscrafting.duels.paper

import io.papermc.paper.entity.TeleportFlag
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.simulate.entity.PlayerSimulation
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.teleport.PaperTeleportExecutor
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.RecordingPaperAudienceEffects
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.MultiplayerMatchOutcome
import ru.ruscrafting.duels.domain.MultiplayerMatchRepository
import ru.ruscrafting.duels.domain.MultiplayerParticipant
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.MultiplayerRules
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.PlayerStateEscrow
import ru.ruscrafting.duels.domain.PlayerStateEscrowRepository
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.redis.CrossServerGroupBus
import java.time.Instant
import java.time.Clock
import java.util.concurrent.CompletableFuture

internal data class MultiplayerHarness(
    val paper: MockBukkitTestRuntime,
    val plugin: ArcDuelsPlugin,
    val manager: MultiplayerSessionManager,
    val players: List<PlayerMock>,
    val kits: KitRegistry,
    val locales: LocaleService,
    val teleports: ControlledTeleports,
    val results: ControlledMultiplayerResults,
    val audience: RecordingPaperAudienceEffects,
    val tasks: LifecycleTaskScope,
    val tickets: PaperChunkTicketRegistry,
    val arenas: PaperArenaCatalog,
    val playerStates: DurablePlayerStateService,
    val runtimeSettings: () -> ArcDuelsRuntimeSettings?,
) : AutoCloseable {
    override fun close() {
        manager.close()
        tasks.close()
        tickets.close()
    }
}

internal fun multiplayerHarness(
    paper: MockBukkitTestRuntime,
    playerNames: List<String> = List(4) { "GroupPlayer$it" },
    countdownSeconds: Int = 0,
    escrowRepository: PlayerStateEscrowRepository = InMemoryEscrowRepository(),
    teleports: ControlledTeleports = ControlledTeleports(),
    results: ControlledMultiplayerResults = ControlledMultiplayerResults(),
    chunkTickets: PaperChunkTicketBackend = AlwaysAvailableChunkTickets,
    playerData: PaperPlayerDataPersistence = PaperPlayerDataPersistence { },
    networkReturn: (Player, ServerId) -> Unit = { _, _ -> },
    enableArena: Boolean = true,
    runtimeSettings: () -> ArcDuelsRuntimeSettings? = { null },
): MultiplayerHarness {
    val plugin = paper.loadPlugin<ArcDuelsPlugin>()
    HandlerList.unregisterAll(plugin)
    val world = paper.server.getWorld("world") ?: paper.server.addSimpleWorld("world")
    plugin.config.set("arenas.example.enabled", enableArena)
    plugin.config.set("arenas.example.allowed-loadouts", listOf("KIT"))
    plugin.config.set("arenas.example.allowed-objectives", listOf("ELIMINATION"))
    val players: List<PlayerMock> = playerNames.mapIndexed { index, name ->
        paper.server.addPlayer(name).also { player ->
            player.teleport(Location(world, index.toDouble(), 70.0, 40.0))
            player.inventory.setItem(index % player.inventory.size, ItemStack(Material.GOLDEN_APPLE, index + 1))
        }
    }
    val tasks = LifecycleTaskScope()
    val tickets = PaperChunkTicketRegistry(chunkTickets)
    val audience = RecordingPaperAudienceEffects()
    val kits = KitRegistry.load(plugin)
    val locales = LocaleService.load(plugin)
    val playerStates = DurablePlayerStateService(plugin, ServerId("group-test"), escrowRepository)
    val arenas = PaperArenaCatalog.load(plugin)
    val manager = MultiplayerSessionManager(
        serverId = ServerId("group-test"),
        arenas = arenas,
        kits = kits,
        playerStates = playerStates,
        results = results,
        locales = locales,
        tasks = tasks,
        chunkTickets = tickets,
        audience = audience,
        teleports = teleports,
        playerData = playerData,
        networkReturn = networkReturn,
        countdownSeconds = countdownSeconds,
        runtimeSettings = runtimeSettings,
    )
    return MultiplayerHarness(
        paper,
        plugin,
        manager,
        players,
        kits,
        locales,
        teleports,
        results,
        audience,
        tasks,
        tickets,
        arenas,
        playerStates,
        runtimeSettings,
    )
}

internal fun ffaRoster(players: List<Player>): MultiplayerRoster =
    MultiplayerRoster(
        MultiplayerRules(MultiplayerLayout.FREE_FOR_ALL, MultiplayerKitPolicy.SHARED, KitId("classic")),
        players.map { player -> MultiplayerParticipant(PlayerId(player.uniqueId), kitId = KitId("classic")) },
    )

internal fun teamRoster(players: List<Player>): MultiplayerRoster =
    MultiplayerRoster(
        MultiplayerRules(MultiplayerLayout.TWO_TEAMS, MultiplayerKitPolicy.SHARED, KitId("classic")),
        players.mapIndexed { index, player ->
            MultiplayerParticipant(PlayerId(player.uniqueId), team = index % 2 + 1, kitId = KitId("classic"))
        },
    )

internal fun MultiplayerHarness.registerGui(
    targets: DuelTargetDirectory = DuelTargetDirectory(plugin, ServerId("group-test"), null),
    groupBus: CrossServerGroupBus? = null,
    transfer: PlayerTransfer? = null,
    duelSessions: DuelSessionManager = mockk(relaxed = true),
    playerDataReady: (Player) -> Boolean = { true },
    clock: Clock = Clock.systemUTC(),
    backAction: (Player) -> Unit = { },
): MultiplayerGuiService =
    MultiplayerGuiService(
        plugin = plugin,
        kits = kits,
        sessions = manager,
        duelSessions = duelSessions,
        locales = locales,
        tasks = tasks,
        targets = targets,
        localServer = ServerId("group-test"),
        serverNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning),
        groupBus = groupBus,
        transfer = transfer,
        playerDataReady = playerDataReady,
        clock = clock,
        runtimeSettings = runtimeSettings,
        backAction = backAction,
    ).also { plugin.server.pluginManager.registerEvents(it, plugin) }

internal fun PlayerMock.click(slot: Int, clickType: ClickType = ClickType.LEFT) =
    PlayerSimulation(this).simulateInventoryClick(openInventory, clickType, slot)

internal fun ItemStack?.plainLore(): String =
    this?.itemMeta?.lore().orEmpty().joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }

internal fun ItemStack?.plainName(): String =
    this?.itemMeta?.displayName()?.let(PlainTextComponentSerializer.plainText()::serialize).orEmpty()

internal fun Component.runCommands(): List<String> =
    buildList {
        clickEvent()
            ?.takeIf { it.action() == ClickEvent.Action.RUN_COMMAND }
            ?.payload()
            ?.let { it as? ClickEvent.Payload.Text }
            ?.value()
            ?.let(::add)
        children().forEach { addAll(it.runCommands()) }
    }

internal fun MultiplayerHarness.registerGameplayListener() {
    plugin.server.pluginManager.registerEvents(MultiplayerGameplayListener(manager, locales), plugin)
}

internal fun MultiplayerHarness.startAndArrive(roster: MultiplayerRoster) {
    manager.start(roster, roster.playerIds.associateWith { playerId -> requireNotNull(plugin.server.getPlayer(playerId.value)) })
    paper.performTicks(4)
    teleports.completeAll()
    paper.performTicks(4)
}

internal object AlwaysAvailableChunkTickets : PaperChunkTicketBackend {
    override fun add(key: PaperChunkKey): PaperChunkTicketAddResult = PaperChunkTicketAddResult.ADDED
    override fun remove(key: PaperChunkKey): Boolean = true
}

internal class ControlledTeleports : PaperTeleportExecutor {
    private data class Pending(
        val entity: Entity,
        val destination: Location,
        val cause: PlayerTeleportEvent.TeleportCause,
        val completion: CompletableFuture<Boolean>,
    )

    private val pending = mutableListOf<Pending>()

    val pendingCount: Int get() = pending.size

    override fun teleportAsync(
        entity: Entity,
        destination: Location,
        cause: PlayerTeleportEvent.TeleportCause,
        vararg flags: TeleportFlag,
    ): CompletableFuture<Boolean> =
        CompletableFuture<Boolean>().also { pending += Pending(entity, destination.clone(), cause, it) }

    fun completeAll(arrived: Boolean = true) {
        val requests = pending.toList()
        pending.clear()
        requests.forEach { request ->
            request.completion.complete(arrived && request.entity.teleport(request.destination, request.cause))
        }
    }
}

internal class ControlledMultiplayerResults(
    var failuresBeforeSuccess: Int = 0,
) : MultiplayerMatchRepository {
    val writes = mutableListOf<MultiplayerMatchOutcome>()
    val completion = CompletableFuture<Boolean>()

    override fun record(outcome: MultiplayerMatchOutcome): CompletableFuture<Boolean> {
        writes += outcome
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            return CompletableFuture.failedFuture(IllegalStateException("planned result failure"))
        }
        return completion
    }
}

internal class InMemoryEscrowRepository(
    var retainFailuresBeforeSuccess: Int = 0,
) : PlayerStateEscrowRepository {
    private val pending = linkedMapOf<PlayerId, PlayerStateEscrow>()
    private val retained = linkedMapOf<PlayerId, PlayerStateEscrow>()
    var retainCalls: Int = 0
        private set

    override fun save(snapshot: PlayerStateEscrow): CompletableFuture<Unit> = saveAll(listOf(snapshot))

    override fun saveAll(snapshots: List<PlayerStateEscrow>): CompletableFuture<Unit> = synchronized(this) {
        snapshots.forEach { snapshot ->
            val current = pending[snapshot.playerId]
            check(current == null || current.sameContent(snapshot)) { "Conflicting escrow" }
        }
        snapshots.forEach { pending[it.playerId] = it }
        CompletableFuture.completedFuture(Unit)
    }

    override fun findPending(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> =
        CompletableFuture.completedFuture(synchronized(this) { pending[playerId] })

    override fun pending(serverId: ServerId): CompletableFuture<List<PlayerStateEscrow>> =
        CompletableFuture.completedFuture(synchronized(this) { pending.values.filter { it.serverId == serverId } })

    override fun findLatestRetained(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> =
        CompletableFuture.completedFuture(synchronized(this) { retained[playerId] })

    override fun retainRestored(
        snapshot: PlayerStateEscrow,
        restoredAt: Instant,
        purgeAfter: Instant,
    ): CompletableFuture<Boolean> = synchronized(this) {
        retainCalls++
        if (retainFailuresBeforeSuccess > 0) {
            retainFailuresBeforeSuccess--
            return@synchronized CompletableFuture.failedFuture(IllegalStateException("planned retain failure"))
        }
        val current = pending[snapshot.playerId]
        if (current != null && current.sameContent(snapshot)) {
            pending.remove(snapshot.playerId)
            retained[snapshot.playerId] = snapshot
            CompletableFuture.completedFuture(true)
        } else {
            CompletableFuture.completedFuture(false)
        }
    }

    override fun purgeRetained(cutoff: Instant): CompletableFuture<Int> = CompletableFuture.completedFuture(0)

    fun pendingCount(): Int = synchronized(this) { pending.size }

    fun retainedCount(): Int = synchronized(this) { retained.size }
}
