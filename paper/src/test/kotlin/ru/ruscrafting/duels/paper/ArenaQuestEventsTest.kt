package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.duels.domain.MatchEndReason
import ru.ruscrafting.ranks.api.ClanArenaQuestApi
import ru.ruscrafting.ranks.api.ClanMembership
import ru.ruscrafting.ranks.api.ClanMembershipProvider
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ArenaQuestEventsTest : StringSpec({
    val first = UUID(0, 1)
    val second = UUID(0, 2)
    val third = UUID(0, 3)
    val fourth = UUID(0, 4)
    val alpha = ClanMembership("justteams:1", "Alpha", setOf(first, second, third, fourth))
    val beta = ClanMembership("justteams:2", "Beta", setOf(first, second, third, fourth))
    val now = Instant.parse("2026-10-25T23:59:59Z")

    "only played elimination and objective matches publish clan quest activity" {
        val reasons = mutableListOf<MatchEndReason>()
        var current = MatchEndReason.ELIMINATION
        val session = RanksArenaQuestSession(mapOf(first to alpha, second to alpha), { alpha }, { _, _, _ ->
            reasons += current
            CompletableFuture.completedFuture(1)
        })
        session.freeze(listOf(setOf(first, second)))
        MatchEndReason.entries.forEach { reason ->
            current = reason
            session.publish("match", reason, now)
        }
        session.publish("match", null, now)
        reasons shouldBe listOf(MatchEndReason.ELIMINATION, MatchEndReason.OBJECTIVE)
    }

    "a delayed durable result keeps the finish snapshot after a player changes clans or disconnects" {
        val current = mutableMapOf(first to alpha, second to alpha)
        var recorded = emptyList<Map<UUID, ClanMembership>>()
        var recordedAt: Instant? = null
        val session = RanksArenaQuestSession(current, current::get, { _, groups, at ->
            recorded = groups
            recordedAt = at
            CompletableFuture.completedFuture(1)
        })
        session.freeze(listOf(setOf(first, second)))
        val durable = CompletableFuture<Unit>()
        durable.thenRun { session.publish("played-match", MatchEndReason.ELIMINATION, now) }
        current[first] = beta
        current.remove(second)
        recorded shouldBe emptyList()
        durable.complete(Unit)
        recorded shouldBe listOf(mapOf(first to alpha, second to alpha))
        recordedAt shouldBe now
    }

    "a member who changes clans before finishing and a late joiner are excluded" {
        val current = mapOf(first to alpha, second to beta, third to alpha)
        var records = 0
        val session = RanksArenaQuestSession(mapOf(first to alpha, second to alpha), current::get, { _, _, _ ->
            records++
            CompletableFuture.completedFuture(1)
        })
        session.freeze(listOf(setOf(first, second, third)))
        session.publish("played-match", MatchEndReason.ELIMINATION, now)
        records shouldBe 0
    }

    "arena sides remain separate while a practice group includes both opponents" {
        var recorded = emptyList<Map<UUID, ClanMembership>>()
        val start = setOf(first, second, third, fourth).associateWith { alpha }
        val session = RanksArenaQuestSession(start, { alpha }, { _, groups, _ ->
            recorded = groups
            CompletableFuture.completedFuture(1)
        })
        session.freeze(listOf(setOf(first, second), setOf(third, fourth), setOf(first)))
        session.publish("team-match", MatchEndReason.ELIMINATION, now)
        recorded.map { it.keys } shouldBe listOf(setOf(first, second), setOf(third, fourth))
        session.freeze(listOf(setOf(first, second)))
        session.publish("practice-match", MatchEndReason.OBJECTIVE, now)
        recorded.single().keys shouldBe setOf(first, second)
    }

    "a later series round overwrites earlier participation instead of publishing stale credit" {
        val current = mutableMapOf(first to alpha, second to alpha)
        var records = 0
        val session = RanksArenaQuestSession(current, current::get, { _, _, _ ->
            records++
            CompletableFuture.completedFuture(1)
        })
        session.freeze(listOf(setOf(first, second)))
        current[second] = beta
        session.freeze(listOf(setOf(first, second)))
        session.publish("series", MatchEndReason.ELIMINATION, now)
        records shouldBe 0
    }

    "optional provider failure cannot interrupt the played match" {
        val failures = mutableListOf<Throwable>()
        val session = RanksArenaQuestSession(mapOf(first to alpha, second to alpha), { error("membership unavailable") },
            { _, _, _ -> error("must not publish") }, failures::add)
        session.freeze(listOf(setOf(first, second)))
        session.publish("match", MatchEndReason.ELIMINATION, now)
        failures.size shouldBe 2
    }

    "the actual multiplayer completion freezes clans before its delayed result write" {
        MockBukkitTestRuntime.open().use { paper ->
            val provider = paper.createSimplePlugin("ArcRanks")
            multiplayerHarness(paper).use { harness ->
                val ids = harness.players.map { it.uniqueId }.toSet()
                val startClan = ClanMembership("justteams:10", "Original", ids)
                val newClan = ClanMembership("justteams:11", "Changed", ids)
                val memberships = ids.associateWith { startClan }.toMutableMap()
                var recorded = emptyList<Map<UUID, ClanMembership>>()
                paper.server.servicesManager.register(ClanMembershipProvider::class.java,
                    ClanMembershipProvider(memberships::get), provider, ServicePriority.Normal)
                paper.server.servicesManager.register(ClanArenaQuestApi::class.java,
                    ClanArenaQuestApi { _, groups, _ ->
                        recorded = groups
                        CompletableFuture.completedFuture(1)
                    }, provider, ServicePriority.Normal)
                harness.startAndArrive(ffaRoster(harness.players))
                memberships[harness.players.first().uniqueId] = newClan
                harness.players.dropLast(1).forEach(harness.manager::eliminate)
                harness.results.writes.size shouldBe 1
                recorded shouldBe emptyList()
                memberships.keys.toList().forEach { memberships[it] = newClan }
                harness.results.completion.complete(true)
                paper.performTicks(4)
                recorded.single().keys shouldBe ids - harness.players.first().uniqueId
                recorded.single().values.all { it.clanId() == startClan.clanId() } shouldBe true
            }
        }
    }
})
