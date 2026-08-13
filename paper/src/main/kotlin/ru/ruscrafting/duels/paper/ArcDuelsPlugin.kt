package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.ruscrafting.duels.domain.ChallengeRegistry
import ru.ruscrafting.duels.domain.DuelEventPublisher
import ru.ruscrafting.duels.domain.InMemoryStatisticsRepository
import ru.ruscrafting.duels.domain.MatchCompletedEvent
import ru.ruscrafting.duels.domain.MatchCoordinator
import ru.ruscrafting.duels.domain.NoOpDuelEventPublisher
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.StatisticsRepository
import ru.ruscrafting.duels.mysql.MySqlStatisticsRepository
import ru.ruscrafting.duels.redis.CrossServerDuelBus
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

open class ArcDuelsPlugin : JavaPlugin() {
    private val closeables = mutableListOf<AutoCloseable>()
    private var sessions: DuelSessionManager? = null

    override fun onEnable() {
        saveDefaultConfig()
        runCatching { bootstrap() }
            .onFailure { failure ->
                logger.severe("ArcDuels could not start: ${failure.javaClass.simpleName}: ${failure.message}")
                closeResources()
                server.pluginManager.disablePlugin(this)
            }
    }

    override fun onDisable() {
        sessions?.shutdown()
        sessions = null
        closeResources()
    }

    private fun bootstrap() {
        val serverId = ServerId(config.getString("server-id", server.name)!!)
        val arenas = PaperArenaCatalog.load(this)
        val kits = KitRegistry.load(this)
        val statistics = createStatistics()
        val publisher = createNetwork(serverId, statistics)
        val coordinator = MatchCoordinator(serverId, arenas, statistics, publisher, Clock.systemUTC())
        val sessionManager = DuelSessionManager(this, coordinator, arenas, kits)
        sessions = sessionManager
        val challenges =
            ChallengeRegistry(
                Clock.systemUTC(),
                Duration.ofSeconds(config.getLong("challenge-timeout-seconds", 45L).coerceIn(5L, 600L)),
            )
        val controller = DuelController(this, challenges, sessionManager, statistics)
        val gui = DuelGuiService(this, kits, statistics, controller::challenge)
        val command = DuelCommand(controller, gui)
        val pluginCommand = requireNotNull(getCommand("duel")) { "Command /duel is missing from plugin.yml" }
        pluginCommand.setExecutor(command)
        pluginCommand.tabCompleter = command
        server.pluginManager.registerEvents(gui, this)
        server.pluginManager.registerEvents(DuelGameplayListener(sessionManager), this)
        val identities = PlayerIdentityListener(this, statistics)
        server.pluginManager.registerEvents(identities, this)
        server.onlinePlayers.forEach(identities::remember)
        logger.info("ArcDuels enabled: ${arenas.size()} arenas, ${kits.all().size} kits, MySQL=${config.getBoolean("mysql.enabled")}, Redis=${config.getBoolean("redis.enabled")}")
        if (arenas.size() == 0) logger.warning("No enabled duel arenas are configured; challenges cannot start yet")
        if (kits.all().isEmpty()) logger.warning("No kits are configured; only own-inventory mode is available")
    }

