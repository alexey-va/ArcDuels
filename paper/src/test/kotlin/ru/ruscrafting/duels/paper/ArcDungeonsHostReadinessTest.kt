package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.plugin.ServicePriority
import ru.arc.dungeons.api.ArcDungeonConfiguration
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class ArcDungeonsHostReadinessTest : StringSpec({
    "enabled provider readiness still gates actual plugin bootstrap" {
        MockBukkitTestRuntime.open().use { paper ->
            val ready = CompletableFuture<Void>()
            registerDungeonHost(paper, mockk { every { ready() } returns ready })
            val plugin = paper.loadPlugin<ArcDuelsPlugin>()
            plugin.ready().isDone shouldBe false
            shouldThrow<IllegalArgumentException> { plugin.config }
            ready.complete(null)
            awaitHostBootstrap(paper, plugin.ready())
            plugin.isEnabled shouldBe true
        }
    }

    "enabled provider readiness failure still fails plugin startup" {
        MockBukkitTestRuntime.open().use { paper ->
            val ready = CompletableFuture<Void>()
            registerDungeonHost(paper, mockk { every { ready() } returns ready })
            val plugin = paper.loadPlugin<ArcDuelsPlugin>()
            val failure = IllegalStateException("dungeon host could not prepare")
            ready.completeExceptionally(failure)
            shouldThrow<ExecutionException> { awaitHostBootstrap(paper, plugin.ready()) }.cause shouldBe failure
            plugin.isEnabled shouldBe false
        }
    }

    "enabled provider synchronous failure still reaches the existing startup failure boundary" {
        MockBukkitTestRuntime.open().use { paper ->
            val failure = IllegalStateException("dungeon host ready failed synchronously")
            registerDungeonHost(paper, mockk { every { ready() } throws failure })
            val plugin = paper.loadPlugin<ArcDuelsPlugin>()
            shouldThrow<ExecutionException> { plugin.ready().get(1, TimeUnit.SECONDS) }.cause shouldBe failure
            plugin.isEnabled shouldBe false
        }
    }
})

private fun registerDungeonHost(paper: MockBukkitTestRuntime, host: ArcDungeonConfiguration) {
    val provider = paper.createSimplePlugin("ArcDungeons")
    paper.server.servicesManager.register(ArcDungeonConfiguration::class.java, host, provider, ServicePriority.Normal)
}

private fun awaitHostBootstrap(paper: MockBukkitTestRuntime, ready: CompletableFuture<*>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!ready.isDone && System.nanoTime() < deadline) {
        paper.performTicks(1)
        Thread.sleep(1)
    }
    ready.get(1, TimeUnit.SECONDS)
}
