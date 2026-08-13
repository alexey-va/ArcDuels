package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.DuelObjectiveType

class PaperArenaQueueTest : StringSpec({
    lateinit var server: ServerMock
    lateinit var plugin: ArcDuelsPlugin

    beforeSpec {
        server = MockBukkit.mock()
        plugin = MockBukkit.load(ArcDuelsPlugin::class.java)
        server.addSimpleWorld("queue-world")
        configureArena(plugin, "queue", "queue-world")
    }

    afterSpec { MockBukkit.unmock() }

    "arena waiters are FIFO cancelled entries are skipped and reservation stays exclusive" {
        val catalog = PaperArenaCatalog.load(plugin)
        val rules = DuelRules(DuelMode.OWN_INVENTORY)

        val active = catalog.reserve(rules)
        val cancelled = catalog.reserve(rules)
        val next = catalog.reserve(rules)

        active.isDone shouldBe true
        cancelled.isDone shouldBe false
        next.isDone shouldBe false
        catalog.queueSize() shouldBe 2
        catalog.reservedCount() shouldBe 1

        cancelled.cancel(false) shouldBe true
        active.get().close()

        next.isDone shouldBe true
        next.get().arenaId shouldBe ArenaId("queue")
        catalog.queueSize() shouldBe 0
        catalog.reservedCount() shouldBe 1
        next.get().close()
        catalog.reservedCount() shouldBe 0
    }

    "arena catalog cannot hot reload while a match owns an arena or a pair is queued" {
        val catalog = PaperArenaCatalog.load(plugin)
        val active = catalog.reserve(DuelRules(DuelMode.OWN_INVENTORY)).get()
        val queued = catalog.reserve(DuelRules(DuelMode.OWN_INVENTORY))

        shouldThrow<IllegalStateException> { catalog.reload(plugin) }
        shouldThrow<IllegalStateException> { catalog.disable(ArenaId("queue")) }

        active.close()
        queued.get().close()
        catalog.disable(ArenaId("queue")) shouldBe 0
        shouldThrow<java.util.concurrent.ExecutionException> {
            catalog.reserve(DuelRules(DuelMode.OWN_INVENTORY)).get()
        }
    }

    "king of the hill fails fast without a hill and reserves a compatible arena when configured" {
        val eliminationOnly = PaperArenaCatalog.load(plugin)
        val kothRules = DuelRules(DuelMode.OWN_INVENTORY, objective = DuelObjectiveType.KING_OF_THE_HILL)
        shouldThrow<java.util.concurrent.ExecutionException> { eliminationOnly.reserve(kothRules).get() }

        val path = "arenas.queue.hill"
        plugin.config.set("$path.center.world", "queue-world")
        plugin.config.set("$path.center.x", 0.0)
        plugin.config.set("$path.center.y", 70.0)
        plugin.config.set("$path.center.z", 0.0)
        plugin.config.set("$path.radius", 3.5)
        plugin.config.set("$path.height", 3.0)
        val compatible = PaperArenaCatalog.load(plugin)

        compatible.reserve(kothRules).get().arenaId shouldBe ArenaId("queue")

        plugin.config.set("$path.radius", 20.0)
        shouldThrow<IllegalArgumentException> { PaperArenaCatalog.load(plugin) }
    }
})

private fun configureArena(
    plugin: ArcDuelsPlugin,
    id: String,
    world: String,
) {
    val path = "arenas.$id"
    plugin.config.set("$path.enabled", true)
    plugin.config.set("$path.first-spawn.world", world)
    plugin.config.set("$path.first-spawn.x", -5.0)
    plugin.config.set("$path.first-spawn.y", 70.0)
    plugin.config.set("$path.first-spawn.z", 0.0)
    plugin.config.set("$path.first-spawn.yaw", -90.0)
    plugin.config.set("$path.first-spawn.pitch", 0.0)
    plugin.config.set("$path.second-spawn.world", world)
    plugin.config.set("$path.second-spawn.x", 5.0)
    plugin.config.set("$path.second-spawn.y", 70.0)
    plugin.config.set("$path.second-spawn.z", 0.0)
    plugin.config.set("$path.second-spawn.yaw", 90.0)
    plugin.config.set("$path.second-spawn.pitch", 0.0)
    plugin.config.set("$path.bounds.min.x", -10.0)
    plugin.config.set("$path.bounds.min.y", 60.0)
    plugin.config.set("$path.bounds.min.z", -10.0)
    plugin.config.set("$path.bounds.max.x", 10.0)
    plugin.config.set("$path.bounds.max.y", 90.0)
    plugin.config.set("$path.bounds.max.z", 10.0)
}
