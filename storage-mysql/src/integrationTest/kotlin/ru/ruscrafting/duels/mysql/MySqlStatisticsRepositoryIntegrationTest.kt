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
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchOutcome
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
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
            val runtime =
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
                    "arcduels-it",
                )
            repository = MySqlStatisticsRepository(runtime)
            repository.migrate().get().appliedVersions shouldContainExactly listOf(1, 2)
            repository.migrate().get().existingVersions shouldContainExactly listOf(1, 2)
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
    }
}
