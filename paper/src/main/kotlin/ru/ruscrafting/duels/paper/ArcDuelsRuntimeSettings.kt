package ru.ruscrafting.duels.paper

import org.bukkit.configuration.Configuration
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable settings that may be installed without rebuilding ArcDuels' external resources.
 *
 * Durations use wall-clock time while tick-based settings stay explicit. A reload owner should
 * capture the relevant values when it creates a challenge or session so an in-flight activity
 * keeps the policy under which it started.
 */
data class ArcDuelsRuntimeSettings(
    val challengeTimeout: Duration,
    val countdownSeconds: Int,
    val teleportStabilizationTicks: Long,
    val rematchWindow: Duration,
    val boundaryWarningDistance: Double,
    val playerDataSettleDelayTicks: Long,
    val defaultPostMatchReturnPolicy: PostMatchReturnPolicy,
    val automaticReturnTimeout: Duration,
    val seriesRoundIntermissionTicks: Long,
    val shutdownRecoveryTimeout: Duration,
    val celebrationDurationTicks: Long,
    val celebrationFireworksEnabled: Boolean,
    val celebrationFireworkCount: Int,
    val transferTimeout: Duration,
    val broadcastWins: Boolean,
    val multiplayerInvitationTimeout: Duration,
    val multiplayerFinishDelayTicks: Long,
    val multiplayerDefaultLayout: MultiplayerLayout,
    val multiplayerDefaultKitPolicy: MultiplayerKitPolicy,
    val guiArenaNameInputTimeout: Duration,
    val guiLeaderboardLimit: Int,
    val guiHistoryLimit: Int,
) {
    init {
        require(challengeTimeout in MIN_CHALLENGE_TIMEOUT..MAX_CHALLENGE_TIMEOUT) {
            "challenge-timeout-seconds must be between 5 and 600"
        }
        require(countdownSeconds in 0..10) { "countdown-seconds must be between 0 and 10" }
        require(teleportStabilizationTicks in 0L..20L) {
            "teleport-stabilization-ticks must be between 0 and 20"
        }
        require(rematchWindow in MIN_REMATCH_WINDOW..MAX_REMATCH_WINDOW) {
            "rematch-window-seconds must be between 30 and 900"
        }
        require(boundaryWarningDistance.isFinite() && boundaryWarningDistance in 1.0..16.0) {
            "boundary-warning-distance must be between 1 and 16"
        }
        require(playerDataSettleDelayTicks in 0L..1_200L) {
            "player-data-sync.settle-delay-ticks must be between 0 and 1200"
        }
        require(automaticReturnTimeout in MIN_AUTOMATIC_RETURN_TIMEOUT..MAX_AUTOMATIC_RETURN_TIMEOUT) {
            "post-match.automatic-return-timeout-seconds must be between 30 and 600"
        }
        require(seriesRoundIntermissionTicks in 0L..200L) {
            "series.round-intermission-ticks must be between 0 and 200"
        }
        require(shutdownRecoveryTimeout in MIN_SHUTDOWN_TIMEOUT..MAX_SHUTDOWN_TIMEOUT) {
            "shutdown.recovery-timeout-ms must be between 100 and 30000"
        }
        require(celebrationDurationTicks in 0L..200L) {
            "celebration.duration-ticks must be between 0 and 200"
        }
        require(celebrationFireworkCount in 1..5) {
            "celebration.fireworks.count must be between 1 and 5"
        }
        require(transferTimeout in MIN_TRANSFER_TIMEOUT..MAX_TRANSFER_TIMEOUT) {
            "redis.transfer-timeout-seconds must be between 10 and 120"
        }
        require(multiplayerInvitationTimeout in MIN_INVITATION_TIMEOUT..MAX_INVITATION_TIMEOUT) {
            "multiplayer.invitation-timeout-seconds must be between 5 and 600"
        }
        require(multiplayerFinishDelayTicks in 0L..200L) {
            "multiplayer.finish-delay-ticks must be between 0 and 200"
        }
        require(guiArenaNameInputTimeout in MIN_GUI_INPUT_TIMEOUT..MAX_GUI_INPUT_TIMEOUT) {
            "gui.arena-name-input-timeout-seconds must be between 5 and 300"
        }
        require(guiLeaderboardLimit in 1..100) { "gui.leaderboard-limit must be between 1 and 100" }
        require(guiHistoryLimit in 1..100) { "gui.history-limit must be between 1 and 100" }
    }

    companion object {
        private val MIN_CHALLENGE_TIMEOUT = Duration.ofSeconds(5)
        private val MAX_CHALLENGE_TIMEOUT = Duration.ofSeconds(600)
        private val MIN_REMATCH_WINDOW = Duration.ofSeconds(30)
        private val MAX_REMATCH_WINDOW = Duration.ofSeconds(900)
        private val MIN_AUTOMATIC_RETURN_TIMEOUT = Duration.ofSeconds(30)
        private val MAX_AUTOMATIC_RETURN_TIMEOUT = Duration.ofSeconds(600)
        private val MIN_SHUTDOWN_TIMEOUT = Duration.ofMillis(100)
        private val MAX_SHUTDOWN_TIMEOUT = Duration.ofMillis(30_000)
        private val MIN_TRANSFER_TIMEOUT = Duration.ofSeconds(10)
        private val MAX_TRANSFER_TIMEOUT = Duration.ofSeconds(120)
        private val MIN_INVITATION_TIMEOUT = Duration.ofSeconds(5)
        private val MAX_INVITATION_TIMEOUT = Duration.ofSeconds(600)
        private val MIN_GUI_INPUT_TIMEOUT = Duration.ofSeconds(5)
        private val MAX_GUI_INPUT_TIMEOUT = Duration.ofSeconds(300)

        /** Parses and validates both reloadable values and the restart-only comparison baseline. */
        fun parse(configuration: Configuration): ArcDuelsRuntimeSettingsCandidate =
            ArcDuelsRuntimeSettingsParser.parse(configuration)
    }
}

