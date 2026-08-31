package ru.ruscrafting.duels.paper

import org.bukkit.configuration.Configuration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import ru.ruscrafting.duels.domain.ServerId
import java.io.File
import java.util.Locale

/**
 * Validates restart-owned configuration without opening database or Redis connections.
 *
 * Reload may publish live settings while retaining process-owned resources. Consequently every
 * restart-only candidate must still be proven bootable before it can be acknowledged, even though
 * the corresponding pool, subscription or identity will only be replaced by the next process.
 */
internal object ArcDuelsRestartOnlySettingsValidator {
    fun validateStructure(configuration: Configuration) {
        SECTION_PATHS.forEach(configuration::strictConfigurationSection)
        ServerId(configuration.strictString("server-id", "duels-1"))
        validateMySql(configuration)
        validateRedisConfiguration(configuration)
        validatePlayerDataProvider(configuration, huskSyncEnabled = true)
    }

    /** Adds process/environment checks that cannot be performed by the pure configuration parser. */
    fun validateReloadEnvironment(
        plugin: JavaPlugin,
        configuration: Configuration,
        capturedArcRedis: (() -> Configuration?)? = null,
    ): ArcDuelsRestartOnlyEnvironment =
        ArcDuelsRestartOnlyEnvironment(
            effectiveRedis =
                resolveEffectiveRedis(
                    plugin,
                    configuration,
                    capturedArcRedis,
                ),
            playerDataProvider =
                validatePlayerDataProvider(
                    configuration,
                    huskSyncEnabled = plugin.server.pluginManager.isPluginEnabled("HuskSync"),
                ),
        )

    private fun validateMySql(configuration: Configuration) {
        val enabled = configuration.strictBoolean("mysql.enabled", false)
        configuration.strictLong("mysql.inventory-snapshots.retention-days", 7L).requireIn(
            "mysql.inventory-snapshots.retention-days",
            1L..3_650L,
        )
        configuration.strictLong("mysql.inventory-snapshots.cleanup-interval-minutes", 60L).requireIn(
            "mysql.inventory-snapshots.cleanup-interval-minutes",
            1L..10_080L,
        )
        if (!enabled) return
        val sslMode =
            configuration.strictString("mysql.ssl-mode", "VERIFY_IDENTITY").let { configured ->
                runCatching { SqlSslMode.valueOf(configured.trim().uppercase(Locale.ROOT)) }
                    .getOrElse {
                        throw IllegalArgumentException(
                            "mysql.ssl-mode must be DISABLED, REQUIRED, VERIFY_CA, or VERIFY_IDENTITY",
                        )
                    }
            }
        val minimumIdle = configuration.strictInteger("mysql.pool.minimum-idle", 1)
        val maximumPoolSize = configuration.strictInteger("mysql.pool.maximum-size", 8)
        val connectionTimeoutMs = configuration.strictLong("mysql.pool.connection-timeout-ms", 10_000L)
        val validationTimeoutMs = configuration.strictLong("mysql.pool.validation-timeout-ms", 5_000L)
        runCatching {
            SqlConnectionConfig(
                host = configuration.strictString("mysql.host", "127.0.0.1"),
                port = configuration.strictInteger("mysql.port", 3306),
                database = configuration.strictString("mysql.database", "common"),
                username = configuration.strictString("mysql.username", "arcduels"),
                password = configuration.strictString("mysql.password", ""),
                sslMode = sslMode,
                minimumIdle = minimumIdle,
                maximumPoolSize = maximumPoolSize,
                connectionTimeoutMs = connectionTimeoutMs,
                socketTimeoutMs = configuration.strictLong("mysql.pool.socket-timeout-ms", 30_000L),
                validationTimeoutMs = validationTimeoutMs,
                maxLifetimeMs = configuration.strictLong("mysql.pool.max-lifetime-ms", 1_700_000L),
                failFast = true,
            )
        }.getOrElse { failure ->
            throw IllegalArgumentException("Invalid mysql connection or pool configuration", failure)
        }
    }

    private fun validateRedisConfiguration(configuration: Configuration) {
        val enabled = configuration.strictBoolean("redis.enabled", false)
        if (!enabled) return
        configuration.strictBoolean("redis.import-arc-credentials", false)
        validateRedisEndpoint(
            host = configuration.strictString("redis.host", "127.0.0.1"),
            port = configuration.strictInteger("redis.port", 6379),
            source = "redis",
        )
        configuration.optionalString("redis.username")
        configuration.optionalString("redis.password")
        ServerId(configuration.strictString("redis.player-list-origin", "proxy"))
        configuration.strictLong("redis.player-list-ttl-seconds", 5L).requireIn(
            "redis.player-list-ttl-seconds",
            2L..30L,
        )
    }

