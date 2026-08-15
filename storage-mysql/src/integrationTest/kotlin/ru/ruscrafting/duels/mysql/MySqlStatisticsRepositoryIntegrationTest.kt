package ru.ruscrafting.duels.mysql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelPreset
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.CombatModifiers
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchOutcome
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.PlayerStateEscrow
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ExecutionException

class MySqlStatisticsRepositoryIntegrationTest : StringSpec() {
    private val mysql = MySQLContainer(DockerImageName.parse("mysql:8.4.10"))
        .withDatabaseName("arcduels")
        .withUsername("arcduels")
        .withPassword("integration-test-only")
    private lateinit var repository: MySqlStatisticsRepository

    init {
        beforeSpec {
            mysql.start()
            repository = openRepository("arcduels-it")
            repository.migrate().get().appliedVersions shouldContainExactly listOf(1, 2, 3, 4, 5, 6, 7, 8)
            repository.migrate().get().existingVersions shouldContainExactly listOf(1, 2, 3, 4, 5, 6, 7, 8)
        }

        afterSpec {
            if (::repository.isInitialized) repository.close()
            mysql.stop()
        }

        "ranked result is atomic, idempotent and visible in the leaderboard" {
            val winner = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            val loser = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
            val outcome =
                MatchOutcome(
                    matchId = MatchId(UUID.fromString("00000000-0000-0000-0000-000000000010")),
                    winner = winner,
                    loser = loser,
                    mode = DuelMode.KIT,
                    kitId = KitId("classic"),
                    ranked = true,
                    serverId = ServerId("duels-it"),
                    completedAt = Instant.parse("2026-08-13T10:00:00Z"),
                    objective = DuelObjectiveType.BOXING,
                    arenaId = ArenaId("boxing-one"),
                    bestOf = 3,
                    modifiers = CombatModifiers(false, false, false, false, boxingHitsToWin = 50),
                    winnerScore = 2,
                    loserScore = 1,
                    endReason = ru.ruscrafting.duels.domain.MatchEndReason.OBJECTIVE,
                )
            repository.rememberPlayerName(winner, "Winner").get()
            repository.rememberPlayerName(loser, "Loser").get()

            val first = repository.record(outcome).get()
            val duplicate = repository.record(outcome).get()

            first.newlyRecorded shouldBe true
            first.winnerRatingAfter shouldBe 1_016
            first.loserRatingAfter shouldBe 984
            duplicate shouldBe first.copy(newlyRecorded = false)
            repository.find(winner).get().wins shouldBe 1
            repository.find(loser).get().losses shouldBe 1
            repository.findPlayerName(winner).get() shouldBe "Winner"
            repository.leaderboard(10).get().map { it.playerId } shouldContainExactly listOf(winner, loser)
            repository.leaderboard(10).get().map { it.playerName } shouldContainExactly listOf("Winner", "Loser")
            repository.findMatch(outcome.matchId).get()?.outcome shouldBe outcome
            repository.recentMatches(winner, 10).get().single().winnerName shouldBe "Winner"
            repository.recentMatches(loser, 10).get().single().loserName shouldBe "Loser"
            repository.headToHead(winner, loser).get().firstWins shouldBe 1
            repository.headToHead(winner, loser).get().secondWins shouldBe 0
        }

        "saved rule presets preserve exact rules and arena selection" {
            val player = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
            val first =
                DuelPreset(
                    player,
                    2,
                    DuelRules(
                        DuelMode.KIT,
                        KitId("boxing"),
                        ranked = true,
                        bestOf = 5,
                        objective = DuelObjectiveType.COMBO,
                        modifiers = CombatModifiers(false, false, false, false, comboHitsToWin = 15),
                    ),
                    ArenaSelection(ServerId("spawn"), ArenaId("kit-test")),
                    Instant.parse("2026-08-13T10:00:00.123456Z"),
                )

            repository.savePreset(first).get()
            repository.savePreset(first).get()
            repository.presets(player).get().single() shouldBe
                first.copy(updatedAt = Instant.parse("2026-08-13T10:00:00.123Z"))

            val updated =
                first.copy(
                    rules = DuelRules(DuelMode.OWN_INVENTORY, bestOf = 3),
                    arenaSelection = null,
                    updatedAt = Instant.parse("2026-08-13T10:01:00Z"),
                )
            repository.savePreset(updated).get()
            repository.presets(player).get() shouldContainExactly listOf(updated)
            repository.deletePreset(player, 2).get() shouldBe true
            repository.deletePreset(player, 2).get() shouldBe false
            repository.presets(player).get() shouldContainExactly emptyList()
        }

        "same match id with a different outcome is rejected" {
            val first = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
            val second = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
            val matchId = MatchId(UUID.fromString("00000000-0000-0000-0000-000000000011"))
            val original =
                MatchOutcome(
                    matchId,
                    first,
                    second,
                    DuelMode.OWN_INVENTORY,
                    null,
                    ranked = false,
                    ServerId("duels-it"),
                    Instant.parse("2026-08-13T10:01:00Z"),
                )
            repository.record(original).get()

            shouldThrow<ExecutionException> {
                repository.record(original.copy(winner = second, loser = first)).get()
            }
        }

        "concurrent duplicate transactions update both players exactly once" {
            val winner = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000030"))
            val loser = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000031"))
            val outcome =
                MatchOutcome(
                    MatchId(UUID.fromString("00000000-0000-0000-0000-000000000032")),
                    winner,
                    loser,
                    DuelMode.KIT,
                    KitId("classic"),
                    ranked = true,
                    ServerId("duels-it"),
                    Instant.parse("2026-08-13T10:02:00Z"),
                )

            val results = List(32) { repository.record(outcome) }.map { it.get() }

            results.count { it.newlyRecorded } shouldBe 1
            results.map { it.leaderboardRevision }.distinct() shouldHaveSize 1
            repository.find(winner).get().wins shouldBe 1
            repository.find(winner).get().revision shouldBe 1
            repository.find(loser).get().losses shouldBe 1
            repository.find(loser).get().revision shouldBe 1
        }

        "concurrent unique matches serialize shared player rows without lost updates" {
            val first = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000040"))
            val second = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000041"))
            val baseTime = Instant.parse("2026-08-13T10:03:00Z")
            val writes =
                List(24) { index ->
                    val firstWins = index % 2 == 0
                    repository.record(
                        MatchOutcome(
                            MatchId(UUID(0L, 100L + index)),
                            if (firstWins) first else second,
                            if (firstWins) second else first,
                            DuelMode.OWN_INVENTORY,
                            null,
                            ranked = false,
                            ServerId("duels-it"),
                            baseTime.plusMillis(index.toLong()),
                        ),
                    )
                }

            val results = writes.map { it.get() }
            val firstStats = repository.find(first).get()
            val secondStats = repository.find(second).get()

            results.all { it.newlyRecorded } shouldBe true
            results.map { it.leaderboardRevision }.distinct() shouldHaveSize 24
            results.map { it.leaderboardRevision }.sorted().zipWithNext().all { (before, after) -> after == before + 1 } shouldBe true
            firstStats.wins shouldBe 12
            firstStats.losses shouldBe 12
            firstStats.revision shouldBe 24
            secondStats.wins shouldBe 12
            secondStats.losses shouldBe 12
            secondStats.revision shouldBe 24
            firstStats.rating shouldBe 1_000
            secondStats.rating shouldBe 1_000
        }

        "database precision canonicalizes nanoseconds before idempotency comparison" {
            val winner = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000050"))
            val loser = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000051"))
            val outcome =
                MatchOutcome(
                    MatchId(UUID.fromString("00000000-0000-0000-0000-000000000052")),
                    winner,
                    loser,
                    DuelMode.OWN_INVENTORY,
                    null,
                    ranked = false,
                    ServerId("duels-it"),
                    Instant.parse("2026-08-13T10:04:00.123456789Z"),
                )

            val firstWrite = repository.record(outcome).get()
            val duplicate = repository.record(outcome).get()

            firstWrite.outcome.completedAt shouldBe Instant.parse("2026-08-13T10:04:00.123Z")
            duplicate shouldBe firstWrite.copy(newlyRecorded = false)
        }

        "single origin state save is idempotent and rejects conflicting bytes" {
            val serverId = ServerId("duels-origin-it")
            val matchId = MatchId(UUID.fromString("00000000-0000-0000-0000-000000000055"))
            val state = escrow("00000000-0000-0000-0000-000000000056", matchId, serverId, "origin-state")

            repository.save(state).get()
            repository.save(state).get()

            repository.findPending(state.playerId).get()?.sameContent(state) shouldBe true
            val conflicting = escrow(state.playerId.value.toString(), matchId, serverId, "different-origin-state")
            shouldThrow<ExecutionException> { repository.save(conflicting).get() }
            repository.findPending(state.playerId).get()?.sameContent(state) shouldBe true

            val restoredAt = Instant.parse("2026-08-14T11:00:00Z")
            repository.retainRestored(state, restoredAt, restoredAt.plusSeconds(3600)).get() shouldBe true
        }

        "committed recovery state survives process loss before apply and between participant acknowledgements" {
            repository.purgeRetained(Instant.parse("9999-12-31T23:59:59.999Z")).get()
            val serverId = ServerId("duels-crash-it")
            val matchId = MatchId(UUID.fromString("00000000-0000-0000-0000-000000000057"))
            val first = escrow("00000000-0000-0000-0000-000000000058", matchId, serverId, "crash-first")
            val second = escrow("00000000-0000-0000-0000-000000000059", matchId, serverId, "crash-second")
            val restoredAt = Instant.parse("2026-08-14T11:30:00Z")
            val purgeAfter = restoredAt.plusSeconds(3600)

            openRepository("arcduels-crash-writer").use { writer ->
                writer.migrate().get()
                writer.savePair(first, second).get()
            }

            openRepository("arcduels-crash-after-commit").use { afterCommit ->
                afterCommit.migrate().get()
                afterCommit.pending(serverId).get().map { it.playerId } shouldContainExactly listOf(first.playerId, second.playerId)
                afterCommit.retainRestored(first, restoredAt, purgeAfter).get() shouldBe true
            }

            openRepository("arcduels-crash-after-partial-ack").use { afterPartialAcknowledgement ->
                afterPartialAcknowledgement.migrate().get()
                afterPartialAcknowledgement.findPending(first.playerId).get() shouldBe null
                afterPartialAcknowledgement.findLatestRetained(first.playerId).get()?.sameContent(first) shouldBe true
                afterPartialAcknowledgement.findPending(second.playerId).get()?.sameContent(second) shouldBe true
                afterPartialAcknowledgement.retainRestored(first, restoredAt, purgeAfter).get() shouldBe true
                afterPartialAcknowledgement.retainRestored(second, restoredAt, purgeAfter).get() shouldBe true
                afterPartialAcknowledgement.purgeRetained(purgeAfter).get() shouldBe 2
            }
        }

        "player state pair is atomically moved to retained history and purged only after expiry" {
            // The suite intentionally shares one container. Isolate the purge
            // count from retained rows created by earlier scenarios while
            // preserving the exact expiry-minus-one-millisecond boundary check.
            repository.purgeRetained(Instant.parse("9999-12-31T23:59:59.999Z")).get()
            val serverId = ServerId("duels-it")
            val matchId = MatchId(UUID.fromString("00000000-0000-0000-0000-000000000060"))
            val first = escrow("00000000-0000-0000-0000-000000000061", matchId, serverId, "first-state")
            val second = escrow("00000000-0000-0000-0000-000000000062", matchId, serverId, "second-state")
            val restoredAt = Instant.parse("2026-08-14T12:00:00Z")
            val purgeAfter = Instant.parse("2026-08-21T12:00:00Z")

            repository.savePair(first, second).get()
            repository.savePair(first, second).get()

            repository.findPending(first.playerId).get()?.sameContent(first) shouldBe true
            repository.findPending(second.playerId).get()?.sameContent(second) shouldBe true
            repository.pending(serverId).get().map { it.playerId } shouldContainExactly listOf(first.playerId, second.playerId)

            val wrongReceipt = escrow(first.playerId.value.toString(), matchId, serverId, "different-state")
            repository.retainRestored(wrongReceipt, restoredAt, purgeAfter).get() shouldBe false
            repository.findPending(first.playerId).get()?.sameContent(first) shouldBe true

            repository.retainRestored(first, restoredAt, purgeAfter).get() shouldBe true
            repository.retainRestored(first, restoredAt.plusSeconds(30), purgeAfter.plusSeconds(30)).get() shouldBe true
            repository.findPending(first.playerId).get() shouldBe null
            repository.findLatestRetained(first.playerId).get()?.sameContent(first) shouldBe true
            repository.findPending(second.playerId).get()?.sameContent(second) shouldBe true
            retainedCount(first) shouldBe 1
            repository.purgeRetained(purgeAfter.minusMillis(1)).get() shouldBe 0
            retainedCount(first) shouldBe 1

            repository.retainRestored(second, restoredAt, purgeAfter).get() shouldBe true
            repository.purgeRetained(purgeAfter).get() shouldBe 2
            retainedCount(first) shouldBe 0
            retainedCount(second) shouldBe 0
        }

        "conflicting participant rolls back the other participant in the pair" {
            val serverId = ServerId("duels-it")
            val occupiedMatch = MatchId(UUID.fromString("00000000-0000-0000-0000-000000000070"))
            val conflictPlayer = escrow("00000000-0000-0000-0000-000000000071", occupiedMatch, serverId, "occupied")
            val occupiedPeer = escrow("00000000-0000-0000-0000-000000000072", occupiedMatch, serverId, "occupied-peer")
            repository.savePair(conflictPlayer, occupiedPeer).get()

            val newMatch = MatchId(UUID.fromString("00000000-0000-0000-0000-000000000073"))
            val innocent = escrow("00000000-0000-0000-0000-000000000074", newMatch, serverId, "must-roll-back")
            val conflicting = escrow(conflictPlayer.playerId.value.toString(), newMatch, serverId, "new-conflict")

            shouldThrow<ExecutionException> { repository.savePair(innocent, conflicting).get() }

            repository.findPending(innocent.playerId).get() shouldBe null
            repository.findPending(conflictPlayer.playerId).get()?.sameContent(conflictPlayer) shouldBe true
            val restoredAt = Instant.parse("2026-08-14T13:00:00Z")
            val purgeAfter = Instant.parse("2026-08-21T13:00:00Z")
            repository.retainRestored(conflictPlayer, restoredAt, purgeAfter).get() shouldBe true
            repository.retainRestored(occupiedPeer, restoredAt, purgeAfter).get() shouldBe true
        }

        "migration rerun converges after ddl committed before history record" {
            mysql.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM `arcduels_schema_history` WHERE `version` = 4")
                }
            }