/** A fully validated candidate prepared from one configuration view. */
data class ArcDuelsRuntimeSettingsCandidate(
    val settings: ArcDuelsRuntimeSettings,
    val restartOnlyFingerprint: ArcDuelsRestartOnlyFingerprint,
) {
    fun restartRequiredComparedTo(active: ArcDuelsRuntimeSettingsCandidate): Set<ArcDuelsRestartOnlyField> =
        restartOnlyFingerprint.changedFieldsSince(active.restartOnlyFingerprint)
}

/**
 * Restart-only groups reported to operators. These labels identify config paths, never values.
 */
enum class ArcDuelsRestartOnlyField(
    val reportKey: String,
) {
    SERVER_ID("server-id"),
    MYSQL("mysql"),
    REDIS_CONNECTION("redis.connection"),
    PLAYER_DATA_PROVIDER("player-data-sync.provider"),
}

/**
 * One-way comparison material for settings whose resources cannot be rebuilt by the first
 * live-reload slice. Raw values and digests are deliberately not exposed by this type.
 */
class ArcDuelsRestartOnlyFingerprint private constructor(
    sourceDigests: Map<ArcDuelsRestartOnlyField, ByteArray>,
) {
    private val digests = sourceDigests.mapValues { (_, digest) -> digest.copyOf() }

    fun changedFieldsSince(active: ArcDuelsRestartOnlyFingerprint): Set<ArcDuelsRestartOnlyField> {
        val changed = EnumSet.noneOf(ArcDuelsRestartOnlyField::class.java)
        ArcDuelsRestartOnlyField.entries.forEach { field ->
            if (!MessageDigest.isEqual(digests.getValue(field), active.digests.getValue(field))) {
                changed += field
            }
        }
        return Collections.unmodifiableSet(changed)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ArcDuelsRestartOnlyFingerprint &&
            ArcDuelsRestartOnlyField.entries.all { field ->
                MessageDigest.isEqual(digests.getValue(field), other.digests.getValue(field))
            }

    override fun hashCode(): Int =
        ArcDuelsRestartOnlyField.entries.fold(1) { hash, field ->
            31 * hash + digests.getValue(field).contentHashCode()
        }

    override fun toString(): String =
        "ArcDuelsRestartOnlyFingerprint(fields=${ArcDuelsRestartOnlyField.entries.joinToString { it.reportKey }})"

    internal companion object {
        fun capture(
            configuration: Configuration,
            effectiveRedis: ArcDuelsEffectiveRedisSettings? = null,
            effectivePlayerDataProvider: PlayerDataSyncProvider? = null,
        ): ArcDuelsRestartOnlyFingerprint =
            ArcDuelsRestartOnlyFingerprint(
                RESTART_INPUTS.mapValues { (field, inputs) ->
                    fingerprint(
                        configuration,
                        inputs,
                        when (field) {
                            ArcDuelsRestartOnlyField.REDIS_CONNECTION ->
                                effectiveRedis?.let(::effectiveRedisOverrides).orEmpty()
                            ArcDuelsRestartOnlyField.PLAYER_DATA_PROVIDER ->
                                effectivePlayerDataProvider
                                    ?.let { provider -> mapOf("player-data-sync.provider" to provider.name) }
                                    .orEmpty()
                            else -> emptyMap()
                        },
                    )
                },
            )

        private fun fingerprint(
            configuration: Configuration,
            inputs: List<RestartInput>,
            overrides: Map<String, Any?>,
        ): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            inputs.forEach { input ->
                digest.updateFramed(input.path)
                val configured =
                    when {
                        overrides.containsKey(input.path) -> overrides[input.path]
                        configuration.contains(input.path) -> configuration.get(input.path)
                        else -> input.defaultValue
                    }
                digest.updateFramed(canonicalValue(input.normalize(configured)))
            }
            return digest.digest()
        }

        private fun effectiveRedisOverrides(settings: ArcDuelsEffectiveRedisSettings): Map<String, Any?> =
            mapOf(
                "redis.host" to settings.host,
                "redis.port" to settings.port,
                "redis.username" to settings.username,
                "redis.password" to settings.password,
            )

        private fun canonicalValue(value: Any?): String =
            when (value) {
                null -> "null"
                is String -> "string:$value"
                is Boolean -> "boolean:$value"
                is Byte, is Short, is Int, is Long -> "integer:${(value as Number).toLong()}"
                is Float, is Double -> "decimal:${java.lang.Double.toHexString((value as Number).toDouble())}"
                else -> "${value.javaClass.name}:$value"
            }

        private fun MessageDigest.updateFramed(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            update((bytes.size ushr 24).toByte())
            update((bytes.size ushr 16).toByte())
            update((bytes.size ushr 8).toByte())
            update(bytes.size.toByte())
            update(bytes)
        }

        private data class RestartInput(
            val path: String,
            val defaultValue: Any?,
            val normalize: (Any?) -> Any? = { it },
        )

        private val RESTART_INPUTS =
            mapOf(
                ArcDuelsRestartOnlyField.SERVER_ID to
                    listOf(RestartInput("server-id", "duels-1")),
                ArcDuelsRestartOnlyField.MYSQL to
                    listOf(
                        RestartInput("mysql.enabled", false),
                        RestartInput("mysql.host", "127.0.0.1"),
                        RestartInput("mysql.port", 3306),
                        RestartInput("mysql.database", "common"),
                        RestartInput("mysql.username", "arcduels"),
                        RestartInput("mysql.password", ""),
                        RestartInput("mysql.ssl-mode", "VERIFY_IDENTITY", ::uppercaseString),
                        RestartInput("mysql.inventory-snapshots.retention-days", 7L),
                        RestartInput("mysql.inventory-snapshots.cleanup-interval-minutes", 60L),
                        RestartInput("mysql.pool.minimum-idle", 1),
                        RestartInput("mysql.pool.maximum-size", 8),
                        RestartInput("mysql.pool.connection-timeout-ms", 10_000L),
                        RestartInput("mysql.pool.socket-timeout-ms", 30_000L),
                        RestartInput("mysql.pool.validation-timeout-ms", 5_000L),
                        RestartInput("mysql.pool.max-lifetime-ms", 1_700_000L),
                    ),
                ArcDuelsRestartOnlyField.REDIS_CONNECTION to
                    listOf(
                        RestartInput("redis.enabled", false),
                        RestartInput("redis.import-arc-credentials", false),
                        RestartInput("redis.host", "127.0.0.1"),
                        RestartInput("redis.port", 6379),
                        RestartInput("redis.username", ""),
                        RestartInput("redis.password", ""),
                        RestartInput("redis.player-list-origin", "proxy"),
                        RestartInput("redis.player-list-ttl-seconds", 5L),
                    ),
                ArcDuelsRestartOnlyField.PLAYER_DATA_PROVIDER to
                    listOf(RestartInput("player-data-sync.provider", "AUTO", ::uppercaseString)),
            )

        private fun uppercaseString(value: Any?): Any? =
            if (value is String) value.trim().uppercase(Locale.ROOT) else value
    }
}

