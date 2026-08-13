package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ChallengeId
import ru.ruscrafting.duels.domain.ChallengeRegistry
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.StatisticsRepository
import java.util.concurrent.CompletionException

class DuelController(
    private val plugin: JavaPlugin,
    private val challenges: ChallengeRegistry,
    private val sessions: DuelSessionManager,
    private val statistics: StatisticsRepository,
) {
    private val miniMessage = MiniMessage.miniMessage()

    fun challenge(
        challenger: Player,
        target: Player,
        rules: DuelRules,
    ) {
        if (sessions.matchFor(challenger) != null || sessions.matchFor(target) != null) {
            challenger.sendMessage(message("<red>Один из игроков уже участвует в дуэли.</red>"))
            return
        }
        runCatching { challenges.create(PlayerId(challenger.uniqueId), PlayerId(target.uniqueId), rules) }
            .onSuccess { challenge ->
                challenger.sendMessage(message("<green>Вызов отправлен игроку <white>${target.name}</white>.</green>"))
                target.sendMessage(challengeMessage(challenger, challenge))
                target.playSound(target.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f)
            }
            .onFailure { failure -> challenger.sendMessage(message("<red>${failure.message}</red>")) }
    }

    fun accept(
        player: Player,
        challengeId: ChallengeId? = null,
    ) {
        val challenge = resolveCandidate(player, challengeId, incoming = true) ?: return
        val accepted =
            runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), ChallengeStatus.ACCEPTED) }
                .getOrElse { failure ->
                    player.sendMessage(message("<red>${failure.message}</red>"))
                    return
                }
        if (accepted.status != ChallengeStatus.ACCEPTED) {
            player.sendMessage(message("<red>Этот вызов уже истёк.</red>"))
            return
        }
        runCatching { sessions.start(accepted) }
            .getOrElse { failure -> java.util.concurrent.CompletableFuture.failedFuture(failure) }
            .whenComplete { _, failure ->
            runSync {
                if (failure != null) {
                    val cause = unwrap(failure)
                    participants(accepted).forEach { it.sendMessage(message("<red>Не удалось начать дуэль: ${cause.message}</red>")) }
                }
            }
            }
    }

    fun deny(
        player: Player,
        challengeId: ChallengeId? = null,
    ) {
        val challenge = resolveCandidate(player, challengeId, incoming = true) ?: return
        runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), ChallengeStatus.DENIED) }
            .onSuccess { resolved ->
                participants(resolved).forEach { it.sendMessage(message("<gray>Вызов на дуэль отклонён.</gray>")) }
            }
            .onFailure { failure -> player.sendMessage(message("<red>${failure.message}</red>")) }
    }

    fun cancel(player: Player) {
        val challenge = resolveCandidate(player, null, incoming = false) ?: return
        runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), ChallengeStatus.CANCELLED) }
            .onSuccess { resolved -> participants(resolved).forEach { it.sendMessage(message("<gray>Вызов отменён.</gray>")) } }
            .onFailure { failure -> player.sendMessage(message("<red>${failure.message}</red>")) }
    }

    fun showStatistics(
        viewer: Player,
        target: Player,
    ) {
        statistics.find(PlayerId(target.uniqueId)).whenComplete { stats, failure ->
            runSync {
                if (failure != null) {
                    viewer.sendMessage(message("<red>Не удалось загрузить статистику.</red>"))
                    return@runSync
                }
                viewer.sendMessage(
                    message(
                        """
                        <dark_gray>────────</dark_gray> <gradient:#55ffff:#5555ff><bold>${target.name}</bold></gradient> <dark_gray>────────</dark_gray>
                        <gray>Рейтинг:</gray> <aqua><bold>${stats.rating}</bold></aqua>
                        <gray>Победы:</gray> <green>${stats.wins}</green>  <gray>Поражения:</gray> <red>${stats.losses}</red>
                        <gray>Винрейт:</gray> <yellow>${"%.1f".format(stats.winRate * 100)}%</yellow>
                        <gray>Серия:</gray> <gold>${stats.currentWinStreak}</gold>  <gray>Лучшая:</gray> <gold>${stats.bestWinStreak}</gold>
                        <dark_gray>────────────────────</dark_gray>
                        """.trimIndent(),
                    ),
                )
            }
        }
    }

    private fun resolveCandidate(
        player: Player,
        challengeId: ChallengeId?,
        incoming: Boolean,
    ): DuelChallenge? {
        val playerId = PlayerId(player.uniqueId)
        val pending = challenges.pendingFor(playerId)
        val candidate =
            challengeId?.let(challenges::find)
                ?: pending.lastOrNull { if (incoming) it.target == playerId else it.challenger == playerId }
        if (candidate == null) {
            player.sendMessage(message(if (incoming) "<red>У тебя нет входящих вызовов.</red>" else "<red>У тебя нет исходящих вызовов.</red>"))
        }
        return candidate
    }

    private fun challengeMessage(
        challenger: Player,
        challenge: DuelChallenge,
    ): Component {
        val kitId = challenge.rules.kitId
        val mode =
            if (kitId != null) {
                "кит <white>${kitId.value}</white>"
            } else {
                "своё снаряжение"
            }
        val prefix = message("<yellow><white>${challenger.name}</white> вызывает тебя на дуэль: $mode.</yellow> ")
        val accept =
            message("<green><bold>[ПРИНЯТЬ]</bold></green>")
                .clickEvent(ClickEvent.runCommand("/duel accept ${challenge.id}"))
                .hoverEvent(HoverEvent.showText(message("<green>Начать дуэль</green>")))
        val deny =
            message(" <red><bold>[ОТКЛОНИТЬ]</bold></red>")
                .clickEvent(ClickEvent.runCommand("/duel deny ${challenge.id}"))
                .hoverEvent(HoverEvent.showText(message("<red>Отклонить вызов</red>")))
        return prefix.append(accept).append(deny)
    }

    private fun participants(challenge: DuelChallenge): List<Player> =
        listOfNotNull(plugin.server.getPlayer(challenge.challenger.value), plugin.server.getPlayer(challenge.target.value))

    private fun message(input: String): Component = miniMessage.deserialize(input)

    private fun runSync(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (plugin.server.isPrimaryThread) block() else plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private fun unwrap(failure: Throwable): Throwable =
        if (failure is CompletionException && failure.cause != null) requireNotNull(failure.cause) else failure.cause ?: failure
}
