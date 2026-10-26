package ru.ruscrafting.duels.paper

import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.PaperArcRuntime
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.whenCompleteSync
import ru.arc.paper.api.ArcRedisService
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.io.InputStreamReader
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.network.BackendTransferResult
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.runtime.PaperPluginHotload
import ru.arc.logging.ArcLogging
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.redis.RedisConnection
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.player.PaperOfflinePlayerData
import ru.arc.paper.sql.PaperSqlServices
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.ruscrafting.duels.domain.ChallengeRegistry
import ru.ruscrafting.duels.domain.DuelEventPublisher
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelPresetRepository
import ru.ruscrafting.duels.domain.InMemoryStatisticsRepository
import ru.ruscrafting.duels.domain.MatchCompletedEvent
import ru.ruscrafting.duels.domain.MatchCoordinator
import ru.ruscrafting.duels.domain.MultiplayerMatchRepository
import ru.ruscrafting.duels.domain.NoOpDuelEventPublisher
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.PlayerStateEscrow
import ru.ruscrafting.duels.domain.PlayerStateEscrowRepository
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.StatisticsRepository
import ru.ruscrafting.duels.mysql.MySqlStatisticsRepository
import ru.ruscrafting.duels.mysql.MySqlDuelMigrations
import ru.ruscrafting.duels.redis.ArenaNodeStatus
import ru.ruscrafting.duels.redis.CrossServerChallengeBus
import ru.ruscrafting.duels.redis.CrossServerGroupBus
import ru.ruscrafting.duels.redis.CrossServerDuelBus
import ru.ruscrafting.duels.redis.NetworkArenaDirectory
import ru.ruscrafting.duels.redis.NetworkPlayerDirectory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

open class ArcDuelsPlugin : JavaPlugin() {
    private lateinit var worker: BoundedAsyncExecutor
    private lateinit var nativeWork: BudgetedTaskQueue
    private var repository: MySqlStatisticsRepository? = null
    private var redisClient: PaperRedisClient? = null
    private var blockRollback: DuelBlockRollback? = null
    private var retentions = CompletableFuture.completedFuture(Unit)
    private var shutdownDrain = CompletableFuture.completedFuture(Unit)
    private var storageDrain = CompletableFuture.completedFuture(Unit)
    private var installedScheduler: ru.arc.core.TaskScheduler? = null
    private val hotload = PaperPluginHotload(this) {
        val failure = runCatching(::stopRuntime).exceptionOrNull()
        linkedMapOf(
            "storage" to storageDrain,
            "recovery" to retentions,
            "close" to (failure?.let { CompletableFuture.failedFuture<Unit>(it) }
                ?: CompletableFuture.completedFuture(Unit)),
        )
    }
    @Volatile private var preparedConfiguration: YamlConfiguration? = null
    private var pluginRuntime: PaperPluginRuntime? = null
    private var sessions: DuelSessionManager? = null
    private var challengeRegistry: ChallengeRegistry? = null
    private var runtimeSettingsState: ArcDuelsRuntimeSettingsState? = null
    private var configReloader: ArcDuelsConfigReloader? = null

    private var readiness = CompletableFuture<Unit>()
    internal fun ready(): CompletableFuture<Unit> = readiness.copy()
    internal fun drained(): CompletableFuture<Unit> = shutdownDrain.copy()

    override fun getConfig(): FileConfiguration = requireNotNull(preparedConfiguration) { "ArcDuels configuration is preparing" }

