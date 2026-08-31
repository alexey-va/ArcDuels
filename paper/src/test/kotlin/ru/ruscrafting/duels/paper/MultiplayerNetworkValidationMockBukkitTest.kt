package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.network.BackendTransferResult
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.redis.CrossServerGroupBus
import ru.ruscrafting.duels.redis.CrossServerGroupMessage
import ru.ruscrafting.duels.redis.GroupLobbyMessageType
import ru.ruscrafting.duels.redis.GroupLobbyResponse
import ru.ruscrafting.duels.redis.NetworkPlayerDirectory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MultiplayerNetworkValidationMockBukkitTest : StringSpec({
    "mismatched response participants do not mutate the local lobby" {
        withNetworkScenario { paper ->
            multiplayerHarness(paper, listOf("GroupHost", "LocalMate")).use { harness ->
                networkLobby(harness).use { scenario ->
                    val mismatched = scenario.offer.copy(
                        messageId = "${scenario.offer.lobbyId}:response:mismatch",
                        sourceServer = ServerId("parkour"),
                        type = GroupLobbyMessageType.RESPONSE,
                        participants = scenario.offer.participants.mapIndexed { index, participant ->
                            if (index == 1) participant.copy(playerId = PlayerId(UUID.randomUUID())) else participant
                        },
                        targetId = PlayerId(scenario.remoteId),
                        response = GroupLobbyResponse.ACCEPTED,
                        kitId = requireNotNull(scenario.offer.sharedKitId),
                    )

                    deliver(scenario, mismatched)

                    harness.players.first().openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK
                    harness.manager.activeCount() shouldBe 0
                }
            }
        }
    }

    "duplicate accepted response publishes preparation only once" {
        withNetworkScenario { paper ->
            multiplayerHarness(paper, listOf("GroupHost", "LocalMate")).use { harness ->
                networkLobby(harness).use { scenario ->
                    val accepted = scenario.offer.copy(
                        messageId = "${scenario.offer.lobbyId}:response:${scenario.remoteId}:accepted",
                        sourceServer = ServerId("parkour"),
                        type = GroupLobbyMessageType.RESPONSE,
                        targetId = PlayerId(scenario.remoteId),
                        response = GroupLobbyResponse.ACCEPTED,
                        kitId = requireNotNull(scenario.offer.sharedKitId),
                    )

                    deliver(scenario, accepted)
                    deliver(scenario, accepted.copy(messageId = "${accepted.messageId}:retry"))

                    scenario.sent.count { it.type == GroupLobbyMessageType.PREPARE } shouldBe 1
                    harness.manager.activeCount() shouldBe 0
                }
            }
        }
    }

    "unknown remote kit fails closed and cancels the lobby" {
        withNetworkScenario { paper ->
            multiplayerHarness(paper, listOf("GroupHost", "LocalMate")).use { harness ->
                networkLobby(harness).use { scenario ->
                    val invalid = scenario.offer.copy(
                        messageId = "${scenario.offer.lobbyId}:response:${scenario.remoteId}:invalid",
                        sourceServer = ServerId("parkour"),
                        type = GroupLobbyMessageType.RESPONSE,
                        targetId = PlayerId(scenario.remoteId),
                        response = GroupLobbyResponse.ACCEPTED,
                        kitId = KitId("not-a-real-kit"),
                    )

                    deliver(scenario, invalid)

                    harness.players.first().openInventory.topInventory shouldBe null
                    harness.manager.activeCount() shouldBe 0
                }
            }
        }
    }

    "cancellation with a colliding lobby id cannot cancel a different lobby contract" {
        withNetworkScenario { paper ->
            multiplayerHarness(paper, listOf("GroupHost", "LocalMate")).use { harness ->
                networkLobby(harness).use { scenario ->
                    val remote = scenario.offer.participant(PlayerId(scenario.remoteId))
                    val forgedCancellation =
                        scenario.offer.copy(
                            messageId = "${scenario.offer.lobbyId}:cancel:forged-contract",
                            sourceServer = ServerId("parkour"),
                            type = GroupLobbyMessageType.CANCEL,
                            hostId = remote.playerId,
                            hostServer = remote.originServer,
                            participants =
                                listOf(remote) +
                                    scenario.offer.participants.filterNot { it.playerId == remote.playerId },
                            targetId = null,
                        )

                    deliver(scenario, forgedCancellation)

                    harness.players.first().openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK
                    val accepted =
                        scenario.offer.copy(
                            messageId = "${scenario.offer.lobbyId}:response:${scenario.remoteId}:accepted-after-forgery",
                            sourceServer = ServerId("parkour"),
                            type = GroupLobbyMessageType.RESPONSE,
                            targetId = PlayerId(scenario.remoteId),
                            response = GroupLobbyResponse.ACCEPTED,
                            kitId = requireNotNull(scenario.offer.sharedKitId),
                        )
                    deliver(scenario, accepted)

                    scenario.sent.count { it.type == GroupLobbyMessageType.PREPARE } shouldBe 1
                }
            }
        }
    }

    "ready arriving before preparation is ignored until a valid post-prepare ready arrives" {
        withNetworkScenario { paper ->
            multiplayerHarness(paper, listOf("GroupHost", "LocalMate")).use { harness ->
                networkLobby(harness).use { scenario ->
                    val ready = scenario.offer.copy(
                        messageId = "${scenario.offer.lobbyId}:ready:${scenario.remoteId}:parkour",
                        sourceServer = ServerId("parkour"),
                        type = GroupLobbyMessageType.READY,
                        targetId = PlayerId(scenario.remoteId),
                    )

                    deliver(scenario, ready)
                    val accepted = scenario.offer.copy(
                        messageId = "${scenario.offer.lobbyId}:response:${scenario.remoteId}:accepted",
                        sourceServer = ServerId("parkour"),
                        type = GroupLobbyMessageType.RESPONSE,
                        targetId = PlayerId(scenario.remoteId),
                        response = GroupLobbyResponse.ACCEPTED,
                        kitId = requireNotNull(scenario.offer.sharedKitId),
                    )
                    deliver(scenario, accepted)

                    val transferredRemote = PlayerMock(paper.server, "RemotePlayer", scenario.remoteId)
                    paper.server.addPlayer(transferredRemote)
                    paper.performTicks(10)

                    scenario.sent.none { it.type == GroupLobbyMessageType.CANCEL } shouldBe true
                    harness.manager.activeCount() shouldBe 0

                    deliver(
                        scenario,
                        ready.copy(
                            messageId = "${ready.messageId}:mismatched-layout",
                            layout = MultiplayerLayout.TWO_TEAMS,
                        ),
                    )
                    paper.performTicks(10)

                    scenario.sent.none { it.type == GroupLobbyMessageType.CANCEL } shouldBe true
                    harness.manager.activeCount() shouldBe 0

                    deliver(scenario, ready.copy(messageId = "${ready.messageId}:after-prepare"))
                    paper.performTicks(10)

                    harness.manager.activeCount() shouldBe 0
                    withClue("messages=${scenario.sent.map { it.type to it.messageId }}") {
                        scenario.sent.count { it.type == GroupLobbyMessageType.CANCEL } shouldBe 1
                    }
                }
            }
        }
    }

    "expired preparation is ignored after the lobby has been cancelled" {
        withNetworkScenario { paper ->
            multiplayerHarness(paper, listOf("GroupHost", "LocalMate")).use { harness ->
                val clock = MutableTestClock(Instant.parse("2026-08-31T00:00:00Z"))
                networkLobby(harness, clock).use { scenario ->
                    val prepare = scenario.offer.copy(
                        messageId = "${scenario.offer.lobbyId}:prepare:${scenario.offer.hostId.value}",
                        type = GroupLobbyMessageType.PREPARE,
                        targetId = null,
                        response = null,
                        kitId = null,
                    )

                    clock.advanceTo(Instant.ofEpochMilli(scenario.offer.expiresAtEpochMillis + 1L))
                    paper.performTicks(900)
                    val responsesBefore = scenario.sent.count { it.type == GroupLobbyMessageType.RESPONSE }
                    val cancellationsBefore = scenario.sent.count { it.type == GroupLobbyMessageType.CANCEL }
                    scenario.bus.publish(prepare)
                    paper.performTicks(2)

                    harness.players.first().openInventory.topInventory shouldBe null
                    harness.manager.activeCount() shouldBe 0
                    withClue("messages=${scenario.sent.map { it.type to it.messageId }}") {
                        scenario.sent.count { it.type == GroupLobbyMessageType.RESPONSE } shouldBe responsesBefore
                        scenario.sent.count { it.type == GroupLobbyMessageType.CANCEL } shouldBe cancellationsBefore
                    }
                }
            }
        }
    }
})

