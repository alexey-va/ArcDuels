package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.inventory.Inventory
import org.bukkit.Material
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.simulate.entity.LivingEntitySimulation
import ru.arc.paper.network.BackendTransferResult
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.PaperAudienceEffectObservation
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.MultiplayerMatchState
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.redis.CrossServerGroupBus
import ru.ruscrafting.duels.redis.GroupLobbyMessageType
import ru.ruscrafting.duels.redis.CrossServerGroupMessage
import ru.ruscrafting.duels.redis.NetworkGroupParticipant
import ru.ruscrafting.duels.redis.NetworkPlayerDirectory
import java.time.Clock
import java.util.Locale
import java.util.concurrent.CompletableFuture

class MultiplayerMockBukkitIntegrationTest : StringSpec({
    "six players configure three teams and personal kits through the GUI before starting" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(
                    paper,
                    listOf("GroupHost", "Alpha", "Bravo", "Charlie", "Delta", "Echo"),
                ).use { harness ->
                    val gui = harness.registerGui()
                    val host = harness.players.first()
                    host.setLocale(Locale.ENGLISH)

                    gui.open(host)
                    host.openInventory.topInventory.getItem(40)?.type shouldBe Material.GRAY_STAINED_GLASS_PANE
                    (10..14).forEach { slot ->
                        host.click(slot).isCancelled shouldBe true
                    }
                    host.click(28)
                    host.click(28)
                    host.click(30)
                    host.click(32)
                    host.click(34)

                    val members = harness.players.drop(1)
                    val lobbyIds = members.map { member ->
                        val topBeforeAccept: Inventory? = member.openInventory.topInventory
                        topBeforeAccept shouldBe null
                        val invitation = requireNotNull(member.nextComponentMessage())
                        val commands = invitation.runCommands()
                        commands.any { it.startsWith("/duel group open ") } shouldBe true
                        commands.any { it.startsWith("/duel group decline ") } shouldBe true
                        val lobbyId = java.util.UUID.fromString(commands.first { it.startsWith("/duel group open ") }.substringAfterLast(' '))
                        gui.openInvitation(member, lobbyId)
                        member.openInventory.topInventory.getItem(34)?.type shouldBe Material.LIME_CONCRETE
                        lobbyId
                    }
                    lobbyIds.distinct().size shouldBe 1
                    members[0].click(32)
                    members[1].click(32)
                    members[1].click(32)
                    members.forEach { member ->
                        member.click(34).isCancelled shouldBe true
                    }

                    paper.performTicks(4)
                    harness.teleports.completeAll()
                    paper.performTicks(4)

                    val match = requireNotNull(harness.manager.matchFor(host))
                    match.state shouldBe MultiplayerMatchState.ACTIVE
                    match.roster.rules.layout shouldBe MultiplayerLayout.THREE_TEAMS
                    match.roster.rules.kitPolicy shouldBe MultiplayerKitPolicy.PER_PLAYER
                    match.roster.participants.map { it.team } shouldContainExactly listOf(1, 2, 3, 1, 2, 3)
                    match.roster.participants.map { it.kitId.value } shouldContainExactly
                        listOf("crossbow", "axe", "berserker", "archer", "archer", "archer")

                    host.inventory.getItem(0)?.type shouldBe Material.CROSSBOW
                    members[0].inventory.getItem(0)?.type shouldBe Material.DIAMOND_AXE
                    members[1].inventory.getItem(0)?.type shouldBe Material.NETHERITE_AXE
                    members[2].inventory.getItem(0)?.type shouldBe Material.BOW
                }
            }
        }
    }

    "setup pagination preserves selections across pages and cancellation releases every participant" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                val names = listOf("GroupHost") + List(14) { index -> "Candidate${index.toString().padStart(2, '0')}" }
                multiplayerHarness(paper, names).use { harness ->
                    val gui = harness.registerGui()
                    val host = harness.players.first()
                    host.setLocale(Locale.ENGLISH)

                    gui.open(host)
                    host.click(10)
                    host.click(43)
                    host.openInventory.topInventory.getItem(10).plainName() shouldBe "Candidate11"
                    host.click(10)
                    host.click(37)
                    host.openInventory.topInventory.getItem(10).plainLore().contains("selected / ready") shouldBe true

                    host.click(34)
                    val lobbyPlayers = host.openInventory.topInventory.contents
                        .filterNotNull()
                        .filter { it.type == Material.PLAYER_HEAD }
                        .map { it.plainName() }
                    lobbyPlayers shouldContainExactlyInAnyOrder listOf("GroupHost", "Candidate00", "Candidate11")

                    host.click(36)
                    gui.open(harness.players[1])
                    harness.players[1].openInventory.topInventory.getItem(36)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    harness.manager.activeCount() shouldBe 0
                }
            }
        }
    }

    "network player directory feeds the MockBukkit group picker and publishes a remote offer" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "Alpha")).use { harness ->
                    val redis = InMemoryRedis(ServerIdentity { "group-test" })
                    val networkPlayers = NetworkPlayerDirectory(redis)
                    val remoteId = java.util.UUID.randomUUID()
                    redis.simulateExternalMessage(
                        NetworkPlayerDirectory.CHANNEL,
                        """[{"username":"RemotePlayer","uuid":"$remoteId","server":"parkour","joinTime":1}]""",
                        "proxy",
                    )
                    val bus = CrossServerGroupBus(redis, ServerId("group-test"))
                    val sent = mutableListOf<ru.ruscrafting.duels.redis.CrossServerGroupMessage>()
                    bus.subscribe(sent::add)
                    val gui = harness.registerGui(
                        targets = DuelTargetDirectory(harness.plugin, ServerId("group-test"), networkPlayers),
                        groupBus = bus,
                        transfer = PlayerTransfer { _, _ -> BackendTransferResult.SENT },
                    )
                    val host = harness.players.first()

                    gui.open(host)
                    val shownNames = (10..11).mapNotNull { slot ->
                        host.openInventory.topInventory.getItem(slot)?.itemMeta?.displayName()
                            ?.let(PlainTextComponentSerializer.plainText()::serialize)
                    }
                    shownNames shouldContainExactly listOf("Alpha", "RemotePlayer")
                    host.click(10)
                    host.click(11)
                    host.click(34)

                    sent.single { it.type == GroupLobbyMessageType.OFFER }.targetId?.value shouldBe remoteId
                    host.click(36)
                    gui.close()
                    bus.close()
                    networkPlayers.close()
                }
            }
        }
    }

    "cross-server invitation stays in chat until the player opens kit selection" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("RemoteInvitee")).use { harness ->
                    val hostServer = ServerId("parkour")
                    val localServer = ServerId("group-test")
                    val attackerRedis = InMemoryRedis(ServerIdentity { hostServer.value })
                    val attackerBus = CrossServerGroupBus(attackerRedis, hostServer)
                    val victimRedis = InMemoryRedis(ServerIdentity { localServer.value })
                    val victimBus = CrossServerGroupBus(victimRedis, localServer)
                    val victim = harness.players.single()
                    val host = NetworkGroupParticipant(PlayerId(java.util.UUID.randomUUID()), "RemoteHost", hostServer)
                    val teammate = NetworkGroupParticipant(PlayerId(java.util.UUID.randomUUID()), "RemoteMate", hostServer)
                    val target = NetworkGroupParticipant(PlayerId(victim.uniqueId), victim.name, localServer)
                    val lobbyId = java.util.UUID.randomUUID()
                    val offer = CrossServerGroupMessage(
                        messageId = "$lobbyId:offer:${victim.uniqueId}",
                        sourceServer = hostServer,
                        type = GroupLobbyMessageType.OFFER,
                        lobbyId = lobbyId,
                        hostId = host.playerId,
                        hostServer = hostServer,
                        participants = listOf(host, teammate, target),
                        layout = MultiplayerLayout.FREE_FOR_ALL,
                        kitPolicy = MultiplayerKitPolicy.SHARED,
                        sharedKitId = KitId("classic"),
                        expiresAtEpochMillis = System.currentTimeMillis() + 30_000L,
                        targetId = target.playerId,
                    )
                    val gui = harness.registerGui(
                        groupBus = victimBus,
                        transfer = PlayerTransfer { _, _ -> BackendTransferResult.SENT },
                    )

                    attackerBus.publish(offer)
                    val payload = attackerRedis.getPublishedMessages().single { it.channel == CrossServerGroupBus.CHANNEL }.message
                    victimRedis.simulateExternalMessage(CrossServerGroupBus.CHANNEL, payload, hostServer.value)
                    paper.performTicks(2)

                    val topBeforeOpen: Inventory? = victim.openInventory.topInventory
                    topBeforeOpen shouldBe null
                    val invitation = requireNotNull(victim.nextComponentMessage())
                    invitation.runCommands() shouldContainExactlyInAnyOrder listOf(
                        "/duel group open $lobbyId",
                        "/duel group decline $lobbyId",
                    )

                    gui.openInvitation(victim, lobbyId)
                    victim.openInventory.topInventory.getItem(34)?.type shouldBe Material.LIME_CONCRETE

                    gui.close()
                    victimBus.close()
                    attackerBus.close()
                }
            }
        }
    }

    "network preparation waits for player data readiness instead of dropping the participant" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "LocalMate")).use { harness ->
                    val localServer = ServerId("group-test")
                    val remoteServer = ServerId("parkour")
                    val redis = InMemoryRedis(ServerIdentity { localServer.value })
                    val networkPlayers = NetworkPlayerDirectory(redis)
                    val remoteId = java.util.UUID.randomUUID()
                    redis.simulateExternalMessage(
                        NetworkPlayerDirectory.CHANNEL,
                        """[{"username":"RemotePlayer","uuid":"$remoteId","server":"${remoteServer.value}","joinTime":1}]""",
                        "proxy",
                    )
                    val bus = CrossServerGroupBus(redis, localServer)
                    val sent = mutableListOf<CrossServerGroupMessage>()
                    bus.subscribe(sent::add)
                    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                    every { duelSessions.storeOriginSnapshot(any(), any(), any()) } returns
                        CompletableFuture.completedFuture(mockk())
                    val host = harness.players[0]
                    val local = harness.players[1]
                    host.setLocale(Locale.ENGLISH)
                    local.setLocale(Locale.ENGLISH)
                    var hostDataReady = false
                    val gui = harness.registerGui(
                        targets = DuelTargetDirectory(harness.plugin, localServer, networkPlayers),
                        groupBus = bus,
                        transfer = PlayerTransfer { _, _ -> BackendTransferResult.SENT },
                        duelSessions = duelSessions,
                        playerDataReady = { player -> player.uniqueId != host.uniqueId || hostDataReady },
                    )

                    gui.open(host)
                    host.click(10)
                    host.click(11)
                    host.click(34)
                    val invitation = requireNotNull(local.nextComponentMessage())
                    val lobbyId = java.util.UUID.fromString(
                        invitation.runCommands().first { it.startsWith("/duel group open ") }.substringAfterLast(' '),
                    )
                    gui.openInvitation(local, lobbyId)
                    local.click(34)

                    val offer = sent.single { it.type == GroupLobbyMessageType.OFFER }
                    val accepted = offer.copy(
                        messageId = "${offer.lobbyId}:response:$remoteId:accepted",
                        sourceServer = remoteServer,
                        type = GroupLobbyMessageType.RESPONSE,
                        response = ru.ruscrafting.duels.redis.GroupLobbyResponse.ACCEPTED,
                        kitId = requireNotNull(offer.sharedKitId),
                    )
                    val remoteRedis = InMemoryRedis(ServerIdentity { remoteServer.value })
                    val remoteBus = CrossServerGroupBus(remoteRedis, remoteServer)
                    remoteBus.publish(accepted)
                    val acceptedPayload =
                        remoteRedis.getPublishedMessages().single { it.channel == CrossServerGroupBus.CHANNEL }.message
                    redis.simulateExternalMessage(
                        CrossServerGroupBus.CHANNEL,
                        acceptedPayload,
                        remoteServer.value,
                    )
                    paper.performTicks(3)

                    verify(exactly = 0) { duelSessions.storeOriginSnapshot(any(), host, true) }
                    verify(exactly = 1) { duelSessions.storeOriginSnapshot(any(), local, true) }
                    val preparing = PlainTextComponentSerializer.plainText().serialize(requireNotNull(host.nextComponentMessage()))
                    preparing.contains("Everyone is ready") shouldBe true

                    hostDataReady = true
                    paper.performTicks(6)

                    verify(exactly = 1) { duelSessions.storeOriginSnapshot(any(), host, true) }
                    gui.close()
                    bus.close()
                    remoteBus.close()
                    networkPlayers.close()
                }
            }
        }
    }

    "unauthorized network preparation cannot freeze or transfer a MockBukkit player" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "Victim")).use { harness ->
                    val attackerRedis = InMemoryRedis(ServerIdentity { "parkour" })
                    val attackerBus = CrossServerGroupBus(attackerRedis, ServerId("parkour"))
                    val victimRedis = InMemoryRedis(ServerIdentity { "group-test" })
                    val victimBus = CrossServerGroupBus(victimRedis, ServerId("group-test"))
                    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                    val transfers = mutableListOf<Pair<java.util.UUID, ServerId>>()
                    val victim = harness.players[1]
                    val attacker = NetworkGroupParticipant(PlayerId(java.util.UUID.randomUUID()), "Attacker", ServerId("parkour"))
                    val victimRoute = NetworkGroupParticipant(PlayerId(victim.uniqueId), victim.name, ServerId("group-test"))
                    val third = NetworkGroupParticipant(PlayerId(java.util.UUID.randomUUID()), "Third", ServerId("parkour"))
                    val prepare = CrossServerGroupMessage(
                        messageId = "malicious:prepare",
                        sourceServer = ServerId("parkour"),
                        type = GroupLobbyMessageType.PREPARE,
                        lobbyId = java.util.UUID.randomUUID(),
                        hostId = attacker.playerId,
                        hostServer = ServerId("parkour"),
                        participants = listOf(attacker, victimRoute, third),
                        layout = MultiplayerLayout.FREE_FOR_ALL,
                        kitPolicy = MultiplayerKitPolicy.SHARED,
                        sharedKitId = KitId("classic"),
                        expiresAtEpochMillis = System.currentTimeMillis() + 30_000L,
                    )
                    val gui = harness.registerGui(
                        groupBus = victimBus,
                        transfer = PlayerTransfer { player, destination ->
                            transfers += player.uniqueId to destination
                            BackendTransferResult.SENT
                        },
                        duelSessions = duelSessions,
                    )

                    attackerBus.publish(prepare)
                    val payload = attackerRedis.getPublishedMessages().single { it.channel == CrossServerGroupBus.CHANNEL }.message
                    victimRedis.simulateExternalMessage(CrossServerGroupBus.CHANNEL, payload, "parkour")
                    paper.performTicks(2)

                    verify(exactly = 0) { duelSessions.storeOriginSnapshot(any(), any(), any()) }
                    transfers shouldBe emptyList()
                    gui.close()
                    victimBus.close()
                    attackerBus.close()
                }
            }
        }
    }

    "ready check expires exactly on its scheduled timeout and can be opened again" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "Alpha", "Bravo")).use { harness ->
                    var clockMillis = 1_000_000L
                    val clock = mockk<Clock>(relaxed = true)
                    every { clock.millis() } answers { clockMillis }
                    val gui = harness.registerGui(clock = clock)
                    val host = harness.players.first()

                    gui.open(host)
                    host.click(10)
                    host.click(11)
                    host.click(34)
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK
                    val unrelated = Bukkit.createInventory(null, 9)
                    harness.players[1].openInventory(unrelated)

                    paper.performTicks(899)
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK
                    clockMillis += 45_000L
                    paper.performTicks(1)
                    val topAfterTimeout: Inventory? = host.openInventory.topInventory
                    topAfterTimeout shouldBe null
                    harness.players[1].openInventory.topInventory shouldBe unrelated

                    gui.open(harness.players[1])
                    harness.players[1].openInventory.topInventory.getItem(36)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    harness.manager.activeCount() shouldBe 0
                }
            }
        }
    }

    "registered combat listener blocks friendly fire and completes a team match on lethal enemy hits" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(
                    paper,
                    listOf("TeamOneA", "TeamTwoA", "TeamOneB", "TeamTwoB", "Outsider"),
                ).use { harness ->
                    harness.registerGameplayListener()
                    val participants = harness.players.take(4)
                    harness.startAndArrive(teamRoster(participants))

                    val friendly = damage(participants[2], participants[0], 4.0)
                    friendly.isCancelled shouldBe true

                    val enemy = damage(participants[1], participants[0], 4.0)
                    enemy.isCancelled shouldBe false

                    val outsider = damage(participants[1], harness.players[4], 4.0)
                    outsider.isCancelled shouldBe true

                    damage(participants[1], participants[0], 20.0).isCancelled shouldBe true
                    participants[1].gameMode shouldBe GameMode.SPECTATOR
                    damage(participants[3], participants[2], 20.0).isCancelled shouldBe true

                    val completing = requireNotNull(harness.manager.matchFor(participants[0]))
                    completing.state shouldBe MultiplayerMatchState.COMPLETING
                    completing.winningTeam shouldBe 1
                    completing.winners shouldContainExactlyInAnyOrder
                        listOf(PlayerId(participants[0].uniqueId), PlayerId(participants[2].uniqueId))
                    harness.results.writes shouldHaveSize 1

                    harness.results.completion.complete(true)
                    paper.performTicks(64)
                }
            }
        }
    }

    "registered gameplay listener enforces countdown movement arena bounds inventory teleport and commands" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, countdownSeconds = 1).use { harness ->
                    harness.registerGameplayListener()
                    val roster = ffaRoster(harness.players)
                    harness.manager.start(roster, harness.players.associateBy { PlayerId(it.uniqueId) })
                    paper.performTicks(4)
                    harness.teleports.completeAll()
                    paper.performTicks(1)

                    val player = harness.players[0]
                    requireNotNull(harness.manager.matchFor(player)).state shouldBe MultiplayerMatchState.COUNTDOWN
                    val anchor = requireNotNull(harness.manager.anchor(player))
                    val countdownMove = paper.callEvent(PlayerMoveEvent(player, anchor, anchor.clone().add(5.0, 0.0, 0.0)))
                    countdownMove.to.blockX shouldBe anchor.blockX
                    harness.audience.observations().filterIsInstance<PaperAudienceEffectObservation.TitleShown>() shouldHaveSize 4

                    paper.performTicks(19)
                    requireNotNull(harness.manager.matchFor(player)).state shouldBe MultiplayerMatchState.COUNTDOWN
                    paper.performTicks(1)
                    requireNotNull(harness.manager.matchFor(player)).state shouldBe MultiplayerMatchState.ACTIVE

                    player.openInventory(Bukkit.createInventory(null, 9))
                    player.click(0).isCancelled shouldBe true
                    val unauthorizedTeleport = paper.callEvent(
                        PlayerTeleportEvent(
                            player,
                            player.location,
                            player.location.clone().add(1.0, 0.0, 0.0),
                            PlayerTeleportEvent.TeleportCause.COMMAND,
                        ),
                    )
                    unauthorizedTeleport.isCancelled shouldBe true

                    paper.callEvent(PlayerCommandPreprocessEvent(player, "/msg Alpha hello")).isCancelled shouldBe false
                    paper.callEvent(PlayerCommandPreprocessEvent(player, "/spawn")).isCancelled shouldBe true

                    val outside = anchor.clone().apply { x = 150.0 }
                    val boundaryMove = paper.callEvent(PlayerMoveEvent(player, anchor, outside))
                    boundaryMove.to.blockX shouldBe anchor.blockX
                    player.gameMode shouldBe GameMode.SPECTATOR

                    val forfeiter = harness.players[1]
                    val leave = paper.callEvent(PlayerCommandPreprocessEvent(forfeiter, "/duel leave"))
                    leave.isCancelled shouldBe true
                    forfeiter.gameMode shouldBe GameMode.SPECTATOR
                }
            }
        }
    }

    "admin player debug reports the active multiplayer session and its arena state" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("DebugHost", "DebugAlpha", "DebugBravo")).use { harness ->
                    val player = harness.players.first()
                    player.isOp = true
                    requireNotNull(player.getAttribute(Attribute.MAX_HEALTH)).baseValue = 40.0
                    harness.startAndArrive(ffaRoster(harness.players))
                    while (player.nextComponentMessage() != null) Unit

                    val duelSessions = mockk<DuelSessionManager>(relaxed = true)
                    every { duelSessions.matchFor(player) } returns null
                    val admin =
                        DuelAdminCommand(
                            plugin = harness.plugin,
                            arenas = harness.arenas,
                            sessions = duelSessions,
                            multiplayerSessions = harness.manager,
                        )
                    val match = requireNotNull(harness.manager.matchFor(player))
                    val distance = requireNotNull(harness.manager.boundaryDistance(player, player.location))
                    val plain = PlainTextComponentSerializer.plainText()

                    admin.execute(player, listOf("debug", "player", player.name))

                    plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
                        "ARCDUELS_DEBUG kind=player name=DebugHost uuid=${player.uniqueId} preparing=false locked=true post_match=false pending_recovery=false return_offer=false"
                    plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
                        "ARCDUELS_DEBUG kind=match player=DebugHost match=${match.id.value} arena=example state=ACTIVE type=MULTIPLAYER layout=FREE_FOR_ALL kit_policy=SHARED players=3 active_players=3 team=none kit=classic"
                    plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
                        "ARCDUELS_DEBUG kind=health player=DebugHost health=20.000 max=20.000 absorption=0.000 kit_cap=true"
                    plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
                        "ARCDUELS_DEBUG kind=position player=DebugHost world=world x=${"%.3f".format(Locale.ROOT, player.location.x)} y=${"%.3f".format(Locale.ROOT, player.location.y)} z=${"%.3f".format(Locale.ROOT, player.location.z)} in_bounds=true boundary_distance=${"%.3f".format(Locale.ROOT, distance)}"
                }
            }
        }
    }
})

private fun damage(victim: PlayerMock, attacker: PlayerMock, amount: Double) =
    LivingEntitySimulation(victim).simulateDamage(amount, attacker)
