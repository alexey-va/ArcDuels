package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.ruscrafting.duels.domain.ChallengeId
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.ServerId
import java.util.UUID

class DuelCommand internal constructor(
    private val controller: DuelController,
    private val gui: DuelGuiService,
    private val admin: DuelAdminCommand,
    private val targets: DuelTargetDirectory? = null,
    private val locales: LocaleService? = null,
    private val multiplayerInvitations: MultiplayerInvitationActions? = null,
) : CommandExecutor, TabCompleter {
    private val miniMessage = MiniMessage.miniMessage()

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        if (args.firstOrNull()?.equals("admin", ignoreCase = true) == true) {
            if (sender is Player && args.size == 1) gui.openAdmin(sender) else admin.execute(sender, args.drop(1))
            return true
        }
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(locales?.notice(sender, "error.player-only") ?: miniMessage.deserialize("<red>This command requires a player.</red>"))
            return true
        }
        if (args.isEmpty()) {
            gui.openMain(player)
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
            "return", "вернуться" -> controller.returnToOrigin(player)
            "stats", "статы" -> {
                val target =
                    if (args.size > 1) {
                        resolveTarget(player, args[1]) ?: run {
                            player.sendMessage(message(player, "error.player-left", "<red>Player not found.</red>"))
                            return true
                        }
                    } else {
                        targets?.local(player) ?: localTarget(player)
                    }
                controller.showStatistics(player, target)
            }
            "top", "топ" -> gui.openLeaderboard(player)
            "history", "история" -> gui.openHistory(player)
            "group", "группа" -> handleGroupInvitation(player, args)
            "rematch", "реванш" -> {
                val matchId =
                    args.getOrNull(1)?.let { raw ->
                        runCatching { MatchId(UUID.fromString(raw)) }
                            .onFailure { player.sendMessage(message(player, "error.invalid-match-id", "<red>Invalid match identifier.</red>")) }
                            .getOrNull() ?: return true
                    }
                controller.rematch(player, matchId)
            }
            else -> {
                val target = resolveTarget(player, args[0])
                if (target == null || target.uniqueId == player.uniqueId) {
                    player.sendMessage(message(player, "error.player-left", "<red>Player not found.</red>"))
                } else {
                    gui.openChallenge(player, target)
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
        val commands = mutableListOf("accept", "deny", "cancel", "leave", "return", "stats", "top", "history", "rematch")
        if (sender.hasPermission("arcduels.admin")) commands += "admin"
        val playerNames = targets?.players()?.map(DuelTarget::name) ?: sender.server.onlinePlayers.map(Player::getName)
        return (commands + playerNames)
            .distinctBy { it.lowercase() }
            .filter { it.lowercase().startsWith(prefix) }
            .sorted()
    }

    private fun parseOptionalChallengeId(
        args: Array<out String>,
        player: Player,
    ): ChallengeId? =
        args.getOrNull(1)?.let { raw ->
            runCatching { ChallengeId(UUID.fromString(raw)) }
            .onFailure { player.sendMessage(message(player, "error.invalid-challenge-id", "<red>Invalid challenge identifier.</red>")) }
            .getOrNull()
        }

    private fun handleGroupInvitation(player: Player, args: Array<out String>) {
        val lobbyId =
            args.getOrNull(2)?.let { raw ->
                runCatching { UUID.fromString(raw) }
                    .onFailure { player.sendMessage(message(player, "error.invalid-lobby-id", "<red>Invalid group lobby identifier.</red>")) }
                    .getOrNull()
            } ?: return
        when (args.getOrNull(1)?.lowercase()) {
            "open", "открыть" -> multiplayerInvitations?.openInvitation(player, lobbyId)
                ?: player.sendMessage(message(player, "multiplayer.invite-unavailable", "<gray>This group invitation is no longer available.</gray>"))
            "decline", "отклонить" -> multiplayerInvitations?.declineInvitation(player, lobbyId)
                ?: player.sendMessage(message(player, "multiplayer.invite-unavailable", "<gray>This group invitation is no longer available.</gray>"))
            else -> player.sendMessage(message(player, "error.invalid-lobby-id", "<red>Invalid group lobby identifier.</red>"))
        }
    }

    private fun message(player: Player, key: String, fallback: String) =
        locales?.notice(player, key) ?: miniMessage.deserialize(fallback)

    private fun resolveTarget(player: Player, name: String): DuelTarget? =
        targets?.find(name) ?: player.server.getPlayerExact(name)?.let(::localTarget)

    private fun localTarget(player: Player): DuelTarget =
        DuelTarget(player.uniqueId, player.name, ServerId("local"), local = true)
}
