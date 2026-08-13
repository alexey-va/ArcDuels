package ru.ruscrafting.duels.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchCompletedEvent
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.util.UUID

class DuelEventCodecTest : StringSpec({
    "match completion round-trips through stable versioned JSON" {
        val event =
            MatchCompletedEvent(
                eventId = "match:completed",
                occurredAt = Instant.parse("2026-08-13T10:00:00Z"),
                sourceServer = ServerId("duels-2"),
                matchId = MatchId(UUID.fromString("00000000-0000-0000-0000-000000000010")),
                winner = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                loser = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                mode = DuelMode.KIT,
                kitId = KitId("classic"),
                ranked = true,
                winnerRating = 1_016,
            )

        val codec = DuelEventCodec()

        codec.decode(codec.encode(event)) shouldBe event
    }

    "oversized and internally inconsistent events are rejected" {
        val codec = DuelEventCodec()
        shouldThrow<IllegalArgumentException> { codec.decode("x".repeat(8_193)) }

        val winner = "00000000-0000-0000-0000-000000000001"
        val event =
            MatchCompletedEvent(
                eventId = "match:completed",
                occurredAt = Instant.parse("2026-08-13T10:00:00Z"),
                sourceServer = ServerId("duels-2"),
                matchId = MatchId(UUID.fromString("00000000-0000-0000-0000-000000000010")),
                winner = PlayerId(UUID.fromString(winner)),
                loser = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                mode = DuelMode.KIT,
                kitId = KitId("classic"),
                ranked = true,
                winnerRating = 1_016,
            )
        val duplicateParticipant = codec.encode(event).replace("00000000-0000-0000-0000-000000000002", winner)

        shouldThrow<IllegalArgumentException> { codec.decode(duplicateParticipant) }
    }
})
