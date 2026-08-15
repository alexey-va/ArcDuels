package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.ConfigManager
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.ruscrafting.duels.domain.MatchId

/**
 * Structured duel diagnostics backed by arc-core.
 *
 * Every line has a stable action and, where available, match/player context so
 * one match can be reconstructed from console or Loki without parsing prose.
 */
object DuelLog {
    fun install(plugin: JavaPlugin) {
        val loggingFile = plugin.dataFolder.resolve("logging.yml")
        if (!loggingFile.isFile) plugin.saveResource("logging.yml", false)
        val config = ConfigManager.of(plugin.dataFolder.toPath(), "logging.yml")
        ArcLogging.install(
            platform = PaperLoggingPlatform(brandTag = "ArcDuels", julLoggerName = "ArcDuels"),
            configSource = LoggingConfigSource { config },
        )
        info("logging-ready", "structured duel diagnostics enabled level={}", ArcLogging.getLogLevel())
    }

    fun debug(action: String, message: String, vararg args: Any?) =
        write(false, action, null, null, message, *args)

    fun debug(action: String, matchId: MatchId?, message: String, vararg args: Any?) =
        write(false, action, matchId, null, message, *args)

    fun debug(action: String, player: Player, message: String, vararg args: Any?) =
        write(false, action, null, player, message, *args)

    fun info(action: String, message: String, vararg args: Any?) =
        write(true, action, null, null, message, *args)

    fun info(action: String, matchId: MatchId?, message: String, vararg args: Any?) =
        write(true, action, matchId, null, message, *args)

    fun info(action: String, player: Player, message: String, vararg args: Any?) =
        write(true, action, null, player, message, *args)

    fun warn(action: String, message: String, vararg args: Any?) =
        warn(action, null, null, message, *args)

    fun warn(action: String, matchId: MatchId?, message: String, vararg args: Any?) =
        warn(action, matchId, null, message, *args)

    fun warn(action: String, player: Player, message: String, vararg args: Any?) =
        warn(action, null, player, message, *args)

    fun debug(
        action: String,
        matchId: MatchId? = null,
        player: Player? = null,
        message: String,
        vararg args: Any?,
    ) = write(false, action, matchId, player, message, *args)

    fun info(
        action: String,
        matchId: MatchId? = null,
        player: Player? = null,
        message: String,
        vararg args: Any?,
    ) = write(true, action, matchId, player, message, *args)

    fun warn(
        action: String,
        matchId: MatchId? = null,
        player: Player? = null,
        message: String,
        vararg args: Any?,
    ) {
        withContext(action, player) {
            ArcLogging.warn(prefix(action, matchId) + message, *args)
        }
    }

    private fun write(
        lifecycle: Boolean,
        action: String,
        matchId: MatchId?,
        player: Player?,
        message: String,
        vararg args: Any?,
    ) {
        withContext(action, player) {
            if (lifecycle) {
                ArcLogging.info(prefix(action, matchId) + message, *args)
            } else {
                ArcLogging.debug(prefix(action, matchId) + message, *args)
            }
        }
    }

    private fun withContext(
        action: String,
        player: Player?,
        block: () -> Unit,
    ) {
        ArcLogging.withContext(
            module = "duels",
            player = player?.uniqueId?.toString(),
            action = action,
            block = Runnable(block),
        )
    }

    private fun prefix(action: String, matchId: MatchId?): String =
        buildString {
            append("event=").append(action)
            if (matchId != null) append(" match=").append(matchId.value)
            append(' ')
        }
}
