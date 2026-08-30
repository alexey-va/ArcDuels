package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.redis.CrossServerGroupBus
import ru.ruscrafting.duels.redis.CrossServerGroupMessage
import ru.ruscrafting.duels.redis.GroupLobbyMessageType
import ru.ruscrafting.duels.redis.GroupLobbyResponse
import ru.ruscrafting.duels.redis.NetworkGroupParticipant
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

class MultiplayerInvitationMockBukkitTest : StringSpec({
    "setup enforces minimum teams bottom-inventory isolation and single-page chrome" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "Alpha", "Bravo")).use { harness ->
                    val gui = harness.registerGui()
                    val host = harness.players.first().apply { setLocale(Locale.ENGLISH) }

                    gui.open(host)
                    host.openInventory.topInventory.getItem(40)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.GRAY_CONCRETE
                    host.assertOpenInventoryItemsNonItalic()

                    host.click(28)
                    host.click(28)
                    host.openInventory.topInventory.getItem(28)?.type shouldBe Material.YELLOW_WOOL
                    host.click(10)
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.GRAY_CONCRETE
                    host.click(11)
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.LIME_CONCRETE

                    val selectedBefore = host.openInventory.topInventory.getItem(10).plainLore()
                    host.click(45).isCancelled shouldBe true
                    host.openInventory.topInventory.getItem(10).plainLore() shouldBe selectedBefore
                    host.assertOpenInventoryItemsNonItalic()
                    gui.close()
                }
            }
        }
    }

    "setup rejects a thirteenth participant without losing the draft" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                val names = listOf("GroupHost") + List(12) { "Candidate${it.toString().padStart(2, '0')}" }
                multiplayerHarness(paper, names).use { harness ->
                    val gui = harness.registerGui()
                    val host = harness.players.first().apply { setLocale(Locale.ENGLISH) }

                    gui.open(host)
                    (listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22)).forEach(host::click)
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.LIME_CONCRETE
                    host.click(43)
                    host.openInventory.topInventory.getItem(10).plainName() shouldBe "Candidate11"
                    host.click(10)
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.GRAY_CONCRETE
                    host.click(34)

                    host.openInventory.topInventory.getItem(36)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    host.nextPlainMessage().contains("Select 3 to 12 participants") shouldBe true
                    harness.players.drop(1).all { it.nextComponentMessage() == null } shouldBe true
                    gui.close()
                }
            }
        }
    }

    "local invitation opens only for the invited member and exact lobby id" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "Alpha", "Bravo")).use { harness ->
                    val gui = harness.registerGui()
                    val host = harness.players[0].apply { setLocale(Locale.ENGLISH) }
                    val alpha = harness.players[1].apply { setLocale(Locale.ENGLISH) }

                    gui.open(host)
                    host.click(10)
                    host.click(11)
                    host.click(34)
                    val lobbyId = alpha.nextInvitationLobbyId()

                    val wrongId = UUID.randomUUID()
                    gui.openInvitation(alpha, wrongId)
                    (alpha.openInventory.topInventory as Inventory?) shouldBe null
                    alpha.nextPlainMessage().contains("no longer available") shouldBe true

                    gui.openInvitation(host, lobbyId)
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK
                    host.nextPlainMessage().contains("no longer available") shouldBe true

                    gui.openInvitation(alpha, lobbyId)
                    alpha.openInventory.topInventory.getItem(34)?.type shouldBe Material.LIME_CONCRETE
                    alpha.assertOpenInventoryItemsNonItalic()
                    gui.close()
                }
            }
        }
    }

    "local chat decline cancels the lobby releases everyone and preserves unrelated inventories" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "Alpha", "Bravo")).use { harness ->
                    val gui = harness.registerGui()
                    val host = harness.players[0].apply { setLocale(Locale.ENGLISH) }
                    val alpha = harness.players[1].apply { setLocale(Locale.ENGLISH) }
                    val bravo = harness.players[2].apply { setLocale(Locale.ENGLISH) }

                    gui.open(host)
                    host.click(10)
                    host.click(11)
                    host.click(34)
                    val lobbyId = alpha.nextInvitationLobbyId()
                    requireNotNull(bravo.nextComponentMessage())
                    gui.openInvitation(alpha, lobbyId)
                    val unrelated = Bukkit.createInventory(null, 9)
                    bravo.openInventory(unrelated)

                    gui.declineInvitation(alpha, lobbyId)

                    (host.openInventory.topInventory as Inventory?) shouldBe null
                    (alpha.openInventory.topInventory as Inventory?) shouldBe null
                    bravo.openInventory.topInventory shouldBe unrelated
                    host.nextPlainMessage().contains("declined") shouldBe true

                    gui.open(host)
                    gui.open(alpha)
                    host.openInventory.topInventory.getItem(36)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    alpha.openInventory.topInventory.getItem(36)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    gui.close()
                }
            }
        }
    }

    "member quit cancels an inviting lobby while an unrelated player stays untouched" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "Alpha", "Bravo", "Observer")).use { harness ->
                    val gui = harness.registerGui()
                    val host = harness.players[0].apply { setLocale(Locale.ENGLISH) }
                    val alpha = harness.players[1]
                    val observer = harness.players[3]
                    val unrelated = Bukkit.createInventory(null, 9)

                    gui.open(host)
                    host.click(10)
                    host.click(11)
                    host.click(34)
                    observer.openInventory(unrelated)
                    alpha.disconnect() shouldBe true

                    (host.openInventory.topInventory as Inventory?) shouldBe null
                    observer.openInventory.topInventory shouldBe unrelated
                    host.nextPlainMessage().contains("left or became busy") shouldBe true
                    gui.open(host)
                    host.openInventory.topInventory.getItem(36)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    gui.close()
                }
            }
        }
    }

    "busy local targets are omitted before the host can invite them" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "Busy", "Free")).use { harness ->
                    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                    val busyId = harness.players[1].uniqueId
                    every { duelSessions.isEngaged(any()) } answers { (args[0] as Player).uniqueId == busyId }
                    every { duelSessions.isStateLocked(any()) } returns false
                    val gui = harness.registerGui(duelSessions = duelSessions)
                    val host = harness.players.first()

                    gui.open(host)

                    host.openInventory.topInventory.contents.filterNotNull()
                        .filter { it.type == Material.PLAYER_HEAD }
                        .map { it.plainName() } shouldContainExactly listOf("Free")
                    gui.close()
                }
            }
        }
    }

    "remote personal kit acceptance publishes the final kit once and becomes immutable" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    RemoteFixture(harness).use { fixture ->
                        val player = harness.players.single().apply { setLocale(Locale.ENGLISH) }
                        val offer = fixture.offer(player, kitPolicy = MultiplayerKitPolicy.PER_PLAYER, sharedKitId = null)
                        fixture.deliver(offer)
                        requireNotNull(player.nextComponentMessage())

                        fixture.gui.openInvitation(player, offer.lobbyId)
                        player.openInventory.topInventory.getItem(32)?.type shouldBe Material.BOW
                        player.click(32)
                        player.click(32)
                        player.openInventory.topInventory.getItem(32)?.type shouldBe Material.NETHERITE_AXE
                        player.click(34)
                        player.click(32)
                        player.click(34)

                        player.openInventory.topInventory.getItem(34)?.type shouldBe Material.LIME_DYE
                        player.openInventory.topInventory.getItem(32)?.type shouldBe Material.NETHERITE_AXE
                        player.assertOpenInventoryItemsNonItalic()
                        fixture.responses(GroupLobbyResponse.ACCEPTED).map { it.kitId } shouldContainExactly listOf(KitId("berserker"))
                    }
                }
            }
        }
    }

    "remote chat decline is idempotent and removes the invitation without opening a GUI" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    RemoteFixture(harness).use { fixture ->
                        val player = harness.players.single().apply { setLocale(Locale.ENGLISH) }
                        val offer = fixture.offer(player)
                        fixture.deliver(offer)
                        requireNotNull(player.nextComponentMessage())

                        fixture.gui.declineInvitation(player, offer.lobbyId)
                        fixture.gui.declineInvitation(player, offer.lobbyId)

                        (player.openInventory.topInventory as Inventory?) shouldBe null
                        fixture.responses(GroupLobbyResponse.DECLINED) shouldHaveSize 1
                        player.nextPlainMessage().contains("declined") shouldBe true
                        player.nextPlainMessage().contains("no longer available") shouldBe true
                    }
                }
            }
        }
    }

    "remote invitation timeout publishes failure closes only its own GUI and permits a fresh offer" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    val now = Instant.parse("2026-08-31T00:00:00Z")
                    val clock = Clock.fixed(now, ZoneOffset.UTC)
                    RemoteFixture(harness, clock = clock).use { fixture ->
                        val player = harness.players.single().apply { setLocale(Locale.ENGLISH) }
                        val first = fixture.offer(player, expiresAt = now.plusMillis(100).toEpochMilli())
                        fixture.deliver(first)
                        requireNotNull(player.nextComponentMessage())
                        fixture.gui.openInvitation(player, first.lobbyId)

                        paper.performTicks(2)

                        (player.openInventory.topInventory as Inventory?) shouldBe null
                        fixture.responses(GroupLobbyResponse.FAILED).map { it.lobbyId } shouldContainExactly listOf(first.lobbyId)
                        player.nextPlainMessage().contains("expired") shouldBe true

                        val second = fixture.offer(player, expiresAt = now.plusSeconds(30).toEpochMilli())
                        fixture.deliver(second)
                        player.nextInvitationLobbyId() shouldBe second.lobbyId
                    }
                }
            }
        }
    }

    "expired and busy remote offers fail closed without distracting the player" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    val player = harness.players.single()
                    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                    val now = Instant.parse("2026-08-31T00:00:00Z")
                    RemoteFixture(harness, duelSessions = duelSessions, clock = Clock.fixed(now, ZoneOffset.UTC)).use { fixture ->
                        val expired = fixture.offer(player, expiresAt = now.minusMillis(1).toEpochMilli())
                        fixture.deliver(expired)
                        player.nextComponentMessage() shouldBe null

                        every { duelSessions.isEngaged(player) } returns true
                        val busy = fixture.offer(player, expiresAt = now.plusSeconds(30).toEpochMilli())
                        fixture.deliver(busy)
                        player.nextComponentMessage() shouldBe null

                        fixture.responses(GroupLobbyResponse.FAILED).map { it.lobbyId } shouldContainExactly
                            listOf(expired.lobbyId, busy.lobbyId)
                    }
                }
            }
        }
    }

    "snapshot failure publishes one failed response and never transfers the player" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                    every { duelSessions.storeOriginSnapshot(any(), any(), true) } returns
                        CompletableFuture.failedFuture(IllegalStateException("snapshot unavailable"))
                    val transfers = mutableListOf<Pair<UUID, ServerId>>()
                    RemoteFixture(
                        harness,
                        duelSessions = duelSessions,
                        transfer = PlayerTransfer { player, server -> transfers += player.uniqueId to server },
                    ).use { fixture ->
                        val player = harness.players.single()
                        val offer = fixture.offer(player)
                        fixture.deliver(offer)
                        requireNotNull(player.nextComponentMessage())
                        fixture.gui.openInvitation(player, offer.lobbyId)
                        player.click(34)

                        fixture.deliver(fixture.prepare(offer))
                        paper.performTicks(2)

                        verify(exactly = 1) { duelSessions.storeOriginSnapshot(any(), player, true) }
                        fixture.responses(GroupLobbyResponse.FAILED) shouldHaveSize 1
                        transfers shouldBe emptyList()
                    }
                }
            }
        }
    }

    "duplicate preparation snapshots once then publishes one ready and transfers once" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                    val snapshot = CompletableFuture<StoredPlayerSnapshot>()
                    every { duelSessions.storeOriginSnapshot(any(), any(), true) } returns snapshot
                    val transfers = mutableListOf<Pair<UUID, ServerId>>()
                    RemoteFixture(
                        harness,
                        duelSessions = duelSessions,
                        transfer = PlayerTransfer { player, server -> transfers += player.uniqueId to server },
                    ).use { fixture ->
                        val player = harness.players.single()
                        val offer = fixture.offer(player)
                        fixture.deliver(offer)
                        requireNotNull(player.nextComponentMessage())
                        fixture.gui.openInvitation(player, offer.lobbyId)
                        player.click(34)

                        val prepare = fixture.prepare(offer)
                        fixture.deliver(prepare)
                        fixture.deliver(prepare.copy(messageId = "${prepare.messageId}:retry"))
                        verify(exactly = 1) { duelSessions.storeOriginSnapshot(any(), player, true) }

                        snapshot.complete(mockk())
                        paper.performTicks(2)

                        fixture.observed.filter { it.type == GroupLobbyMessageType.READY && it.sourceServer == fixture.localServer } shouldHaveSize 1
                        transfers shouldContainExactly listOf(player.uniqueId to fixture.hostServer)
                    }
                }
            }
        }
    }

    "network cancellation during preparation recovers the participant and preserves a new inventory" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                    every { duelSessions.storeOriginSnapshot(any(), any(), true) } returns CompletableFuture.completedFuture(mockk())
                    every { duelSessions.requestRecovery(any()) } returns false
                    RemoteFixture(harness, duelSessions = duelSessions).use { fixture ->
                        val player = harness.players.single()
                        val offer = fixture.offer(player)
                        fixture.deliver(offer)
                        requireNotNull(player.nextComponentMessage())
                        fixture.gui.openInvitation(player, offer.lobbyId)
                        player.click(34)
                        fixture.deliver(fixture.prepare(offer))
                        paper.performTicks(2)

                        val unrelated = Bukkit.createInventory(null, 9)
                        player.openInventory(unrelated)
                        fixture.deliver(fixture.cancel(offer))

                        player.openInventory.topInventory shouldBe unrelated
                        verify(exactly = 1) { duelSessions.requestRecovery(player) }
                        verify(exactly = 1) { duelSessions.handleJoin(player) }
                    }
                }
            }
        }
    }
})