/** An atomically published runtime generation. */
@ConsistentCopyVisibility
data class ArcDuelsRuntimeSettingsSnapshot internal constructor(
    val generation: Long,
    val settings: ArcDuelsRuntimeSettings,
)

/** Explicit outcome of an optimistic runtime-settings commit. */
sealed interface ArcDuelsRuntimeSettingsCommitResult {
    data class Applied(
        val snapshot: ArcDuelsRuntimeSettingsSnapshot,
        val restartRequired: Set<ArcDuelsRestartOnlyField>,
    ) : ArcDuelsRuntimeSettingsCommitResult

    data class Stale(
        val current: ArcDuelsRuntimeSettingsSnapshot,
    ) : ArcDuelsRuntimeSettingsCommitResult
}

/**
 * Thread-safe owner for the active immutable settings generation.
 *
 * Preparation happens outside this class. [commit] publishes the whole candidate atomically only
 * when the caller prepared it from the still-current generation; a stale candidate changes no
 * state and returns the generation that won the race. The restart-only baseline is fixed at
 * construction, so repeatedly reloading a changed database or network value keeps reporting that
 * a process restart is required until a new process constructs a new state owner.
 */
class ArcDuelsRuntimeSettingsState(
    initial: ArcDuelsRuntimeSettingsCandidate,
) {
    private val restartOnlyBaseline = initial.restartOnlyFingerprint
    private val current = AtomicReference(ArcDuelsRuntimeSettingsSnapshot(INITIAL_GENERATION, initial.settings))

    fun snapshot(): ArcDuelsRuntimeSettingsSnapshot = current.get()

    fun commit(
        expectedGeneration: Long,
        candidate: ArcDuelsRuntimeSettingsCandidate,
    ): ArcDuelsRuntimeSettingsCommitResult {
        while (true) {
            val active = current.get()
            if (active.generation != expectedGeneration) {
                return ArcDuelsRuntimeSettingsCommitResult.Stale(active)
            }
            val updated =
                ArcDuelsRuntimeSettingsSnapshot(
                    generation = Math.incrementExact(active.generation),
                    settings = candidate.settings,
                )
            if (current.compareAndSet(active, updated)) {
                return ArcDuelsRuntimeSettingsCommitResult.Applied(
                    snapshot = updated,
                    restartRequired = candidate.restartOnlyFingerprint.changedFieldsSince(restartOnlyBaseline),
                )
            }
        }
    }

    private companion object {
        const val INITIAL_GENERATION = 1L
    }
}

