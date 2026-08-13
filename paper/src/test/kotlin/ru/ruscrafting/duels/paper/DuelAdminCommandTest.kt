package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock

class DuelAdminCommandTest : StringSpec({
    lateinit var server: ServerMock
    lateinit var plugin: ArcDuelsPlugin

    beforeSpec {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(ArcDuelsPlugin::class.java)
    }

    afterSpec { MockBukkit.unmock() }

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
})
