package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
})