/** Strict scalar parser: an explicitly configured value never silently degrades to a default. */
object ArcDuelsRuntimeSettingsParser {
    fun parse(configuration: Configuration): ArcDuelsRuntimeSettingsCandidate = parse(configuration, null)

    internal fun parse(
        configuration: Configuration,
        effectiveRedis: ArcDuelsEffectiveRedisSettings?,
        effectivePlayerDataProvider: PlayerDataSyncProvider? = null,
    ): ArcDuelsRuntimeSettingsCandidate {
        ArcDuelsRestartOnlySettingsValidator.validateStructure(configuration)
        val settleDelayTicks =
            when {
                configuration.contains("player-data-sync.settle-delay-ticks", true) ->
                    configuration.long("player-data-sync.settle-delay-ticks", 40L, 0L..1_200L)
                configuration.contains("recovery.apply-delay-ticks", true) ->
                    configuration.long("recovery.apply-delay-ticks", 40L, 0L..1_200L)
                else -> 40L
            }
        val postMatchPolicy =
            configuration.string("post-match.return-policy", "PROMPT").let { configured ->
                try {
                    PostMatchReturnPolicy.parse(configured)
                } catch (failure: IllegalStateException) {
                    throw IllegalArgumentException(failure.message, failure)
                }
            }
        return ArcDuelsRuntimeSettingsCandidate(
            settings =
                ArcDuelsRuntimeSettings(
                    challengeTimeout =
                        Duration.ofSeconds(configuration.long("challenge-timeout-seconds", 45L, 5L..600L)),
                    countdownSeconds = configuration.int("countdown-seconds", 3, 0..10),
                    teleportStabilizationTicks =
                        configuration.long("teleport-stabilization-ticks", 3L, 0L..20L),
                    rematchWindow =
                        Duration.ofSeconds(configuration.long("rematch-window-seconds", 180L, 30L..900L)),
                    boundaryWarningDistance =
                        configuration.double("boundary-warning-distance", 12.0, 1.0..16.0),
                    playerDataSettleDelayTicks = settleDelayTicks,
                    defaultPostMatchReturnPolicy = postMatchPolicy,
                    automaticReturnTimeout =
                        Duration.ofSeconds(
                            configuration.long("post-match.automatic-return-timeout-seconds", 120L, 30L..600L),
                        ),
                    seriesRoundIntermissionTicks =
                        configuration.long("series.round-intermission-ticks", 30L, 0L..200L),
                    shutdownRecoveryTimeout =
                        Duration.ofMillis(configuration.long("shutdown.recovery-timeout-ms", 5_000L, 100L..30_000L)),
                    celebrationDurationTicks =
                        configuration.long("celebration.duration-ticks", 80L, 0L..200L),
                    celebrationFireworksEnabled =
                        configuration.boolean("celebration.fireworks.enabled", true),
                    celebrationFireworkCount =
                        configuration.int("celebration.fireworks.count", 4, 1..5),
                    transferTimeout =
                        Duration.ofSeconds(configuration.long("redis.transfer-timeout-seconds", 30L, 10L..120L)),
                    broadcastWins = configuration.boolean("redis.broadcast-wins", true),
                    multiplayerInvitationTimeout =
                        Duration.ofSeconds(
                            configuration.long("multiplayer.invitation-timeout-seconds", 45L, 5L..600L),
                        ),
                    multiplayerFinishDelayTicks =
                        configuration.long("multiplayer.finish-delay-ticks", 60L, 0L..200L),
                    multiplayerDefaultLayout =
                        configuration.enum("multiplayer.defaults.layout", MultiplayerLayout.FREE_FOR_ALL),
                    multiplayerDefaultKitPolicy =
                        configuration.enum("multiplayer.defaults.kit-policy", MultiplayerKitPolicy.SHARED),
                    guiArenaNameInputTimeout =
                        Duration.ofSeconds(
                            configuration.long("gui.arena-name-input-timeout-seconds", 60L, 5L..300L),
                        ),
                    guiLeaderboardLimit = configuration.int("gui.leaderboard-limit", 100, 1..100),
                    guiHistoryLimit = configuration.int("gui.history-limit", 100, 1..100),
                ),
            restartOnlyFingerprint =
                ArcDuelsRestartOnlyFingerprint.capture(
                    configuration,
                    effectiveRedis,
                    effectivePlayerDataProvider,
                ),
        )
    }

