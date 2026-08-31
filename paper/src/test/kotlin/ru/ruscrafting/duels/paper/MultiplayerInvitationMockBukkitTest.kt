package ru.ruscrafting.duels.paper

import com.google.gson.JsonParser
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
import ru.arc.paper.network.BackendTransferResult
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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

    "setup refuses an impossible group match before sending invitations" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(
                    paper,
                    listOf("GroupHost", "Alpha", "Bravo"),
                    configureArena = { plugin ->
                        plugin.config.set("arenas.example.first-spawn.z", -1.0)
                        plugin.config.set("arenas.example.second-spawn.z", 1.0)
                    },
                ).use { harness ->
                    val gui = harness.registerGui()
                    val host = harness.players[0].apply { setLocale(Locale.ENGLISH) }

                    gui.open(host)
                    host.click(10)
                    host.click(11)
                    host.click(34)

                    host.nextPlainMessage().contains("no suitable group arena") shouldBe true
                    harness.players.drop(1).all { it.nextComponentMessage() == null } shouldBe true
                    host.openInventory.topInventory.getItem(36)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    harness.manager.activeCount() shouldBe 0
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

    "local invitation cannot be accepted after its wall-clock deadline when ticks stall" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                var settings = defaultRuntimeSettingsForInvitation().copy(multiplayerInvitationTimeout = Duration.ofSeconds(5))
                val clock = MutableInvitationClock(Instant.parse("2026-08-31T12:00:00Z"))
                multiplayerHarness(
                    paper,
                    listOf("DeadlineHost", "DeadlineAlpha", "DeadlineBravo"),
                    runtimeSettings = { settings },
                ).use { harness ->
                    val gui = harness.registerGui(clock = clock)
                    val host = harness.players[0].apply { setLocale(Locale.ENGLISH) }
                    val alpha = harness.players[1].apply { setLocale(Locale.ENGLISH) }

                    gui.open(host)
                    host.click(10)
                    host.click(11)
                    host.click(34)
                    val lobbyId = alpha.nextInvitationLobbyId()
                    gui.openInvitation(alpha, lobbyId)
                    alpha.openInventory.topInventory.getItem(34)?.type shouldBe Material.LIME_CONCRETE

                    clock.advance(Duration.ofSeconds(6))
                    alpha.click(34)

                    harness.manager.activeCount() shouldBe 0
                    gui.activeFlowCount() shouldBe 0
                    alpha.nextPlainMessage().contains("expired") shouldBe true
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

    "remote acceptance stays retryable when the response cannot be published" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    RemoteFixture(harness).use { fixture ->
                        val player = harness.players.single().apply { setLocale(Locale.ENGLISH) }
                        val offer = fixture.offer(player)
                        fixture.deliver(offer)
                        requireNotNull(player.nextComponentMessage())
                        fixture.gui.openInvitation(player, offer.lobbyId)
                        fixture.failLocalPublishes()

                        player.click(34)

                        player.openInventory.topInventory.getItem(34)?.type shouldBe Material.LIME_CONCRETE
                        fixture.responses(GroupLobbyResponse.ACCEPTED) shouldHaveSize 0
                        fixture.gui.activeFlowCount() shouldBe 1
                        player.nextPlainMessage().contains("unavailable") shouldBe true
                    }
                }
            }
        }
    }

    "unknown shared kit offer fails closed before invitation state is retained" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    RemoteFixture(harness).use { fixture ->
                        val player = harness.players.single().apply { setLocale(Locale.ENGLISH) }
                        val offer = fixture.offer(player, sharedKitId = KitId("not-a-real-kit"))

                        fixture.deliver(offer)

                        fixture.responses(GroupLobbyResponse.FAILED).map { it.lobbyId } shouldContainExactly
                            listOf(offer.lobbyId)
                        player.nextPlainMessage().contains("no longer available") shouldBe true
                        fixture.gui.activeFlowCount() shouldBe 0

                        fixture.gui.openInvitation(player, offer.lobbyId)

                        (player.openInventory.topInventory as Inventory?) shouldBe null
                        player.nextPlainMessage().contains("no longer available") shouldBe true
                        fixture.gui.activeFlowCount() shouldBe 0
                    }
                }
            }
        }
    }

    "over-limit remote offer is rejected before chat or invitation state" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    val now = Instant.parse("2026-08-31T00:00:00Z")
                    val clock = MutableInvitationClock(now)
                    RemoteFixture(harness, clock = clock).use { fixture ->
                        val player = harness.players.single()
                        val acceptedWindow =
                            CrossServerGroupMessage.MAX_FUTURE_TTL_MILLIS +
                                CrossServerGroupMessage.CLOCK_SKEW_ALLOWANCE_MILLIS
                        val offer = fixture.offer(player, expiresAt = now.toEpochMilli() + acceptedWindow + 1L)

                        fixture.deliverWithWireExpiry(offer, offer.expiresAtEpochMillis)

                        player.nextComponentMessage() shouldBe null
                        fixture.gui.activeFlowCount() shouldBe 0
                    }
                }
            }
        }
    }

    "over-limit preparation cannot snapshot or transfer and does not poison a valid retry" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                    every { duelSessions.storeOriginSnapshot(any(), any(), true) } returns
                        CompletableFuture.completedFuture(mockk())
                    val transfers = mutableListOf<Pair<UUID, ServerId>>()
                    val now = Instant.parse("2026-08-31T00:00:00Z")
                    val clock = MutableInvitationClock(now)
                    RemoteFixture(
                        harness,
                        duelSessions = duelSessions,
                        transfer = PlayerTransfer { player, server ->
                            transfers += player.uniqueId to server
                            BackendTransferResult.SENT
                        },
                        clock = clock,
                    ).use { fixture ->
                        val player = harness.players.single()
                        val offer = fixture.offer(player, expiresAt = now.plusSeconds(30).toEpochMilli())
                        fixture.deliver(offer)
                        requireNotNull(player.nextComponentMessage())
                        fixture.gui.openInvitation(player, offer.lobbyId)
                        player.click(34)
                        val acceptedWindow =
                            CrossServerGroupMessage.MAX_FUTURE_TTL_MILLIS +
                                CrossServerGroupMessage.CLOCK_SKEW_ALLOWANCE_MILLIS
                        val prepare = fixture.prepare(offer)
                        val overLimit =
                            prepare.copy(
                                messageId = "${prepare.messageId}:over-limit",
                                expiresAtEpochMillis = now.toEpochMilli() + acceptedWindow + 1L,
                            )

                        fixture.deliverWithWireExpiry(overLimit, overLimit.expiresAtEpochMillis)

                        verify(exactly = 0) { duelSessions.storeOriginSnapshot(any(), player, true) }
                        transfers shouldBe emptyList()
                        fixture.deliver(prepare)

                        verify(exactly = 1) { duelSessions.storeOriginSnapshot(any(), player, true) }
                        transfers shouldContainExactly listOf(player.uniqueId to fixture.hostServer)
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
                    val clock = MutableInvitationClock(now)
                    RemoteFixture(harness, clock = clock).use { fixture ->
                        val player = harness.players.single().apply { setLocale(Locale.ENGLISH) }
                        val first = fixture.offer(player, expiresAt = now.plusMillis(100).toEpochMilli())
                        fixture.deliver(first)
                        requireNotNull(player.nextComponentMessage())
                        fixture.gui.openInvitation(player, first.lobbyId)

                        paper.performTicks(2)

                        player.openInventory.topInventory.getItem(34)?.type shouldBe Material.LIME_CONCRETE
                        fixture.responses(GroupLobbyResponse.FAILED) shouldHaveSize 0
                        clock.advance(Duration.ofMillis(101))
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
                        transfer = PlayerTransfer { player, server ->
                            transfers += player.uniqueId to server
                            BackendTransferResult.SENT
                        },
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
                        transfer = PlayerTransfer { player, server ->
                            transfers += player.uniqueId to server
                            BackendTransferResult.SENT
                        },
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

    listOf(
        BackendTransferResult.PLAYER_OFFLINE,
        BackendTransferResult.TRANSFER_CLOSED,
        BackendTransferResult.SEND_FAILED,
    ).forEach { transferResult ->
        "group preparation fails closed when backend transfer returns $transferResult" {
            MockBukkitTestRuntime.open().use { paper ->
                failOnUnsupportedMockBukkitOperation {
                    multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                        val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                        every { duelSessions.storeOriginSnapshot(any(), any(), true) } returns
                            CompletableFuture.completedFuture(mockk())
                        every { duelSessions.requestRecovery(any()) } returns true
                        var transferCalls = 0
                        RemoteFixture(
                            harness,
                            duelSessions = duelSessions,
                            transfer = PlayerTransfer { _, _ ->
                                transferCalls += 1
                                transferResult
                            },
                        ).use { fixture ->
                            val player = harness.players.single().apply { setLocale(Locale.ENGLISH) }
                            val offer = fixture.offer(player)
                            fixture.deliver(offer)
                            requireNotNull(player.nextComponentMessage())
                            fixture.gui.openInvitation(player, offer.lobbyId)
                            player.click(34)

                            val prepare = fixture.prepare(offer)
                            fixture.deliver(prepare)
                            paper.performTicks(2)

                            transferCalls shouldBe 1
                            fixture.observed.filter {
                                it.type == GroupLobbyMessageType.READY && it.sourceServer == fixture.localServer
                            } shouldHaveSize 1
                            fixture.responses(GroupLobbyResponse.FAILED) shouldHaveSize 1
                            fixture.gui.activeFlowCount() shouldBe 0
                            verify(exactly = 1) { duelSessions.requestRecovery(player) }
                            player.nextPlainMessage().contains("Everyone is ready") shouldBe true
                            player.nextPlainMessage().contains("unavailable") shouldBe true

                            fixture.deliver(prepare.copy(messageId = "${prepare.messageId}:retry"))

                            transferCalls shouldBe 1
                            verify(exactly = 1) { duelSessions.storeOriginSnapshot(any(), player, true) }
                            verify(exactly = 1) { duelSessions.requestRecovery(player) }
                        }
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
                    val snapshot = CompletableFuture<StoredPlayerSnapshot>()
                    every { duelSessions.storeOriginSnapshot(any(), any(), true) } returns snapshot
                    every { duelSessions.requestRecovery(any()) } returnsMany listOf(false, true)
                    val transfers = mutableListOf<Pair<UUID, ServerId>>()
                    RemoteFixture(
                        harness,
                        duelSessions = duelSessions,
                        transfer = PlayerTransfer { player, server ->
                            transfers += player.uniqueId to server
                            BackendTransferResult.SENT
                        },
                    ).use { fixture ->
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

                        snapshot.complete(mockk())
                        paper.performTicks(2)

                        fixture.observed.none {
                            it.type == GroupLobbyMessageType.READY && it.sourceServer == fixture.localServer
                        } shouldBe true
                        transfers shouldBe emptyList()
                        fixture.gui.activeFlowCount() shouldBe 0
                        verify(exactly = 2) { duelSessions.requestRecovery(player) }
                        verify(exactly = 1) { duelSessions.handleJoin(player) }
                    }
                }
            }
        }
    }

    "snapshot completion after the wall deadline recovers without ready or transfer" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                    val snapshot = CompletableFuture<StoredPlayerSnapshot>()
                    every { duelSessions.storeOriginSnapshot(any(), any(), true) } returns snapshot
                    every { duelSessions.requestRecovery(any()) } returns true
                    val transfers = mutableListOf<Pair<UUID, ServerId>>()
                    val now = Instant.parse("2026-08-31T00:00:00Z")
                    val clock = MutableInvitationClock(now)
                    RemoteFixture(
                        harness,
                        duelSessions = duelSessions,
                        transfer = PlayerTransfer { player, server ->
                            transfers += player.uniqueId to server
                            BackendTransferResult.SENT
                        },
                        clock = clock,
                    ).use { fixture ->
                        val player = harness.players.single()
                        val offer = fixture.offer(player, expiresAt = now.plusSeconds(30).toEpochMilli())
                        fixture.deliver(offer)
                        requireNotNull(player.nextComponentMessage())
                        fixture.gui.openInvitation(player, offer.lobbyId)
                        player.click(34)
                        fixture.deliver(fixture.prepare(offer))
                        verify(exactly = 1) { duelSessions.storeOriginSnapshot(any(), player, true) }

                        clock.advance(Duration.ofSeconds(31))
                        snapshot.complete(mockk())
                        paper.performTicks(2)

                        fixture.responses(GroupLobbyResponse.FAILED).map { it.lobbyId } shouldContainExactly
                            listOf(offer.lobbyId)
                        fixture.observed.none {
                            it.type == GroupLobbyMessageType.READY && it.sourceServer == fixture.localServer
                        } shouldBe true
                        transfers shouldBe emptyList()
                        fixture.gui.activeFlowCount() shouldBe 0
                        verify(exactly = 1) { duelSessions.requestRecovery(player) }
                        verify(exactly = 0) { duelSessions.handleJoin(player) }
                    }
                }
            }
        }
    }
})

