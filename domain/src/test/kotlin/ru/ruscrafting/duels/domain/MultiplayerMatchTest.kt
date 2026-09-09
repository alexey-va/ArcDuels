package ru.ruscrafting.duels.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class MultiplayerMatchTest :
    StringSpec({
        "FFA completes when one player remains" {
            val players = (1..4).map(::player)
            var match = match(MultiplayerLayout.FREE_FOR_ALL, players)
            match = match.beginCountdown().activate(Instant.ofEpochSecond(2))
            match = match.eliminate(players[0], Instant.ofEpochSecond(3), MatchEndReason.ELIMINATION)
            match.state shouldBe MultiplayerMatchState.ACTIVE
            match = match.eliminate(players[1], Instant.ofEpochSecond(4), MatchEndReason.DISCONNECT)
            match = match.eliminate(players[2], Instant.ofEpochSecond(5), MatchEndReason.ELIMINATION)
            match.state shouldBe MultiplayerMatchState.COMPLETING
            match.winners.shouldContainExactly(players[3])
            match.placementOf(players[0]) shouldBe 4
            match.placementOf(players[1]) shouldBe 3
            match.placementOf(players[2]) shouldBe 2
            match.placementOf(players[3]) shouldBe 1
        }

        "team match preserves the whole winning team" {
            val players = (1..6).map(::player)
            val participants =
                players.mapIndexed { index, playerId ->
                    MultiplayerParticipant(playerId, team = if (index % 2 == 0) 1 else 2, kitId = KitId("classic"))
                }
            var match =
                MultiplayerMatch.reserve(
                    MatchId(UUID.randomUUID()),
                    ArenaId("team-arena"),
                    ServerId("spawn"),
                    MultiplayerRoster(MultiplayerRules(MultiplayerLayout.TWO_TEAMS, MultiplayerKitPolicy.SHARED, KitId("classic")), participants),
                    Instant.EPOCH,
                ).beginCountdown().activate(Instant.ofEpochSecond(1))
            players.filterIndexed { index, _ -> index % 2 == 1 }.forEachIndexed { index, playerId ->
                match = match.eliminate(playerId, Instant.ofEpochSecond((index + 2).toLong()), MatchEndReason.ELIMINATION)
            }
            match.state shouldBe MultiplayerMatchState.COMPLETING
            match.winningTeam shouldBe 1
            match.winners shouldBe players.filterIndexed { index, _ -> index % 2 == 0 }.toSet()
            match.isEnemy(players[0], players[2]) shouldBe false
            match.isEnemy(players[0], players[1]) shouldBe true
        }

        "three teams must be balanced and all represented" {
            val kit = KitId("classic")
            val invalid = listOf(1, 1, 1, 1, 2, 3).mapIndexed { index, team -> MultiplayerParticipant(player(index), team, kit) }
            shouldThrow<IllegalArgumentException> {
                MultiplayerRoster(MultiplayerRules(MultiplayerLayout.THREE_TEAMS, MultiplayerKitPolicy.SHARED, kit), invalid)
            }
        }

        "per-player policy accepts different kits" {
            val participants =
                listOf(
                    MultiplayerParticipant(player(1), kitId = KitId("classic")),
                    MultiplayerParticipant(player(2), kitId = KitId("axe")),
                    MultiplayerParticipant(player(3), kitId = KitId("archer")),
                )
            val roster = MultiplayerRoster(MultiplayerRules(MultiplayerLayout.FREE_FOR_ALL, MultiplayerKitPolicy.PER_PLAYER), participants)
            roster.participants.map(MultiplayerParticipant::kitId) shouldContainExactly listOf(KitId("classic"), KitId("axe"), KitId("archer"))
        }

        "participant bounds accept three through twelve and reject the neighbors" {
            val kit = KitId("classic")
            fun roster(size: Int): MultiplayerRoster = MultiplayerRoster(
                MultiplayerRules(MultiplayerLayout.FREE_FOR_ALL, MultiplayerKitPolicy.SHARED, kit),
                (1..size).map { MultiplayerParticipant(player(it), kitId = kit) },
            )

            roster(3).participants.size shouldBe 3
            roster(12).participants.size shouldBe 12
            shouldThrow<IllegalArgumentException> { roster(2) }
            shouldThrow<IllegalArgumentException> { roster(13) }
        }

        "king of the hill shares progress across a team and keeps FFA progress separate" {
            val players = (1..4).map(::player)
            val participants = players.mapIndexed { index, playerId ->
                MultiplayerParticipant(playerId, team = index % 2 + 1, kitId = KitId("classic"))
            }
            val roster = MultiplayerRoster(
                MultiplayerRules(MultiplayerLayout.TWO_TEAMS, MultiplayerKitPolicy.SHARED, KitId("classic"), objective = DuelObjectiveType.KING_OF_THE_HILL),
                participants,
            )
            val match = MultiplayerMatch.reserve(MatchId(UUID.randomUUID()), ArenaId("hill"), ServerId("test"), roster, Instant.EPOCH)
                .beginCountdown().activate(Instant.EPOCH)
            val objective = MultiplayerScoreObjective(DuelObjectiveType.KING_OF_THE_HILL, 5, 1)
            objective.evaluate(
                match,
                MultiplayerObjectiveFrame(100, setOf(players[0], players[2]), mapOf(players[0] to 40L, players[2] to 60L)),
            ) shouldBe MultiplayerObjectiveDecision.Complete(setOf(players[0], players[2]), 1)
        }

        "boxing uses separate FFA player progress" {
            val players = (1..3).map(::player)
            val roster = MultiplayerRoster(
                MultiplayerRules(MultiplayerLayout.FREE_FOR_ALL, MultiplayerKitPolicy.SHARED, KitId("classic"), objective = DuelObjectiveType.BOXING, modifiers = CombatModifiers(false, false, false, false)),
                players.map { MultiplayerParticipant(it, kitId = KitId("classic")) },
            )
            val match = MultiplayerMatch.reserve(MatchId(UUID.randomUUID()), ArenaId("boxing"), ServerId("test"), roster, Instant.EPOCH)
                .beginCountdown().activate(Instant.EPOCH)
            MultiplayerScoreObjective(DuelObjectiveType.BOXING, 5, 10).evaluate(
                match,
                MultiplayerObjectiveFrame(10, emptySet(), mapOf(players[0] to 9L, players[1] to 10L)),
            ) shouldBe MultiplayerObjectiveDecision.Complete(setOf(players[1]), null)
        }

        "objective completion records the whole winning team" {
            val players = (1..4).map(::player)
            val roster = MultiplayerRoster(
                MultiplayerRules(MultiplayerLayout.TWO_TEAMS, MultiplayerKitPolicy.SHARED, KitId("classic"), objective = DuelObjectiveType.KING_OF_THE_HILL),
                players.mapIndexed { index, id -> MultiplayerParticipant(id, index % 2 + 1, KitId("classic")) },
            )
            val match = MultiplayerMatch.reserve(MatchId(UUID.randomUUID()), ArenaId("hill"), ServerId("test"), roster, Instant.EPOCH)
                .beginCountdown().activate(Instant.EPOCH)
                .completeObjective(setOf(players[0], players[2]), 1, Instant.ofEpochSecond(1))
            match.state shouldBe MultiplayerMatchState.COMPLETING
            match.outcome().endReason shouldBe MatchEndReason.OBJECTIVE
            match.outcome().winners shouldBe setOf(players[0], players[2])
        }

        "default multiplayer modifiers lock both hit race objectives" {
            defaultMultiplayerModifiers(DuelObjectiveType.BOXING).projectiles shouldBe false
            defaultMultiplayerModifiers(DuelObjectiveType.BOXING).consumables shouldBe false
            defaultMultiplayerModifiers(DuelObjectiveType.COMBO).enderPearls shouldBe false
            defaultMultiplayerModifiers(DuelObjectiveType.COMBO).naturalRegeneration shouldBe false
            defaultMultiplayerModifiers(DuelObjectiveType.KING_OF_THE_HILL).projectiles shouldBe true
        }
    })

private fun player(index: Int): PlayerId = PlayerId(UUID(0L, index.toLong()))

private fun match(
    layout: MultiplayerLayout,
    players: List<PlayerId>,
): MultiplayerMatch {
    val kit = KitId("classic")
    val participants =
        players.mapIndexed { index, playerId ->
            MultiplayerParticipant(
                playerId,
                team = layout.teamCount?.let { index % it + 1 },
                kitId = kit,
            )
        }
    return MultiplayerMatch.reserve(
        id = MatchId(UUID.randomUUID()),
        arenaId = ArenaId("arena"),
        serverId = ServerId("spawn"),
        roster = MultiplayerRoster(MultiplayerRules(layout, MultiplayerKitPolicy.SHARED, kit), participants),
        now = Instant.EPOCH,
    )
}
