package ru.ruscrafting.duels.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class DuelMatchTest : StringSpec({
    val first = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val second = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    val now = Instant.parse("2026-08-13T10:00:00Z")

    "best of three returns to countdown after round one and completes after round two" {
        var match =
            DuelMatch.reserve(
                first,
                second,
                ArenaId("arena-1"),
                ServerId("duels-1"),
                DuelRules(DuelMode.KIT, KitId("classic"), bestOf = 3),
                now,
            ).beginCountdown().activate(now)

        match = match.recordRoundWinner(first, now.plusSeconds(30))
        match.state shouldBe MatchState.COUNTDOWN
        match.score shouldBe MatchScore(first = 1, second = 0)

        match = match.activate(now.plusSeconds(35)).recordRoundWinner(first, now.plusSeconds(60))
        match.state shouldBe MatchState.COMPLETING
        match.winner shouldBe first
        match.markPersisted().state shouldBe MatchState.COMPLETED
    }

    "own inventory mode rejects a kit" {
        shouldThrow<IllegalArgumentException> {
            DuelRules(DuelMode.OWN_INVENTORY, KitId("illegal"))
        }
    }

    "disconnect during countdown awards the whole match to the opponent" {
        val match =
            DuelMatch.reserve(
                first,
                second,
                ArenaId("arena-1"),
                ServerId("duels-1"),
                DuelRules(DuelMode.KIT, KitId("classic"), bestOf = 5),
                now,
            ).beginCountdown().forfeit(second, now.plusSeconds(1), MatchEndReason.DISCONNECT)

        match.state shouldBe MatchState.COMPLETING
        match.winner shouldBe first
        match.score shouldBe MatchScore(first = 3, second = 0)
    }
})
