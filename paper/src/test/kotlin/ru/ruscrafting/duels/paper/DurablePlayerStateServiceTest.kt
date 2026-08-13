package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
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
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DurablePlayerStateServiceTest : StringSpec({
    lateinit var server: ServerMock
    lateinit var plugin: ArcDuelsPlugin

    beforeSpec {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(ArcDuelsPlugin::class.java)
        server.addSimpleWorld("world")
    }

    afterSpec { MockBukkit.unmock() }

    "pair becomes usable only after one durable commit and exact acknowledgement clears it" {
        val repository = GatedEscrowRepository()
        val service = DurablePlayerStateService(plugin, ServerId("test-node"), repository)
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

        service.acknowledge(stored.getValue(first.uniqueId)).get()
        service.isPending(first.uniqueId) shouldBe false
        repository.acknowledged shouldBe first.uniqueId
    }
})

private class GatedEscrowRepository : PlayerStateEscrowRepository {
    val commit = CompletableFuture<Unit>()
    var saved: List<PlayerStateEscrow>? = null
    var acknowledged: UUID? = null

    override fun savePair(
        first: PlayerStateEscrow,
        second: PlayerStateEscrow,
    ): CompletableFuture<Unit> {
        saved = listOf(first, second)
        return commit
    }

    override fun findPending(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> =
        CompletableFuture.completedFuture(saved?.firstOrNull { it.playerId == playerId })

    override fun pending(serverId: ServerId): CompletableFuture<List<PlayerStateEscrow>> =
        CompletableFuture.completedFuture(saved.orEmpty().filter { it.serverId == serverId })

    override fun acknowledgeRestored(snapshot: PlayerStateEscrow): CompletableFuture<Boolean> {
        acknowledged = snapshot.playerId.value
        return CompletableFuture.completedFuture(true)
    }
}
