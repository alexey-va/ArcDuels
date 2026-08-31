package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.MemoryConfiguration
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArcDuelsRuntimeSettingsTest : StringSpec({
    "missing runtime keys use documented safe defaults" {
        val candidate = ArcDuelsRuntimeSettings.parse(MemoryConfiguration())

        candidate.settings shouldBe
            ArcDuelsRuntimeSettings(
                challengeTimeout = Duration.ofSeconds(45),
                countdownSeconds = 3,
                teleportStabilizationTicks = 3L,
                rematchWindow = Duration.ofSeconds(180),
                boundaryWarningDistance = 12.0,
                playerDataSettleDelayTicks = 40L,
                defaultPostMatchReturnPolicy = PostMatchReturnPolicy.PROMPT,
                automaticReturnTimeout = Duration.ofSeconds(120),
                seriesRoundIntermissionTicks = 30L,
                shutdownRecoveryTimeout = Duration.ofMillis(5_000),
                celebrationDurationTicks = 80L,
                celebrationFireworksEnabled = true,
                celebrationFireworkCount = 4,
                transferTimeout = Duration.ofSeconds(30),
                broadcastWins = true,
                multiplayerInvitationTimeout = Duration.ofSeconds(45),
                multiplayerFinishDelayTicks = 60L,
                multiplayerDefaultLayout = MultiplayerLayout.FREE_FOR_ALL,
                multiplayerDefaultKitPolicy = MultiplayerKitPolicy.SHARED,
                guiArenaNameInputTimeout = Duration.ofSeconds(60),
                guiLeaderboardLimit = 100,
                guiHistoryLimit = 100,
            )
        candidate.restartRequiredComparedTo(ArcDuelsRuntimeSettings.parse(MemoryConfiguration())) shouldBe emptySet()
    }

    "all runtime settings are parsed into explicit units" {
        val configuration =
            MemoryConfiguration().apply {
                set("challenge-timeout-seconds", 120L)
                set("countdown-seconds", 7)
                set("teleport-stabilization-ticks", 8L)
                set("rematch-window-seconds", 300L)
                set("boundary-warning-distance", 6.5)
                set("player-data-sync.settle-delay-ticks", 75L)
                set("post-match.return-policy", "automatic")
                set("post-match.automatic-return-timeout-seconds", 240L)
                set("series.round-intermission-ticks", 80L)
                set("shutdown.recovery-timeout-ms", 8_000L)
                set("celebration.duration-ticks", 100L)
                set("celebration.fireworks.enabled", false)
                set("celebration.fireworks.count", 5)
                set("redis.transfer-timeout-seconds", 90L)
                set("redis.broadcast-wins", false)
                set("multiplayer.invitation-timeout-seconds", 240L)
                set("multiplayer.finish-delay-ticks", 120L)
                set("multiplayer.defaults.layout", "three_teams")
                set("multiplayer.defaults.kit-policy", "per_player")
                set("multiplayer.defaults.kit", "axe")
                set("gui.arena-name-input-timeout-seconds", 90L)
                set("gui.leaderboard-limit", 75)
                set("gui.history-limit", 80)
            }

        ArcDuelsRuntimeSettings.parse(configuration).settings shouldBe
            ArcDuelsRuntimeSettings(
                challengeTimeout = Duration.ofSeconds(120),
                countdownSeconds = 7,
                teleportStabilizationTicks = 8L,
                rematchWindow = Duration.ofSeconds(300),
                boundaryWarningDistance = 6.5,
                playerDataSettleDelayTicks = 75L,
                defaultPostMatchReturnPolicy = PostMatchReturnPolicy.AUTOMATIC,
                automaticReturnTimeout = Duration.ofSeconds(240),
                seriesRoundIntermissionTicks = 80L,
                shutdownRecoveryTimeout = Duration.ofMillis(8_000),
                celebrationDurationTicks = 100L,
                celebrationFireworksEnabled = false,
                celebrationFireworkCount = 5,
                transferTimeout = Duration.ofSeconds(90),
                broadcastWins = false,
                multiplayerInvitationTimeout = Duration.ofSeconds(240),
                multiplayerFinishDelayTicks = 120L,
                multiplayerDefaultLayout = MultiplayerLayout.THREE_TEAMS,
                multiplayerDefaultKitPolicy = MultiplayerKitPolicy.PER_PLAYER,
                guiArenaNameInputTimeout = Duration.ofSeconds(90),
                guiLeaderboardLimit = 75,
                guiHistoryLimit = 80,
            )
    }

    "every documented inclusive boundary is accepted" {
        val boundaries =
            listOf(
                Triple("challenge-timeout-seconds", 5L, 600L),
                Triple("countdown-seconds", 0, 10),
                Triple("teleport-stabilization-ticks", 0L, 20L),
                Triple("rematch-window-seconds", 30L, 900L),
                Triple("boundary-warning-distance", 1.0, 16.0),
                Triple("player-data-sync.settle-delay-ticks", 0L, 1_200L),
                Triple("post-match.automatic-return-timeout-seconds", 30L, 600L),
                Triple("series.round-intermission-ticks", 0L, 200L),
                Triple("shutdown.recovery-timeout-ms", 100L, 30_000L),
                Triple("celebration.duration-ticks", 0L, 200L),
                Triple("celebration.fireworks.count", 1, 5),
                Triple("redis.transfer-timeout-seconds", 10L, 120L),
                Triple("multiplayer.invitation-timeout-seconds", 5L, 600L),
                Triple("multiplayer.finish-delay-ticks", 0L, 200L),
                Triple("gui.arena-name-input-timeout-seconds", 5L, 300L),
                Triple("gui.leaderboard-limit", 1, 100),
                Triple("gui.history-limit", 1, 100),
            )

        boundaries.forEach { (path, minimum, maximum) ->
            ArcDuelsRuntimeSettings.parse(MemoryConfiguration().apply { set(path, minimum) })
            ArcDuelsRuntimeSettings.parse(MemoryConfiguration().apply { set(path, maximum) })
        }
    }

    "out of range runtime values are rejected with their config path" {
        val invalidValues =
            listOf(
                "challenge-timeout-seconds" to 4L,
                "countdown-seconds" to 11,
                "teleport-stabilization-ticks" to 21L,
                "rematch-window-seconds" to 29L,
                "boundary-warning-distance" to Double.NaN,
                "player-data-sync.settle-delay-ticks" to 1_201L,
                "post-match.automatic-return-timeout-seconds" to 29L,
                "series.round-intermission-ticks" to 201L,
                "shutdown.recovery-timeout-ms" to 99L,
                "celebration.duration-ticks" to 201L,
                "celebration.fireworks.count" to 6,
                "redis.transfer-timeout-seconds" to 121L,
                "multiplayer.invitation-timeout-seconds" to 601L,
                "multiplayer.finish-delay-ticks" to 201L,
                "gui.arena-name-input-timeout-seconds" to 301L,
                "gui.leaderboard-limit" to 0,
                "gui.history-limit" to 101,
            )

        invalidValues.forEach { (path, value) ->
            val failure =
                shouldThrow<IllegalArgumentException> {
                    ArcDuelsRuntimeSettings.parse(MemoryConfiguration().apply { set(path, value) })
                }
            failure.message.orEmpty().contains(path) shouldBe true
        }
    }

    "explicit values with the wrong scalar type never degrade to defaults" {
        val invalidValues =
            listOf(
                "countdown-seconds" to 3.0,
                "rematch-window-seconds" to "180",
                "boundary-warning-distance" to "12.0",
                "celebration.fireworks.enabled" to "true",
                "post-match.return-policy" to 1,
                "multiplayer.defaults.layout" to 1,
            )

        invalidValues.forEach { (path, value) ->
            val failure =
                shouldThrow<IllegalArgumentException> {
                    ArcDuelsRuntimeSettings.parse(MemoryConfiguration().apply { set(path, value) })
                }
            failure.message.orEmpty().contains(path) shouldBe true
        }
    }

    "unknown post match policy is rejected" {
        val failure =
            shouldThrow<IllegalArgumentException> {
                ArcDuelsRuntimeSettings.parse(
                    MemoryConfiguration().apply { set("post-match.return-policy", "TELEPORT_NOW") },
                )
            }

        failure.message.orEmpty().contains("post-match.return-policy") shouldBe true
    }

    "unknown multiplayer defaults are rejected with their exact path" {
        listOf(
            "multiplayer.defaults.layout" to "FOUR_TEAMS",
            "multiplayer.defaults.kit-policy" to "RANDOM",
        ).forEach { (path, value) ->
            val failure =
                shouldThrow<IllegalArgumentException> {
                    ArcDuelsRuntimeSettings.parse(MemoryConfiguration().apply { set(path, value) })
                }
            failure.message.orEmpty().contains(path) shouldBe true
        }
    }

    "legacy recovery delay remains a fallback but the current key has precedence" {
        val legacy = MemoryConfiguration().apply { set("recovery.apply-delay-ticks", 70L) }
        ArcDuelsRuntimeSettings.parse(legacy).settings.playerDataSettleDelayTicks shouldBe 70L

        legacy.set("player-data-sync.settle-delay-ticks", 80L)
        ArcDuelsRuntimeSettings.parse(legacy).settings.playerDataSettleDelayTicks shouldBe 80L
    }

    "an explicit legacy recovery delay wins over a bundled current-key default" {
        val bundled = MemoryConfiguration().apply { set("player-data-sync.settle-delay-ticks", 40L) }
        val legacy =
            MemoryConfiguration().apply {
                set("recovery.apply-delay-ticks", 70L)
                setDefaults(bundled)
            }

        ArcDuelsRuntimeSettings.parse(legacy).settings.playerDataSettleDelayTicks shouldBe 70L
    }

    "enabled restart-only resources reject invalid typed candidates before fingerprinting" {
        val invalidValues =
            listOf(
                Triple("server-id", "unsafe server", "server"),
                Triple("player-data-sync.provider", "SOMETHING_ELSE", "player-data-sync.provider"),
                Triple("mysql.port", 70_000, "mysql"),
                Triple("mysql.ssl-mode", "PREFERRED", "mysql.ssl-mode"),
                Triple("mysql.pool.maximum-size", 0, "mysql"),
                Triple("redis.host", "redis host with spaces", "redis"),
                Triple("redis.port", 0, "redis"),
                Triple("redis.player-list-origin", "unsafe origin", "server"),
                Triple("redis.player-list-ttl-seconds", 31L, "redis.player-list-ttl-seconds"),
            )

        invalidValues.forEach { (path, value, expectedMessage) ->
            val configuration =
                MemoryConfiguration().apply {
                    if (path.startsWith("mysql.")) set("mysql.enabled", true)
                    if (path.startsWith("redis.")) set("redis.enabled", true)
                    set(path, value)
                }
            val failure =
                shouldThrow<IllegalArgumentException> {
                    ArcDuelsRuntimeSettings.parse(configuration)
                }
            failure.message.orEmpty().contains(expectedMessage, ignoreCase = true) shouldBe true
        }
    }

    "disabled resource placeholders remain backward compatible" {
        val configuration =
            MemoryConfiguration().apply {
                set("mysql.enabled", false)
                set("mysql.host", listOf("legacy", "placeholder"))
                set("mysql.port", "not-used")
                set("redis.enabled", false)
                set("redis.host", listOf("legacy", "placeholder"))
                set("redis.port", "not-used")
            }

        ArcDuelsRuntimeSettings.parse(configuration)
    }

    "effective imported Redis credentials participate in the restart fingerprint without exposure" {
        val configuration =
            MemoryConfiguration().apply {
                set("redis.enabled", true)
                set("redis.import-arc-credentials", true)
            }
        val secretBefore = "external-secret-before"
        val secretAfter = "external-secret-after"
        val before =
            ArcDuelsRuntimeSettingsParser.parse(
                configuration,
                ArcDuelsEffectiveRedisSettings("redis.internal", 6379, "classic", secretBefore),
            )
        val after =
            ArcDuelsRuntimeSettingsParser.parse(
                configuration,
                ArcDuelsEffectiveRedisSettings("redis.internal", 6379, "classic", secretAfter),
            )

        after.restartRequiredComparedTo(before) shouldBe setOf(ArcDuelsRestartOnlyField.REDIS_CONNECTION)
        before.toString().contains(secretBefore) shouldBe false
        after.toString().contains(secretAfter) shouldBe false
        before.restartOnlyFingerprint.toString().contains(secretBefore) shouldBe false
        after.restartOnlyFingerprint.toString().contains(secretAfter) shouldBe false
    }

    "effective AUTO player-data provider changes require restart even when source text is unchanged" {
        val configuration =
            MemoryConfiguration().apply {
                set("player-data-sync.provider", "AUTO")
            }
        val withoutHuskSync =
            ArcDuelsRuntimeSettingsParser.parse(
                configuration,
                effectiveRedis = null,
                effectivePlayerDataProvider = PlayerDataSyncProvider.NONE,
            )
        val withHuskSync =
            ArcDuelsRuntimeSettingsParser.parse(
                configuration,
                effectiveRedis = null,
                effectivePlayerDataProvider = PlayerDataSyncProvider.HUSKSYNC,
            )

        withHuskSync.restartRequiredComparedTo(withoutHuskSync) shouldBe
            setOf(ArcDuelsRestartOnlyField.PLAYER_DATA_PROVIDER)
    }

    "restart fingerprints classify only restart owned fields and never expose values" {
        val active = ArcDuelsRuntimeSettings.parse(MemoryConfiguration())
        val runtimeOnly =
            ArcDuelsRuntimeSettings.parse(
                MemoryConfiguration().apply {
                    set("countdown-seconds", 8)
                    set("redis.broadcast-wins", false)
                    set("redis.transfer-timeout-seconds", 45L)
                },
            )
        runtimeOnly.restartRequiredComparedTo(active) shouldBe emptySet()

        val secret = "mysql-secret-not-for-diagnostics"
        val changed =
            ArcDuelsRuntimeSettings.parse(
                MemoryConfiguration().apply {
                    set("server-id", "duels-2")
                    set("mysql.password", secret)
                    set("redis.host", "redis-2.internal")
                    set("player-data-sync.provider", "NONE")
                },
            )
        changed.restartRequiredComparedTo(active).toList() shouldContainExactly
            listOf(
                ArcDuelsRestartOnlyField.SERVER_ID,
                ArcDuelsRestartOnlyField.MYSQL,
                ArcDuelsRestartOnlyField.REDIS_CONNECTION,
                ArcDuelsRestartOnlyField.PLAYER_DATA_PROVIDER,
            )
        changed.toString().contains(secret) shouldBe false
        changed.restartOnlyFingerprint.toString().contains(secret) shouldBe false
    }

    "restart fingerprints normalize defaults and case insensitive enum values" {
        val active = ArcDuelsRuntimeSettings.parse(MemoryConfiguration())
        val explicitDefaults =
            ArcDuelsRuntimeSettings.parse(
                MemoryConfiguration().apply {
                    set("server-id", "duels-1")
                    set("mysql.ssl-mode", "verify_identity")
                    set("player-data-sync.provider", " auto ")
                    set("redis.player-list-origin", "proxy")
                },
            )

        explicitDefaults.restartRequiredComparedTo(active) shouldBe emptySet()
    }

    "runtime commits never acknowledge restart only changes as active" {
        val initial = ArcDuelsRuntimeSettings.parse(MemoryConfiguration())
        val changed =
            ArcDuelsRuntimeSettings.parse(
                MemoryConfiguration().apply {
                    set("countdown-seconds", 4)
                    set("mysql.host", "mysql-2.internal")
                },
            )
        val state = ArcDuelsRuntimeSettingsState(initial)

        val first =
            state.commit(state.snapshot().generation, changed) as ArcDuelsRuntimeSettingsCommitResult.Applied
        first.snapshot.settings.countdownSeconds shouldBe 4
        first.restartRequired shouldBe setOf(ArcDuelsRestartOnlyField.MYSQL)

        val repeated =
            state.commit(state.snapshot().generation, changed) as ArcDuelsRuntimeSettingsCommitResult.Applied
        repeated.restartRequired shouldBe setOf(ArcDuelsRestartOnlyField.MYSQL)
    }

    "one optimistic commit wins and publishes one complete generation" {
        val initial = ArcDuelsRuntimeSettings.parse(MemoryConfiguration())
        val state = ArcDuelsRuntimeSettingsState(initial)
        val expectedGeneration = state.snapshot().generation
        val candidates =
            (0 until 16).map { index ->
                initial.copy(settings = initial.settings.copy(countdownSeconds = index % 11))
            }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        val results =
            try {
                val futures =
                    candidates.map { candidate ->
                        executor.submit<ArcDuelsRuntimeSettingsCommitResult> {
                            start.await()
                            state.commit(expectedGeneration, candidate)
                        }
                    }
                start.countDown()
                futures.map { it.get(5, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

        val applied = results.filterIsInstance<ArcDuelsRuntimeSettingsCommitResult.Applied>()
        applied.size shouldBe 1
        applied.single().restartRequired shouldBe emptySet()
        results.filterIsInstance<ArcDuelsRuntimeSettingsCommitResult.Stale>().size shouldBe 15
        state.snapshot() shouldBe applied.single().snapshot
        state.snapshot().generation shouldBe 2L
    }
})
