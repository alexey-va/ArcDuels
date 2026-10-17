package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ServerId
import java.util.UUID

class DuelCommandCompletionTest : StringSpec({
    "players are offered only as duel targets and stats arguments with prefix and permission checks" {
        val playerId = UUID.randomUUID()
        val player = mockk<Player>(relaxed = true)
        every { player.uniqueId } returns playerId
        every { player.hasPermission("arcduels.use") } returns true
        val targets = mockk<DuelTargetDirectory> {
            every { players() } returns listOf(
                DuelTarget(playerId, "Self", ServerId("local"), true),
                DuelTarget(UUID.randomUUID(), "Target", ServerId("remote"), false),
            )
        }
        val handler = DuelCommand(mockk(), mockk(), mockk(), targets)
        val command = mockk<Command>()
        handler.onTabComplete(player, command, "duel", arrayOf("ta")) shouldBe listOf("Target")
        handler.onTabComplete(player, command, "duel", arrayOf("Self")) shouldBe emptyList()
        handler.onTabComplete(player, command, "duel", arrayOf("STATS", "ta")) shouldBe listOf("Target")
        handler.onTabComplete(player, command, "duel", arrayOf("stats", "Self")) shouldBe listOf("Self")
        listOf(arrayOf("accept", ""), arrayOf("cancel", ""), arrayOf("Target", ""), arrayOf("stats", "Target", ""), arrayOf("unknown", "")).forEach {
            handler.onTabComplete(player, command, "duel", it) shouldBe emptyList()
        }
        every { player.hasPermission("arcduels.admin") } returns true
        handler.onTabComplete(player, command, "duel", arrayOf("admin")) shouldBe listOf("admin")
        every { player.hasPermission("arcduels.use") } returns false
        handler.onTabComplete(player, command, "duel", arrayOf("")) shouldBe emptyList()
    }

    "unknown admin arena operations do not receive arena ids or default player names" {
        val config = YamlConfiguration().apply { set("arenas.alpha.enabled", false) }
        val plugin = mockk<JavaPlugin> { every { this@mockk.config } returns config }
        val sender = mockk<CommandSender>(relaxed = true)
        every { sender.hasPermission("arcduels.admin") } returns true
        val admin = DuelAdminCommand(plugin, mockk(), mockk(), serverNames = mockk(), startupServerId = ServerId("local"))
        admin.complete(sender, listOf("arena", "unknown", "")) shouldBe emptyList()
        admin.complete(sender, listOf("arena", "create", "")) shouldBe emptyList()
        admin.complete(sender, listOf("arena", "INFO", "AL")) shouldBe listOf("alpha")
        admin.complete(sender, listOf("arena", "info", "alpha", "")) shouldBe emptyList()
        every { sender.hasPermission("arcduels.admin") } returns false
        admin.complete(sender, listOf("recover", "")) shouldBe emptyList()
    }
})