    private fun Configuration.int(
        path: String,
        defaultValue: Int,
        range: IntRange,
    ): Int {
        val value = integral(path, defaultValue.toLong())
        require(value in range.first.toLong()..range.last.toLong()) {
            "$path must be between ${range.first} and ${range.last}"
        }
        return value.toInt()
    }

    private fun Configuration.long(
        path: String,
        defaultValue: Long,
        range: LongRange,
    ): Long {
        val value = integral(path, defaultValue)
        require(value in range) { "$path must be between ${range.first} and ${range.last}" }
        return value
    }

    private fun Configuration.integral(
        path: String,
        defaultValue: Long,
    ): Long {
        if (!contains(path)) return defaultValue
        val configured = get(path)
        return when (configured) {
            is Byte, is Short, is Int, is Long -> (configured as Number).toLong()
            else -> throw IllegalArgumentException("$path must be an integer")
        }
    }

    private fun Configuration.double(
        path: String,
        defaultValue: Double,
        range: ClosedFloatingPointRange<Double>,
    ): Double {
        if (!contains(path)) return defaultValue
        val configured = get(path)
        val value = (configured as? Number)?.toDouble()
            ?: throw IllegalArgumentException("$path must be a number")
        require(value.isFinite() && value in range) {
            "$path must be between ${range.start} and ${range.endInclusive}"
        }
        return value
    }

    private fun Configuration.boolean(
        path: String,
        defaultValue: Boolean,
    ): Boolean {
        if (!contains(path)) return defaultValue
        return get(path) as? Boolean
            ?: throw IllegalArgumentException("$path must be a boolean")
    }

    private fun Configuration.string(
        path: String,
        defaultValue: String,
    ): String {
        if (!contains(path)) return defaultValue
        return get(path) as? String
            ?: throw IllegalArgumentException("$path must be a string")
    }

    private inline fun <reified T : Enum<T>> Configuration.enum(
        path: String,
        defaultValue: T,
    ): T {
        val configured = string(path, defaultValue.name).trim().uppercase(Locale.ROOT)
        return enumValues<T>().firstOrNull { it.name == configured }
            ?: throw IllegalArgumentException("$path has unsupported value '$configured'")
    }

}
