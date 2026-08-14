package ru.ruscrafting.duels.paper

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
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.PlayerStateEscrow
import ru.ruscrafting.duels.domain.PlayerStateEscrowRepository
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.StatisticsRepository
import ru.ruscrafting.duels.mysql.MySqlStatisticsRepository
import ru.ruscrafting.duels.redis.CrossServerDuelBus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
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
        val locales = LocaleService.load(this)
        val arenas = PaperArenaCatalog.load(this)
        val kits = KitRegistry.load(this)
        val persistence = createPersistence()
        val statistics = persistence.statistics
        val publisher = createNetwork(serverId, statistics, locales)
        val coordinator = MatchCoordinator(serverId, arenas, statistics, publisher, Clock.systemUTC())
        val retentionDays = config.getLong("mysql.inventory-snapshots.retention-days", 7L)
        require(retentionDays in 1L..3_650L) { "mysql.inventory-snapshots.retention-days must be between 1 and 3650" }
        val cleanupMinutes = config.getLong("mysql.inventory-snapshots.cleanup-interval-minutes", 60L)
        require(cleanupMinutes in 1L..10_080L) {
            "mysql.inventory-snapshots.cleanup-interval-minutes must be between 1 and 10080"
        }
        val playerStates =
            DurablePlayerStateService(
                this,
                serverId,
                persistence.playerStates,
                retention = Duration.ofDays(retentionDays),
            )
        if (persistence.durable) {
            val recovered = playerStates.loadPending(config.getLong("mysql.pool.connection-timeout-ms", 10_000L) + 30_000L)
            if (recovered > 0) logger.warning("Loaded $recovered pending player state snapshot(s) for crash recovery")
            val purge = Runnable {
                playerStates.purgeExpired().whenComplete { deleted, failure ->
                    if (failure != null) {
                        logger.warning("Could not purge expired retained player snapshots: ${failure.message}")
                    } else if (deleted > 0) {
                        logger.info("Purged $deleted expired retained player snapshot(s)")
                    }
                }
            }
            purge.run()
            server.scheduler.runTaskTimer(this, purge, cleanupMinutes * 1_200L, cleanupMinutes * 1_200L)
        } else {
            logger.severe("MySQL is disabled: duel starts are locked because durable player state escrow is mandatory")
        }
        val sessionManager = DuelSessionManager(this, coordinator, arenas, kits, playerStates, locales)
        sessions = sessionManager
        val challenges =
            ChallengeRegistry(
                Clock.systemUTC(),
                Duration.ofSeconds(config.getLong("challenge-timeout-seconds", 45L).coerceIn(5L, 600L)),
            )
        val controller = DuelController(this, challenges, sessionManager, statistics, locales)
        val admin = DuelAdminCommand(this, arenas, sessionManager, locales)
        val gui = DuelGuiService(this, kits, statistics, sessionManager, locales, admin, controller::challenge, controller::showStatistics)
        val command = DuelCommand(controller, gui, admin, locales)
        val pluginCommand = requireNotNull(getCommand("duel")) { "Command /duel is missing from plugin.yml" }
        pluginCommand.setExecutor(command)
        pluginCommand.tabCompleter = command
        server.pluginManager.registerEvents(gui, this)
        server.pluginManager.registerEvents(DuelGameplayListener(sessionManager, locales), this)
        val identities = PlayerIdentityListener(this, statistics)
        server.pluginManager.registerEvents(identities, this)
        server.onlinePlayers.forEach(identities::remember)
        server.onlinePlayers.forEach(sessionManager::handleJoin)
        logger.info("ArcDuels enabled: ${arenas.size()} arenas, ${kits.all().size} kits, MySQL=${config.getBoolean("mysql.enabled")}, Redis=${config.getBoolean("redis.enabled")}")
        if (arenas.size() == 0) logger.warning("No enabled duel arenas are configured; challenges cannot start yet")
        if (kits.all().isEmpty()) logger.warning("No kits are configured; only own-inventory mode is available")
    }

    private fun createPersistence(): Persistence {
        if (!config.getBoolean("mysql.enabled", false)) {
            return Persistence(InMemoryStatisticsRepository(), UnavailablePlayerStateEscrowRepository, durable = false)
        }
        val sslMode =
            runCatching { SqlSslMode.valueOf(config.getString("mysql.ssl-mode", "VERIFY_IDENTITY")!!.uppercase()) }
                .getOrElse { error("Invalid mysql.ssl-mode") }
        val connection =
            SqlConnectionConfig(
                host = config.getString("mysql.host", "127.0.0.1")!!,
                port = config.getInt("mysql.port", 3306),
                database = config.getString("mysql.database", "common")!!,
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
        return Persistence(repository, repository, durable = true)
    }

    private fun createNetwork(
        serverId: ServerId,
        statistics: StatisticsRepository,
        locales: LocaleService,
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
                                val recipients = server.onlinePlayers.toList() + server.consoleSender
                                recipients.forEach { recipient ->
                                    recipient.sendMessage(
                                        locales.component(
                                            recipient,
                                            "network.win",
                                            LocaleService.text("winner", winner),
                                            LocaleService.text("loser", loser),
                                            LocaleService.text("rating", event.winnerRating),
                                        ),
                                    )
                                }
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

    private data class Persistence(
        val statistics: StatisticsRepository,
        val playerStates: PlayerStateEscrowRepository,
        val durable: Boolean,
    )

    private object UnavailablePlayerStateEscrowRepository : PlayerStateEscrowRepository {
        private fun <T> unavailable(): CompletableFuture<T> =
            CompletableFuture.failedFuture(IllegalStateException("MySQL durable inventory escrow is not configured"))

        override fun savePair(
            first: PlayerStateEscrow,
            second: PlayerStateEscrow,
        ): CompletableFuture<Unit> = unavailable()

        override fun findPending(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> =
            CompletableFuture.completedFuture(null)

        override fun pending(serverId: ServerId): CompletableFuture<List<PlayerStateEscrow>> =
            CompletableFuture.completedFuture(emptyList())

        override fun retainRestored(
            snapshot: PlayerStateEscrow,
            restoredAt: Instant,
            purgeAfter: Instant,
        ): CompletableFuture<Boolean> = unavailable()

        override fun purgeRetained(cutoff: Instant): CompletableFuture<Int> = unavailable()
    }
}
