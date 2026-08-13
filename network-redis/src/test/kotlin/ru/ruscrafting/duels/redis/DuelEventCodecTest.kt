package ru.ruscrafting.duels.redis

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchCompletedEvent
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.LeaderboardInvalidatedEvent
import ru.ruscrafting.duels.domain.RatingCalculator
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
                objective = DuelObjectiveType.KING_OF_THE_HILL,
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

    "every event shape round-trips without optional field confusion" {
        val codec = DuelEventCodec()
        val events =
            listOf(
                MatchCompletedEvent(
                    eventId = "own:completed",
                    occurredAt = Instant.parse("2026-08-13T10:00:00.123Z"),
                    sourceServer = ServerId("duels-2"),
                    matchId = MatchId.random(),
                    winner = PlayerId(UUID.randomUUID()),
                    loser = PlayerId(UUID.randomUUID()),
                    mode = DuelMode.OWN_INVENTORY,
                    kitId = null,
                    ranked = false,
                    winnerRating = RatingCalculator.MAX_RATING,
                ),
                LeaderboardInvalidatedEvent(
                    eventId = "leaderboard:maximum",
                    occurredAt = Instant.EPOCH,
                    sourceServer = ServerId("duels-3"),
                    revision = Long.MAX_VALUE,
                ),
            )

        events.forEach { codec.decode(codec.encode(it)) shouldBe it }
    }

    "malformed versions types UUIDs and ratings are rejected" {
        val codec = DuelEventCodec()
        val valid =
            codec.encode(
                MatchCompletedEvent(
                    "matrix:completed",
                    Instant.EPOCH,
                    ServerId("duels-2"),
                    MatchId.random(),
                    PlayerId(UUID.randomUUID()),
                    PlayerId(UUID.randomUUID()),
                    DuelMode.KIT,
                    KitId("classic"),
                    ranked = true,
                    winnerRating = 1_000,
                ),
            )

        shouldThrow<IllegalArgumentException> { codec.decode(valid.replace("\"version\":1", "\"version\":2")) }
        shouldThrow<IllegalStateException> { codec.decode(valid.replace("match_completed", "unknown_type")) }
        shouldThrow<IllegalArgumentException> {
            codec.decode(valid.replace(Regex("\"winner\":\"[^\"]+\""), "\"winner\":\"not-a-uuid\""))
        }
        shouldThrow<IllegalArgumentException> {
            codec.decode(valid.replace("\"winnerRating\":1000", "\"winnerRating\":10000001"))
        }
    }
})