            repository.migrate().get().appliedVersions shouldContainExactly listOf(4)
            repository.migrate().get().existingVersions shouldContainExactly listOf(1, 2, 3, 4, 5, 6, 7, 8)
        }

        "history and preset migrations converge after ddl committed before journal" {
            mysql.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        UPDATE `arcduels_matches`
                        SET `projectiles` = TRUE, `consumables` = TRUE,
                            `ender_pearls` = TRUE, `natural_regeneration` = TRUE,
                            `end_reason` = 'ELIMINATION'
                        WHERE `objective` = 'BOXING'
                        """.trimIndent(),
                    )
                    statement.executeUpdate("DELETE FROM `arcduels_schema_history` WHERE `version` IN (7, 8)")
                }
            }

            repository.migrate().get().appliedVersions shouldContainExactly listOf(7, 8)
            repository.migrate().get().existingVersions shouldContainExactly listOf(1, 2, 3, 4, 5, 6, 7, 8)
            repository.findMatch(MatchId(UUID.fromString("00000000-0000-0000-0000-000000000010"))).get()?.outcome?.let {
                it.modifiers.projectiles shouldBe false
                it.modifiers.consumables shouldBe false
                it.modifiers.enderPearls shouldBe false
                it.modifiers.naturalRegeneration shouldBe false
                it.endReason shouldBe ru.ruscrafting.duels.domain.MatchEndReason.OBJECTIVE
            }
        }
    }

    private fun escrow(
        playerUuid: String,
        matchId: MatchId,
        serverId: ServerId,
        content: String,
    ): PlayerStateEscrow {
        val payload = content.toByteArray()
        return PlayerStateEscrow(
            playerId = PlayerId(UUID.fromString(playerUuid)),
            matchId = matchId,
            serverId = serverId,
            formatVersion = 1,
            inventoryReplaced = true,
            payload = payload,
            checksum = MessageDigest.getInstance("SHA-256").digest(payload),
            createdAt = Instant.parse("2026-08-13T11:00:00Z"),
        )
    }

    private fun retainedCount(snapshot: PlayerStateEscrow): Int =
        mysql.createConnection("").use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM `arcduels_player_state_archive` WHERE `player_id` = ? AND `match_id` = ?",
            ).use { statement ->
                statement.setBytes(1, snapshot.playerId.value.toBytes())
                statement.setBytes(2, snapshot.matchId.value.toBytes())
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }

    private fun openRepository(poolName: String): MySqlStatisticsRepository =
        MySqlStatisticsRepository(
            SqlRuntime.create(
                SqlConnectionConfig(
                    host = mysql.host,
                    port = mysql.firstMappedPort,
                    database = mysql.databaseName,
                    username = mysql.username,
                    password = mysql.password,
                    sslMode = SqlSslMode.DISABLED,
                    minimumIdle = 0,
                    maximumPoolSize = 4,
                    connectionTimeoutMs = 10_000,
                    socketTimeoutMs = 30_000,
                    validationTimeoutMs = 5_000,
                    maxLifetimeMs = 60_000,
                    failFast = true,
                ),
                poolName,
            ),
        )

    private fun UUID.toBytes(): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(mostSignificantBits)
            .putLong(leastSignificantBits)
            .array()
}