private fun defaultRuntimeSettingsForInvitation(): ArcDuelsRuntimeSettings =
    ArcDuelsRuntimeSettings.parse(org.bukkit.configuration.MemoryConfiguration()).settings

private class MutableInvitationClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableInvitationClock(current, zone)

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

private class RemoteFixture(
    private val harness: MultiplayerHarness,
    duelSessions: DuelSessionManager = mockk(relaxed = true),
    transfer: PlayerTransfer = PlayerTransfer { _, _ -> BackendTransferResult.SENT },
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    val hostServer = ServerId("parkour")
    val localServer = ServerId("group-test")
    private val hostRedis = InMemoryRedis(ServerIdentity { hostServer.value })
    private val localStorage = InMemoryRedis(ServerIdentity { localServer.value })
    private val localRedis = ControllablePublishRedis(localStorage)
    private val hostBus = CrossServerGroupBus(hostRedis, hostServer, clock = clock)
    private val localBus = CrossServerGroupBus(localRedis, localServer, clock = clock)
    val observed = mutableListOf<CrossServerGroupMessage>()
    private val observation = localBus.subscribe(observed::add)
    val gui = harness.registerGui(groupBus = localBus, transfer = transfer, duelSessions = duelSessions, clock = clock)

    fun failLocalPublishes() {
        localRedis.failPublish = true
    }

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
        localStorage.simulateExternalMessage(CrossServerGroupBus.CHANNEL, payload, hostServer.value)
        harness.paper.performTicks(2)
    }

    fun deliverWithWireExpiry(message: CrossServerGroupMessage, expiresAtEpochMillis: Long) {
        val encodable = message.copy(expiresAtEpochMillis = clock.millis() + 30_000L)
        hostBus.publish(encodable)
        val payload =
            JsonParser.parseString(
                hostRedis.getPublishedMessages().last { it.channel == CrossServerGroupBus.CHANNEL }.message,
            ).asJsonObject.apply {
                addProperty("expiresAtEpochMillis", expiresAtEpochMillis)
            }
        localStorage.simulateExternalMessage(CrossServerGroupBus.CHANNEL, payload.toString(), hostServer.value)
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

private class ControllablePublishRedis(
    private val delegate: InMemoryRedis,
) : RedisOperations by delegate {
    var failPublish: Boolean = false

    override fun publish(channel: String, message: String) {
        check(!failPublish) { "planned publish failure" }
        delegate.publish(channel, message)
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