private data class NetworkScenario(
    val harness: MultiplayerHarness,
    val redis: InMemoryRedis,
    val sent: MutableList<CrossServerGroupMessage>,
    val offer: CrossServerGroupMessage,
    val remoteId: UUID,
    val bus: CrossServerGroupBus,
    val gui: MultiplayerGuiService,
    val observation: AutoCloseable,
    val networkPlayers: NetworkPlayerDirectory,
) : AutoCloseable {
    override fun close() {
        gui.close()
        observation.close()
        bus.close()
        networkPlayers.close()
    }
}

private fun networkLobby(harness: MultiplayerHarness, clock: Clock = Clock.systemUTC()): NetworkScenario {
    val redis = InMemoryRedis(ServerIdentity { "group-test" })
    val networkPlayers = NetworkPlayerDirectory(redis)
    val remoteId = UUID.randomUUID()
    redis.simulateExternalMessage(
        ru.ruscrafting.duels.redis.NetworkPlayerDirectory.CHANNEL,
        "[{\"username\":\"RemotePlayer\",\"uuid\":\"$remoteId\",\"server\":\"parkour\",\"joinTime\":1}]",
        "proxy",
    )
    val bus = CrossServerGroupBus(redis, ServerId("group-test"))
    val sent = mutableListOf<CrossServerGroupMessage>()
    val observation = bus.subscribe(sent::add)
    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
    every {
        duelSessions.storeOriginSnapshot(any<MatchId>(), any(), any())
    } returns CompletableFuture.completedFuture(mockk(relaxed = true))
    val gui = harness.registerGui(
        targets = DuelTargetDirectory(harness.plugin, ServerId("group-test"), networkPlayers),
        groupBus = bus,
        transfer = PlayerTransfer { _, _ -> BackendTransferResult.SENT },
        duelSessions = duelSessions,
        clock = clock,
    )
    val host = harness.players.first()
    host.setLocale(java.util.Locale.ENGLISH)
    gui.open(host)
    host.click(10)
    host.click(11)
    host.click(34)
    val local = harness.players[1]
    val invitation = requireNotNull(local.nextComponentMessage())
    val lobbyId = UUID.fromString(invitation.runCommands().first { it.startsWith("/duel group open ") }.substringAfterLast(' '))
    gui.openInvitation(local, lobbyId)
    local.click(34)
    val offer = sent.single { it.type == GroupLobbyMessageType.OFFER && it.targetId?.value == remoteId }
    return NetworkScenario(harness, redis, sent, offer, remoteId, bus, gui, observation, networkPlayers)
}

private fun withNetworkScenario(block: (MockBukkitTestRuntime) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation { block(paper) }
    }
}

private fun deliver(scenario: NetworkScenario, message: CrossServerGroupMessage) {
    val remoteRedis = InMemoryRedis(ServerIdentity { "parkour" })
    val remoteBus = CrossServerGroupBus(remoteRedis, ServerId("parkour"))
    remoteBus.publish(message)
    val payload = remoteRedis.getPublishedMessages().single { it.channel == CrossServerGroupBus.CHANNEL }.message
    scenario.redis.simulateExternalMessage(CrossServerGroupBus.CHANNEL, payload, "parkour")
    scenario.harness.paper.performTicks(2)
    remoteBus.close()
}

private class MutableTestClock(private var current: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId): Clock = this
    override fun instant(): Instant = current
    fun advanceTo(instant: Instant) {
        current = instant
    }
    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
