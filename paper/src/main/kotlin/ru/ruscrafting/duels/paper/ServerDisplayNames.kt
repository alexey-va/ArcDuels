package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.Configuration
import ru.ruscrafting.duels.domain.ServerId
import java.util.concurrent.ConcurrentHashMap

/** Keeps transport ids internal while exposing configurable Adventure names to players. */
class ServerDisplayNames private constructor(
    private val names: Map<ServerId, Component>,
    private val warn: (String) -> Unit,
) {
    private val warnedMissing = ConcurrentHashMap.newKeySet<ServerId>()

    fun display(serverId: ServerId): Component {
        val configured: Component? = names[serverId]
        if (configured != null) return configured
        if (warnedMissing.add(serverId)) {
            warn("Missing player-facing display name for ArcDuels server id '${serverId.value}'")
        }
        return Component.text(serverId.value)
    }

    companion object {
        private val miniMessage = MiniMessage.builder().strict(true).build()

        fun load(
            configuration: Configuration,
            warn: (String) -> Unit,
        ): ServerDisplayNames {
            val section = configuration.getConfigurationSection("server-display-names")
            val names =
                section?.getKeys(false).orEmpty().associate { rawId ->
                    val configured = requireNotNull(section?.getString(rawId)) {
                        "server-display-names.$rawId must be a MiniMessage string"
                    }
                    require(configured.isNotBlank()) { "server-display-names.$rawId cannot be blank" }
                    ServerId(rawId) to miniMessage.deserialize(configured)
                }
            return ServerDisplayNames(names, warn)
        }
    }
}
