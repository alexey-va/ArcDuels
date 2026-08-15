package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.mockbukkit.mockbukkit.MockBukkit
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.util.UUID

class PostMatchDestinationTest : StringSpec({
    val server = MockBukkit.mock()

    afterSpec { MockBukkit.unmock() }

    "an arena without a lobby returns each participant to their assigned arena spawn" {
        val world = server.addSimpleWorld("pvp")
        val first = PlayerId(UUID.randomUUID())
        val second = PlayerId(UUID.randomUUID())
        val arena =
            PaperArena(
                ArenaId("safe-default"),
                "Safe default",
                Location(world, 10.5, 72.0, -4.5),
                Location(world, -10.5, 72.0, 4.5),
                ArenaBounds(world.uid, -20.0, 60.0, -20.0, 20.0, 100.0, 20.0),
            )
        val match = DuelMatch.reserve(first, second, arena.id, ServerId("parkour"), DuelRules(DuelMode.OWN_INVENTORY), Instant.EPOCH)

        postMatchDestination(arena, match, first) shouldBe arena.firstSpawn
        postMatchDestination(arena, match, second) shouldBe arena.secondSpawn

        val copy = postMatchDestination(arena, match, first)
        copy.x = 999.0
        arena.firstSpawn.x shouldBe 10.5
    }

    "an explicit arena lobby remains the shared post-match destination" {
        val world = server.addSimpleWorld("pvp-lobby")
        val first = PlayerId(UUID.randomUUID())
        val second = PlayerId(UUID.randomUUID())
        val lobby = Location(world, 0.5, 80.0, 0.5)
        val arena =
            PaperArena(
                ArenaId("with-lobby"),
                "With lobby",
                Location(world, 10.0, 70.0, 0.0),
                Location(world, -10.0, 70.0, 0.0),
                ArenaBounds(world.uid, -20.0, 60.0, -20.0, 20.0, 100.0, 20.0),
                lobby = lobby,
            )
        val match = DuelMatch.reserve(first, second, arena.id, ServerId("parkour"), DuelRules(DuelMode.OWN_INVENTORY), Instant.EPOCH)

        postMatchDestination(arena, match, first) shouldBe lobby
        postMatchDestination(arena, match, second) shouldBe lobby
    }
})
