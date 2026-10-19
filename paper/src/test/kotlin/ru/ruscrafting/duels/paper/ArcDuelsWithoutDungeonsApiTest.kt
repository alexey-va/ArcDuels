package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime

/** Runs only in dungeonsApiAbsentTest, whose JVM classpath excludes the optional API JAR. */
class ArcDuelsWithoutDungeonsApiTest : StringSpec({
    "actual plugin bootstraps with neither ArcDungeons nor its API on the runtime classpath" {
        val loader = ArcDuelsPlugin::class.java.classLoader
        loader.getResource("ru/arc/dungeons/api/ArcDungeonConfiguration.class") shouldBe null
        shouldThrow<ClassNotFoundException> { loader.loadClass("ru.arc.dungeons.api.ArcDungeonConfiguration") }

        MockBukkitTestRuntime.open().use { paper ->
            paper.server.pluginManager.isPluginEnabled("ArcDungeons") shouldBe false
            val plugin = paper.loadReadyDuelsPlugin()
            plugin.isEnabled shouldBe true
        }
    }
})
