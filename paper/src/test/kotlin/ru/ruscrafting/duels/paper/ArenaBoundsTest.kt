package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

class ArenaBoundsTest : StringSpec({
    "bounds include their edges and reject another world" {
        val worldId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val bounds = ArenaBounds(worldId, -10.0, 60.0, -20.0, 10.0, 100.0, 20.0)

        bounds.contains(worldId, -10.0, 60.0, -20.0) shouldBe true
        bounds.contains(worldId, 10.0, 100.0, 20.0) shouldBe true
        bounds.contains(worldId, 10.01, 80.0, 0.0) shouldBe false
        bounds.contains(UUID.randomUUID(), 0.0, 80.0, 0.0) shouldBe false
    }

    "bounds reject spatial overlap including a shared boundary" {
        val worldId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val bounds = ArenaBounds(worldId, -10.0, 60.0, -10.0, 10.0, 90.0, 10.0)

        bounds.overlaps(ArenaBounds(worldId, 9.0, 70.0, -5.0, 20.0, 80.0, 5.0)) shouldBe true
        bounds.overlaps(ArenaBounds(worldId, 10.0, 70.0, -5.0, 20.0, 80.0, 5.0)) shouldBe true
        bounds.overlaps(ArenaBounds(worldId, 10.01, 70.0, -5.0, 20.0, 80.0, 5.0)) shouldBe false
        bounds.overlaps(ArenaBounds(UUID.randomUUID(), -5.0, 70.0, -5.0, 5.0, 80.0, 5.0)) shouldBe false
    }

    "edge distance drives a warning before the player crosses the boundary" {
        val worldId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val world = mockk<World>()
        every { world.uid } returns worldId
        val bounds = ArenaBounds(worldId, -10.0, 60.0, -20.0, 10.0, 100.0, 20.0)

        bounds.distanceToEdge(Location(world, 8.0, 70.0, 0.0)) shouldBe 2.0
        bounds.distanceToEdge(Location(world, 0.0, 70.0, 0.0)) shouldBe 10.0
        bounds.distanceToEdge(Location(world, 0.0, 62.0, 0.0)) shouldBe 2.0
        bounds.distanceToEdge(Location(world, 11.0, 70.0, 0.0)) shouldBe null
    }

    "boundary particles form a vertical strip on the nearest horizontal edge" {
        val worldId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val world = mockk<World>()
        every { world.uid } returns worldId
        val bounds = ArenaBounds(worldId, -10.0, -64.0, -20.0, 10.0, 320.0, 20.0)

        val points = bounds.horizontalBoundaryPoints(Location(world, 8.0, 70.0, 1.0))

        points.isNotEmpty() shouldBe true
        points.all { it.x == 10.0 } shouldBe true
        points.minOf(Location::getY) shouldBe 69.0
        points.maxOf(Location::getY) shouldBe 73.0
    }
})
