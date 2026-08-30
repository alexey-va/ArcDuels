package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.RecordingPaperAudienceEffects
import ru.arc.paper.testing.RecordingPaperTeleportExecutor

class ArcCorePaperPortsTest : StringSpec({
    "group surfaces and teleports stay observable through arc-core Paper ports" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.server.addSimpleWorld("ports-world")
            val player = paper.server.addPlayer()
            val audience = RecordingPaperAudienceEffects()
            val teleports = RecordingPaperTeleportExecutor()
            val destination = Location(world, 4.5, 72.0, -3.5)

            audience.sendMessage(player, Component.text("ready"))
            teleports.teleportAsync(player, destination, PlayerTeleportEvent.TeleportCause.PLUGIN).get() shouldBe true

            audience.observations().size shouldBe 1
            teleports.observations().single().destination shouldBe destination
        }
    }
})
