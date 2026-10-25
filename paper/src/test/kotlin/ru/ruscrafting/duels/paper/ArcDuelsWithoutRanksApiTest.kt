package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime

/** A separate JVM excludes the API artifact, rather than merely hiding its Bukkit service. */
class ArcDuelsWithoutRanksApiTest : StringSpec({
    "actual plugin and session types bootstrap without ArcRanks or its API" {
        val loader = ArcDuelsPlugin::class.java.classLoader
        loader.getResource("ru/ruscrafting/ranks/api/ClanArenaQuestApi.class") shouldBe null
        shouldThrow<ClassNotFoundException> { loader.loadClass("ru.ruscrafting.ranks.api.ClanArenaQuestApi") }
        MockBukkitTestRuntime.open().use { paper ->
            paper.server.pluginManager.isPluginEnabled("ArcRanks") shouldBe false
            multiplayerHarness(paper).use { harness ->
                harness.plugin.isEnabled shouldBe true
                captureArenaQuestSession(emptySet()) shouldBe null
                DuelSessionManager::class.java.declaredMethods.isNotEmpty() shouldBe true
                MultiplayerSessionManager::class.java.declaredMethods.isNotEmpty() shouldBe true
                harness.startAndArrive(ffaRoster(harness.players))
                harness.manager.activeCount() shouldBe 1
                harness.players.dropLast(1).forEach(harness.manager::eliminate)
                harness.results.completion.complete(true)
                paper.performTicks(64)
                harness.manager.activeCount() shouldBe 0
            }
        }
    }
})
