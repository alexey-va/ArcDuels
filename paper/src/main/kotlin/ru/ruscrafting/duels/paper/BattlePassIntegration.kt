package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.DuelEvent
import ru.ruscrafting.duels.domain.DuelEventPublisher
import ru.ruscrafting.duels.domain.MatchCompletedEvent
import ru.ruscrafting.duels.domain.PlayerId
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.math.BigInteger
import java.util.Locale

internal data class BattlePassAward(
    val player: PlayerId,
    val type: String,
    val variable: String,
)

internal fun DuelEvent.battlePassAwards(): List<BattlePassAward> {
    if (this !is MatchCompletedEvent) return emptyList()
    val objective = objective.name.lowercase(Locale.ROOT)
    return buildList {
        add(BattlePassAward(winner, "arcduels-match", objective))
        add(BattlePassAward(loser, "arcduels-match", objective))
        add(BattlePassAward(winner, "arcduels-win", objective))
        if (ranked) add(BattlePassAward(winner, "arcduels-ranked-win", objective))
    }
}

internal class BattlePassIntegration(
    private val plugin: JavaPlugin,
) {
    private val sink = ReflectiveBattlePassProgressSink.create(plugin)

    fun observing(delegate: DuelEventPublisher): DuelEventPublisher =
        DuelEventPublisher { event ->
            observe(event)
            delegate.publish(event)
        }

    private fun observe(event: DuelEvent) {
        val activeSink = sink ?: return
        val awards = event.battlePassAwards()
        if (awards.isEmpty()) return
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                if (!plugin.isEnabled) return@Runnable
                awards.forEach { award ->
                    plugin.server.getPlayer(award.player.value)?.let { player ->
                        activeSink.progress(player, award.type, award.variable)
                    }
                }
            },
        )
    }
}

private fun interface BattlePassProgressSink {
    fun progress(player: Player, type: String, variable: String)
}

private class ReflectiveBattlePassProgressSink(
    private val plugin: JavaPlugin,
    private val resultConstructor: Constructor<*>,
    private val rootFrom: Method,
    private val executionConstructor: Constructor<*>,
    private val reader: Any,
    private val onAction: Method,
) : BattlePassProgressSink {
    private var active = true

    override fun progress(player: Player, type: String, variable: String) {
        if (!active) return
        runCatching {
            val result = resultConstructor.newInstance()
            rootFrom.invoke(result, variable)
            val execution = executionConstructor.newInstance(player, type, BigInteger.ONE, false, result)
            onAction.invoke(reader, execution)
        }.onFailure { failure ->
            active = false
            plugin.logger.warning(
                "BattlePass progress bridge disabled after an API error: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    companion object {
        fun create(plugin: JavaPlugin): BattlePassProgressSink? {
            val battlePass = plugin.server.pluginManager.getPlugin("BattlePass")
            if (battlePass == null || !battlePass.isEnabled) {
                plugin.logger.info("BattlePass is not active; duel progress integration is disabled")
                return null
            }
            return runCatching {
                val loader = battlePass.javaClass.classLoader
                val resultClass =
                    Class.forName(
                        "net.advancedplugins.bp.impl.actions.objects.variable.ExecutableActionResult",
                        true,
                        loader,
                    )
                val executionClass =
                    Class.forName("net.advancedplugins.bp.impl.actions.ActionExecution", true, loader)
                val registryClass =
                    Class.forName("net.advancedplugins.bp.impl.actions.ActionRegistry", true, loader)
                val readerClass =
                    Class.forName("net.advancedplugins.bp.impl.actions.ActionsReader", true, loader)
                val registry = requireNotNull(registryClass.getMethod("getRegistry").invoke(null))
                val reader = requireNotNull(registryClass.getMethod("getReader").invoke(registry))
                ReflectiveBattlePassProgressSink(
                    plugin = plugin,
                    resultConstructor = resultClass.getConstructor(),
                    rootFrom = resultClass.getMethod("rootFrom", Any::class.java),
                    executionConstructor =
                        executionClass.getConstructor(
                            Player::class.java,
                            String::class.java,
                            BigInteger::class.java,
                            Boolean::class.javaPrimitiveType,
                            resultClass,
                        ),
                    reader = reader,
                    onAction = readerClass.getMethod("onAction", executionClass),
                )
            }.onSuccess {
                plugin.logger.info("BattlePass duel progress integration enabled")
            }.onFailure { failure ->
                plugin.logger.warning(
                    "BattlePass is active but its progress API is incompatible: " +
                        "${failure.javaClass.simpleName}: ${failure.message}",
                )
            }.getOrNull()
        }
    }
}
