package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.inventory.Inventory
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.simulate.entity.LivingEntitySimulation
import org.mockbukkit.mockbukkit.simulate.entity.PlayerSimulation
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
import java.util.Locale

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
                        listOf("axe", "axe", "berserker", "archer", "archer", "archer")

                    host.inventory.getItem(0)?.type shouldBe Material.DIAMOND_AXE
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
                        transfer = PlayerTransfer { _, _ -> },
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
                        transfer = PlayerTransfer { player, destination -> transfers += player.uniqueId to destination },
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
                    val gui = harness.registerGui()
                    val host = harness.players.first()

                    gui.open(host)
                    host.click(10)
                    host.click(11)
                    host.click(34)
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK

                    paper.performTicks(899)
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK
                    paper.performTicks(1)
                    val topAfterTimeout: Inventory? = host.openInventory.topInventory
                    topAfterTimeout shouldBe null

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
})

private fun MultiplayerHarness.registerGui(
    targets: DuelTargetDirectory = DuelTargetDirectory(plugin, ServerId("group-test"), null),
    groupBus: CrossServerGroupBus? = null,
    transfer: PlayerTransfer? = null,
    duelSessions: DuelSessionManager = mockk(relaxed = true),
): MultiplayerGuiService =
    MultiplayerGuiService(
        plugin = plugin,
        kits = kits,
        sessions = manager,
        duelSessions = duelSessions,
        locales = locales,
        tasks = tasks,
        targets = targets,
        localServer = ServerId("group-test"),
        serverNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning),
        groupBus = groupBus,
        transfer = transfer,
        backAction = { },
    ).also { plugin.server.pluginManager.registerEvents(it, plugin) }

private fun MultiplayerHarness.registerGameplayListener() {
    plugin.server.pluginManager.registerEvents(MultiplayerGameplayListener(manager, locales), plugin)
}

private fun MultiplayerHarness.startAndArrive(roster: ru.ruscrafting.duels.domain.MultiplayerRoster) {
    manager.start(roster, roster.playerIds.associateWith { playerId -> requireNotNull(plugin.server.getPlayer(playerId.value)) })
    paper.performTicks(4)
    teleports.completeAll()
    paper.performTicks(4)
}

private fun PlayerMock.click(slot: Int, clickType: ClickType = ClickType.LEFT) =
    PlayerSimulation(this).simulateInventoryClick(openInventory, clickType, slot)

private fun damage(victim: PlayerMock, attacker: PlayerMock, amount: Double) =
    LivingEntitySimulation(victim).simulateDamage(amount, attacker)

private fun org.bukkit.inventory.ItemStack?.plainLore(): String =
    this?.itemMeta?.lore().orEmpty().joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }

private fun org.bukkit.inventory.ItemStack?.plainName(): String =
    this?.itemMeta?.displayName()?.let(PlainTextComponentSerializer.plainText()::serialize).orEmpty()
