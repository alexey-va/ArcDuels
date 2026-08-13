package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

class InternalTeleportAuthorizerTest : StringSpec({
    "only the exact destination is authorized during the scoped action" {
        val playerId = UUID.randomUUID()
        val worldId = UUID.randomUUID()
        val world = mockk<World> { every { uid } returns worldId }
        val expected = Location(world, 1.5, 80.0, -2.5, 90f, 5f)
        val other = Location(world, 1.5, 80.0, -2.4, 90f, 5f)
        val authorizer = InternalTeleportAuthorizer()

        authorizer.authorize(playerId, expected) {
            authorizer.isAuthorized(playerId, expected.clone()) shouldBe true
            authorizer.isAuthorized(playerId, other) shouldBe false
        }

        authorizer.isAuthorized(playerId, expected) shouldBe false
    }

    "authorization is cleared when teleport execution fails" {
        val playerId = UUID.randomUUID()
        val world = mockk<World> { every { uid } returns UUID.randomUUID() }
        val destination = Location(world, 0.0, 64.0, 0.0)
        val authorizer = InternalTeleportAuthorizer()

        shouldThrow<IllegalStateException> {
            authorizer.authorize(playerId, destination) { error("teleport failed") }
        }

        authorizer.isAuthorized(playerId, destination) shouldBe false
    }
})
