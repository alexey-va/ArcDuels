package ru.ruscrafting.duels.paper

import org.bukkit.Server
import ru.arc.dungeons.api.ArcDungeonConfiguration
import java.util.concurrent.CompletableFuture

/** The optional API is resolved only after its enabled plugin has crossed the soft-dependency boundary. */
internal object ArcDungeonsHostReadiness {
    fun ready(server: Server): CompletableFuture<Void> {
        if (!server.pluginManager.isPluginEnabled("ArcDungeons")) return CompletableFuture.completedFuture(null)
        return AvailableDungeonHost.ready(server)
    }

    private object AvailableDungeonHost {
        fun ready(server: Server): CompletableFuture<Void> =
            server.servicesManager.load(ArcDungeonConfiguration::class.java)?.ready()
                ?: CompletableFuture.completedFuture(null)
    }
}
