package ru.ruscrafting.duels.paper

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ChallengeRegistry
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest

internal data class ArcDuelsReloadActivity(
    val reservedArenas: Int,
    val queuedMatches: Int,
    val pendingChallenges: Int,
    val multiplayerSessions: Int,
    val multiplayerFlows: Int,
    val duelGuiFlows: Int,
    val acceptedMatches: Int,
) {
    val blocksCatalogReplacement: Boolean
        get() =
            reservedArenas + queuedMatches + pendingChallenges + multiplayerSessions + multiplayerFlows +
                duelGuiFlows + acceptedMatches > 0
}

internal data class ArcDuelsReloadReport(
    val generation: Long,
    val arenaCount: Int,
    val catalogsDeferred: Boolean,
    val restartRequired: Set<ArcDuelsRestartOnlyField>,
)

/**
 * Prepares every reloadable resource before publishing a new runtime generation.
 *
 * The plugin remains enabled. Existing listeners, subscriptions, task scopes, database pools and
 * Redis connections are deliberately retained. Arena and kit catalogs are held until all flows
 * that may reference their ids are idle; the latest validated candidate then applies automatically.
 */
internal class ArcDuelsConfigReloader(
    private val plugin: JavaPlugin,
    private val runtime: ArcDuelsRuntimeSettingsState,
    private val arenas: PaperArenaCatalog,
    private val kits: KitRegistry,
    private val locales: LocaleService,
    private val serverNames: ServerDisplayNames,
    private val guiItems: GuiItemCatalog,
    private val challenges: ChallengeRegistry,
    private val afterSourceCapture: () -> Unit = {},
    private val beforeCommit: () -> Unit = {},
    private val afterCatalogReplacement: () -> Unit = {},
    private val activity: () -> ArcDuelsReloadActivity,
) {
    private var deferredCatalogs: PreparedCatalogs? = null

    @Synchronized
    fun reload(): Result<ArcDuelsReloadReport> =
        runCatching {
            check(plugin.server.isPrimaryThread) { "ArcDuels configuration must be committed on the primary thread" }
            val expected = runtime.snapshot()
            val sources = captureSources()
            afterSourceCapture()
            val configuration = loadCandidateConfiguration(sources)

            // Every potentially throwing parser runs before JavaPlugin.config or live services change.
            val restartOnlyEnvironment =
                ArcDuelsRestartOnlySettingsValidator.validateReloadEnvironment(
                    plugin,
                    configuration,
                    capturedArcRedis = {
                        sources.firstOptionalYaml(arcRedisSourceFiles(), "ARC Redis configuration")
                    },
                )
            val settings =
                ArcDuelsRuntimeSettingsParser.parse(
                    configuration,
                    restartOnlyEnvironment.effectiveRedis,
                    restartOnlyEnvironment.playerDataProvider,
                )
            val prepared =
                PreparedReload(
                    settings = settings,
                    arenas = PaperArenaCatalog.load(plugin, configuration),
                    kits =
                        KitRegistry.loadCandidate(
                            plugin,
                            configuration,
                            sources.requiredYaml(loadoutsFile(), "ArcDuels loadouts.yml"),
                        ),
                    locales =
                        LocaleService.loadCandidate(
                            plugin,
                            configuration,
                            localeFiles().mapValues { (language, file) ->
                                sources.requiredYaml(file, "ArcDuels locale $language")
                            },
                        ),
                    serverNames = ServerDisplayNames.load(configuration, plugin.logger::warning),
                    guiItems = GuiItemCatalog.load(configuration),
                    sourceRevision = sources.revision,
                )
            // Prove the exact candidate can be copied into Bukkit's live configuration type
            // before the runtime generation is advanced.
            publishValidatedConfiguration(YamlConfiguration(), configuration)
            val catalogsInitiallyDeferred = activity().blocksCatalogReplacement

            beforeCommit()
            require(readSourceRevision() == prepared.sourceRevision) {
                "Configuration sources changed while the candidate was being validated; retry the reload"
            }
            val committed = runtime.commit(expected.generation, prepared.settings)
            check(committed is ArcDuelsRuntimeSettingsCommitResult.Applied) {
                "Configuration changed concurrently; retry the reload"
            }
            // Publish the exact parsed tree. JavaPlugin.reloadConfig() would reread mutable disk
            // state and could pair candidate A services with candidate B plugin.config.
            publishValidatedConfiguration(plugin.config, configuration)

            // These stable service objects are retained by every listener/GUI. Replacing their
            // immutable backing state makes the next render observe the new generation.
            locales.replaceWith(prepared.locales)
            serverNames.replaceWith(prepared.serverNames)
            guiItems.replaceWith(prepared.guiItems)
            challenges.updateTtl(committed.snapshot.settings.challengeTimeout)

            val immediateArenaCount =
                if (catalogsInitiallyDeferred) {
                    null
                } else {
                    arenas.tryReplaceWith(prepared.arenas)
                }
            val catalogsDeferred = immediateArenaCount == null
            val arenaCount =
                if (immediateArenaCount == null) {
                    deferredCatalogs = PreparedCatalogs(prepared.arenas, prepared.kits, prepared.sourceRevision)
                    arenas.size()
                } else {
                    deferredCatalogs = null
                    kits.replaceWith(prepared.kits)
                    notifyCatalogReplacement()
                    immediateArenaCount
                }

            val report =
                ArcDuelsReloadReport(
                    generation = committed.snapshot.generation,
                    arenaCount = arenaCount,
                    catalogsDeferred = catalogsDeferred,
                    restartRequired = committed.restartRequired,
                )
            DuelLog.info(
                "config-reload",
                "version={} status=applied catalogs={} restart={}",
                report.generation,
                if (catalogsDeferred) "deferred" else "applied",
                report.restartRequired.joinToString(",") { it.reportKey }.ifEmpty { "none" },
            )
            report
        }.onFailure { failure ->
            DuelLog.warn(
                "config-reload-failed",
                "version={} error_type={} error={}",
                runtime.snapshot().generation,
                failure.javaClass.simpleName,
                configReloadFailureSummary(failure),
            )
        }

    /** Applies the latest validated arena/loadout candidate once no flow can retain old ids. */
    @Synchronized
    fun applyDeferredIfIdle(): Boolean {
        val prepared = deferredCatalogs ?: return false
        if (activity().blocksCatalogReplacement) return false
        if (readSourceRevision() != prepared.sourceRevision) {
            // A direct admin edit or an operator file update supersedes this deferred generation.
            // Revalidate the newest complete candidate, but retain the last validated deferred
            // catalogs if that newer disk state is malformed.
            DuelLog.info("config-reload-catalogs", "status=stale action=revalidate")
            return reload().getOrNull()?.catalogsDeferred == false
        }
        if (arenas.tryReplaceWith(prepared.arenas) == null) return false
        kits.replaceWith(prepared.kits)
        deferredCatalogs = null
        notifyCatalogReplacement()
        DuelLog.info("config-reload-catalogs", "status=applied arenas={} kits={}", arenas.size(), kits.all().size)
        return true
    }

    internal fun hasDeferredCatalogs(): Boolean = synchronized(this) { deferredCatalogs != null }

    private fun notifyCatalogReplacement() {
        runCatching(afterCatalogReplacement)
            .onFailure { failure ->
                DuelLog.warn(
                    "config-reload-catalog-advertisement-failed",
                    "error_type={} error={}",
                    failure.javaClass.simpleName,
                    configReloadFailureSummary(failure),
                )
            }
    }

    private fun loadCandidateConfiguration(sources: CapturedSources): YamlConfiguration {
        val external = sources.requiredYaml(configFile(), "ArcDuels config.yml")
        val bundled =
            requireNotNull(plugin.getResource("config.yml")) { "Missing bundled ArcDuels config.yml" }
                .use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
        external.setDefaults(bundled)
        return external
    }

    private fun readSourceRevision(): SourceRevision =
        SourceRevision(
            sourceFiles().associate { file -> file.absolutePath to file.sha256OrMissing() },
        )

    private fun captureSources(): CapturedSources =
        CapturedSources(
            sourceFiles().associate { file ->
                file.absolutePath to if (file.isFile) file.readBytes() else null
            },
        )

    private fun sourceFiles(): List<File> =
        listOf(configFile(), loadoutsFile()) + localeFiles().values + arcRedisSourceFiles()

    private fun configFile(): File = File(plugin.dataFolder, "config.yml")

    private fun loadoutsFile(): File = File(plugin.dataFolder, "loadouts.yml")

    private fun localeFiles(): Map<String, File> =
        mapOf(
            "ru" to File(plugin.dataFolder, "lang/ru.yml"),
            "en" to File(plugin.dataFolder, "lang/en.yml"),
        )

    private fun arcRedisSourceFiles(): List<File> =
        listOf(
            File(plugin.dataFolder.parentFile, "ARC/modules/redis.yml"),
            File(plugin.dataFolder.parentFile, "ARC/config.yml"),
        )

    private data class PreparedReload(
        val settings: ArcDuelsRuntimeSettingsCandidate,
        val arenas: PaperArenaCatalog,
        val kits: KitRegistry,
        val locales: LocaleService,
        val serverNames: ServerDisplayNames,
        val guiItems: GuiItemCatalog,
        val sourceRevision: SourceRevision,
    )

    private data class PreparedCatalogs(
        val arenas: PaperArenaCatalog,
        val kits: KitRegistry,
        val sourceRevision: SourceRevision,
    )

    private data class SourceRevision(val files: Map<String, String>)

    private data class CapturedSources(
        val files: Map<String, ByteArray?>,
    ) {
        val revision =
            SourceRevision(
                files.mapValues { (_, bytes) -> bytes?.sha256() ?: "missing" },
            )

        fun requiredYaml(
            file: File,
            label: String,
        ): YamlConfiguration {
            val bytes = requireNotNull(files[file.absolutePath]) { "Missing $label" }
            return parseYaml(bytes)
        }

        fun firstOptionalYaml(
            candidates: List<File>,
            label: String,
        ): YamlConfiguration? {
            val bytes = candidates.firstNotNullOfOrNull { file -> files[file.absolutePath] } ?: return null
            return runCatching { parseYaml(bytes) }
                .getOrElse { failure -> throw IllegalArgumentException("Invalid $label", failure) }
        }

        private fun parseYaml(bytes: ByteArray): YamlConfiguration =
            YamlConfiguration().apply { loadFromString(bytes.toString(Charsets.UTF_8)) }
    }
}

