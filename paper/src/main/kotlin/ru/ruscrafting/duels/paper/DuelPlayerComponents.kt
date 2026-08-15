package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.entity.Player
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.PlayerStatistics
import ru.ruscrafting.duels.domain.StatisticsRepository
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class DuelPlayerComponents(
    private val statistics: StatisticsRepository,
    private val locales: LocaleService,
) {
    fun load(
        viewer: Player,
        playerId: UUID,
        playerName: String,
    ): CompletableFuture<Component> =
        statistics.find(PlayerId(playerId)).handle { stats, _ -> component(viewer, playerId, playerName, stats) }

    fun component(
        viewer: Player,
        playerId: UUID,
        playerName: String,
        stats: PlayerStatistics? = null,
    ): Component {
        val hover =
            if (stats == null) {
                locales.component(
                    viewer,
                    "player-link.hover-unavailable",
                    LocaleService.text("player", playerName),
                )
            } else {
                locales.component(
                    viewer,
                    "player-link.hover",
                    LocaleService.text("player", playerName),
                    LocaleService.text("rating", stats.rating),
                    LocaleService.text("wins", stats.wins),
                    LocaleService.text("losses", stats.losses),
                    LocaleService.text("winrate", "%.1f".format(java.util.Locale.ROOT, stats.winRate * 100)),
                    LocaleService.text("streak", stats.currentWinStreak),
                )
            }
        val name =
            locales.component(
                viewer,
                "player-link.name",
                LocaleService.text("player", playerName),
            ).hoverEvent(HoverEvent.showText(hover))
        val command =
            when {
                playerId == viewer.uniqueId -> "/duel stats"
                playerName.matches(MINECRAFT_NAME) -> "/duel $playerName"
                else -> null
            }
        return command?.let { name.clickEvent(ClickEvent.runCommand(it)) } ?: name
    }

    private companion object {
        val MINECRAFT_NAME = Regex("[A-Za-z0-9_]{1,16}")
    }
}