private class RemoteFixture(
    private val harness: MultiplayerHarness,
    duelSessions: DuelSessionManager = mockk(relaxed = true),
    transfer: PlayerTransfer = PlayerTransfer { _, _ -> },
    clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    val hostServer = ServerId("parkour")
    val localServer = ServerId("group-test")
    private val hostRedis = InMemoryRedis(ServerIdentity { hostServer.value })
    private val localRedis = InMemoryRedis(ServerIdentity { localServer.value })
    private val hostBus = CrossServerGroupBus(hostRedis, hostServer)
    private val localBus = CrossServerGroupBus(localRedis, localServer)
    val observed = mutableListOf<CrossServerGroupMessage>()
    private val observation = localBus.subscribe(observed::add)
    val gui = harness.registerGui(groupBus = localBus, transfer = transfer, duelSessions = duelSessions, clock = clock)

    fun offer(
        player: Player,
        kitPolicy: MultiplayerKitPolicy = MultiplayerKitPolicy.SHARED,
        sharedKitId: KitId? = KitId("classic"),
        expiresAt: Long = System.currentTimeMillis() + 30_000L,
    ): CrossServerGroupMessage {
        val host = NetworkGroupParticipant(PlayerId(UUID.randomUUID()), "RemoteHost", hostServer)
        val teammate = NetworkGroupParticipant(PlayerId(UUID.randomUUID()), "RemoteMate", hostServer)
        val target = NetworkGroupParticipant(PlayerId(player.uniqueId), player.name, localServer)
        val lobbyId = UUID.randomUUID()
        return CrossServerGroupMessage(
            messageId = "$lobbyId:offer:${player.uniqueId}",
            sourceServer = hostServer,
            type = GroupLobbyMessageType.OFFER,
            lobbyId = lobbyId,
            hostId = host.playerId,
            hostServer = hostServer,
            participants = listOf(host, teammate, target),
            layout = MultiplayerLayout.FREE_FOR_ALL,
            kitPolicy = kitPolicy,
            sharedKitId = sharedKitId,
            expiresAtEpochMillis = expiresAt,
            targetId = target.playerId,
        )
    }

    fun prepare(offer: CrossServerGroupMessage): CrossServerGroupMessage =
        offer.copy(
            messageId = "${offer.lobbyId}:prepare:${hostServer.value}",
            type = GroupLobbyMessageType.PREPARE,
            targetId = null,
        )

    fun cancel(offer: CrossServerGroupMessage): CrossServerGroupMessage =
        offer.copy(
            messageId = "${offer.lobbyId}:cancel:${hostServer.value}",
            type = GroupLobbyMessageType.CANCEL,
            targetId = null,
        )

    fun deliver(message: CrossServerGroupMessage) {
        hostBus.publish(message)
        val payload = hostRedis.getPublishedMessages().last { it.channel == CrossServerGroupBus.CHANNEL }.message
        localRedis.simulateExternalMessage(CrossServerGroupBus.CHANNEL, payload, hostServer.value)
        harness.paper.performTicks(2)
    }

    fun responses(response: GroupLobbyResponse): List<CrossServerGroupMessage> =
        observed.filter {
            it.type == GroupLobbyMessageType.RESPONSE && it.sourceServer == localServer && it.response == response
        }

    override fun close() {
        gui.close()
        observation.close()
        localBus.close()
        hostBus.close()
    }
}

private fun PlayerMock.nextInvitationLobbyId(): UUID =
    UUID.fromString(
        requireNotNull(nextComponentMessage()).runCommands()
            .first { it.startsWith("/duel group open ") }
            .substringAfterLast(' '),
    )

private fun PlayerMock.nextPlainMessage(): String =
    PlainTextComponentSerializer.plainText().serialize(requireNotNull(nextComponentMessage()))

private fun PlayerMock.assertOpenInventoryItemsNonItalic() {
    openInventory.topInventory.contents.filterNotNull().forEach { item ->
        item.itemMeta.displayName()?.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        item.itemMeta.lore().orEmpty().forEach { line ->
            line.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        }
    }
}