    override fun onEnable() {
        try {
            hotload.beginGeneration()
            hotload.register()
        } catch (failure: Throwable) {
            logger.log(java.util.logging.Level.SEVERE, "ArcDuels startup refused: previous cleanup must finish successfully", failure)
            server.pluginManager.disablePlugin(this)
            return
        }
        PaperArcRuntime.installScheduling(this)
        installedScheduler = ru.arc.core.Tasks.scheduler
        readiness = CompletableFuture()
        preparedConfiguration = null
        shutdownDrain = CompletableFuture()
        storageDrain = CompletableFuture.completedFuture(Unit)
        retentions = CompletableFuture.completedFuture(Unit)
        val lifecycle = PaperPluginRuntime(this, "arc-duels").also { pluginRuntime = it; it.start("version" to pluginMeta.version) }
        worker = BoundedAsyncExecutor("ArcDuels-storage", 1, 256)
        nativeWork = BudgetedTaskQueue(lifecycle.tasks, 512, 4_000_000L, System::nanoTime, 4096)
        lifecycle.own(PaperSqlServices.installConsumer(this, shutdownDrain))
        val redisService = server.servicesManager.load(ArcRedisService::class.java)
        val hostReady = try { ArcDungeonsHostReadiness.ready(server) }
            catch (failure: Throwable) { failStartup(lifecycle, failure); return }
        // Host readiness may itself need Paper callbacks; maintenance must not join it on this thread.
        hostReady.whenCompleteSync(lifecycle.tasks) { _, hostFailure ->
            if (hostFailure != null) { failStartup(lifecycle, hostFailure); return@whenCompleteSync }
            try {
                val candidate = prepareBootstrap()
                preparedConfiguration = candidate.configuration
                val environment = ArcDuelsRestartOnlySettingsValidator.validateReloadEnvironment(this, candidate.configuration) { candidate.arcRedis }
                val persistence = createPersistence(candidate.configuration)
                val redis = if (!candidate.configuration.getBoolean("redis.enabled", false)) null else {
                    val endpoint = requireNotNull(environment.effectiveRedis)
                    PaperRedisClient.open(requireNotNull(redisService) { "ARC Redis service is unavailable" }, "ArcDuels",
                        RedisConnection(endpoint.host, endpoint.port, endpoint.username, endpoint.password),
                        candidate.configuration.getString("server-id", "duels-1")!!).also { it.retainDuringShutdown(); redisClient = it }
                }
                val kits = KitRegistry.loadCandidate(this, candidate.configuration, candidate.loadouts,
                    bundledLoadouts = candidate.bundledLoadouts)
                val arenas = PaperArenaCatalog.load(this, candidate.configuration,
                    preferredArenaSelection = ArcDuelsRuntimeSettingsParser.parsePreferredArenaSelection(candidate.configuration))
                arenas.prepareNavigationForMaintenance()
                bootstrap(lifecycle, candidate, environment, persistence, redis, kits, arenas)
            } catch (failure: Throwable) {
                failStartup(lifecycle, failure)
            }
        }
    }

    private data class PreparedBootstrap(
        val configuration: YamlConfiguration,
        val locales: LocaleService,
        val menus: ArcDuelsMenuLayouts,
        val loadouts: YamlConfiguration,
        val bundledLoadouts: YamlConfiguration,
        val arcRedis: YamlConfiguration?,
    )

