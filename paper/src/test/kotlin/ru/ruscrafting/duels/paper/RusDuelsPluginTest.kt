package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.bukkit.util.Vector

class RusDuelsPluginTest : StringSpec({
    lateinit var server: ServerMock
    lateinit var plugin: RusDuelsPlugin

    beforeSpec {
        server = MockBukkit.mock()
    }

    afterSpec {
        MockBukkit.unmock()
    }

    "default plugin configuration enables without MySQL or Redis" {
        plugin = MockBukkit.load(RusDuelsPlugin::class.java)

        plugin.isEnabled shouldBe true
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

    "enabled arena requires valid bounds containing both spawns" {
        server.addSimpleWorld("world")
        plugin.config.set("arenas.example.enabled", true)

        PaperArenaCatalog.load(plugin).size() shouldBe 1

        plugin.config.set("arenas.example.bounds.min.x", 20.0)
        shouldThrow<IllegalArgumentException> { PaperArenaCatalog.load(plugin) }
    }
})
