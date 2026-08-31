package ru.ruscrafting.duels.paper

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.inventory.ClickType
import org.mockbukkit.mockbukkit.simulate.entity.PlayerSimulation
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.loadPlugin
import java.io.File

class ArcDuelsStrictReloadValidationMockBukkitTest : StringSpec({
    "arena boolean and numeric scalar types reject the candidate without replacing last-good" {
        withStrictReloadPlugin { paper, plugin ->
            installKnownGoodGeneration(plugin)
            paper.server.addSimpleWorld("world")
            val cases =
                listOf(
                    InvalidScalar("arenas.example.enabled") {
                        set("arenas.example.enabled", "true")
                    },
                    InvalidScalar("arenas.example.bounds.min.x") {
                        set("arenas.example.enabled", true)
                        set("arenas.example.bounds.min.x", "-100")
                    },
                    InvalidScalar("arenas.example.first-spawn.yaw") {
                        set("arenas.example.enabled", true)
                        set("arenas.example.first-spawn.yaw", false)
                    },
                    InvalidScalar("arenas.example.hill.radius") {
                        set("arenas.example.enabled", true)
                        set("arenas.example.hill.radius", "3.5")
                    },
                    InvalidScalar("arenas.example.multiplayer-spawns.1.team-2") {
                        set("arenas.example.enabled", true)
                        set("arenas.example.multiplayer-spawns.1.team-2", 1.0)
                    },
                    InvalidScalar("arenas.example.hill") {
                        set("arenas.example.enabled", true)
                        set("arenas.example.hill", false)
                    },
                    InvalidScalar("arenas.example.lobby") {
                        set("arenas.example.enabled", true)
                        set("arenas.example.lobby", false)
                    },
                    InvalidScalar("arenas.example.multiplayer-spawns") {
                        set("arenas.example.enabled", true)
                        set("arenas.example.multiplayer-spawns", false)
                    },
                    InvalidScalar("arenas.example.allowed-loadouts") {
                        set("arenas.example.enabled", true)
                        set("arenas.example.allowed-loadouts", listOf("KIT", 7))
                    },
                    InvalidScalar("arenas.example.allowed-objectives") {
                        set("arenas.example.enabled", true)
                        set("arenas.example.allowed-objectives", listOf("ELIMINATION", false))
                    },
                )

            assertConfigCandidatesRejected(plugin, cases)
        }
    }

    "structured kit scalar types reject the candidate without replacing last-good" {
        withStrictReloadPlugin { _, plugin ->
            installKnownGoodGeneration(plugin)
            val loadouts = File(plugin.dataFolder, "loadouts.yml")
            val lastGood = loadouts.readText()
            val cases =
                listOf(
                    InvalidScalar("kits.crossbow.items.0.amount") {
                        set("kits.crossbow.items.0.amount", "1")
                    },
                    InvalidScalar("kits.crossbow.items.0.enchantments.QUICK_CHARGE") {
                        set("kits.crossbow.items.0.enchantments.QUICK_CHARGE", "3")
                    },
                    InvalidScalar("kits.crossbow.items.0.unbreakable") {
                        set("kits.crossbow.items.0.unbreakable", "true")
                    },
                    InvalidScalar("kits.crossbow.items") {
                        set("kits.crossbow.items", false)
                    },
                    InvalidScalar("kits.crossbow.armor") {
                        set("kits.crossbow.armor", false)
                    },
                    InvalidScalar("kits.crossbow.items.0.enchantments") {
                        set("kits.crossbow.items.0.enchantments", false)
                    },
                )

            cases.forEach { candidate ->
                loadouts.writeText(lastGood)
                editStrictYaml(loadouts, candidate.edit)

                assertLastGoodRetained(plugin, candidate.path)
            }
            loadouts.writeText(lastGood)
        }
    }

    "catalog mapping scalar shapes reject the candidate without replacing last-good" {
        withStrictReloadPlugin { paper, plugin ->
            installKnownGoodGeneration(plugin)
            val cases =
                listOf(
                    InvalidScalar("arenas") { set("arenas", false) },
                    InvalidScalar("arenas.example") { set("arenas.example", false) },
                    InvalidScalar("kits") { set("kits", false) },
                    InvalidScalar("gui.items") { set("gui.items", false) },
                    InvalidScalar("gui.items.background") { set("gui.items.background", false) },
                    InvalidScalar("gui.items.background.material") { set("gui.items.background.material", 7) },
                    InvalidScalar("gui.items.background.custom-model-data") {
                        set("gui.items.background.custom-model-data", "17")
                    },
                    InvalidScalar("server-display-names.spawn") { set("server-display-names.spawn", 7) },
                )

            assertConfigCandidatesRejected(plugin, cases)

            val player = paper.server.addPlayer("GoodGuiViewer")
            player.performCommand("duel") shouldBe true
            player.openInventory.topInventory.getItem(0)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
        }
    }

    "loadouts kits scalar rejects the candidate and keeps the last-good kit catalog" {
        withStrictReloadPlugin { paper, plugin ->
            val loadouts = File(plugin.dataFolder, "loadouts.yml")
            editStrictYaml(loadouts) { set("kits.archer.icon", "BLAZE_ROD") }
            installKnownGoodGeneration(plugin)
            val lastGood = loadouts.readText()
            editStrictYaml(loadouts) { set("kits", false) }

            assertLastGoodRetained(plugin, "kits")

            val player = paper.server.addPlayer("GoodKitViewer")
            player.performCommand("duel") shouldBe true
            PlayerSimulation(player).simulateInventoryClick(player.openInventory, ClickType.LEFT, 29)
            player.openInventory.topInventory.getItem(10)?.type shouldBe Material.BLAZE_ROD
            loadouts.writeText(lastGood)
        }
    }

    "bootstrap repairs malformed loadouts while reload validation stays read-only" {
        withStrictReloadPlugin { _, plugin ->
            val loadouts = File(plugin.dataFolder, "loadouts.yml")
            loadouts.writeText("kits: [unterminated")

            val repaired = KitRegistry.load(plugin)

            repaired.all().any { kit -> kit.id.value == "classic" } shouldBe true
            YamlConfiguration().apply { load(loadouts) }

            val malformed = "kits: [still-unterminated"
            loadouts.writeText(malformed)
            plugin.reloadConfiguration().isFailure shouldBe true
            plugin.currentConfigGeneration() shouldBe 1L
            loadouts.readText() shouldBe malformed
        }
    }

    "locale selectors require supported strings and a real boolean while last-good stays live" {
        withStrictReloadPlugin { _, plugin ->
            installKnownGoodGeneration(plugin)
            val cases =
                listOf(
                    InvalidScalar("locale.default") { set("locale.default", 7) },
                    InvalidScalar("locale.default") { set("locale.default", "de") },
                    InvalidScalar("locale.use-client-locale") { set("locale.use-client-locale", "false") },
                )

            assertConfigCandidatesRejected(plugin, cases)
        }
    }

    "locale leaf shapes placeholders and strict MiniMessage reject before replacing last-good" {
        withStrictReloadPlugin { paper, plugin ->
            val locale = File(plugin.dataFolder, "lang/en.yml")
            editStrictYaml(locale) { set("menu.main.challenge", "<gold>Last-good duel</gold>") }
            installKnownGoodGeneration(plugin)
            val lastGood = locale.readText()
            val cases =
                listOf(
                    InvalidScalar("menu.main.challenge") {
                        set("menu.main.challenge", listOf("<gold>Wrong shape</gold>"))
                    },
                    InvalidScalar("menu.main.challenge-lore") {
                        set("menu.main.challenge-lore", "<gray>Wrong shape</gray>")
                    },
                    InvalidScalar("menu.main.challenge-lore[1]") {
                        set("menu.main.challenge-lore", listOf("<gray>Valid line</gray>", 7))
                    },
                    InvalidScalar("menu.main.challenge") {
                        set("menu.main.challenge", "<gold>Unclosed formatting")
                    },
                    InvalidScalar("menu.main.challenge") {
                        set("menu.main.challenge", "<unknown-value>Undeclared placeholder")
                    },
                    InvalidScalar("menu.main.challenge") {
                        set("menu.main.challenge", "<player>Placeholder declared for another key")
                    },
                )

            cases.forEach { candidate ->
                locale.writeText(lastGood)
                editStrictYaml(locale, candidate.edit)

                assertLastGoodRetained(plugin, candidate.path)
            }
            locale.writeText(lastGood)

            val player = paper.server.addPlayer("LocaleGood")
            player.performCommand("duel") shouldBe true
            PlainTextComponentSerializer.plainText().serialize(
                requireNotNull(player.openInventory.topInventory.getItem(11)?.itemMeta?.displayName()),
            ) shouldBe "Last-good duel"
        }
    }

    "locale reload validates declared placeholders without requiring live values" {
        withStrictReloadPlugin { paper, plugin ->
            editStrictConfig(plugin) {
                set("locale.default", "en")
                set("locale.use-client-locale", false)
            }
            editStrictYaml(File(plugin.dataFolder, "lang/en.yml")) {
                set("menu.common.page", "<gray><page>/<pages></gray>")
                set("menu.main.queue-lore", listOf("<gray><active>/<waiting></gray>"))
            }

            plugin.reloadConfiguration().getOrThrow().generation shouldBe 2L

            val player = paper.server.addPlayer("LocaleValues")
            player.performCommand("duel") shouldBe true
            val queueLore = requireNotNull(player.openInventory.topInventory.getItem(31)?.itemMeta?.lore())
            queueLore.map(PlainTextComponentSerializer.plainText()::serialize) shouldBe listOf("0/0")
        }
    }

    "known configuration parent sections reject scalar replacement without exposing bundled defaults" {
        withStrictReloadPlugin { _, plugin ->
            installKnownGoodGeneration(plugin)
            val cases =
                listOf(
                    "server-display-names",
                    "multiplayer",
                    "player-data-sync",
                    "post-match",
                    "recovery",
                    "shutdown",
                    "locale",
                    "celebration",
                    "celebration.fireworks",
                    "mysql",
                    "mysql.inventory-snapshots",
                    "mysql.pool",
                    "redis",
                    "gui",
                ).map { path -> InvalidScalar(path) { set(path, false) } }

            assertConfigCandidatesRejected(plugin, cases)
        }
    }
})