    /** Explicit startup maintenance may prepare files and parse configuration directly. */
    private fun prepareBootstrap(): PreparedBootstrap {
        val root = dataFolder.toPath()
        Files.createDirectories(root)
        fun bundled(resource: String): YamlConfiguration = requireNotNull(getResource(resource)).use {
            YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))
        }
        val file = root.resolve("config.yml")
        if (!Files.exists(file)) requireNotNull(getResource("config.yml")).use { Files.copy(it, file) }
        ArcDuelsConfigDefaults.mergeMissing(root)
        val configuration = YamlConfiguration().apply { load(file.toFile()); setDefaults(bundled("config.yml")) }
        DuelLog.install(this)
        ru.arc.config.Config(root, "loadouts.yml").mergeMissingFromBundled("loadouts.yml")
        val loadouts = YamlConfiguration().apply { load(root.resolve("loadouts.yml").toFile()) }
        val arcRoot = root.parent.resolve("ARC")
        val arcRedis = listOf(arcRoot.resolve("modules/redis.yml"), arcRoot.resolve("config.yml"))
            .firstOrNull { Files.isRegularFile(it) }?.let { path -> YamlConfiguration().apply { load(path.toFile()) } }
        return PreparedBootstrap(configuration, LocaleService.prepare(this, configuration), ArcDuelsMenuLayouts.load(this),
            loadouts, bundled("loadouts.yml"), arcRedis)
    }

    private fun failStartup(lifecycle: PaperPluginRuntime, failure: Throwable) {
        if (pluginRuntime !== lifecycle) return
        readiness.completeExceptionally(failure)
        logger.log(java.util.logging.Level.SEVERE, "ArcDuels could not start", failure)
        lifecycle.health.markDown()
        lifecycle.emitHealth()
        server.pluginManager.disablePlugin(this)
    }

    /** Captures immutable scalar paths on Paper; YAML encoding, atomic commit and readback are worker-only. */
    private fun persistConfiguration(): CompletableFuture<Unit> {
        check(server.isPrimaryThread)
        val root = dataFolder.toPath()
        val snapshot = config.getValues(true).filterValues { it !is org.bukkit.configuration.ConfigurationSection }
            .mapValues { (_, value) -> if (value is List<*>) value.toList() else value }
        return worker.submit {
            val text = YamlConfiguration().apply { snapshot.forEach(::set) }.saveToString()
            ru.arc.persistence.AtomicFileStore(root, java.nio.file.Path.of("config.yml"), 8L * 1024 * 1024,
                { value: String -> value.toByteArray(Charsets.UTF_8) }, { bytes -> bytes.toString(Charsets.UTF_8) },
                { value -> YamlConfiguration().loadFromString(value) }).write(text)
            Unit
        }
    }

    /** Only cached native profiles are read on Paper; exact missing UUIDs use bounded worker NBT IO. */
    internal fun loadPlayerNames(ids: Collection<UUID>): CompletableFuture<Map<UUID, String>> {
        val lifecycle = pluginRuntime ?: return CompletableFuture.failedFuture(IllegalStateException("ArcDuels is stopping"))
        val activeWorker = worker
        if (ids.isEmpty()) return CompletableFuture.completedFuture(emptyMap())
        val token = lifecycle.tasks.token()
        return lifecycle.tasks.callSync(token) {
            val cached = ids.associateWith { id -> server.getPlayer(id)?.name ?: server.getOfflinePlayer(id).playerProfile.name }
            val missing = cached.filterValues { it == null }.keys
            Triple(cached, missing, if (missing.isEmpty()) null else PaperOfflinePlayerData.capture(server))
        }.thenCompose { (cached, missing, offline) -> activeWorker.submit {
            val saved = offline?.names(missing)
            if (saved?.failures?.isNotEmpty() == true) {
                logger.warning("Could not read ${saved.failures.size} requested offline player names")
            }
            cached.mapValues { (id, name) -> name ?: saved?.names?.get(id) ?: id.toString().take(8) }
        } }.thenCompose { prepared -> lifecycle.tasks.callSync(token) { prepared } }
    }

    override fun onDisable() {
        try { hotload.prepare() } finally { hotload.close() }
    }

    private fun stopRuntime() {
        val lifecycle = pluginRuntime ?: return
        pluginRuntime = null
        if (!readiness.isDone) readiness.cancel(false)
        DuelLog.info("plugin-disable", "shutting down runtime-owned sessions and resources")
        var failure: Throwable? = null
        fun attempt(operation: () -> Unit) {
            try { operation() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
            }
        }
        attempt { lifecycle.tasks.close() }
        attempt { nativeWork.close() }
        attempt { lifecycle.close() }
        sessions = null
        challengeRegistry = null
        configReloader = null
        runtimeSettingsState = null
        // Scope cancellation precedes the FIFO drain. These final operations use detached data/JDBC only.
        attempt {
            storageDrain = worker.closeAsync {
                var terminalFailure: Throwable? = null
                fun finish(operation: () -> Unit) {
                    try { operation() } catch (error: Throwable) {
                        val previous = terminalFailure
                        if (previous == null) terminalFailure = error else if (previous !== error) previous.addSuppressed(error)
                    }
                }
                finish { blockRollback?.flushPending() }
                finish { retentions.join() }
                finish { redisClient?.close() }
                finish { repository?.close() }
                terminalFailure?.let { throw it }
            }
            storageDrain.join()
        }
        attempt { hotload.retireGameplayRegistrations() }
        val scheduler = installedScheduler.also { installedScheduler = null }
        attempt { if (scheduler != null && runCatching { ru.arc.core.Tasks.scheduler }.getOrNull() === scheduler) ru.arc.core.Tasks.reset() }
        attempt { ArcLogging.close() }
        val terminalFailure = failure
        if (terminalFailure == null) {
            shutdownDrain.complete(Unit)
            blockRollback = null
            redisClient = null
            repository = null
        } else {
            // Retain failed detached records in their existing owner; do not start a replacement runtime.
            shutdownDrain.completeExceptionally(terminalFailure)
            logger.log(java.util.logging.Level.SEVERE, "ArcDuels terminal recovery drain failed", terminalFailure)
            throw terminalFailure
        }
    }

    /** Returns whether the player currently has an unexpired incoming duel challenge. */
    fun hasIncomingChallenge(playerId: UUID): Boolean =
        challengeRegistry?.hasIncomingChallenge(PlayerId(playerId)) == true

    private fun bootstrap(lifecycle: PaperPluginRuntime, prepared: PreparedBootstrap,
        restartOnlyEnvironment: ArcDuelsRestartOnlyEnvironment, persistence: Persistence, redis: PaperRedisClient?,
        kits: KitRegistry, arenas: PaperArenaCatalog) {
        val initialRuntime =
            ArcDuelsRuntimeSettingsParser.parse(
                config,
                restartOnlyEnvironment.effectiveRedis,
                restartOnlyEnvironment.playerDataProvider,
            )
        val liveRuntime = ArcDuelsRuntimeSettingsState(initialRuntime).also { runtimeSettingsState = it }
        val settings = initialRuntime.settings
        val serverId = ServerId(config.getString("server-id", server.name)!!)
        DuelLog.info("plugin-bootstrap", "server={} version={}", serverId.value, pluginMeta.version)
        val locales = prepared.locales
        val serverNames = ServerDisplayNames.load(config, logger::warning)
        serverNames.display(serverId)
        val guiItems = GuiItemCatalog.load(config)
        val menuLayouts = prepared.menus
        val statistics = persistence.statistics
        val network = createNetwork(serverId, statistics, locales, lifecycle, redis)
        val transfer = if (network.challenges != null || network.groups != null) ProxyPlayerTransfer(this) else null
        if (transfer != null) lifecycle.own(transfer)
        val battlePass = BattlePassIntegration(this)
        val coordinator =
            MatchCoordinator(
                serverId,
                arenas,
                statistics,
                battlePass.observing(network.publisher),
                Clock.systemUTC(),
            )
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
                nativeWork = nativeWork,
                worker = worker,
            )
        val huskSyncEnabled = server.pluginManager.isPluginEnabled("HuskSync")
        val syncProvider = restartOnlyEnvironment.playerDataProvider
        val playerDataSync = PlayerDataSyncGate(explicitSyncRequired = syncProvider.requiresReadinessEvent)
        server.pluginManager.registerEvents(playerDataSync, this)
        if (syncProvider == PlayerDataSyncProvider.HUSKSYNC) {
            val huskSyncListener = HuskSyncReadinessListener(playerDataSync)
            server.pluginManager.registerEvents(huskSyncListener, this)
            server.onlinePlayers.forEach(huskSyncListener::inspectAlreadyOnline)
            logger.info("Player data synchronization provider: HUSKSYNC; duel transfer and recovery wait for synchronization")
        } else {
            if (huskSyncEnabled) logger.warning("HuskSync is enabled but ArcDuels is explicitly configured with player-data-sync.provider=NONE")
            logger.info("Player data synchronization provider: NONE; this node will host cross-server kit arenas only")
        }
        val blockRollback = DuelBlockRollback(this, lifecycle.tasks, nativeWork, worker, server.structureManager)
        this.blockRollback = blockRollback
        val pendingLoad = if (persistence.durable && settings.playerStateMode == PlayerStateMode.PRESERVE)
            playerStates.loadPending(config.getLong("mysql.pool.connection-timeout-ms", 10_000L) + 30_000L)
        else CompletableFuture.completedFuture(0)
        blockRollback.recover().thenCombine(pendingLoad) { _, recovered -> recovered }
            .whenCompleteSync(lifecycle.tasks) { recovered, failure ->
                if (failure != null) { failStartup(lifecycle, failure); return@whenCompleteSync }
                if (recovered != null && recovered > 0) logger.warning("Loaded $recovered pending player state snapshot(s) for crash recovery")
                if (persistence.durable) {
                    val purge = { playerStates.purgeExpired().whenComplete { _, error ->
                        if (error != null) logger.log(java.util.logging.Level.WARNING, "Could not purge retained player snapshots", error)
                    }; Unit }
                    purge()
                    lifecycle.tasks.runTimer(cleanupMinutes * 1_200L, cleanupMinutes * 1_200L, purge)
                } else logger.severe("MySQL is disabled: duel starts are locked because durable player state escrow is mandatory")
        val cmiCombatTags = CmiCombatTagIntegration(this)
        lifecycle.own(cmiCombatTags)
        val sessionManager =
            DuelSessionManager(
                this,
                coordinator,
                arenas,
                kits,
                playerStates,
                locales,
                settings.countdownSeconds,
                teleportStabilizationTicks = settings.teleportStabilizationTicks,
                serverNames = serverNames,
                remoteRecoveryTransfer = transfer?.let { gateway ->
                    { player, destination ->
                        requireBackendTransferSent(gateway.connect(player, destination), destination)
                    }
                },
                playerDataReady = playerDataSync::isReady,
                recoveryApplyDelayTicks = settings.playerDataSettleDelayTicks,
                syncProvider = syncProvider,
                celebrationDurationTicks = settings.celebrationDurationTicks,
                seriesRoundIntermissionTicks = settings.seriesRoundIntermissionTicks,
                externalCombatTagClear = cmiCombatTags::clear,
                shutdownRecoveryTimeoutMillis = settings.shutdownRecoveryTimeout.toMillis(),
                defaultPostMatchReturnPolicy = settings.defaultPostMatchReturnPolicy,
                playerStateMode = settings.playerStateMode,
                localServerId = serverId,
                runtimeSettings = { liveRuntime.snapshot().settings },
                findRecordedMatch = persistence.statistics::findMatch,
                findMultiplayerResult = persistence.multiplayerResults::findResult,
                blockRollback = blockRollback,
            )
        sessions = sessionManager
        lifecycle.own(AutoCloseable { try { sessionManager.shutdown() } finally { retentions = playerStates.drainRetentions() } })
        val multiplayerChunkTickets = lifecycle.own(PaperChunkTicketRegistry(this))
        val multiplayerSessions =
            MultiplayerSessionManager(
                serverId = serverId,
                arenas = arenas,
                kits = kits,
                playerStates = playerStates,
                results = persistence.multiplayerResults,
                locales = locales,
                tasks = lifecycle.tasks,
                chunkTickets = multiplayerChunkTickets,
                countdownSeconds = settings.countdownSeconds,
                nativeWork = nativeWork,
                recoveryAvailable = { blockRollback.available },
                playerStateMode = settings.playerStateMode,
                runtimeSettings = { liveRuntime.snapshot().settings },
                externalCombatTagClear = cmiCombatTags::clear,
                syncProvider = syncProvider,
                networkReturn = { player, destination ->
                    requireBackendTransferSent(transfer?.connect(player, destination), destination)
                },
            )
        network.groups?.let { groupBus ->
            lifecycle.own(RemoteGroupArena(serverId, groupBus, multiplayerSessions, sessionManager, kits,
                lifecycle.tasks, server::getPlayer, playerDataSync::isReady))
        }
        sessionManager.attachExternalEngagement(multiplayerSessions::isEngaged)
        lifecycle.own(multiplayerSessions)
        // Provider lookup handles absent/old ARC. Once present, policy setup is required:
        // abort startup on failure instead of advertising inert magic kits.
        val staffSpells = try {
            MagicDuelCombatBridge.provider(this)
        } catch (_: LinkageError) {
            null
        }
        staffSpells?.let { spells ->
            lifecycle.own(MagicDuelCombatBridge(this, spells, sessionManager, multiplayerSessions, kits))
        }
        val challenges =
            ChallengeRegistry(
                Clock.systemUTC(),
                settings.challengeTimeout,
            )
        challengeRegistry = challenges
        val targets = DuelTargetDirectory(this, serverId, network.players, ::loadPlayerNames)
        val controller =
            DuelController(
                this,
                challenges,
                sessionManager,
                statistics,
                locales,
                targets,
                serverId,
                serverNames = serverNames,
                challengeBus = network.challenges,
                arenaDirectory = network.arenas,
                transfer = transfer,
                playerDataReady = playerDataSync::isReady,
                transferTimeout = settings.transferTimeout,
                returnPolicy = settings.defaultPostMatchReturnPolicy,
                automaticReturnTimeout = settings.automaticReturnTimeout,
                arenaReturnPolicy = { arenaId -> arenas.get(arenaId).postMatchAction?.asReturnPolicy() },
                rematchWindow = settings.rematchWindow,
                kitAvailable = kits::contains,
                kitFingerprint = kits::fingerprint,
                arenaChoices = { rules ->
                    network.arenas?.choices(rules, rules.kitId?.let(kits::fingerprint)) ?: arenas.choices(serverId, rules)
                },
                runtimeSettings = { liveRuntime.snapshot().settings },
                playerStateMode = settings.playerStateMode,
                preferredArenaSelection = arenas::preferredSelection,
            )
        lifecycle.own(controller)
        lateinit var gui: DuelGuiService
        val multiplayerGui =
            MultiplayerGuiService(
                plugin = this,
                kits = kits,
                sessions = multiplayerSessions,
                duelSessions = sessionManager,
                locales = locales,
                tasks = lifecycle.tasks,
                targets = targets,
                localServer = serverId,
                serverNames = serverNames,
                groupBus = network.groups,
                arenaDirectory = network.arenas,
                preferredArenaSelection = arenas::preferredSelection,
                transfer = transfer,
                playerDataReady = playerDataSync::isReady,
                runtimeSettings = { liveRuntime.snapshot().settings },
                guiItems = guiItems,
                menuLayouts = menuLayouts,
            ) { player ->
                gui.openMain(player)
            }
        lifecycle.own(multiplayerGui)
        var publishArenaStatus: () -> Unit = {}
        val reloader =
            ArcDuelsConfigReloader(
                plugin = this,
                runtime = liveRuntime,
                arenas = arenas,
                kits = kits,
                locales = locales,
                serverNames = serverNames,
                guiItems = guiItems,
                challenges = challenges,
                afterCatalogReplacement = { publishArenaStatus() },
                publishConfiguration = { preparedConfiguration = it },
                commitMenus = { candidate ->
                    multiplayerGui.replaceMenus(candidate)
                    gui.replaceMenus(candidate)
                },
                activity = {
                    ArcDuelsReloadActivity(
                        reservedArenas = arenas.reservedCount(),
                        queuedMatches = arenas.queueSize(),
                        pendingChallenges = challenges.pendingCount,
                        multiplayerSessions = multiplayerSessions.activeCount(),
                        multiplayerFlows = multiplayerGui.activeFlowCount(),
                        duelGuiFlows = gui.activeConfigurationFlowCount(),
                        acceptedMatches = controller.activeAcceptedMatchCount(),
                    )
                },
            ).also { configReloader = it }
        lifecycle.tasks.runTimer(CONFIG_DEFERRED_APPLY_TICKS, CONFIG_DEFERRED_APPLY_TICKS) {
            reloader.applyDeferredIfIdleAsync(worker, nativeWork, lifecycle.tasks)
        }
        val admin =
            DuelAdminCommand(
                this,
                arenas,
                sessionManager,
                locales,
                serverNames,
                controller::hasReturnOffer,
                reloadAction = reloader::reload,
                saveAsync = ::persistConfiguration,
                configGeneration = { liveRuntime.snapshot().generation },
                startupServerId = serverId,
                configurationBusy = {
                    challenges.pendingCount > 0 ||
                        multiplayerSessions.activeCount() > 0 ||
                        multiplayerGui.activeFlowCount() > 0 ||
                        controller.activeAcceptedMatchCount() > 0
                },
                multiplayerSessions = multiplayerSessions,
            )
        gui =
            DuelGuiService(
                this,
                kits,
                statistics,
                persistence.presets,
                sessionManager,
                locales,
                admin,
                targets,
                controller::challenge,
                controller::showStatistics,
                serverNames,
                arenaChoices = { rules ->
                    network.arenas?.choices(rules, rules.kitId?.let(kits::fingerprint)) ?: arenas.choices(serverId, rules)
                },
                multiplayerAction = multiplayerGui::open,
                guiItems = guiItems,
                menuLayouts = menuLayouts,
                runtimeSettings = { liveRuntime.snapshot().settings },
                startupServerId = serverId,
            )
        lifecycle.own(gui)
        val command = DuelCommand(controller, gui, admin, targets, locales, multiplayerGui)
        val pluginCommand = requireNotNull(getCommand("duel")) { "Command /duel is missing from plugin.yml" }
        pluginCommand.setExecutor(command)
        pluginCommand.tabCompleter = command
        server.pluginManager.registerEvents(gui, this)
        server.pluginManager.registerEvents(multiplayerGui, this)
        server.pluginManager.registerEvents(MultiplayerGameplayListener(multiplayerSessions, locales), this)
        server.pluginManager.registerEvents(
            DuelGameplayListener(
                sessionManager,
                locales,
                controller = controller,
                boundaryWarningDistance = settings.boundaryWarningDistance,
                runtimeSettings = { liveRuntime.snapshot().settings },
            ),
            this,
        )
        val worldGuardEnabled = server.pluginManager.isPluginEnabled("WorldGuard")
        server.pluginManager.registerEvents(DuelFluidListener(sessionManager, worldGuardEnabled), this)
        if (worldGuardEnabled) {
            server.pluginManager.registerEvents(WorldGuardDuelListener(sessionManager, logger, multiplayerSessions), this)
            logger.info("WorldGuard duel PvP and arena-fluid compatibility enabled")
        }
        val identities = PlayerIdentityListener(this, statistics)
        server.pluginManager.registerEvents(identities, this)
        server.onlinePlayers.forEach(identities::remember)
        server.onlinePlayers.forEach(sessionManager::handleJoin)
        network.arenas?.let { directory ->
            publishArenaStatus = {
                directory.publish(
                    ArenaNodeStatus(
                        server = serverId,
                        arenas = arenas.advertisements(syncProvider.sharesInventoryBetweenServers),
                        kitFingerprints = kits.fingerprints(),
                        queuedPairs = arenas.queueSize(),
                    ),
                )
            }
            publishArenaStatus()
            lifecycle.tasks.runTimer(ARENA_HEARTBEAT_TICKS, ARENA_HEARTBEAT_TICKS, publishArenaStatus)
        }
        lifecycle.registerHealth("runtime") {
            val mysqlReady = persistence.durable && blockRollback.available
            val redisReady = network.redisReady
            RuntimeHealthContribution(
                state = if (mysqlReady && redisReady) RuntimeHealthState.UP else RuntimeHealthState.DEGRADED,
                recoveryBacklog =
                    if (settings.playerStateMode == PlayerStateMode.PRESERVE) playerStates.pendingCount() else 0,
                activeLeases = network.activeLeaseCount() + multiplayerChunkTickets.activeLeaseCount,
                schemas = buildMap {
                    put("player_escrow", DurablePlayerStateService.CORE_ESCROW_FORMAT_VERSION)
                    if (mysqlReady) put("mysql", MySqlDuelMigrations.CURRENT_VERSION)
                },
                dependencies = mapOf("mysql" to mysqlReady, "redis" to redisReady),
            )
        }
        lifecycle.ready(
            "server" to serverId.value,
            "arenas" to arenas.size(),
            "multiplayer" to multiplayerSessions.activeCount(),
            "mysql" to persistence.durable,
            "redis" to network.redisReady,
            "player-state" to settings.playerStateMode.name,
        )
        readiness.complete(Unit)
        lifecycle.reportHealthEvery(HEALTH_REPORT_TICKS)
        logger.info("ArcDuels enabled: ${arenas.size()} arenas, ${kits.all().size} kits, MySQL=${config.getBoolean("mysql.enabled")}, Redis=${config.getBoolean("redis.enabled")}")
        DuelLog.info(
            "plugin-ready",
            "server={} arenas={} kits={} mysql={} redis={} sync_provider={} player_state={}",
            serverId.value,
            arenas.size(),
            kits.all().size,
            config.getBoolean("mysql.enabled"),
            config.getBoolean("redis.enabled"),
            syncProvider,
            settings.playerStateMode,
        )
        if (arenas.size() == 0) {
            if (hasUsableArenaRoute(arenas.size(), network.arenas != null)) {
                logger.info("No local duel arenas are configured; compatible Redis arena routing is enabled")
            } else {
                logger.warning("No enabled duel arenas are configured; challenges cannot start yet")
            }
        }
        if (kits.all().isEmpty()) logger.warning("No kits are configured; only own-inventory mode is available")
        }
    }

    private fun createPersistence(config: FileConfiguration): Persistence {
        if (!config.getBoolean("mysql.enabled", false)) {
            val repository = InMemoryStatisticsRepository()
            return Persistence(repository, repository, UnavailablePlayerStateEscrowRepository, repository, durable = false)
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
        this.repository = repository
        return Persistence(repository, repository, repository, repository, durable = true)
    }

    private fun createNetwork(
        serverId: ServerId,
        statistics: StatisticsRepository,
        locales: LocaleService,
        lifecycle: PaperPluginRuntime,
        preparedRedis: PaperRedisClient?,
    ): NetworkRuntime {
        if (!config.getBoolean("redis.enabled", false)) return NetworkRuntime(NoOpDuelEventPublisher, redisReady = true)
        val manager = requireNotNull(preparedRedis) { "Redis is enabled but its connection was not prepared" }
        val bus = CrossServerDuelBus(manager, serverId)
        val challengeBus = CrossServerChallengeBus(manager, serverId)
        val groupBus = CrossServerGroupBus(manager, serverId)
        val players =
            NetworkPlayerDirectory(
                manager,
                expectedOrigin = ServerId(config.getString("redis.player-list-origin", "proxy")!!),
                staleAfter = Duration.ofSeconds(config.getLong("redis.player-list-ttl-seconds", 5L).coerceIn(2L, 30L)),
            )
        val arenas = NetworkArenaDirectory(manager, serverId)
        val playerComponents = DuelPlayerComponents(statistics, locales)
        val networkToken = lifecycle.tasks.token()
        bus.subscribe { event ->
            if (!currentRuntimeSettings().broadcastWins) return@subscribe
            if (!isEnabled) return@subscribe
            if (event is MatchCompletedEvent) {
                val names = statistics.findPlayerName(event.winner).thenCombine(statistics.findPlayerName(event.loser), ::Pair)
                val playerStats = statistics.find(event.winner).thenCombine(statistics.find(event.loser), ::Pair)
                names.thenCombine(playerStats) { resolvedNames, resolvedStats -> resolvedNames to resolvedStats }
                    .handle { resolved, _ -> resolved }.thenCompose { resolved ->
                        val missing = buildList {
                            if (resolved?.first?.first == null) add(event.winner.value)
                            if (resolved?.first?.second == null) add(event.loser.value)
                        }
                        loadPlayerNames(missing).handle { fallback, _ -> resolved to fallback.orEmpty() }
                    }.whenCompleteSync(lifecycle.tasks, networkToken) { prepared, failure ->
                            if (failure != null || prepared == null) return@whenCompleteSync
                            val (resolved, fallback) = prepared
                            val winner = resolved?.first?.first ?: fallback[event.winner.value] ?: event.winner.toString().take(8)
                            val loser = resolved?.first?.second ?: fallback[event.loser.value] ?: event.loser.toString().take(8)
                            val recipients = server.onlinePlayers.toList() + server.consoleSender
                            recipients.forEach { recipient ->
                                if (recipient is org.bukkit.entity.Player &&
                                    isMatchParticipant(recipient.uniqueId, event.winner.value, event.loser.value)
                                ) {
                                    return@forEach
                                }
                                val winnerComponent =
                                    if (recipient is org.bukkit.entity.Player) {
                                        playerComponents.component(recipient, event.winner.value, winner, resolved?.second?.first)
                                    } else {
                                        net.kyori.adventure.text.Component.text(winner)
                                    }
                                val loserComponent =
                                    if (recipient is org.bukkit.entity.Player) {
                                        playerComponents.component(recipient, event.loser.value, loser, resolved?.second?.second)
                                    } else {
                                        net.kyori.adventure.text.Component.text(loser)
                                    }
                                val modeComponent =
                                    if (recipient is org.bukkit.entity.Player) {
                                        duelModeComponent(locales, recipient, event.mode, event.objective, event.kitId)
                                    } else {
                                        net.kyori.adventure.text.Component.text(
                                            "${event.objective.name.lowercase().replace("king_of_the_hill", "koth")} / ${event.mode.name.lowercase()}",
                                        )
                                    }
                                recipient.sendMessage(
                                    locales.notice(
                                        recipient,
                                        "network.win",
                                        LocaleService.component("winner", winnerComponent),
                                        LocaleService.component("loser", loserComponent),
                                        LocaleService.text("rating", event.winnerRating),
                                        LocaleService.component("mode", modeComponent),
                                    ),
                                )
                            }
                    }
            }
        }
        try {
            manager.init()
        } catch (failure: Throwable) {
            runCatching(players::close)
            runCatching(arenas::close)
            runCatching(challengeBus::close)
            runCatching(groupBus::close)
            runCatching(bus::close)
            worker.submit { manager.close() }
            logger.warning("Redis is unavailable; ArcDuels will continue without cross-server events: ${failure.javaClass.simpleName}")
            return NetworkRuntime(NoOpDuelEventPublisher, redisReady = false)
        }
        lifecycle.own(AutoCloseable {
            var firstFailure: Throwable? = null
            listOf(players::close, arenas::close, groupBus::close, challengeBus::close, bus::close).forEach { close ->
                runCatching(close).onFailure { failure ->
                    val existing = firstFailure
                    if (existing == null) firstFailure = failure else existing.addSuppressed(failure)
                }
            }
            firstFailure?.let { throw it }
        })
        return NetworkRuntime(bus, players, arenas, challengeBus, groupBus, redisReady = true)
    }

    internal fun currentRuntimeSettings(): ArcDuelsRuntimeSettings =
        requireNotNull(runtimeSettingsState) { "ArcDuels runtime settings are unavailable" }.snapshot().settings

    internal fun currentConfigGeneration(): Long =
        requireNotNull(runtimeSettingsState) { "ArcDuels runtime settings are unavailable" }.snapshot().generation

    internal fun reloadConfiguration(): Result<ArcDuelsReloadReport> =
        requireNotNull(configReloader) { "ArcDuels configuration reloader is unavailable" }.reload()

    internal fun hasDeferredConfigurationCatalogs(): Boolean = configReloader?.hasDeferredCatalogs() == true

    private data class Persistence(
        val statistics: StatisticsRepository,
        val presets: DuelPresetRepository,
        val playerStates: PlayerStateEscrowRepository,
        val multiplayerResults: MultiplayerMatchRepository,
        val durable: Boolean,
    )

    private data class NetworkRuntime(
        val publisher: DuelEventPublisher,
        val players: NetworkPlayerDirectory? = null,
        val arenas: NetworkArenaDirectory? = null,
        val challenges: CrossServerChallengeBus? = null,
        val groups: CrossServerGroupBus? = null,
        val redisReady: Boolean,
    ) {
        fun activeLeaseCount(): Int = (players?.activeLeaseCount() ?: 0) + (arenas?.activeLeaseCount() ?: 0)
    }

    private object UnavailablePlayerStateEscrowRepository : PlayerStateEscrowRepository {
        private fun <T> unavailable(): CompletableFuture<T> =
            CompletableFuture.failedFuture(IllegalStateException("MySQL durable inventory escrow is not configured"))

        override fun save(snapshot: PlayerStateEscrow): CompletableFuture<Unit> = unavailable()

        override fun saveAll(snapshots: List<PlayerStateEscrow>): CompletableFuture<Unit> = unavailable()

        override fun savePair(
            first: PlayerStateEscrow,
            second: PlayerStateEscrow,
        ): CompletableFuture<Unit> = unavailable()

        override fun findPending(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> =
            CompletableFuture.completedFuture(null)

        override fun pending(serverId: ServerId): CompletableFuture<List<PlayerStateEscrow>> =
            CompletableFuture.completedFuture(emptyList())

        override fun findLatestRetained(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?> =
            CompletableFuture.completedFuture(null)

        override fun retainRestored(
            snapshot: PlayerStateEscrow,
            restoredAt: Instant,
            purgeAfter: Instant,
        ): CompletableFuture<Boolean> = unavailable()

        override fun purgeRetained(cutoff: Instant): CompletableFuture<Int> = unavailable()
    }

    private companion object {
        const val ARENA_HEARTBEAT_TICKS = 40L
        const val HEALTH_REPORT_TICKS = 1_200L
        const val CONFIG_DEFERRED_APPLY_TICKS = 20L
    }
}

internal fun requireBackendTransferSent(
    result: BackendTransferResult?,
    destination: ServerId,
) {
    if (!isSuccessfulBackendTransfer(result)) {
        throw IllegalStateException(
            "Backend transfer failed: destination=${destination.value} result=${result?.name ?: "UNAVAILABLE"}",
        )
    }
}

internal fun hasUsableArenaRoute(
    localArenaCount: Int,
    networkArenaRoutingEnabled: Boolean,
): Boolean = localArenaCount > 0 || networkArenaRoutingEnabled

internal fun isMatchParticipant(playerId: UUID, winner: UUID, loser: UUID): Boolean =
    playerId == winner || playerId == loser
