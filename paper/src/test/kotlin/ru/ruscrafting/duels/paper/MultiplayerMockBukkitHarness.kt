package ru.ruscrafting.duels.paper

import io.papermc.paper.entity.TeleportFlag
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.entity.PlayerMock
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
import java.time.Instant
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
): MultiplayerHarness {
    val plugin = paper.loadPlugin<ArcDuelsPlugin>()
    HandlerList.unregisterAll(plugin)
    val world = paper.server.getWorld("world") ?: paper.server.addSimpleWorld("world")
    plugin.config.set("arenas.example.enabled", true)
    plugin.config.set("arenas.example.allowed-loadouts", listOf("KIT"))
    plugin.config.set("arenas.example.allowed-objectives", listOf("ELIMINATION"))
    val players: List<PlayerMock> = playerNames.mapIndexed { index, name ->
        paper.server.addPlayer(name).also { player ->
            player.teleport(Location(world, index.toDouble(), 70.0, 40.0))
            player.inventory.setItem(index % player.inventory.size, ItemStack(Material.GOLDEN_APPLE, index + 1))
        }
    }
    val tasks = LifecycleTaskScope()
    val tickets = PaperChunkTicketRegistry(AlwaysAvailableChunkTickets)
    val teleports = ControlledTeleports()
    val results = ControlledMultiplayerResults()
    val audience = RecordingPaperAudienceEffects()
    val kits = KitRegistry.load(plugin)
    val locales = LocaleService.load(plugin)
    val playerStates = DurablePlayerStateService(plugin, ServerId("group-test"), InMemoryEscrowRepository())
    val manager = MultiplayerSessionManager(
        serverId = ServerId("group-test"),
        arenas = PaperArenaCatalog.load(plugin),
        kits = kits,
        playerStates = playerStates,
        results = results,
        locales = locales,
        tasks = tasks,
        chunkTickets = tickets,
        audience = audience,
        teleports = teleports,
        playerData = PaperPlayerDataPersistence { },
        countdownSeconds = countdownSeconds,
    )
    return MultiplayerHarness(paper, plugin, manager, players, kits, locales, teleports, results, audience, tasks, tickets)
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

private object AlwaysAvailableChunkTickets : PaperChunkTicketBackend {
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

    override fun teleportAsync(
        entity: Entity,
        destination: Location,
        cause: PlayerTeleportEvent.TeleportCause,
        vararg flags: TeleportFlag,
    ): CompletableFuture<Boolean> =
        CompletableFuture<Boolean>().also { pending += Pending(entity, destination.clone(), cause, it) }

    fun completeAll() {
        val requests = pending.toList()
        pending.clear()
        requests.forEach { request ->
            request.completion.complete(request.entity.teleport(request.destination, request.cause))
        }
    }
}

internal class ControlledMultiplayerResults : MultiplayerMatchRepository {
    val writes = mutableListOf<MultiplayerMatchOutcome>()
    val completion = CompletableFuture<Boolean>()

    override fun record(outcome: MultiplayerMatchOutcome): CompletableFuture<Boolean> {
        writes += outcome
        return completion
    }
}

private class InMemoryEscrowRepository : PlayerStateEscrowRepository {
    private val pending = linkedMapOf<PlayerId, PlayerStateEscrow>()
    private val retained = linkedMapOf<PlayerId, PlayerStateEscrow>()

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
}
