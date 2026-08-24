package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.LeaderboardInvalidatedEvent
import ru.ruscrafting.duels.domain.MatchCompletedEvent
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.util.UUID

class BattlePassIntegrationTest : StringSpec({
    val winner = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    val loser = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))

    fun match(
        winner: PlayerId,
        loser: PlayerId,
        ranked: Boolean,
        objective: DuelObjectiveType = DuelObjectiveType.ELIMINATION,
    ) = MatchCompletedEvent(
        eventId = "match:1:completed",
        occurredAt = Instant.EPOCH,
        sourceServer = ServerId("classic"),
        matchId = MatchId(UUID.fromString("00000000-0000-0000-0000-000000000003")),
        winner = winner,
        loser = loser,
        mode = DuelMode.KIT,
        kitId = KitId("classic"),
        ranked = ranked,
        winnerRating = 1_000,
        objective = objective,
    )

    "completed duel awards participation to both players and a win to the winner" {
        match(winner, loser, ranked = false, objective = DuelObjectiveType.SUMO)
            .battlePassAwards()
            .shouldContainExactly(
                BattlePassAward(winner, "arcduels-match", "sumo"),
                BattlePassAward(loser, "arcduels-match", "sumo"),
                BattlePassAward(winner, "arcduels-win", "sumo"),
            )
    }

    "ranked duel adds one ranked win and never awards the loser" {
        val awards = match(winner, loser, ranked = true).battlePassAwards()

        awards.count { it.type == "arcduels-ranked-win" } shouldBe 1
        awards.single { it.type == "arcduels-ranked-win" }.player shouldBe winner
    }

    "non-match network events do not change BattlePass" {
        LeaderboardInvalidatedEvent(
            eventId = "leaderboard:1",
            occurredAt = Instant.EPOCH,
            sourceServer = ServerId("classic"),
            revision = 1,
        ).battlePassAwards() shouldBe emptyList()
    }

})
