package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchCoordinator
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.PlayerStateEscrow
import ru.ruscrafting.duels.domain.PlayerStateEscrowRepository
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

class DurablePlayerStateServiceTest : StringSpec({
    lateinit var server: ServerMock
    lateinit var plugin: ArcDuelsPlugin

    beforeSpec {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(ArcDuelsPlugin::class.java)
        server.addSimpleWorld("world")
    }

    afterSpec { MockBukkit.unmock() }

    "pair becomes usable only after one durable commit and exact archival clears active recovery" {
        val repository = GatedEscrowRepository()
        val restoredAt = Instant.parse("2026-08-14T10:00:00Z")
        val service =
            DurablePlayerStateService(
                plugin,
                ServerId("test-node"),
                repository,
                clock = Clock.fixed(restoredAt, ZoneOffset.UTC),
                retention = Duration.ofDays(7),
            )
        val first = server.addPlayer()
        val second = server.addPlayer()
        first.inventory.setItem(0, ItemStack(Material.NETHERITE_SWORD))
        second.inventory.setItem(4, ItemStack(Material.TOTEM_OF_UNDYING))

        val storedFuture = service.storePair(MatchId.random(), first, second, inventoryReplaced = true)

        storedFuture.isDone shouldBe false
        service.isPending(first.uniqueId) shouldBe false
        repository.saved?.size shouldBe 2

        repository.commit.complete(Unit)
        val stored = storedFuture.get()

        service.isPending(first.uniqueId) shouldBe true
        service.decode(stored.getValue(first.uniqueId).escrow).state.storage[0]?.type shouldBe Material.NETHERITE_SWORD
        service.decode(stored.getValue(second.uniqueId).escrow).state.storage[4]?.type shouldBe Material.TOTEM_OF_UNDYING

        service.retain(stored.getValue(first.uniqueId)).get()
        service.retain(stored.getValue(first.uniqueId)).get()
        service.isPending(first.uniqueId) shouldBe false
        repository.retained shouldBe first.uniqueId
        service.latestRetained(first.uniqueId).get()?.inventoryReplaced shouldBe true
        repository.retentionCalls shouldBe 1
        repository.restoredAt shouldBe restoredAt
        repository.purgeAfter shouldBe restoredAt.plus(Duration.ofDays(7))
    }

    "one origin snapshot is captured before transfer and becomes visible only after commit" {
        val repository = GatedEscrowRepository()
        val service = DurablePlayerStateService(plugin, ServerId("origin"), repository)
        val player = server.addPlayer()
        player.inventory.setItem(0, ItemStack(Material.DIAMOND_SWORD))
        val matchId = MatchId.random()

        val storedFuture = service.store(matchId, player, inventoryReplaced = true)
        player.inventory.setItem(0, ItemStack(Material.DIRT))

        service.isPending(player.uniqueId) shouldBe false
        repository.commit.complete(Unit)
        val stored = storedFuture.get()
        stored.escrow.matchId shouldBe matchId
        stored.escrow.serverId shouldBe ServerId("origin")
        stored.state.storage[0]?.type shouldBe Material.DIAMOND_SWORD
        service.isPending(player.uniqueId) shouldBe true
    }

    "concurrent restoration paths share one database archival" {
        val repository = GatedEscrowRepository()
        val archival = CompletableFuture<Boolean>()
        repository.archival = archival
        val service = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        val first = server.addPlayer()
        val second = server.addPlayer()
        val storedFuture = service.storePair(MatchId.random(), first, second, inventoryReplaced = true)
        repository.commit.complete(Unit)
        val stored = storedFuture.get().getValue(first.uniqueId)

        val firstAttempt = service.retain(stored)
        val secondAttempt = service.retain(stored)

        repository.retentionCalls shouldBe 1
        firstAttempt.isDone shouldBe false
        secondAttempt.isDone shouldBe false
        archival.complete(true)
        firstAttempt.get()
        secondAttempt.get()
        service.isPending(first.uniqueId) shouldBe false
    }

    "shutdown retention drain times out without claiming or deleting the active snapshot" {
        val repository = GatedEscrowRepository()
        repository.commit.complete(Unit)
        val archival = CompletableFuture<Boolean>()
        repository.archival = archival
        val service = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        val player = server.addPlayer()
        val stored = service.store(MatchId.random(), player, inventoryReplaced = true).get()
        val retention = service.retain(stored)

        val report = service.awaitRetentions(Duration.ofMillis(1))

        report shouldBe RetentionDrainReport(observed = 1, acknowledged = 0, failed = 0, timedOut = 1)
        service.isPending(player.uniqueId) shouldBe true
        archival.complete(true)
        retention.get()
        service.isPending(player.uniqueId) shouldBe false
    }

    "own-inventory snapshots are marked so routine restoration stays quiet" {
        val repository = GatedEscrowRepository()
        repository.commit.complete(Unit)
        val service = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        val first = server.addPlayer()
        val second = server.addPlayer()

        val stored = service.storePair(MatchId.random(), first, second, inventoryReplaced = false).get()

        stored.getValue(first.uniqueId).escrow.inventoryReplaced shouldBe false
        stored.getValue(second.uniqueId).escrow.inventoryReplaced shouldBe false
    }

    "origin inventory freezes before its asynchronous MySQL commit completes" {
        val repository = GatedEscrowRepository()
        val service = DurablePlayerStateService(plugin, ServerId("origin"), repository)
        val player = server.addPlayer()
        val peer = server.addPlayer()
        val sessions =
            DuelSessionManager(
                plugin,
                mockk(relaxed = true),
                PaperArenaCatalog.load(plugin),
                KitRegistry.load(plugin),
                service,
                LocaleService.load(plugin),
                countdownSeconds = 0,
                playerDataSaver = {},
            )
        val challenge =
            DuelChallenge.create(
                PlayerId(player.uniqueId),
                PlayerId(peer.uniqueId),
                DuelRules(DuelMode.OWN_INVENTORY),
                Instant.parse("2026-08-15T13:00:00Z"),
                Duration.ofSeconds(30),
            )

        val stored = sessions.storeOriginSnapshot(challenge, player)

        sessions.isPreparing(player) shouldBe true
        val heldSlotChange = PlayerItemHeldEvent(player, 0, 1)
        DuelGameplayListener(sessions, LocaleService.load(plugin)).onHeldSlotChange(heldSlotChange)
        heldSlotChange.isCancelled shouldBe true

        repository.commit.complete(Unit)
        stored.get()
        sessions.isPreparing(player) shouldBe false
        sessions.isStateLocked(player) shouldBe true
        sessions.shutdown()
    }

    "join recovery waits for player-data readiness and the configured settle window" {
        val repository = GatedEscrowRepository()
        repository.commit.complete(Unit)
        val service = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        val player = server.addPlayer()
        val peer = server.addPlayer()
        player.inventory.setItem(0, ItemStack(Material.DIAMOND_SWORD))
        service.storePair(MatchId.random(), player, peer, inventoryReplaced = true).get()
        player.inventory.setItem(0, ItemStack(Material.STONE))
        var playerDataReady = false
        val sessions =
            DuelSessionManager(
                plugin,
                mockk(relaxed = true),
                PaperArenaCatalog.load(plugin),
                KitRegistry.load(plugin),
                service,
                LocaleService.load(plugin),
                countdownSeconds = 0,
                playerDataReady = { playerDataReady },
                recoveryApplyDelayTicks = 40L,
                playerDataSaver = {},
            )

        sessions.handleJoin(player)
        server.scheduler.performTicks(20)
        player.inventory.getItem(0)?.type shouldBe Material.STONE

        playerDataReady = true
        server.scheduler.performTicks(5)
        server.scheduler.performTicks(39)
        player.inventory.getItem(0)?.type shouldBe Material.STONE

        server.scheduler.performTicks(1)
        player.inventory.getItem(0)?.type shouldBe Material.DIAMOND_SWORD
        service.isPending(player.uniqueId) shouldBe false
        sessions.shutdown()
    }

    "a fresh process instance restores exact kit inventory from the committed snapshot" {
        val repository = GatedEscrowRepository()
        repository.commit.complete(Unit)
        val writer = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        val player = server.addPlayer()
        val peer = server.addPlayer()
        player.inventory.setItem(0, ItemStack(Material.NETHERITE_SWORD))
        peer.inventory.setItem(4, ItemStack(Material.TOTEM_OF_UNDYING))
        writer.storePair(MatchId.random(), player, peer, inventoryReplaced = true).get()
        player.inventory.setItem(0, ItemStack(Material.WOODEN_SWORD))

        val afterCrash = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        afterCrash.loadPending(1_000L) shouldBe 2
        val sessions =
            DuelSessionManager(
                plugin,
                mockk(relaxed = true),
                PaperArenaCatalog.load(plugin),
                KitRegistry.load(plugin),
                afterCrash,
                LocaleService.load(plugin),
                countdownSeconds = 0,
                playerDataReady = { true },
                recoveryApplyDelayTicks = 0L,
                playerDataSaver = {},
            )

        sessions.handleJoin(player)
        server.scheduler.performTicks(2)

        player.inventory.getItem(0)?.type shouldBe Material.NETHERITE_SWORD
        afterCrash.isPending(player.uniqueId) shouldBe false
        val afterAcknowledgementCrash = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        afterAcknowledgementCrash.loadPending(1_000L) shouldBe 1
        afterAcknowledgementCrash.isPending(peer.uniqueId) shouldBe true
        sessions.shutdown()
    }

    "matching inventory is claimed after the settle window without applying or saving it again" {
        val repository = GatedEscrowRepository()
        repository.commit.complete(Unit)
        val service = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        val player = server.addPlayer()
        val peer = server.addPlayer()
        player.inventory.setItem(0, ItemStack(Material.EMERALD))
        service.storePair(MatchId.random(), player, peer, inventoryReplaced = true).get()
        var saveCalls = 0
        val sessions =
            DuelSessionManager(
                plugin,
                mockk(relaxed = true),
                PaperArenaCatalog.load(plugin),
                KitRegistry.load(plugin),
                service,
                LocaleService.load(plugin),
                countdownSeconds = 0,
                playerDataReady = { true },
                recoveryApplyDelayTicks = 0L,
                playerDataSaver = { saveCalls++ },
            )

        sessions.handleJoin(player)
        server.scheduler.performTicks(2)

        player.inventory.getItem(0)?.type shouldBe Material.EMERALD
        saveCalls shouldBe 0
        service.isPending(player.uniqueId) shouldBe false
        sessions.shutdown()
    }

    "matching inventory still returns the player to the captured origin location" {
        val repository = GatedEscrowRepository()
        repository.commit.complete(Unit)
        val service = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        val player = server.addPlayer()
        val peer = server.addPlayer()
        val origin = player.location.clone()
        service.storePair(MatchId.random(), player, peer, inventoryReplaced = true).get()
        player.teleport(origin.clone().add(12.0, 0.0, 0.0))
        var saveCalls = 0
        val sessions =
            DuelSessionManager(
                plugin,
                mockk(relaxed = true),
                PaperArenaCatalog.load(plugin),
                KitRegistry.load(plugin),
                service,
                LocaleService.load(plugin),
                countdownSeconds = 0,
                playerDataReady = { true },
                recoveryApplyDelayTicks = 0L,
                playerDataSaver = { saveCalls++ },
            )

        sessions.handleJoin(player)
        server.scheduler.performTicks(2)

        player.location.x shouldBe origin.x
        player.location.y shouldBe origin.y
        player.location.z shouldBe origin.z
        saveCalls shouldBe 1
        service.isPending(player.uniqueId) shouldBe false
        sessions.shutdown()
    }

    "matching inventory restores non-inventory state without replacing the items" {
        val repository = GatedEscrowRepository()
        repository.commit.complete(Unit)
        val service = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        val player = server.addPlayer()
        val peer = server.addPlayer()
        player.inventory.setItem(0, ItemStack(Material.EMERALD))
        player.gameMode = org.bukkit.GameMode.ADVENTURE
        player.foodLevel = 13
        player.level = 7
        service.storePair(MatchId.random(), player, peer, inventoryReplaced = true).get()
        val liveItem = player.inventory.getItem(0)
        player.gameMode = org.bukkit.GameMode.SURVIVAL
        player.foodLevel = 20
        player.level = 0
        var saveCalls = 0
        val sessions =
            DuelSessionManager(
                plugin,
                mockk(relaxed = true),
                PaperArenaCatalog.load(plugin),
                KitRegistry.load(plugin),
                service,
                LocaleService.load(plugin),
                countdownSeconds = 0,
                playerDataReady = { true },
                recoveryApplyDelayTicks = 0L,
                playerDataSaver = { saveCalls++ },
            )

        sessions.handleJoin(player)
        server.scheduler.performTicks(2)

        player.inventory.getItem(0) shouldBe liveItem
        player.gameMode shouldBe org.bukkit.GameMode.ADVENTURE
        player.foodLevel shouldBe 13
        player.level shouldBe 7
        saveCalls shouldBe 1
        service.isPending(player.uniqueId) shouldBe false
        sessions.shutdown()
    }

    "remote recovery retries a failed backend switch until the player leaves" {
        val repository = GatedEscrowRepository()
        repository.commit.complete(Unit)
        val player = server.addPlayer()
        val originService = DurablePlayerStateService(plugin, ServerId("spawn"), repository)
        originService.store(MatchId.random(), player, inventoryReplaced = false).get()
        val remoteService = DurablePlayerStateService(plugin, ServerId("survival"), repository)
        val destinations = mutableListOf<ServerId>()
        val coordinator = mockk<MatchCoordinator>(relaxed = true)
        every { coordinator.findByPlayer(any()) } returns null
        val sessions =
            DuelSessionManager(
                plugin,
                coordinator,
                PaperArenaCatalog.load(plugin),
                KitRegistry.load(plugin),
                remoteService,
                LocaleService.load(plugin),
                countdownSeconds = 0,
                remoteRecoveryTransfer = { _, destination -> destinations += destination },
                recoveryApplyDelayTicks = 0L,
            )

        sessions.handleJoin(player)

        destinations shouldBe listOf(ServerId("spawn"))
        sessions.isPreparing(player) shouldBe true
        server.scheduler.performTicks(99)
        destinations.size shouldBe 1
        server.scheduler.performTicks(1)
        destinations shouldBe listOf(ServerId("spawn"), ServerId("spawn"))

        sessions.handleQuit(player)
        server.scheduler.performTicks(200)
        destinations.size shouldBe 2
        sessions.isPreparing(player) shouldBe false
        sessions.shutdown()
    }

    "non-inventory restoration runs after teleport listeners normalize player state" {
        val player = server.addPlayer()
        player.inventory.setItem(0, ItemStack(Material.EMERALD))
        player.foodLevel = 13
        player.noDamageTicks = 7
        val origin = player.location.clone()
        val snapshot = PlayerSnapshot.capture(player)
        val liveItem = player.inventory.getItem(0)
        player.teleport(origin.clone().add(8.0, 0.0, 0.0))
        player.foodLevel = 20
        player.noDamageTicks = 0

        snapshot.restoreWithoutInventory(player) { target, destination ->
            val teleported = target.teleport(destination)
            // Represents CMI/HuskSync/other teleport callbacks normalizing
            // transient state after Bukkit has moved the player.
            target.foodLevel = 20
            target.noDamageTicks = 40
            teleported
        }

        player.inventory.getItem(0) shouldBe liveItem
        player.foodLevel shouldBe 13
        player.noDamageTicks shouldBe 7
        snapshot.nonInventoryStateMismatches(player) shouldBe emptyList()
    }

    "health verification tolerates attribute float normalization" {
        val player = server.addPlayer()
        player.health = 18.0
        val snapshot = PlayerSnapshot.capture(player)

        player.health = 17.9999995

        snapshot.nonInventoryStateMismatches(player) shouldNotContain "health"
    }

    "overlapping archive cleanup runs are coalesced" {
        val repository = GatedEscrowRepository()
        val firstPurge = CompletableFuture<Int>()
        repository.purgeResult = firstPurge
        val service = DurablePlayerStateService(plugin, ServerId("test-node"), repository)

        val running = service.purgeExpired()
        service.purgeExpired().get() shouldBe 0
        repository.purgeCalls shouldBe 1

        firstPurge.complete(27)
        running.get() shouldBe 27
        repository.purgeResult = CompletableFuture.completedFuture(3)
        service.purgeExpired().get() shouldBe 3
        repository.purgeCalls shouldBe 2
    }

    "unknown save outcome is accepted only after both exact snapshots are visible" {
        val repository = GatedEscrowRepository()
        val service =
            DurablePlayerStateService(
                plugin,
                ServerId("test-node"),
                repository,
                reconciliationDelay = Duration.ZERO,
            )
        val first = server.addPlayer()
        val second = server.addPlayer()
        val future = service.storePair(MatchId.random(), first, second, inventoryReplaced = true)

        repository.commit.completeExceptionally(IllegalStateException("lost commit response"))

        future.get()
        service.isPending(first.uniqueId) shouldBe true
        service.isPending(second.uniqueId) shouldBe true
        repository.lookupCalls shouldBe 2
    }

    "lost response for one origin snapshot is reconciled against its exact committed bytes" {
        val repository = GatedEscrowRepository()
        val service =
            DurablePlayerStateService(
                plugin,
                ServerId("origin"),
                repository,
                reconciliationDelay = Duration.ZERO,
            )
        val player = server.addPlayer()
        val future = service.store(MatchId.random(), player, inventoryReplaced = true)

        repository.commit.completeExceptionally(IllegalStateException("lost commit response"))

        future.get()
        service.isPending(player.uniqueId) shouldBe true
        repository.lookupCalls shouldBe 1
    }

    "unknown save outcome fails only after repeated confirmation that both rows are absent" {
        val repository = GatedEscrowRepository()
        repository.exposeSavedRows = false
        val service =
            DurablePlayerStateService(
                plugin,
                ServerId("test-node"),
                repository,
                reconciliationDelay = Duration.ZERO,
            )
        val first = server.addPlayer()
        val second = server.addPlayer()
        val future = service.storePair(MatchId.random(), first, second, inventoryReplaced = true)

        repository.commit.completeExceptionally(IllegalStateException("lost commit response"))

        shouldThrow<ExecutionException> { future.get() }
        service.isPending(first.uniqueId) shouldBe false
        service.isPending(second.uniqueId) shouldBe false
        repository.lookupCalls shouldBe 14
    }
})

