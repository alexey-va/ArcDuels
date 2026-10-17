package ru.ruscrafting.duels.paper

import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.util.concurrent.TimeUnit
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BukkitTaskScheduler
import ru.ruscrafting.duels.domain.ArenaSelection

/** Test-only wait: production readiness never blocks the Paper thread. */
internal fun MockBukkitTestRuntime.loadReadyDuelsPlugin(): ArcDuelsPlugin {
    val plugin = loadPlugin<ArcDuelsPlugin>()
    val ready = plugin.ready()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!ready.isDone && System.nanoTime() < deadline) {
        performTicks(1)
        Thread.sleep(1)
    }
    ready.get(1, TimeUnit.SECONDS)
    return plugin
}

/** Join recovery checks are intentionally asynchronous and lock native mutation until the result is known. */
internal fun MockBukkitTestRuntime.addReadyDuelsPlayer(plugin: ArcDuelsPlugin, name: String? = null): PlayerMock {
    val player = if (name == null) server.addPlayer() else server.addPlayer(name)
    val field = ArcDuelsPlugin::class.java.getDeclaredField("sessions").apply { isAccessible = true }
    val sessions = field.get(plugin) as? DuelSessionManager ?: return player
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (sessions.isPreparing(player) && System.nanoTime() < deadline) {
        performTicks(1)
        Thread.sleep(1)
    }
    check(!sessions.isPreparing(player)) { "Fixture player recovery lookup did not finish" }
    return player
}

/** Offline/synchronous catalog fixtures explicitly complete the same queued navigation preparation as runtime startup. */
internal fun MockBukkitTestRuntime.loadReadyArenaCatalog(
    plugin: ArcDuelsPlugin,
    preference: ArenaSelection? = ArcDuelsRuntimeSettingsParser.parsePreferredArenaSelection(plugin.config),
): PaperArenaCatalog {
    val catalog = PaperArenaCatalog.load(plugin, preference)
    // MockBukkit does not implement getChunkAtAsync. Supply the native loaded-chunk precondition
    // explicitly while keeping the production navigation search and its count/time budget real.
    plugin.config.getConfigurationSection("arenas")?.getKeys(false).orEmpty().forEach { id ->
        val path = "arenas.$id"
        if (!plugin.config.getBoolean("$path.enabled")) return@forEach
        val world = requireNotNull(server.getWorld(plugin.config.getString("$path.first-spawn.world")!!))
        val minX = kotlin.math.floor(plugin.config.getDouble("$path.bounds.min.x") - 1).toInt() shr 4
        val minZ = kotlin.math.floor(plugin.config.getDouble("$path.bounds.min.z") - 1).toInt() shr 4
        val maxX = kotlin.math.ceil(plugin.config.getDouble("$path.bounds.max.x") + 1).toInt() shr 4
        val maxZ = kotlin.math.ceil(plugin.config.getDouble("$path.bounds.max.z") + 1).toInt() shr 4
        for (x in minX..maxX) for (z in minZ..maxZ) world.loadChunk(x, z)
    }
    val scope = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val queue = BudgetedTaskQueue(scope)
    try {
        val ready = catalog.prepareNavigation(queue)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!ready.isDone && System.nanoTime() < deadline) { performTicks(1); Thread.sleep(1) }
        ready.get(1, TimeUnit.SECONDS)
        return catalog
    } finally { queue.close(); scope.close() }
}

/** Wait for the real durable-save/reload barrier before the next independent admin action. */
internal fun MockBukkitTestRuntime.awaitDuelsAdminIdle(plugin: ArcDuelsPlugin) {
    val command = requireNotNull(plugin.getCommand("duel")?.executor)
    val admin = DuelCommand::class.java.getDeclaredField("admin").apply { isAccessible = true }.get(command)
    val saving = DuelAdminCommand::class.java.getDeclaredField("saving").apply { isAccessible = true }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (saving.getBoolean(admin) && System.nanoTime() < deadline) { performTicks(1); Thread.sleep(1) }
    check(!saving.getBoolean(admin)) { "Fixture admin durable save/reload did not finish" }
}

internal fun MockBukkitTestRuntime.awaitDuelsCatalogPublication(plugin: ArcDuelsPlugin) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (plugin.hasDeferredConfigurationCatalogs() && System.nanoTime() < deadline) {
        performTicks(1)
        Thread.sleep(1)
    }
    check(!plugin.hasDeferredConfigurationCatalogs()) { "Fixture deferred catalog publication did not finish" }
}
