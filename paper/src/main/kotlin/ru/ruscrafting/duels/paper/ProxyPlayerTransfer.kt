package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ServerId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

fun interface PlayerTransfer {
    fun connect(player: Player, server: ServerId)
}

class ProxyPlayerTransfer(
    private val plugin: JavaPlugin,
) : PlayerTransfer, AutoCloseable {
    init {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
    }

    override fun connect(player: Player, server: ServerId) {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            output.writeUTF("Connect")
            output.writeUTF(server.value)
        }
        player.sendPluginMessage(plugin, CHANNEL, payload.toByteArray())
    }

    override fun close() {
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL)
    }

    private companion object {
        const val CHANNEL = "BungeeCord"
    }
}
