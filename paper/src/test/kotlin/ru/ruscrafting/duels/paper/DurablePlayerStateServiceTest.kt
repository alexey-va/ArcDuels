package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import ru.ruscrafting.duels.domain.MatchId
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

        val storedFuture = service.storePair(MatchId.random(), first, second)

        storedFuture.isDone shouldBe false
        service.isPending(first.uniqueId) shouldBe false
        repository.saved?.size shouldBe 2

        repository.commit.complete(Unit)
        val stored = storedFuture.get()

        service.isPending(first.uniqueId) shouldBe true
        service.decode(stored.getValue(first.uniqueId).escrow).snapshot.storage[0]?.type shouldBe Material.NETHERITE_SWORD
        service.decode(stored.getValue(second.uniqueId).escrow).snapshot.storage[4]?.type shouldBe Material.TOTEM_OF_UNDYING

        service.retain(stored.getValue(first.uniqueId)).get()
        service.retain(stored.getValue(first.uniqueId)).get()
        service.isPending(first.uniqueId) shouldBe false
        repository.retained shouldBe first.uniqueId
        repository.retentionCalls shouldBe 1
        repository.restoredAt shouldBe restoredAt
        repository.purgeAfter shouldBe restoredAt.plus(Duration.ofDays(7))
    }

    "concurrent restoration paths share one database archival" {
        val repository = GatedEscrowRepository()
        val archival = CompletableFuture<Boolean>()
        repository.archival = archival
        val service = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
        val first = server.addPlayer()
        val second = server.addPlayer()
        val storedFuture = service.storePair(MatchId.random(), first, second)
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
        val future = service.storePair(MatchId.random(), first, second)

        repository.commit.completeExceptionally(IllegalStateException("lost commit response"))

        future.get()
        service.isPending(first.uniqueId) shouldBe true
        service.isPending(second.uniqueId) shouldBe true
        repository.lookupCalls shouldBe 2
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
        val future = service.storePair(MatchId.random(), first, second)

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
    var retentionCalls: Int = 0
    var archival: CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)
    var restoredAt: Instant? = null
    var purgeAfter: Instant? = null
    var exposeSavedRows: Boolean = true
    var lookupCalls: Int = 0
    var purgeCalls: Int = 0
    var purgeResult: CompletableFuture<Int> = CompletableFuture.completedFuture(0)

    override fun savePair(
        first: PlayerStateEscrow,
        second: PlayerStateEscrow,
    ): CompletableFuture<Unit> {
        saved = listOf(first, second)
        return commit
    }

    override fun findPending(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> {
        lookupCalls++
        return CompletableFuture.completedFuture(saved?.takeIf { exposeSavedRows }?.firstOrNull { it.playerId == playerId })
    }

    override fun pending(serverId: ServerId): CompletableFuture<List<PlayerStateEscrow>> =
        CompletableFuture.completedFuture(saved.orEmpty().filter { it.serverId == serverId })

    override fun retainRestored(
        snapshot: PlayerStateEscrow,
        restoredAt: Instant,
        purgeAfter: Instant,
    ): CompletableFuture<Boolean> {
        retentionCalls++
        retained = snapshot.playerId.value
        this.restoredAt = restoredAt
        this.purgeAfter = purgeAfter
        return archival
    }

    override fun purgeRetained(cutoff: Instant): CompletableFuture<Int> {
        purgeCalls++
        return purgeResult
    }
}
