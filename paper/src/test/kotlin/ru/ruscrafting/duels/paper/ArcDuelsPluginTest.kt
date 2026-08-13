package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.bukkit.util.Vector
import ru.ruscrafting.duels.domain.ChallengeId
import java.util.UUID

class ArcDuelsPluginTest : StringSpec({
    lateinit var server: ServerMock
    lateinit var plugin: ArcDuelsPlugin

    beforeSpec {
        server = MockBukkit.mock()
    }

    afterSpec {
        MockBukkit.unmock()
    }

    "default plugin configuration enables without MySQL or Redis" {
        plugin = MockBukkit.load(ArcDuelsPlugin::class.java)

        plugin.isEnabled shouldBe true
        plugin.pluginMeta.name shouldBe "ArcDuels"
        plugin.getCommand("duel")?.executor?.javaClass shouldBe DuelCommand::class.java
    }

    "player snapshot restores cursor slot experience and movement state" {
        val player = server.addPlayer()
        val world = server.addSimpleWorld("snapshot-world")
        val savedLocation = Location(world, 12.5, 70.0, -8.5, 45f, 10f)
        player.teleport(savedLocation)
        player.inventory.setItem(0, ItemStack(Material.DIAMOND_SWORD))
        player.setItemOnCursor(ItemStack(Material.GOLD_INGOT, 3))
        player.inventory.heldItemSlot = 4
        player.foodLevel = 17
        player.saturation = 3.5f
        player.exhaustion = 1.25f
        player.totalExperience = 321
        player.level = 12
        player.exp = 0.4f
        player.gameMode = GameMode.CREATIVE
        player.allowFlight = true
        player.isFlying = true
        player.velocity = Vector(0.2, 0.3, -0.1)
        val snapshot = PlayerSnapshot.capture(player)

        player.inventory.clear()
        player.setItemOnCursor(ItemStack.empty())
        player.inventory.heldItemSlot = 0
        player.totalExperience = 0
        player.level = 0
        player.exp = 0f
        player.gameMode = GameMode.SURVIVAL
        player.allowFlight = false
        player.teleport(Location(world, 0.0, 64.0, 0.0))

        snapshot.restore(player) { restored, destination -> restored.teleport(destination) }

        player.inventory.getItem(0)?.type shouldBe Material.DIAMOND_SWORD
        player.itemOnCursor.type shouldBe Material.GOLD_INGOT
        player.itemOnCursor.amount shouldBe 3
        player.inventory.heldItemSlot shouldBe 4
        player.totalExperience shouldBe 321
        player.level shouldBe 12
        player.exp shouldBe 0.4f
        player.gameMode shouldBe GameMode.CREATIVE
        player.isFlying shouldBe true
        player.location.x shouldBe savedLocation.x
        player.location.z shouldBe savedLocation.z
        player.velocity shouldBe Vector(0.2, 0.3, -0.1)
    }

    "versioned snapshot codec round trips Paper item bytes and rejects corrupt framing" {
        val player = server.addPlayer()
        val world = server.addSimpleWorld("codec-world")
        player.teleport(Location(world, 2.5, 72.0, -3.5, 30f, -5f))
        player.inventory.setItem(0, ItemStack(Material.DIAMOND_SWORD))
        player.inventory.setItem(8, ItemStack(Material.GOLDEN_APPLE, 7))
        player.inventory.helmet = ItemStack(Material.NETHERITE_HELMET)
        player.setItemOnCursor(ItemStack(Material.EMERALD, 11))
        val snapshot = PlayerSnapshot.capture(player)
        val codec = PlayerSnapshotCodec(server)

        val payload = codec.encode(snapshot)
        val decoded = codec.decode(payload)
        player.inventory.clear()
        player.inventory.armorContents = arrayOfNulls(4)
        player.setItemOnCursor(ItemStack.empty())
        player.teleport(Location(world, 0.0, 64.0, 0.0))

        decoded.restore(player) { restored, destination -> restored.teleport(destination) }

        player.inventory.getItem(0)?.type shouldBe Material.DIAMOND_SWORD
        player.inventory.getItem(8)?.amount shouldBe 7
        player.inventory.helmet?.type shouldBe Material.NETHERITE_HELMET
        player.itemOnCursor.amount shouldBe 11
        val corrupt = payload.copyOf().also { it[0] = (it[0].toInt() xor 0x7f).toByte() }
        shouldThrow<IllegalArgumentException> { codec.decode(corrupt) }
    }

    "enabled arena requires valid bounds containing both spawns" {
        server.addSimpleWorld("world")
        plugin.config.set("arenas.example.enabled", true)

        PaperArenaCatalog.load(plugin).size() shouldBe 1

        plugin.config.set("arenas.example.bounds.min.x", 20.0)
        shouldThrow<IllegalArgumentException> { PaperArenaCatalog.load(plugin) }
    }

    "invalid explicit challenge ids never fall back to another pending challenge" {
        val controller = mockk<DuelController>(relaxed = true)
        val gui = mockk<DuelGuiService>(relaxed = true)
        val admin = mockk<DuelAdminCommand>(relaxed = true)
        val executor = DuelCommand(controller, gui, admin)
        val player = server.addPlayer()
        val command = requireNotNull(plugin.getCommand("duel"))

        executor.onCommand(player, command, "duel", arrayOf("accept", "not-a-uuid"))

        verify(exactly = 0) { controller.accept(player, any()) }

        val id = ChallengeId(UUID.randomUUID())
        executor.onCommand(player, command, "duel", arrayOf("accept", id.toString()))
        verify(exactly = 1) { controller.accept(player, id) }
    }

    "case-normalized kit and arena ids cannot silently overwrite each other" {
        plugin.config.set("kits.Classic.icon", "STONE")
        shouldThrow<IllegalArgumentException> { KitRegistry.load(plugin) }
        plugin.config.set("kits.Classic", null)

        plugin.config.set("arenas.example.bounds.min.x", -15.0)
        val originalArena = requireNotNull(plugin.config.getConfigurationSection("arenas.example"))
        originalArena.getValues(true).forEach { (key, value) -> plugin.config.set("arenas.Example.$key", value) }
        plugin.config.set("arenas.example.enabled", true)
        plugin.config.set("arenas.Example.enabled", true)

        shouldThrow<IllegalArgumentException> { PaperArenaCatalog.load(plugin) }
    }
})
