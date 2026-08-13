package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ChallengeId
import ru.ruscrafting.duels.domain.ChallengeRegistry
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.StatisticsRepository
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException

class DuelController(
    private val plugin: JavaPlugin,
    private val challenges: ChallengeRegistry,
    private val sessions: DuelSessionManager,
    private val statistics: StatisticsRepository,
    private val locales: LocaleService,
) {

    fun challenge(
        challenger: Player,
        target: Player,
        rules: DuelRules,
    ) {
        if (sessions.isEngaged(challenger) || sessions.isEngaged(target) ||
            sessions.isStateLocked(challenger) || sessions.isStateLocked(target)
        ) {
            challenger.sendMessage(locales.component(challenger, "controller.busy"))
            return
        }
        runCatching { challenges.create(PlayerId(challenger.uniqueId), PlayerId(target.uniqueId), rules) }
            .onSuccess { challenge ->
                challenger.sendMessage(locales.component(challenger, "controller.sent", LocaleService.text("player", target.name)))
                target.sendMessage(challengeMessage(target, challenger, challenge))
                target.playSound(target.location, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.5f)
            }
            .onFailure { challenger.sendMessage(locales.component(challenger, "controller.failed")) }
    }

    fun accept(
        player: Player,
        challengeId: ChallengeId? = null,
    ) {
        val challenge = resolveCandidate(player, challengeId, incoming = true) ?: return
        val accepted =
            runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), ChallengeStatus.ACCEPTED) }
                .getOrElse { failure ->
                    player.sendMessage(locales.component(player, "controller.failed"))
                    return
                }
        if (accepted.status != ChallengeStatus.ACCEPTED) {
            player.sendMessage(locales.component(player, "controller.expired"))
            return
        }
        runCatching { sessions.start(accepted) }
            .getOrElse { failure -> java.util.concurrent.CompletableFuture.failedFuture(failure) }
            .whenComplete { _, failure ->
                runSync {
                    if (failure != null) {
                        val cause = unwrap(failure)
                        participants(accepted).forEach {
                            val reasonKey = if (cause is CancellationException) "controller.wait-cancelled" else "controller.start-internal"
                            it.sendMessage(locales.component(it, "controller.start-failed", LocaleService.component("reason", locales.component(it, reasonKey))))
                        }
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
                participants(resolved).forEach { it.sendMessage(locales.component(it, "controller.denied")) }
            }
            .onFailure { player.sendMessage(locales.component(player, "controller.failed")) }
    }

    fun cancel(player: Player) {
        val challenge = resolveCandidate(player, null, incoming = false) ?: return
        runCatching { challenges.resolve(challenge.id, PlayerId(player.uniqueId), ChallengeStatus.CANCELLED) }
            .onSuccess { resolved -> participants(resolved).forEach { it.sendMessage(locales.component(it, "controller.cancelled")) } }
            .onFailure { player.sendMessage(locales.component(player, "controller.failed")) }
    }

    fun leave(player: Player) {
        if (sessions.handleForfeit(player)) {
            player.sendMessage(locales.component(player, "controller.forfeit"))
        } else {
            player.sendMessage(locales.component(player, "controller.not-fighting"))
        }
    }

    fun showStatistics(
        viewer: Player,
        target: Player,
    ) {
        statistics.find(PlayerId(target.uniqueId)).whenComplete { stats, failure ->
            runSync {
                if (failure != null) {
                    viewer.sendMessage(locales.component(viewer, "controller.stats-failed"))
                    return@runSync
                }
                viewer.sendMessage(
                    locales.component(
                        viewer,
                        "controller.stats",
                        LocaleService.text("player", target.name),
                        LocaleService.text("rating", stats.rating),
                        LocaleService.text("wins", stats.wins),
                        LocaleService.text("losses", stats.losses),
                        LocaleService.text("winrate", "%.1f".format(java.util.Locale.ROOT, stats.winRate * 100)),
                        LocaleService.text("streak", stats.currentWinStreak),
                        LocaleService.text("best", stats.bestWinStreak),
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
            player.sendMessage(locales.component(player, if (incoming) "controller.no-incoming" else "controller.no-outgoing"))
        }
        return candidate
    }

    private fun challengeMessage(
        recipient: Player,
        challenger: Player,
        challenge: DuelChallenge,
    ): Component {
        val kitId = challenge.rules.kitId
        val mode =
            if (kitId != null) {
                val key = "kit.${kitId.value}.name"
                val kitName =
                    if (locales.hasKey(locales.language(recipient), key)) locales.component(recipient, key)
                    else Component.text(kitId.value)
                locales.component(recipient, "controller.loadout-kit", LocaleService.component("kit", kitName))
            } else {
                locales.component(recipient, "controller.loadout-own")
            }
        val objectiveKey =
            when (challenge.rules.objective) {
                ru.ruscrafting.duels.domain.DuelObjectiveType.ELIMINATION -> "objective.elimination.name"
                ru.ruscrafting.duels.domain.DuelObjectiveType.KING_OF_THE_HILL -> "objective.koth.name"
                ru.ruscrafting.duels.domain.DuelObjectiveType.SUMO -> "objective.sumo.name"
            }
        fun state(value: Boolean) = locales.component(recipient, if (value) "menu.common.enabled" else "menu.common.disabled")
        val prefix =
            locales.component(
                recipient,
                "controller.received",
                LocaleService.text("player", challenger.name),
                LocaleService.component("objective", locales.component(recipient, objectiveKey)),
                LocaleService.component("loadout", mode),
                LocaleService.text("bestof", challenge.rules.bestOf),
                LocaleService.component("ranked", state(challenge.rules.ranked)),
                LocaleService.text("sudden", challenge.rules.modifiers.suddenDeathAfterSeconds),
                LocaleService.component("projectiles", state(challenge.rules.modifiers.projectiles)),
                LocaleService.component("consumables", state(challenge.rules.modifiers.consumables)),
                LocaleService.component("pearls", state(challenge.rules.modifiers.enderPearls)),
                LocaleService.component("regeneration", state(challenge.rules.modifiers.naturalRegeneration)),
            )
        val accept =
            locales.component(recipient, "controller.accept")
                .clickEvent(ClickEvent.runCommand("/duel accept ${challenge.id}"))
                .hoverEvent(HoverEvent.showText(locales.component(recipient, "controller.accept-hover")))
        val deny =
            locales.component(recipient, "controller.deny")
                .clickEvent(ClickEvent.runCommand("/duel deny ${challenge.id}"))
                .hoverEvent(HoverEvent.showText(locales.component(recipient, "controller.deny-hover")))
        return prefix.append(accept).append(deny)
    }

    private fun participants(challenge: DuelChallenge): List<Player> =
        listOfNotNull(plugin.server.getPlayer(challenge.challenger.value), plugin.server.getPlayer(challenge.target.value))

    private fun runSync(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (plugin.server.isPrimaryThread) block() else plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private fun unwrap(failure: Throwable): Throwable =
        if (failure is CompletionException && failure.cause != null) requireNotNull(failure.cause) else failure.cause ?: failure
}