private fun File.sha256OrMissing(): String {
    if (!isFile) return "missing"
    return readBytes().sha256()
}

private fun ByteArray.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

/** Bounded operator feedback that cannot echo YAML source lines containing credentials. */
internal fun configReloadFailureSummary(failure: Throwable): String {
    val chain = generateSequence(failure) { it.cause }.toList()
    if (chain.any { cause ->
            cause.javaClass.simpleName.contains("InvalidConfiguration", ignoreCase = true) ||
                cause.javaClass.simpleName.contains("Yaml", ignoreCase = true) ||
                cause.javaClass.name.startsWith("org.snakeyaml")
        }
    ) {
        return "invalid YAML configuration"
    }
    val firstLine =
        chain.asSequence()
            .mapNotNull { cause -> cause.message }
            .flatMap { message -> message.lineSequence() }
            .map { line -> line.trim() }
            .firstOrNull(String::isNotEmpty)
            ?: failure.javaClass.simpleName
    if (SENSITIVE_FAILURE_TEXT.containsMatchIn(firstLine)) return "invalid protected configuration value"
    return firstLine.take(MAX_FAILURE_SUMMARY_LENGTH)
}

private val SENSITIVE_FAILURE_TEXT = Regex("(?i)(password|secret|token|credential|private[-_. ]?key)")
private const val MAX_FAILURE_SUMMARY_LENGTH = 200
