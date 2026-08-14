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
import org.bukkit.event.inventory.ClickType
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.bukkit.util.Vector
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.format.TextDecoration
import io.papermc.paper.datacomponent.DataComponentTypes
import ru.ruscrafting.duels.domain.ChallengeId
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord

@Suppress("DEPRECATION")
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
        plugin.config.getInt("countdown-seconds") shouldBe 0
        plugin.config.getString("player-data-sync.provider") shouldBe "AUTO"
        plugin.config.getLong("player-data-sync.settle-delay-ticks") shouldBe 40L
        plugin.config.getString("post-match.return-policy") shouldBe "PROMPT"
        plugin.getCommand("duel")?.executor?.javaClass shouldBe DuelCommand::class.java
        KitRegistry.load(plugin).all().map { it.id.value } shouldBe listOf("archer", "axe", "boxing", "classic", "sumo", "tank", "uhc")
        java.io.File(plugin.dataFolder, "lang/ru.yml").isFile shouldBe true
        java.io.File(plugin.dataFolder, "lang/en.yml").isFile shouldBe true

        val localeWarnings = mutableListOf<String>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    localeWarnings += record.message
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        plugin.logger.addHandler(handler)
        try {
            LocaleService.load(plugin)
        } finally {
            plugin.logger.removeHandler(handler)
        }
        localeWarnings.none { "already exists" in it } shouldBe true
    }

    "network arena routing prevents a false no-arena startup failure" {
        hasUsableArenaRoute(localArenaCount = 0, networkArenaRoutingEnabled = true) shouldBe true
        hasUsableArenaRoute(localArenaCount = 0, networkArenaRoutingEnabled = false) shouldBe false
        hasUsableArenaRoute(localArenaCount = 1, networkArenaRoutingEnabled = false) shouldBe true
    }

    "GUI item specs preserve the configured ItemsAdder material and modern model data" {
        val item = GuiItemSpec(Material.BLUE_STAINED_GLASS_PANE, 11_013).create()

        item.type shouldBe Material.BLUE_STAINED_GLASS_PANE
        item.getData(DataComponentTypes.CUSTOM_MODEL_DATA)?.floats() shouldBe listOf(11_013f)
        shouldThrow<IllegalArgumentException> { GuiItemSpec(Material.STONE, 0) }
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
        player.health = 18.0
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

        snapshot.restore(player) { restored, destination ->
            val teleported = restored.teleport(destination)
            // Mirrors destination plugins that normalize health during teleport.
            restored.health = 1.0
            teleported
        }

        player.inventory.getItem(0)?.type shouldBe Material.DIAMOND_SWORD
        player.itemOnCursor.type shouldBe Material.GOLD_INGOT
        player.itemOnCursor.amount shouldBe 3
        player.inventory.heldItemSlot shouldBe 4
        player.totalExperience shouldBe 321
        player.level shouldBe 12
        player.exp shouldBe 0.4f
        player.gameMode shouldBe GameMode.CREATIVE
        player.isFlying shouldBe true
        player.health shouldBe 18.0
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
        plugin.config.set("arenas.example.bounds.min.x", -15.0)
        plugin.config.set("arenas.example.enabled", false)
    }

    "arena loading runs the environment inspector for every enabled arena" {
        plugin.config.set("arenas.example.enabled", true)
        var inspected: PaperArena? = null

        val catalog = PaperArenaCatalog.load(plugin, ArenaEnvironmentInspector { inspected = it })

        catalog.size() shouldBe 1
        inspected?.id?.value shouldBe "example"
        plugin.config.set("arenas.example.enabled", false)
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
        plugin.config.set("arenas.Example", null)
        plugin.config.set("arenas.example.enabled", false)
    }

    "main hub follows the challenge submenu path and renders the client language" {
        val player = server.addPlayer("MenuTester")
        server.addPlayer("Opponent")
        player.setLocale(java.util.Locale.ENGLISH)

        player.performCommand("duel") shouldBe true
        player.openInventory.topInventory.size shouldBe 45
        player.openInventory.topInventory.getItem(11)?.type shouldBe Material.NETHERITE_SWORD
        val challengeName = requireNotNull(player.openInventory.topInventory.getItem(11)?.itemMeta?.displayName())
        PlainTextComponentSerializer.plainText().serialize(challengeName) shouldBe "Challenge a player"
        challengeName.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE

        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 11)
        player.openInventory.topInventory.getItem(10)?.type shouldBe Material.PLAYER_HEAD
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 10)
        player.openInventory.topInventory.getItem(13)?.type shouldBe Material.BEACON
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 13)
        player.openInventory.topInventory.getItem(10)?.type shouldBe Material.BOW
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 10)
        player.openInventory.topInventory.getItem(31)?.type shouldBe Material.BEACON
        player.openInventory.topInventory.getItem(40)?.type shouldBe Material.LIME_CONCRETE

        player.closeInventory()
        player.performCommand("duel Opponent") shouldBe true
        player.openInventory.topInventory.getItem(29)?.type shouldBe Material.LEATHER_BOOTS
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 29)
        player.openInventory.topInventory.getItem(10)?.type shouldBe Material.LEATHER_BOOTS
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 10)
        player.openInventory.topInventory.getItem(14)?.type shouldBe Material.TARGET
        player.openInventory.topInventory.getItem(19)?.type shouldBe Material.GRAY_DYE

        player.closeInventory()
        player.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
        player.performCommand("duel") shouldBe true
        PlainTextComponentSerializer.plainText().serialize(requireNotNull(player.openInventory.topInventory.getItem(11)?.itemMeta?.displayName())) shouldBe "Вызвать на бой"
    }

    "admin command opens a real arena editor and its actions use the current position" {
        val player = server.addPlayer("ArenaAdmin")
        player.isOp = true
        player.setLocale(java.util.Locale.ENGLISH)
        val world = requireNotNull(server.getWorld("world"))

        player.performCommand("duels admin") shouldBe true
        player.openInventory.topInventory.getItem(11)?.type shouldBe Material.FILLED_MAP
        player.openInventory.topInventory.getItem(29)?.type shouldBe Material.NAME_TAG
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 11)
        player.openInventory.topInventory.getItem(10)?.type shouldBe Material.YELLOW_BANNER
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 10)
        player.openInventory.topInventory.getItem(12)?.type shouldBe Material.COMPASS
        player.openInventory.topInventory.getItem(31)?.type shouldBe Material.LIME_CONCRETE
        player.openInventory.topInventory.getItem(27)?.type shouldBe Material.CLOCK
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 27)
        player.openInventory.topInventory.getItem(29)?.type shouldBe Material.LEATHER_BOOTS
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 29)
        plugin.config.getStringList("arenas.example.allowed-objectives").contains("BOXING") shouldBe false
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 36)

        player.teleport(Location(world, 7.5, 82.0, -4.5, 45f, 5f))
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 12)

        plugin.config.getDouble("arenas.example.first-spawn.x") shouldBe 7.5
        plugin.config.getDouble("arenas.example.first-spawn.y") shouldBe 82.0
        player.openInventory.topInventory.getItem(12)?.type shouldBe Material.COMPASS

        player.performCommand("duels admin") shouldBe true
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 29)
        player.chat("gui_arena")
        server.scheduler.performTicks(2)
        plugin.config.isConfigurationSection("arenas.gui_arena") shouldBe true
        player.openInventory.topInventory.getItem(12)?.type shouldBe Material.COMPASS
        plugin.config.set("arenas.gui_arena", null)
    }
})
