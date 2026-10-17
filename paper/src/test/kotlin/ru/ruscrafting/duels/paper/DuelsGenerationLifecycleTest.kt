package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.ServicePriority
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.dungeons.api.ArcDungeonConfiguration
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class DuelsGenerationLifecycleTest : StringSpec({
    "reenable waits actual old worker drain and host readiness without opening new configuration" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadReadyDuelsPlugin()
                val field = ArcDuelsPlugin::class.java.getDeclaredField("worker").apply { isAccessible = true }
                val oldWorker = field.get(plugin) as BoundedAsyncExecutor
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val hostReady = CompletableFuture<Void>()
                val providerPlugin = paper.createSimplePlugin("DungeonsHost")
                val host = mockk<ArcDungeonConfiguration> {
                    every { ready() } answers { check(Bukkit.isPrimaryThread()); hostReady }
                    every { serverName() } throws AssertionError("No eager host configuration access")
                }
                paper.server.servicesManager.register(ArcDungeonConfiguration::class.java, host, providerPlugin, ServicePriority.Normal)
                oldWorker.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                try {
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    paper.server.pluginManager.disablePlugin(plugin)
                    plugin.drained().isDone shouldBe false
                    plugin.onDisable()
                    plugin.drained().isDone shouldBe false
                    paper.server.pluginManager.enablePlugin(plugin)
                    paper.performTicks(2)
                    plugin.ready().isDone shouldBe false
                    runCatching { plugin.config }.isFailure shouldBe true
                    release.countDown()
                    plugin.drained().get(5, TimeUnit.SECONDS)
                    paper.performTicks(2)
                    plugin.ready().isDone shouldBe false
                    runCatching { plugin.config }.isFailure shouldBe true
                    hostReady.complete(null)
                    awaitDuels(paper, plugin.ready())
                    plugin.isEnabled shouldBe true
                } finally {
                    release.countDown()
                    hostReady.complete(null)
                    paper.server.pluginManager.disablePlugin(plugin)
                    plugin.drained().get(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    "offline display names prefer cached profiles and wait the worker for exact missing names" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadReadyDuelsPlugin()
                val worldFolder = Files.createTempDirectory("duels-offline-names")
                val world = object : org.mockbukkit.mockbukkit.world.WorldMock(org.bukkit.WorldCreator("world")) {
                    override fun getWorldFolder() = worldFolder.toFile()
                }
                paper.server.addWorld(world)
                val cached = UUID.randomUUID()
                val persisted = UUID.randomUUID()
                val root = world.worldFolder.toPath().resolve("playerdata")
                Files.createDirectories(root)
                DataOutputStream(GZIPOutputStream(Files.newOutputStream(root.resolve("$persisted.dat")))).use { out ->
                    out.writeByte(10); out.writeUTF("")
                    out.writeByte(10); out.writeUTF("bukkit")
                    out.writeByte(8); out.writeUTF("lastKnownName"); out.writeUTF("SavedName")
                    out.writeByte(0); out.writeByte(0)
                }
                listOf(cached to "CachedName", persisted to null).forEach { (id, name) ->
                    val profile = paper.server.createProfileExact(id, name)
                    paper.server.playerList.addOfflinePlayer(mockk<OfflinePlayer> {
                        every { uniqueId } returns id
                        every { playerProfile } answers { check(Bukkit.isPrimaryThread()); profile }
                        every { getName() } throws AssertionError("Cold OfflinePlayer.name is forbidden")
                    })
                }
                val field = ArcDuelsPlugin::class.java.getDeclaredField("worker").apply { isAccessible = true }
                val worker = field.get(plugin) as BoundedAsyncExecutor
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                worker.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                try {
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    val names = plugin.loadPlayerNames(listOf(cached, persisted))
                    names.isDone shouldBe false
                    release.countDown()
                    awaitDuels(paper, names)
                    names.get() shouldBe mapOf(cached to "CachedName", persisted to "SavedName")
                } finally {
                    release.countDown()
                    paper.server.pluginManager.disablePlugin(plugin)
                    plugin.drained().get(5, TimeUnit.SECONDS)
                    worldFolder.toFile().deleteRecursively()
                }
            }
        }
    }
})

private fun awaitDuels(paper: MockBukkitTestRuntime, future: CompletableFuture<*>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!future.isDone && System.nanoTime() < deadline) { paper.performTicks(1); Thread.sleep(1) }
    future.get(1, TimeUnit.SECONDS)
}
