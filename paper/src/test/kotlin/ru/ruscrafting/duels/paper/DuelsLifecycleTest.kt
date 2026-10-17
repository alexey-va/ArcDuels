package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.Collections
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.ruscrafting.duels.mysql.MySqlStatisticsRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class DuelsLifecycleTest : StringSpec({
    "shutdown cancels native callbacks then waits accepted IO and retention before closing resources" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadReadyDuelsPlugin()
                fun field(name: String) = ArcDuelsPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }
                val worker = field("worker").get(plugin) as BoundedAsyncExecutor
                val lifecycle = field("pluginRuntime").get(plugin) as PaperPluginRuntime
                val token = lifecycle.tasks.token()
                val order = Collections.synchronizedList(mutableListOf<String>())
                val accepted = worker.submit { order += "accepted-io" }
                val retained = CompletableFuture<Unit>()
                val session = field("sessions").get(plugin) as DuelSessionManager
                val states = DuelSessionManager::class.java.getDeclaredField("playerStates").apply { isAccessible = true }
                    .get(session) as DurablePlayerStateService
                @Suppress("UNCHECKED_CAST")
                val pendingRetentions = DurablePlayerStateService::class.java.getDeclaredField("retentions").apply { isAccessible = true }
                    .get(states) as MutableMap<UUID, CompletableFuture<Unit>>
                pendingRetentions[UUID.randomUUID()] = retained
                val terminalFailure = IllegalStateException("injected repository close failure")
                field("blockRollback").set(plugin, mockk<DuelBlockRollback> {
                    every { flushPending() } answers {
                        check(!Bukkit.isPrimaryThread())
                        lifecycle.tasks.isCurrent(token) shouldBe false
                        accepted.isDone shouldBe true
                        order += "flush"
                        CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute {
                            order += "retention"
                            retained.complete(Unit)
                        }
                    }
                })
                field("redisClient").set(plugin, mockk<PaperRedisClient> {
                    every { close() } answers {
                        check(!Bukkit.isPrimaryThread())
                        retained.isDone shouldBe true
                        order += "redis-close"
                        Unit
                    }
                })
                field("repository").set(plugin, mockk<MySqlStatisticsRepository> {
                    every { close() } answers {
                        check(!Bukkit.isPrimaryThread())
                        retained.isDone shouldBe true
                        order += "repository-close"
                        throw terminalFailure
                    }
                })
                try {
                    shouldThrow<CompletionException> { plugin.onDisable() }.cause shouldBe terminalFailure
                    accepted.get(0, TimeUnit.SECONDS)
                    retained.isDone shouldBe true
                    order.toList() shouldBe listOf("accepted-io", "flush", "retention", "redis-close", "repository-close")
                    plugin.drained().isCompletedExceptionally shouldBe true
                    worker.submit { Unit }.isCompletedExceptionally shouldBe true
                    plugin.onDisable()
                } finally {
                    retained.complete(Unit)
                    paper.server.pluginManager.disablePlugin(plugin)
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
