package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.redis.NetworkPlayerDirectory
import java.util.UUID

class DuelTargetDirectoryTest : StringSpec({
    "merges ProxyARC players with authoritative local Bukkit players" {
        val server = MockBukkit.mock()
        try {
            val plugin = MockBukkit.load(ArcDuelsPlugin::class.java)
            val local = server.addPlayer("LocalPlayer")
            val remoteId = UUID.randomUUID()
            val ghostId = UUID.randomUUID()
            val redis = InMemoryRedis(ServerIdentity { "spawn" })
            val network = NetworkPlayerDirectory(redis)
            redis.simulateExternalMessage(
                NetworkPlayerDirectory.CHANNEL,
                """[{"username":"StaleLocalName","server":"survival","uuid":"${local.uniqueId}","joinTime":1},{"username":"RemotePlayer","server":"parkour","uuid":"$remoteId","joinTime":2},{"username":"LocalGhost","server":"spawn","uuid":"$ghostId","joinTime":3}]""",
                "proxy",
            )
            val directory = DuelTargetDirectory(plugin, ServerId("spawn"), network)

            directory.players().map(DuelTarget::name) shouldContainExactlyInAnyOrder listOf("LocalPlayer", "RemotePlayer")
            directory.find(local.uniqueId)?.local shouldBe true
            directory.find(remoteId)?.server shouldBe ServerId("parkour")
            directory.find(ghostId) shouldBe null
            network.close()
        } finally {
            MockBukkit.unmock()
        }
    }
})
