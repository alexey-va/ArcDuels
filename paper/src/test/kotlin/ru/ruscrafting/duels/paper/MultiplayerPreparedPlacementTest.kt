package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.duels.domain.*
import java.util.UUID

class MultiplayerPreparedPlacementTest : StringSpec({
    "prepared capacity and assignment use every balanced team shape without repeating native planning" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("prepared-placement")
            val arena = PaperArena(ArenaId("prepared"), "Prepared", Location(world, -5.0, 70.0, 0.0),
                Location(world, 5.0, 70.0, 0.0), ArenaBounds(world.uid, -20.0, 60.0, -20.0, 20.0, 90.0, 20.0))
            var nativeCalls = 0
            arena.multiplayerNavigation = MultiplayerSpawnNavigation { candidate -> nativeCalls++; candidate.clone() }
            val rosters = buildList {
                for (size in 3..12) for (layout in MultiplayerLayout.entries) {
                    val teams = layout.teamCount
                    for (offset in 0 until (teams ?: 1)) {
                        add(MultiplayerRoster(
                            MultiplayerRules(layout, MultiplayerKitPolicy.SHARED, KitId("classic")),
                            List(size) { index -> MultiplayerParticipant(PlayerId(UUID(0, index + 1L)),
                                team = teams?.let { (index + offset) % it + 1 }, kitId = KitId("classic")) },
                        ))
                    }
                }
            }
            val expected = rosters.map(arena::assignSpawns)
            nativeCalls = 0
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val queue = BudgetedTaskQueue(scope, maximumSteps = 2, nanoTime = { 0 })
            try {
                val ready = arena.prepareMultiplayer(queue)
                nativeCalls shouldBe 0
                var ticks = 0
                while (!ready.isDone && ticks++ < 10_000) {
                    val before = nativeCalls
                    scheduler.tick()
                    (nativeCalls - before in 0..2) shouldBe true
                }
                ready.get()
                val preparedCalls = nativeCalls
                rosters.forEachIndexed { index, roster ->
                    arena.supports(roster) shouldBe true
                    arena.assignSpawns(roster) shouldBe expected[index]
                }
                nativeCalls shouldBe preparedCalls
            } finally { queue.close(); scope.close() }
        }
    }
})