    private fun validatePlayerDataProvider(
        configuration: Configuration,
        huskSyncEnabled: Boolean,
    ): PlayerDataSyncProvider {
        val configured = configuration.strictString("player-data-sync.provider", "AUTO")
        return try {
            PlayerDataSyncProvider.resolve(configured, huskSyncEnabled)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid player-data-sync.provider: ${failure.message}", failure)
        } catch (failure: IllegalStateException) {
            throw IllegalArgumentException("Invalid player-data-sync.provider: ${failure.message}", failure)
        }
    }

    private fun resolveEffectiveRedis(
        plugin: JavaPlugin,
        configuration: Configuration,
        capturedArcRedis: (() -> Configuration?)?,
    ): ArcDuelsEffectiveRedisSettings? {
        if (!configuration.strictBoolean("redis.enabled", false)) return null
        val configured =
            ArcDuelsEffectiveRedisSettings(
                host = configuration.strictString("redis.host", "127.0.0.1"),
                port = configuration.strictInteger("redis.port", 6379),
                username = configuration.optionalString("redis.username")?.takeIf(String::isNotBlank),
                password = configuration.optionalString("redis.password")?.takeIf(String::isNotBlank),
            ).also { validateRedisEndpoint(it.host, it.port, "redis") }
        if (!configuration.strictBoolean("redis.import-arc-credentials", false)) return configured

        val imported =
            if (capturedArcRedis != null) {
                capturedArcRedis()
            } else {
                val arcFolder = plugin.dataFolder.parentFile.resolve("ARC")
                listOf(arcFolder.resolve("modules/redis.yml"), arcFolder.resolve("config.yml"))
                    .firstOrNull(File::isFile)
                    ?.let { source -> YamlConfiguration().apply { load(source) } }
            } ?: throw IllegalArgumentException(
                "redis.import-arc-credentials is enabled but ARC Redis configuration was not found",
            )
        if (imported.contains("redis") && !imported.isConfigurationSection("redis")) {
            throw IllegalArgumentException("Imported ARC Redis redis must be a configuration section")
        }
        val prefix = if (imported.isConfigurationSection("redis")) "redis." else ""
        val host =
            imported.optionalString("${prefix}host")
                ?: imported.optionalString("${prefix}ip")
                ?: configured.host
        val port = imported.optionalInt("${prefix}port") ?: configured.port
        validateRedisEndpoint(host, port, "Imported ARC Redis")
        return ArcDuelsEffectiveRedisSettings(
            host = host,
            port = port,
            username =
                imported.optionalString("${prefix}username")?.takeIf(String::isNotBlank)
                    ?: configured.username,
            password =
                imported.optionalString("${prefix}password")?.takeIf(String::isNotBlank)
                    ?: configured.password,
        )
    }

    private fun validateRedisEndpoint(
        host: String,
        port: Int,
        source: String,
    ) {
        require(host.matches(SAFE_HOST)) { "$source host contains unsupported characters" }
        require(port in 1..65_535) { "$source port must be between 1 and 65535" }
    }

    private fun Configuration.optionalString(path: String): String? {
        if (!contains(path)) return null
        return strictString(path, "")
    }

    private fun Configuration.optionalInt(path: String): Int? {
        if (!contains(path)) return null
        return strictInteger(path, 0)
    }

    private fun Long.requireIn(
        path: String,
        range: LongRange,
    ) {
        require(this in range) { "$path must be between ${range.first} and ${range.last}" }
    }

    private val SAFE_HOST = Regex("[A-Za-z0-9._:\\[\\]-]{1,253}")

    private val SECTION_PATHS =
        listOf(
            "server-display-names",
            "multiplayer",
            "multiplayer.defaults",
            "player-data-sync",
            "post-match",
            "series",
            "recovery",
            "shutdown",
            "locale",
            "celebration",
            "celebration.fireworks",
            "mysql",
            "mysql.inventory-snapshots",
            "mysql.pool",
            "redis",
            "gui",
            "gui.items",
            "arenas",
            "kits",
        )
}

/** Process-aware values used both for bootstrapping resources and for restart fingerprints. */
internal class ArcDuelsRestartOnlyEnvironment(
    val effectiveRedis: ArcDuelsEffectiveRedisSettings?,
    val playerDataProvider: PlayerDataSyncProvider,
)

/** Effective connection identity retained by the running Redis client; diagnostics redact secrets. */
internal class ArcDuelsEffectiveRedisSettings(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
) {
    override fun toString(): String =
        "ArcDuelsEffectiveRedisSettings(host=$host, port=$port, username=<redacted>, password=<redacted>)"
}
