package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.Configuration
import ru.ruscrafting.duels.domain.ServerId
import java.util.concurrent.ConcurrentHashMap

/** Keeps transport ids internal while exposing configurable Adventure names to players. */
class ServerDisplayNames private constructor(
    names: Map<ServerId, Component>,
    private val warn: (String) -> Unit,
) {
    @Volatile
    private var names: Map<ServerId, Component> = names
    private val warnedMissing = ConcurrentHashMap.newKeySet<ServerId>()

    fun display(serverId: ServerId): Component {
        val configured: Component? = names[serverId]
        if (configured != null) return configured
        if (warnedMissing.add(serverId)) {
            warn("Missing player-facing display name for ArcDuels server id '${serverId.value}'")
        }
        return Component.text(serverId.value)
    }

    internal fun replaceWith(replacement: ServerDisplayNames) {
        names = replacement.names
        warnedMissing.clear()
    }

    companion object {
        private val miniMessage = MiniMessage.builder().strict(true).build()

        fun load(
            configuration: Configuration,
            warn: (String) -> Unit,
        ): ServerDisplayNames {
            val section = configuration.strictConfigurationSection("server-display-names")
            val names =
                section?.getKeys(false).orEmpty().associate { rawId ->
                    val configured = requireNotNull(section).strictString(rawId, "")
                    require(configured.isNotBlank()) { "server-display-names.$rawId cannot be blank" }
                    ServerId(rawId) to miniMessage.deserialize(configured)
                }
            return ServerDisplayNames(names, warn)
        }
    }
}
