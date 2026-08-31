package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.paper.network.BackendTransferResult
import ru.arc.paper.network.BungeeBackendTransfer
import ru.ruscrafting.duels.domain.ServerId

fun interface PlayerTransfer {
    fun connect(player: Player, server: ServerId): BackendTransferResult
}

internal fun isSuccessfulBackendTransfer(result: BackendTransferResult?): Boolean =
    result == BackendTransferResult.SENT

class ProxyPlayerTransfer(
    plugin: JavaPlugin,
) : PlayerTransfer, AutoCloseable {
    private val delegate = BungeeBackendTransfer(plugin)

    override fun connect(player: Player, server: ServerId): BackendTransferResult =
        delegate.connect(player, server.toBackendServerId())

    override fun close() = delegate.close()
}