private class GatedEscrowRepository : PlayerStateEscrowRepository {
    val commit = CompletableFuture<Unit>()
    var saved: List<PlayerStateEscrow>? = null
    var retained: UUID? = null
    private val retainedPlayers = linkedSetOf<UUID>()
    var retentionCalls: Int = 0
    var archival: CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
    var restoredAt: Instant? = null
    var purgeAfter: Instant? = null
    var exposeSavedRows: Boolean = true
    var lookupCalls: Int = 0
    var purgeCalls: Int = 0
    var purgeResult: CompletableFuture<Int> = CompletableFuture.completedFuture(0)

    override fun save(snapshot: PlayerStateEscrow): CompletableFuture<Unit> {
        saved = listOf(snapshot)
        return commit
    }

    override fun savePair(
        first: PlayerStateEscrow,
        second: PlayerStateEscrow,
    ): CompletableFuture<Unit> {
        saved = listOf(first, second)
        return commit
    }

    override fun findPending(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> {
        lookupCalls++
        return CompletableFuture.completedFuture(
            saved?.takeIf { exposeSavedRows }?.firstOrNull { it.playerId == playerId && it.playerId.value !in retainedPlayers },
        )
    }

    override fun pending(serverId: ServerId): CompletableFuture<List<PlayerStateEscrow>> =
        CompletableFuture.completedFuture(saved.orEmpty().filter { it.serverId == serverId && it.playerId.value !in retainedPlayers })

    override fun findLatestRetained(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> =
        CompletableFuture.completedFuture(saved?.firstOrNull { it.playerId == playerId && it.playerId.value in retainedPlayers })

    override fun retainRestored(
        snapshot: PlayerStateEscrow,
        restoredAt: Instant,
        purgeAfter: Instant,
    ): CompletableFuture<Boolean> {
        retentionCalls++
        retained = snapshot.playerId.value
        this.restoredAt = restoredAt
        this.purgeAfter = purgeAfter
        return archival.thenApply { archived ->
            if (archived) retainedPlayers += snapshot.playerId.value
            archived
        }
    }

    override fun purgeRetained(cutoff: Instant): CompletableFuture<Int> {
        purgeCalls++
        return purgeResult
    }
}
