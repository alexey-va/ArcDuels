package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.*
import ru.ruscrafting.duels.redis.*
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RemoteGroupArenaTest : StringSpec({
    "remote arena waits for every saved origin and data readiness before starting once" {
        MockBukkitTestRuntime.open().use { paper ->
            multiplayerHarness(paper, listOf("GroupOne", "GroupTwo", "GroupThree")).use { harness ->
                val origin = ServerId("survival")
                val destination = ServerId("parkour")
                val redis = InMemoryRedis(ServerIdentity { destination.value })
                val sourceRedis = InMemoryRedis(ServerIdentity { origin.value })
                CrossServerGroupBus(redis, destination).use { bus ->
                    val reported = mutableListOf<GroupLobbyMessageType>()
                    bus.subscribe { reported += it.type }
                    CrossServerGroupBus(sourceRedis, origin).use { source ->
                        val sessions = mockk<MultiplayerSessionManager>(relaxed = true)
                        val duels = mockk<DuelSessionManager>(relaxed = true)
                        every { sessions.hasArenaCapacity(any()) } returns true
                        every { sessions.startNetwork(any(), any(), any(), any(), any()) } returns CompletableFuture.completedFuture(MatchId(UUID.randomUUID()))
                        var dataReady = false
                        val message = CrossServerGroupMessage(
                            messageId = "group:prepare", sourceServer = origin, type = GroupLobbyMessageType.PREPARE,
                            lobbyId = UUID.randomUUID(), hostId = PlayerId(harness.players.first().uniqueId), hostServer = origin,
                            participants = harness.players.map { NetworkGroupParticipant(PlayerId(it.uniqueId), it.name, origin) },
                            layout = MultiplayerLayout.FREE_FOR_ALL, kitPolicy = MultiplayerKitPolicy.SHARED,
                            sharedKitId = KitId("classic"), expiresAtEpochMillis = System.currentTimeMillis() + 30_000,
                            arenaServer = destination, participantKits = harness.players.associate { PlayerId(it.uniqueId) to KitId("classic") },
                        )
                        fun deliver(value: CrossServerGroupMessage) {
                            source.publish(value)
                            val payload = sourceRedis.getPublishedMessages().last().message
                            redis.simulateExternalMessage(CrossServerGroupBus.CHANNEL, payload, origin.value)
                            paper.performTicks(2)
                        }
                        RemoteGroupArena(destination, bus, sessions, duels, harness.kits, harness.tasks,
                            paper.server::getPlayer, { dataReady }).use {
                            deliver(message)
                            message.participants.forEach { member -> deliver(message.copy(
                                messageId = "group:ready:${member.playerId}", type = GroupLobbyMessageType.READY, targetId = member.playerId,
                            )) }
                            verify(exactly = 0) { sessions.startNetwork(any(), any(), any(), any(), any()) }
                            dataReady = true
                            paper.performTicks(3)
                            verify(exactly = 1) { sessions.startNetwork(MatchId(message.lobbyId), any(), any(), any(), any()) }
                            reported shouldContain GroupLobbyMessageType.STARTED
                            paper.performTicks(3)
                            verify(exactly = 1) { sessions.startNetwork(any(), any(), any(), any(), any()) }
                        }
                    }
                }
            }
        }
    }
})
