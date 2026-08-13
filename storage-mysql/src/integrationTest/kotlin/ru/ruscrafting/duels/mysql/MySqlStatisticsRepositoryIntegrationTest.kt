package ru.ruscrafting.duels.mysql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
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
        .withDatabaseName("rusduels")
        .withUsername("rusduels")
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
                    "rusduels-it",
                )
            repository = MySqlStatisticsRepository(runtime)
            repository.migrate().get().appliedVersions shouldContainExactly listOf(1)
            repository.migrate().get().existingVersions shouldContainExactly listOf(1)
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

            val first = repository.record(outcome).get()
            val duplicate = repository.record(outcome).get()

            first.newlyRecorded shouldBe true
            first.winnerRatingAfter shouldBe 1_016
            first.loserRatingAfter shouldBe 984
            duplicate shouldBe first.copy(newlyRecorded = false)
            repository.find(winner).get().wins shouldBe 1
            repository.find(loser).get().losses shouldBe 1
            repository.leaderboard(10).get().map { it.playerId } shouldContainExactly listOf(winner, loser)
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
    }
}
