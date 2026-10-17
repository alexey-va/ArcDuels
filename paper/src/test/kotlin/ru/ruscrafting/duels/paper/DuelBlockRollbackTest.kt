package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.structure.Structure
import org.bukkit.structure.StructureManager
import org.bukkit.util.BlockVector
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.duels.domain.MatchId
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DuelBlockRollbackTest : StringSpec({
    "terminal retry waits accepted records and retains failed records on a permanent journal failure" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DuelRollbackTerminal")
            val world = paper.addSimpleWorld("terminal")
            world.getChunkAt(0, 0)
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val queue = BudgetedTaskQueue(scope, maximumSteps = 2, nanoTime = { 0 })
            val worker = BoundedAsyncExecutor("duel-rollback-terminal-test", 1, 32)
            val structures = mockk<StructureManager>()
            every { structures.createStructure() } returns mockk<Structure>(relaxed = true)
            every { structures.saveStructure(any<OutputStream>(), any<Structure>()) } answers {
                check(!Bukkit.isPrimaryThread())
                firstArg<OutputStream>().write(byteArrayOf(1, 2, 3))
            }
            val rollback = DuelBlockRollback(plugin, scope, queue, worker, structures)
            val directory = plugin.dataFolder.toPath().resolve("arena-recovery")
            try {
                advanceUntil(scheduler, rollback.recover())
                Files.deleteIfExists(directory)
                Files.writeString(directory, "obstruct journal directory")
                rollback.remember(MatchId.random(), world.getBlockAt(0, 64, 0)) shouldBe true
                worker.submit { Unit }.get(5, TimeUnit.SECONDS)
                rollback.available shouldBe false
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val actualDrain = worker.closeAsync {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    rollback.flushPending()
                }
                try {
                    entered.await(5, TimeUnit.SECONDS) shouldBe true
                    actualDrain.isDone shouldBe false
                    // Keep the obstruction: terminal failure must not become a successful predecessor.
                    release.countDown()
                    runCatching { actualDrain.get(5, TimeUnit.SECONDS) }.isFailure shouldBe true
                    actualDrain.isCompletedExceptionally shouldBe true
                    val pending = DuelBlockRollback::class.java.getDeclaredField("pending").apply { isAccessible = true }
                    (pending.get(rollback) as Map<*, *>).size shouldBe 1
                    Files.isRegularFile(directory) shouldBe true
                    Files.delete(directory)
                    Files.createDirectories(directory)
                    // A retained detached record can be retried by a reserved recovery owner, never on Paper.
                    val retryWorker = BoundedAsyncExecutor("duel-rollback-final-retry-test", 1, 1)
                    try { retryWorker.closeAsync { rollback.flushPending() }.get(5, TimeUnit.SECONDS) }
                    finally { retryWorker.closeAsync().get(5, TimeUnit.SECONDS) }
                    (pending.get(rollback) as Map<*, *>).size shouldBe 0
                    Files.list(directory).use { it.count() } shouldBe 1
                } finally { release.countDown() }
            } finally {
                queue.close()
                scope.close()
                runCatching { worker.closeAsync().get(5, TimeUnit.SECONDS) }
            }
        }
    }

    "accepted before-images survive cancelled native cleanup and replay before recovery becomes available" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("DuelRollbackTest")
            val world = paper.addSimpleWorld("rollback")
            world.getChunkAt(0, 0)
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val queue = BudgetedTaskQueue(scope, maximumSteps = 2, nanoTime = { 0 })
            val worker = BoundedAsyncExecutor("duel-rollback-test", 1, 32)
            val structures = mockk<StructureManager>()
            val encoded = AtomicInteger()
            val replayed = AtomicInteger()
            every { structures.createStructure() } answers {
                check(Bukkit.isPrimaryThread())
                mockk<Structure>(relaxed = true).also { detached ->
                    every { detached.fill(any<Location>(), any<BlockVector>(), false) } answers { check(Bukkit.isPrimaryThread()) }
                }
            }
            every { structures.saveStructure(any<OutputStream>(), any<Structure>()) } answers {
                check(!Bukkit.isPrimaryThread())
                firstArg<OutputStream>().write(byteArrayOf(1, 2, 3))
                encoded.incrementAndGet()
                Unit
            }
            every { structures.loadStructure(any<InputStream>()) } answers {
                check(!Bukkit.isPrimaryThread())
                mockk<Structure>(relaxed = true).also { detached ->
                    every { detached.place(any<Location>(), false, any(), any(), any(), any(), any()) } answers {
                        check(Bukkit.isPrimaryThread())
                        replayed.incrementAndGet()
                        Unit
                    }
                }
            }
            val rollback = DuelBlockRollback(plugin, scope, queue, worker, structures)
            try {
                advanceUntil(scheduler, rollback.recover())
                rollback.available shouldBe true
                val workerEntered = CountDownLatch(1)
                val releaseWorker = CountDownLatch(1)
                worker.submit { workerEntered.countDown(); check(releaseWorker.await(5, TimeUnit.SECONDS)) }
                workerEntered.await(5, TimeUnit.SECONDS) shouldBe true
                val match = MatchId.random()
                val applied = AtomicInteger()
                val originals = linkedMapOf<Int, BlockState>()
                repeat(5) { index ->
                    val block = world.getBlockAt(index, 64, 0).apply { type = Material.CHEST }
                    rollback.remember(match, block) shouldBe true
                    originals[index] = mockk<BlockState>().also { original ->
                        every { original.world } returns world
                        every { original.x } returns index
                        every { original.z } returns 0
                        every { original.update(true, false) } answers { check(Bukkit.isPrimaryThread()); applied.incrementAndGet(); true }
                    }
                }
                encoded.get() shouldBe 0 // Admission never waited for the blocked storage lane.
                releaseWorker.countDown()
                worker.submit { Unit }.get(5, TimeUnit.SECONDS)
                encoded.get() shouldBe 5
                val cleanup = rollback.restore(match, originals)
                scheduler.tick()
                (applied.get() in 1..2) shouldBe true
                cleanup.isDone shouldBe false
                queue.close()
                cleanup.isCompletedExceptionally shouldBe true
                worker.closeAsync().get(5, TimeUnit.SECONDS)
                val directory = plugin.dataFolder.toPath().resolve("arena-recovery")
                Files.list(directory).use { it.count() } shouldBe 5

                val nextScope = LifecycleTaskScope(scheduler)
                val nextQueue = BudgetedTaskQueue(nextScope, maximumSteps = 2, nanoTime = { 0 })
                val nextWorker = BoundedAsyncExecutor("duel-rollback-replay-test", 1, 32)
                val restarted = DuelBlockRollback(plugin, nextScope, nextQueue, nextWorker, structures)
                try {
                    val recovery = restarted.recover()
                    restarted.available shouldBe false
                    advanceUntil(scheduler, recovery)
                    replayed.get() shouldBe 5
                    restarted.available shouldBe true
                    Files.list(directory).use { it.count() } shouldBe 0
                } finally {
                    nextQueue.close()
                    nextScope.close()
                    nextWorker.closeAsync().get(5, TimeUnit.SECONDS)
                }
            } finally {
                queue.close()
                scope.close()
                worker.closeAsync().get(5, TimeUnit.SECONDS)
            }
        }
    }
})

private fun advanceUntil(scheduler: TestTaskScheduler, future: CompletableFuture<*>) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!future.isDone && System.nanoTime() < deadline) {
        scheduler.tick()
        Thread.sleep(1)
    }
    future.get(1, TimeUnit.SECONDS)
}