    private fun createStatistics(): StatisticsRepository {
        if (!config.getBoolean("mysql.enabled", false)) return InMemoryStatisticsRepository()
        val sslMode =
            runCatching { SqlSslMode.valueOf(config.getString("mysql.ssl-mode", "VERIFY_IDENTITY")!!.uppercase()) }
                .getOrElse { error("Invalid mysql.ssl-mode") }
        val connection =
            SqlConnectionConfig(
                host = config.getString("mysql.host", "127.0.0.1")!!,
                port = config.getInt("mysql.port", 3306),
                database = config.getString("mysql.database", "arcduels")!!,
                username = config.getString("mysql.username", "arcduels")!!,
                password = config.getString("mysql.password", "")!!,
                sslMode = sslMode,
                minimumIdle = config.getInt("mysql.pool.minimum-idle", 1),
                maximumPoolSize = config.getInt("mysql.pool.maximum-size", 8),
                connectionTimeoutMs = config.getLong("mysql.pool.connection-timeout-ms", 10_000L),
                socketTimeoutMs = config.getLong("mysql.pool.socket-timeout-ms", 30_000L),
                validationTimeoutMs = config.getLong("mysql.pool.validation-timeout-ms", 5_000L),
                maxLifetimeMs = config.getLong("mysql.pool.max-lifetime-ms", 1_700_000L),
                failFast = true,
            )
        val repository = MySqlStatisticsRepository(SqlRuntime.create(connection, "arcduels"))
        try {
            val report = repository.migrate().get(connection.connectionTimeoutMs + 30_000L, TimeUnit.MILLISECONDS)
            logger.info("MySQL schema ready; newly applied migrations: ${report.appliedVersions}")
        } catch (failure: Throwable) {
            repository.close()
            throw IllegalStateException("MySQL is enabled but its schema could not be prepared", failure)
        }
        closeables += repository
        return repository
    }

    private fun createNetwork(
        serverId: ServerId,
        statistics: StatisticsRepository,
    ): DuelEventPublisher {
        if (!config.getBoolean("redis.enabled", false)) return NoOpDuelEventPublisher
        val username = config.getString("redis.username")?.takeIf(String::isNotBlank)
        val password = config.getString("redis.password")?.takeIf(String::isNotBlank)
        val manager =
            RedisManager(
                RedisConnection(
                    config.getString("redis.host", "127.0.0.1")!!,
                    config.getInt("redis.port", 6379),
                    username,
                    password,
                ),
                ServerIdentity { serverId.value },
            )
        val bus = CrossServerDuelBus(manager, serverId)
        if (config.getBoolean("redis.broadcast-wins", true)) {
            bus.subscribe { event ->
                if (!isEnabled) return@subscribe
                if (event is MatchCompletedEvent) {
                    statistics.findPlayerName(event.winner).thenCombine(statistics.findPlayerName(event.loser), ::Pair)
                        .whenComplete { names, _ ->
                            if (!isEnabled) return@whenComplete
                            server.scheduler.runTask(this, Runnable {
                                val winner = names?.first ?: server.getOfflinePlayer(event.winner.value).name ?: event.winner.toString().take(8)
                                val loser = names?.second ?: server.getOfflinePlayer(event.loser.value).name ?: event.loser.toString().take(8)
                                val prefix =
                                    MiniMessage.miniMessage().deserialize(
                                        "<dark_gray>[</dark_gray><gradient:#55ffff:#5555ff><bold>ДУЭЛИ</bold></gradient><dark_gray>]</dark_gray> ",
                                    )
                                server.broadcast(
                                    prefix
                                        .append(Component.text(winner, NamedTextColor.AQUA))
                                        .append(Component.text(" победил ", NamedTextColor.GRAY))
                                        .append(Component.text(loser, NamedTextColor.RED))
                                        .append(Component.text(" (${event.winnerRating})", NamedTextColor.DARK_GRAY)),
                                )
                            })
                        }
                }
            }
        }
        if (!NetworkLifecycle.initialize(manager, bus) { failure ->
                logger.warning("Redis is unavailable; ArcDuels will continue without cross-server events: ${failure.javaClass.simpleName}")
            }
        ) {
            return NoOpDuelEventPublisher
        }
        closeables += AutoCloseable {
            NetworkLifecycle.close(bus, manager).getOrThrow()
        }
        return bus
    }

    private fun closeResources() {
        closeables.asReversed().forEach { resource ->
            runCatching(resource::close).onFailure { logger.warning("Could not close ArcDuels resource: ${it.message}") }
        }
        closeables.clear()
    }
}
