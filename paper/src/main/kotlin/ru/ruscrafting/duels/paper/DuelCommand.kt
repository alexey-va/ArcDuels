package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.ruscrafting.duels.domain.ChallengeId
import java.util.UUID

class DuelCommand internal constructor(
    private val controller: DuelController,
    private val gui: DuelGuiService,
    private val admin: DuelAdminCommand,
) : CommandExecutor, TabCompleter {
    private val miniMessage = MiniMessage.miniMessage()

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.firstOrNull()?.equals("admin", ignoreCase = true) == true) {
            admin.execute(sender, args.drop(1))
            return true
        }
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage("ArcDuels player command")
            return true
        }
        if (args.isEmpty()) {
            gui.openTargets(player)
            return true
        }
        when (args[0].lowercase()) {
            "accept", "принять" -> {
                val id = parseOptionalChallengeId(args, player) ?: if (args.size > 1) return true else null
                controller.accept(player, id)
            }
            "deny", "отклонить" -> {
                val id = parseOptionalChallengeId(args, player) ?: if (args.size > 1) return true else null
                controller.deny(player, id)
            }
            "cancel", "отмена" -> controller.cancel(player)
            "leave", "покинуть" -> controller.leave(player)
            "stats", "статы" -> {
                val target =
                    if (args.size > 1) {
                        player.server.getPlayerExact(args[1]) ?: run {
                            player.sendMessage(miniMessage.deserialize("<red>Игрок не найден.</red>"))
                            return true
                        }
                    } else {
                        player
                    }
                controller.showStatistics(player, target)
            }
            "top", "топ" -> gui.openLeaderboard(player)
            else -> {
                val target = player.server.getPlayerExact(args[0])
                if (target == null || target.uniqueId == player.uniqueId) {
                    player.sendMessage(miniMessage.deserialize("<red>Игрок не найден.</red>"))
                } else {
                    gui.openMode(player, target)
                }
            }
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.firstOrNull()?.equals("admin", ignoreCase = true) == true) {
            return admin.complete(sender, args.drop(1))
        }
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase()
        val commands = mutableListOf("accept", "deny", "cancel", "leave", "stats", "top")
        if (sender.hasPermission("arcduels.admin")) commands += "admin"
        return (commands + sender.server.onlinePlayers.map(Player::getName))
            .filter { it.lowercase().startsWith(prefix) }
            .sorted()
    }

    private fun parseOptionalChallengeId(
        args: Array<out String>,
        player: Player,
    ): ChallengeId? =
        args.getOrNull(1)?.let { raw ->
            runCatching { ChallengeId(UUID.fromString(raw)) }
            .onFailure { player.sendMessage(miniMessage.deserialize("<red>Некорректный идентификатор вызова.</red>")) }
            .getOrNull()
        }
}