private data class InvalidScalar(
    val path: String,
    val edit: YamlConfiguration.() -> Unit,
)

private fun installKnownGoodGeneration(plugin: ArcDuelsPlugin) {
    editStrictConfig(plugin) {
        set("countdown-seconds", 1)
        set("locale.default", "en")
        set("locale.use-client-locale", false)
        set("gui.items.background.material", "BLUE_STAINED_GLASS_PANE")
    }
    plugin.reloadConfiguration().getOrThrow().generation shouldBe 2L
}

private fun assertConfigCandidatesRejected(
    plugin: ArcDuelsPlugin,
    cases: List<InvalidScalar>,
) {
    val config = File(plugin.dataFolder, "config.yml")
    val lastGood = config.readText()
    cases.forEach { candidate ->
        config.writeText(lastGood)
        editStrictYaml(config, candidate.edit)

        assertLastGoodRetained(plugin, candidate.path)
    }
    config.writeText(lastGood)
}

private fun assertLastGoodRetained(
    plugin: ArcDuelsPlugin,
    expectedPath: String,
) {
    val rejected = plugin.reloadConfiguration()
    withClue("$expectedPath: ${rejected.exceptionOrNull()?.message}") {
        rejected.isFailure shouldBe true
        rejected.exceptionOrNull()?.message.orEmpty().contains(expectedPath) shouldBe true
        plugin.currentConfigGeneration() shouldBe 2L
        plugin.currentRuntimeSettings().countdownSeconds shouldBe 1
        plugin.config.getString("locale.default") shouldBe "en"
        plugin.config.getBoolean("locale.use-client-locale") shouldBe false
        plugin.config.isConfigurationSection("arenas") shouldBe true
        plugin.config.isConfigurationSection("arenas.example") shouldBe true
        plugin.config.isConfigurationSection("gui.items") shouldBe true
        plugin.config.get("kits") shouldBe null
        plugin.isEnabled shouldBe true
    }
}

private fun withStrictReloadPlugin(block: (MockBukkitTestRuntime, ArcDuelsPlugin) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation {
            block(paper, paper.loadPlugin<ArcDuelsPlugin>())
        }
    }
}

private fun editStrictConfig(
    plugin: ArcDuelsPlugin,
    edit: YamlConfiguration.() -> Unit,
) = editStrictYaml(File(plugin.dataFolder, "config.yml"), edit)

private fun editStrictYaml(
    file: File,
    edit: YamlConfiguration.() -> Unit,
) {
    val configuration = YamlConfiguration.loadConfiguration(file)
    configuration.edit()
    configuration.save(file)
}
