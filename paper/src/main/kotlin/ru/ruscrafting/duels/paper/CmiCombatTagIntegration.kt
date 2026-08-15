package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import ru.ruscrafting.duels.domain.MatchId
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional bridge to CMI's public player-combat manager.
 *
 * ArcDuels owns the complete lifecycle of duel combat. CMI may still observe a
 * duel hit and add its general-purpose combat tag, which creates a second
 * bossbar and blocks the post-match teleport. Removing that tag only for active
 * duel participants preserves CMI combat protection everywhere else.
 */
internal class CmiCombatTagIntegration(
    private val plugin: JavaPlugin,
) : AutoCloseable {
    private val bridge = discoverBridge()
    private val settleTasks = ConcurrentHashMap<UUID, BukkitTask>()
    private val warned = AtomicBoolean(false)

    fun clear(
        player: Player,
        matchId: MatchId,
        reason: String,
    ) {
        val activeBridge = bridge ?: return
        if (!plugin.server.isPrimaryThread) {
            plugin.server.scheduler.runTask(plugin, Runnable { clear(player, matchId, reason) })
            return
        }

        clearNow(activeBridge, player, matchId, reason, settled = false)
        settleTasks.remove(player.uniqueId)?.cancel()
        settleTasks[player.uniqueId] =
            plugin.server.scheduler.runTaskLater(
                plugin,
                Runnable {
                    settleTasks.remove(player.uniqueId)
                    if (player.isOnline) clearNow(activeBridge, player, matchId, reason, settled = true)
                },
                SETTLE_DELAY_TICKS,
            )
    }

    override fun close() {
        settleTasks.values.forEach(BukkitTask::cancel)
        settleTasks.clear()
    }

    private fun clearNow(
        activeBridge: ReflectiveBridge,
        player: Player,
        matchId: MatchId,
        reason: String,
        settled: Boolean,
    ) {
        runCatching {
            val before = activeBridge.isTagged(player.uniqueId)
            activeBridge.remove(player)
            val after = activeBridge.isTagged(player.uniqueId)
            DuelLog.debug(
                "cmi-combat-tag-clear",
                matchId,
                player,
                "player={} reason={} settled={} tagged_before={} tagged_after={}",
                player.name,
                reason,
                settled,
                before,
                after,
            )
        }.onFailure { failure ->
            if (warned.compareAndSet(false, true)) {
                DuelLog.warn(
                    "cmi-combat-tag-clear-failed",
                    matchId,
                    player,
                    "player={} error_type={} error={}",
                    player.name,
                    failure.javaClass.simpleName,
                    failure.message,
                )
            }
        }
    }

    private fun discoverBridge(): ReflectiveBridge? {
        val cmi = plugin.server.pluginManager.getPlugin(CMI_PLUGIN_NAME)
        if (cmi == null || !cmi.isEnabled) {
            DuelLog.info("cmi-combat-integration", "available=false")
            return null
        }
        return runCatching { ReflectiveBridge.create(cmi) }
            .onSuccess {
                DuelLog.info(
                    "cmi-combat-integration",
                    "available=true version={}",
                    cmi.pluginMeta.version,
                )
            }
            .onFailure { failure ->
                DuelLog.warn(
                    "cmi-combat-integration-unavailable",
                    "version={} error_type={} error={}",
                    cmi.pluginMeta.version,
                    failure.javaClass.simpleName,
                    failure.message,
                )
            }
            .getOrNull()
    }

    internal class ReflectiveBridge(
        private val manager: Any,
        private val getUser: Method,
        private val removePlayerFromCombat: Method,
        private val isInCombatWithPlayer: Method,
    ) {
        fun remove(player: Player) {
            val user = getUser.invoke(null, player) ?: error("CMI did not return a user for ${player.uniqueId}")
            removePlayerFromCombat.invoke(manager, user)
        }

        fun isTagged(playerId: UUID): Boolean = isInCombatWithPlayer.invoke(manager, playerId) as Boolean

        companion object {
            fun create(
                cmi: Any,
                classLoader: ClassLoader = cmi.javaClass.classLoader,
            ): ReflectiveBridge {
                val manager = cmi.javaClass.getMethod("getPlayerCombatManager").invoke(cmi)
                    ?: error("CMI player combat manager is unavailable")
                val userClass = classLoader.loadClass(CMI_USER_CLASS)
                return ReflectiveBridge(
                    manager = manager,
                    getUser = userClass.getMethod("getUser", Player::class.java),
                    removePlayerFromCombat = manager.javaClass.getMethod("removePlayerFromCombat", userClass),
                    isInCombatWithPlayer = manager.javaClass.getMethod("isInCombatWithPlayer", UUID::class.java),
                )
            }
        }
    }

    private companion object {
        const val CMI_PLUGIN_NAME = "CMI"
        const val CMI_USER_CLASS = "com.Zrips.CMI.Containers.CMIUser"
        const val SETTLE_DELAY_TICKS = 1L
    }
}
