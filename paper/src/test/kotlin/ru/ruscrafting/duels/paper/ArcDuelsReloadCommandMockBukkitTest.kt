package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.inventory.ClickType
import org.bukkit.configuration.file.YamlConfiguration
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.loadPlugin
import java.io.File

@Suppress("DEPRECATION")
class ArcDuelsReloadCommandMockBukkitTest : StringSpec({
    "top-level command and legacy arena alias execute the same live reloader" {
        withReloadCommandPlugin { paper, plugin ->
            val admin = paper.server.addPlayer("ReloadAdmin")
            admin.isOp = true
            admin.setLocale(java.util.Locale.forLanguageTag("ru-RU"))

            admin.performCommand("duel admin reload") shouldBe true
            plugin.currentConfigGeneration() shouldBe 2L
            admin.drainPlainMessages().single().contains("Конфигурация v2 применена") shouldBe true

            admin.performCommand("duel admin arena reload") shouldBe true
            plugin.currentConfigGeneration() shouldBe 3L
            admin.drainPlainMessages().single().contains("Конфигурация v3 применена") shouldBe true

            val player = paper.server.addPlayer("NoReloadPerm")
            player.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
            val beforeDenied = plugin.currentConfigGeneration()
            player.performCommand("duel admin reload") shouldBe true
            plugin.currentConfigGeneration() shouldBe beforeDenied
            player.drainPlainMessages().single().contains("arcduels.admin") shouldBe true
        }
    }

    "rejected command reload sends one last-good error and a revoked GUI permission sends one denial" {
        withReloadCommandPlugin { paper, plugin ->
            val admin = paper.server.addPlayer("ReloadRaceAdmin")
            admin.isOp = true
            admin.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
            editCommandConfig(plugin) { set("countdown-seconds", 99) }

            admin.performCommand("duel admin reload") shouldBe true
            plugin.currentConfigGeneration() shouldBe 1L
            val rejected = admin.drainPlainMessages()
            rejected.size shouldBe 1
            rejected.single().contains("версия v1 осталась активной") shouldBe true

            // Repair the file so only the permission race is under test.
            editCommandConfig(plugin) { set("countdown-seconds", 3) }
            admin.performCommand("duel") shouldBe true
            admin.simulateInventoryClick(admin.openInventory, ClickType.LEFT, 40)
            admin.drainPlainMessages()
            admin.isOp = false
            admin.simulateInventoryClick(admin.openInventory, ClickType.LEFT, 33)

            val denied = admin.drainPlainMessages()
            denied.size shouldBe 1
            denied.single().contains("arcduels.admin") shouldBe true
            plugin.currentConfigGeneration() shouldBe 1L
        }
    }

    "restart-only server id never leaks into active admin status debug or GUI" {
        withReloadCommandPlugin { paper, plugin ->
            val admin = paper.server.addPlayer("StartupIdAdmin")
            admin.isOp = true
            admin.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
            editCommandConfig(plugin) {
                set("server-id", "duels-next")
                set("server-display-names.duels-1", "<green>Startup node</green>")
                set("server-display-names.duels-next", "<red>Candidate node</red>")
            }

            plugin.reloadConfiguration().getOrThrow().restartRequired shouldBe
                setOf(ArcDuelsRestartOnlyField.SERVER_ID)
            plugin.config.getString("server-id") shouldBe "duels-next"

            admin.performCommand("duel admin status") shouldBe true
            val status = admin.drainPlainMessages().single()
            status.contains("Startup node") shouldBe true
            status.contains("Candidate node") shouldBe false

            admin.performCommand("duel admin debug server") shouldBe true
            val debug = admin.drainPlainMessages().single()
            debug.contains("server=duels-1") shouldBe true
            debug.contains("duels-next") shouldBe false

            admin.performCommand("duel admin") shouldBe true
            val statusLore =
                requireNotNull(admin.openInventory.topInventory.getItem(13))
                    .itemMeta
                    .lore()
                    .orEmpty()
                    .joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it) }
            statusLore.contains("Startup node") shouldBe true
            statusLore.contains("Candidate node") shouldBe false
        }
    }

    "malformed YAML feedback never echoes a nearby secret to chat or diagnostics" {
        withReloadCommandPlugin { paper, plugin ->
            val admin = paper.server.addPlayer("SecretReloadAdm")
            admin.isOp = true
            admin.setLocale(java.util.Locale.ENGLISH)
            val secret = "super-secret-never-echo"
            File(plugin.dataFolder, "config.yml").writeText(
                "mysql:\n  password: '$secret'\n  broken: [unterminated\n",
            )

            admin.performCommand("duel admin reload") shouldBe true

            val feedback = admin.drainPlainMessages().single()
            feedback.contains("invalid YAML configuration") shouldBe true
            feedback.contains(secret) shouldBe false
            configReloadFailureSummary(
                IllegalArgumentException("password=$secret"),
            ).contains(secret) shouldBe false
        }
    }
})

private fun withReloadCommandPlugin(block: (MockBukkitTestRuntime, ArcDuelsPlugin) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation {
            block(paper, paper.loadPlugin<ArcDuelsPlugin>())
        }
    }
}

private fun editCommandConfig(
    plugin: ArcDuelsPlugin,
    edit: YamlConfiguration.() -> Unit,
) {
    val file = File(plugin.dataFolder, "config.yml")
    val configuration = YamlConfiguration.loadConfiguration(file)
    configuration.edit()
    configuration.save(file)
}

private fun PlayerMock.drainPlainMessages(): List<String> {
    val plain = PlainTextComponentSerializer.plainText()
    val messages = mutableListOf<String>()
    while (true) {
        val message: Component = nextComponentMessage() ?: break
        messages += plain.serialize(message)
    }
    return messages
}
