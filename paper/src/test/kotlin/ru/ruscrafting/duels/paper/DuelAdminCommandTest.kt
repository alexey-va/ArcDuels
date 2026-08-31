package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.mockbukkit.mockbukkit.ServerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.util.UUID

class DuelAdminCommandTest : StringSpec({
    lateinit var server: ServerMock
    lateinit var plugin: ArcDuelsPlugin
    lateinit var paper: MockBukkitTestRuntime

    beforeSpec {
        paper = MockBukkitTestRuntime.open()
        server = paper.server
        plugin = paper.loadPlugin<ArcDuelsPlugin>()
    }

    afterSpec { paper.close() }

    "admin arena editor persists both spawns normalizes corners and validates before enable" {
        val world = server.addSimpleWorld("admin-world")
        val player = server.addPlayer()
        player.isOp = true
        val catalog = mockk<PaperArenaCatalog>()
        val sessions = mockk<DuelSessionManager>()
        every { sessions.activeArenaCount() } returns 0
        every { sessions.queueSize() } returns 0
        every { catalog.disable(any()) } returns 0
        every { catalog.reload(plugin) } returns 1
        val admin = DuelAdminCommand(plugin, catalog, sessions)

        admin.execute(player, listOf("arena", "create", "alpha"))
        plugin.config.getBoolean("arenas.alpha.enabled") shouldBe false
        plugin.config.getStringList("arenas.alpha.allowed-loadouts").toSet() shouldBe setOf("KIT", "OWN_INVENTORY")
        plugin.config.getStringList("arenas.alpha.allowed-objectives").toSet() shouldBe
            ru.ruscrafting.duels.domain.DuelObjectiveType.entries.map { it.name }.toSet()
        plugin.config.getDouble("arenas.alpha.bounds.min.x") shouldBe -100.0
        plugin.config.getDouble("arenas.alpha.bounds.max.x") shouldBe 100.0
        plugin.config.getDouble("arenas.alpha.bounds.min.z") shouldBe -100.0
        plugin.config.getDouble("arenas.alpha.bounds.max.z") shouldBe 100.0
        admin.execute(player, listOf("arena", "setloadouts", "alpha", "own"))
        plugin.config.getStringList("arenas.alpha.allowed-loadouts") shouldBe listOf("OWN_INVENTORY")
        admin.execute(player, listOf("arena", "setobjectives", "alpha", "boxing", "combo"))
        plugin.config.getStringList("arenas.alpha.allowed-objectives").toSet() shouldBe setOf("BOXING", "COMBO")

        player.teleport(Location(world, -6.5, 70.0, 0.5, -90f, 0f))
        admin.execute(player, listOf("arena", "setspawn", "alpha", "1"))
        player.teleport(Location(world, 6.5, 70.0, 0.5, 90f, 0f))
        admin.execute(player, listOf("arena", "setspawn", "alpha", "2"))
        player.teleport(Location(world, 12.0, 90.0, 12.0))
        admin.execute(player, listOf("arena", "setcorner", "alpha", "1"))
        player.teleport(Location(world, -12.0, 60.0, -12.0))
        admin.execute(player, listOf("arena", "setcorner", "alpha", "2"))
        player.teleport(Location(world, 0.5, 70.0, 0.5))
        admin.execute(player, listOf("arena", "sethill", "alpha", "4.0", "5.0"))
        admin.execute(player, listOf("arena", "enable", "alpha"))

        plugin.config.getBoolean("arenas.alpha.enabled") shouldBe true
        plugin.config.getString("arenas.alpha.first-spawn.world") shouldBe "admin-world"
        plugin.config.getDouble("arenas.alpha.bounds.min.x") shouldBe -12.0
        plugin.config.getDouble("arenas.alpha.bounds.min.y") shouldBe 60.0
        plugin.config.getDouble("arenas.alpha.bounds.max.x") shouldBe 12.0
        plugin.config.getDouble("arenas.alpha.bounds.max.y") shouldBe 90.0
        plugin.config.getDouble("arenas.alpha.hill.center.x") shouldBe 0.5
        plugin.config.getDouble("arenas.alpha.hill.radius") shouldBe 4.0
        plugin.config.getDouble("arenas.alpha.hill.height") shouldBe 5.0
        verify(atLeast = 1) { catalog.disable(any()) }
    }

    "arena edits fail closed while a multiplayer or accepted-match flow is active" {
        val player = server.addPlayer("BusyArenaAdmin")
        player.isOp = true
        val catalog = mockk<PaperArenaCatalog>(relaxed = true)
        val sessions = mockk<DuelSessionManager>()
        every { sessions.activeArenaCount() } returns 0
        every { sessions.queueSize() } returns 0
        plugin.config.set("arenas.busy_gate.enabled", true)
        val admin = DuelAdminCommand(plugin, catalog, sessions, configurationBusy = { true })

        admin.execute(player, listOf("arena", "disable", "busy_gate"))

        plugin.config.getBoolean("arenas.busy_gate.enabled") shouldBe true
        verify(exactly = 0) { catalog.disable(any()) }
        val denial = PlainTextComponentSerializer.plainText().serialize(requireNotNull(player.nextComponentMessage()))
        denial.isNotBlank() shouldBe true
        plugin.config.set("arenas.busy_gate", null)
    }

    "admin debug commands expose stable read-only server player and arena state for QA bots" {
        val world = server.addSimpleWorld("debug-world")
        val player = server.addPlayer("QaBot")
        player.isOp = true
        player.teleport(Location(world, 1.25, 70.0, -2.5))
        val catalog = mockk<PaperArenaCatalog>()
        val sessions = mockk<DuelSessionManager>()
        every { catalog.size() } returns 1
        every { catalog.isReserved(any()) } returns false
        every { catalog.get(any()) } throws IllegalStateException("disabled")
        every { sessions.activeArenaCount() } returns 0
        every { sessions.queueSize() } returns 0
        every { sessions.pendingRecoveryCount() } returns 0
        every { sessions.matchFor(player) } returns null
        every { sessions.isPreparing(player) } returns false
        every { sessions.isStateLocked(player) } returns false
        every { sessions.isPostMatchWaiting(player) } returns true
        every { sessions.hasPendingRecovery(player) } returns false
        every { sessions.isKitHealthCapApplied(player) } returns false
        every { sessions.boundaryDistance(player, any()) } returns null
        plugin.config.set("arenas.debug.enabled", false)
        plugin.config.set("arenas.debug.post-match-action", "LOCAL_LOBBY")
        val admin = DuelAdminCommand(plugin, catalog, sessions, hasReturnOffer = { true })
        val plain = PlainTextComponentSerializer.plainText()

        admin.execute(player, listOf("debug", "server"))
        plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
            "ARCDUELS_DEBUG kind=server version=${plugin.pluginMeta.version} server=duels-1 arenas=1 active=0 queue=0 recoveries=0"

        admin.execute(player, listOf("debug", "player"))
        plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
            "ARCDUELS_DEBUG kind=player name=QaBot uuid=${player.uniqueId} preparing=false locked=false post_match=true pending_recovery=false return_offer=true"
        plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
            "ARCDUELS_DEBUG kind=match player=QaBot match=none"
        plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
            "ARCDUELS_DEBUG kind=health player=QaBot health=20.000 max=20.000 absorption=0.000 kit_cap=false"
        plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
            "ARCDUELS_DEBUG kind=position player=QaBot world=debug-world x=1.250 y=70.000 z=-2.500 in_bounds=true boundary_distance=n/a"

        admin.execute(player, listOf("debug", "arena", "debug"))
        plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
            "ARCDUELS_DEBUG kind=arena id=debug enabled=false loaded=false reserved=false post_match=LOCAL_LOBBY lobby=false"

        val match =
            DuelMatch.reserve(
                PlayerId(player.uniqueId),
                PlayerId(UUID.randomUUID()),
                ArenaId("debug"),
                ServerId("lab"),
                DuelRules(DuelMode.KIT, KitId("classic")),
                Instant.EPOCH,
            ).beginCountdown().activate(Instant.EPOCH)
        every { sessions.matchFor(player) } returns match
        every { sessions.modifiedBlockCount(player) } returns 2
        every { sessions.isKitHealthCapApplied(player) } returns true
        every { sessions.boundaryDistance(player, any()) } returns 4.25
        every { sessions.isInsideArena(player, any()) } returns true
        admin.execute(player, listOf("debug", "player", "QaBot"))
        requireNotNull(player.nextComponentMessage())
        plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
            "ARCDUELS_DEBUG kind=match player=QaBot match=${match.id.value} arena=debug state=ACTIVE mode=KIT objective=ELIMINATION score=0:0 best_of=1 modified_blocks=2"
        plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
            "ARCDUELS_DEBUG kind=health player=QaBot health=20.000 max=20.000 absorption=0.000 kit_cap=true"
        plain.serialize(requireNotNull(player.nextComponentMessage())) shouldBe
            "ARCDUELS_DEBUG kind=position player=QaBot world=debug-world x=1.250 y=70.000 z=-2.500 in_bounds=true boundary_distance=4.250"

        admin.complete(player, listOf("debug", "")) shouldBe listOf("arena", "player", "server")
    }
})
