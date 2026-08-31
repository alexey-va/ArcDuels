package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags
import org.bukkit.command.CommandSender
import org.bukkit.configuration.Configuration
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.Config
import ru.arc.text.LocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import java.io.File
import java.io.InputStreamReader
import java.util.Locale

data class LocaleValue(
    val name: String,
    val component: Component,
)

class LocaleService private constructor(
    state: State,
) {
    @Volatile
    private var state: State = state

    fun component(
        audience: CommandSender?,
        key: String,
        vararg values: LocaleValue,
    ): Component {
        val current = state
        return current.renderer.render(key, current.language(audience), values.asMap())
    }

    fun componentForLanguage(
        language: String,
        key: String,
        vararg values: LocaleValue,
    ): Component {
        val current = state
        return current.renderer.render(key, normalize(language), values.asMap())
    }

    /** A blank locale value deliberately disables this feedback surface. */
    fun optionalComponent(
        audience: CommandSender?,
        key: String,
        vararg values: LocaleValue,
    ): Component? {
        val current = state
        return current.renderer.renderOptional(key, current.language(audience), values.asMap())
    }

    fun notice(
        audience: CommandSender?,
        key: String,
        vararg values: LocaleValue,
    ): Component = frameNotice(audience, component(audience, key, *values))

    fun optionalNotice(
        audience: CommandSender?,
        key: String,
        vararg values: LocaleValue,
    ): Component? = optionalComponent(audience, key, *values)?.let { frameNotice(audience, it) }

    fun frameNotice(
        audience: CommandSender?,
        body: Component,
    ): Component {
        val indent = Component.text("  ")
        val indentedBody =
            body.replaceText(
                TextReplacementConfig.builder()
                    .matchLiteral("\n")
                    .replacement(Component.newline().append(indent))
                    .build(),
            )
        return Component.newline()
            .append(indent)
            .append(component(audience, "identity"))
            .append(indentedBody)
            .append(Component.newline())
    }

    fun lines(
        audience: CommandSender?,
        key: String,
        vararg values: LocaleValue,
    ): List<Component> {
        val current = state
        val placeholders = values.asMap()
        val language = current.language(audience)
        return current.renderer.renderLines(key, language, placeholders)
            .ifEmpty { listOf(current.renderer.render(key, language, placeholders)) }
    }

    fun language(audience: CommandSender?): String = state.language(audience)

    fun hasKey(language: String, key: String): Boolean = state.bundles[normalize(language)]?.contains(key) == true

    internal fun replaceWith(replacement: LocaleService) {
        state = replacement.state
    }

    private fun Array<out LocaleValue>.asMap(): Map<String, Component> {
        require(map(LocaleValue::name).distinct().size == size) { "Duplicate locale placeholder" }
        return associate { it.name to it.component }
    }

    private fun normalize(language: String): String = if (language.lowercase(Locale.ROOT).startsWith("ru")) "ru" else "en"

    private data class State(
        val defaultLanguage: String,
        val useClientLocale: Boolean,
        val bundles: Map<String, YamlConfiguration>,
        val renderer: LocalizedMiniMessage,
    ) {
        fun language(audience: CommandSender?): String =
            if (useClientLocale && audience is Player) normalizeLanguage(audience.locale().language) else defaultLanguage

        private fun normalizeLanguage(language: String): String =
            if (language.lowercase(Locale.ROOT).startsWith("ru")) "ru" else "en"
    }

    companion object {
        fun load(plugin: JavaPlugin): LocaleService = load(plugin, plugin.config, prepareFiles = true)

        internal fun loadCandidate(
            plugin: JavaPlugin,
            configuration: Configuration,
            capturedBundles: Map<String, YamlConfiguration>? = null,
        ): LocaleService =
            load(
                plugin,
                configuration,
                prepareFiles = false,
                capturedBundles = capturedBundles,
            )

        private fun load(
            plugin: JavaPlugin,
            configuration: Configuration,
            prepareFiles: Boolean,
            capturedBundles: Map<String, YamlConfiguration>? = null,
        ): LocaleService {
            val languages = listOf("ru", "en")
            val persistentConfigs =
                if (prepareFiles) {
                    languages.associateWith { language ->
                        val resource = "lang/$language.yml"
                        Config(plugin.dataFolder.toPath(), resource).also {
                            it.mergeMissingFromBundled(resource)
                        }
                    }
                } else {
                    emptyMap()
                }
            languages.forEach { language ->
                val localeFile = File(plugin.dataFolder, "lang/$language.yml")
                if (capturedBundles?.containsKey(language) != true && !localeFile.isFile) {
                    require(prepareFiles) { "Missing ArcDuels locale $language" }
                    error("ArcCore did not restore ArcDuels locale $language")
                }
            }
            val loadedSources =
                languages.associateWith { language ->
                    val external =
                        capturedBundles?.get(language)
                            ?: YamlConfiguration().apply { load(File(plugin.dataFolder, "lang/$language.yml")) }
                    val bundled =
                        requireNotNull(plugin.getResource("lang/$language.yml")) { "Missing bundled ArcDuels locale $language" }
                            .use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
                    external.setDefaults(bundled)
                    external to bundled
                }
            val allowedPlaceholdersByPath =
                buildMap<String, Set<String>> {
                    putAll(OPTIONAL_PLACEHOLDERS_BY_PATH)
                    loadedSources.values.forEach { (_, bundled) ->
                        bundled.getKeys(true).forEach { path ->
                            val names = bundled.localeStrings(path).flatMap(::customTagNames).toSet()
                            if (names.isNotEmpty()) put(path, get(path).orEmpty() + names)
                        }
                    }
                }
            val allowedPlaceholders = allowedPlaceholdersByPath.values.flatten().toSet() + PREFIX_PLACEHOLDER
            val placeholderResolver =
                TagResolver.builder().apply {
                    allowedPlaceholders.sorted().forEach { name ->
                        resolver(Placeholder.component(name, Component.empty()))
                    }
                }.build()
            val sources =
                loadedSources.mapValues { (language, source) ->
                    val (external, bundled) = source
                    validateLocaleBundle(
                        language = language,
                        effective = bundled,
                        bundled = bundled,
                        allowedPlaceholdersByPath = allowedPlaceholdersByPath,
                        placeholderResolver = placeholderResolver,
                        validateAdditionalValues = false,
                    )
                    if (!prepareFiles) {
                        validateLocaleBundle(
                            language = language,
                            effective = external,
                            bundled = bundled,
                            allowedPlaceholdersByPath = allowedPlaceholdersByPath,
                            placeholderResolver = placeholderResolver,
                        )
                        return@mapValues source
                    }
                    val repairs =
                        invalidBundledLocaleValues(
                            language,
                            external,
                            bundled,
                            allowedPlaceholdersByPath,
                            placeholderResolver,
                        )
                    if (repairs.isEmpty()) {
                        validateLocaleBundle(
                            language = language,
                            effective = external,
                            bundled = bundled,
                            allowedPlaceholdersByPath = allowedPlaceholdersByPath,
                            placeholderResolver = placeholderResolver,
                            validateAdditionalValues = false,
                        )
                        return@mapValues source
                    }
                    val displayedPaths = repairs.keys.take(12).joinToString()
                    val undisplayedCount = repairs.size - minOf(repairs.size, 12)
                    plugin.logger.warning(
                        buildString {
                            append("ArcDuels locale $language restored ${repairs.size} invalid bundled-owned value(s): ")
                            append(displayedPaths)
                            if (undisplayedCount > 0) append(" (+$undisplayedCount more)")
                        },
                    )
                    requireNotNull(persistentConfigs[language]).apply {
                        repairs.forEach(::setStructured)
                        saveStrict()
                    }
                    val repaired = YamlConfiguration().apply {
                        load(File(plugin.dataFolder, "lang/$language.yml"))
                        setDefaults(bundled)
                    }
                    validateLocaleBundle(
                        language = language,
                        effective = repaired,
                        bundled = bundled,
                        allowedPlaceholdersByPath = allowedPlaceholdersByPath,
                        placeholderResolver = placeholderResolver,
                        validateAdditionalValues = false,
                    )
                    repaired to bundled
                }
            val bundles = sources.mapValues { (_, source) -> source.first }
            val defaultLanguage = configuration.strictString("locale.default", "ru").lowercase(Locale.ROOT)
            require(defaultLanguage in bundles) { "locale.default must be ru or en" }
            val useClientLocale = configuration.strictBoolean("locale.use-client-locale", true)
            val renderer =
                LocalizedMiniMessage(
                    catalogs = bundles.mapValues { (_, bundle) -> YamlLocaleCatalog(bundle) },
                    defaultLocale = { defaultLanguage },
                    prefixPath = "identity",
                    missingMessage = { key ->
                        plugin.logger.warning("Missing ArcDuels locale key '$key'")
                        "<red>[$key]</red>"
                    },
                )
            return LocaleService(
                State(
                    defaultLanguage,
                    useClientLocale,
                    bundles,
                    renderer,
                ),
            )
        }

        fun text(key: String, value: Any): LocaleValue = LocaleValue(key, Component.text(value.toString()))

        fun component(key: String, value: Component): LocaleValue = LocaleValue(key, value)

        private fun validateLocaleBundle(
            language: String,
            effective: YamlConfiguration,
            bundled: YamlConfiguration,
            allowedPlaceholdersByPath: Map<String, Set<String>>,
            placeholderResolver: TagResolver,
            validateAdditionalValues: Boolean = true,
        ) {
            val bundledPaths = bundled.getKeys(true)
            bundledPaths.sorted().forEach { path ->
                validateBundledLocaleValue(
                    language,
                    path,
                    effective.get(path),
                    requireNotNull(bundled.get(path)),
                    allowedPlaceholdersByPath,
                    placeholderResolver,
                )
            }
            if (validateAdditionalValues) {
                (effective.getKeys(true) - bundledPaths).sorted().forEach { path ->
                    validateAdditionalLocaleValue(
                        language,
                        path,
                        effective.get(path),
                        allowedPlaceholdersByPath,
                        placeholderResolver,
                    )
                }
            }
        }

        private fun invalidBundledLocaleValues(
            language: String,
            effective: YamlConfiguration,
            bundled: YamlConfiguration,
            allowedPlaceholdersByPath: Map<String, Set<String>>,
            placeholderResolver: TagResolver,
        ): Map<String, Any> {
            val repairs = linkedMapOf<String, Any>()
            bundled.getKeys(true)
                .sortedWith(compareBy({ it.count { character -> character == '.' } }, { it }))
                .forEach { path ->
                    if (repairs.keys.any { repaired -> path.startsWith("$repaired.") }) return@forEach
                    try {
                        validateBundledLocaleValue(
                            language,
                            path,
                            effective.get(path),
                            requireNotNull(bundled.get(path)),
                            allowedPlaceholdersByPath,
                            placeholderResolver,
                        )
                    } catch (_: IllegalArgumentException) {
                        repairs[path] = structuredLocaleValue(requireNotNull(bundled.get(path)))
                    }
                }
            return repairs
        }

        private fun validateBundledLocaleValue(
            language: String,
            path: String,
            effectiveValue: Any?,
            bundledValue: Any,
            allowedPlaceholdersByPath: Map<String, Set<String>>,
            placeholderResolver: TagResolver,
        ) {
            when (bundledValue) {
                is ConfigurationSection ->
                    require(effectiveValue is ConfigurationSection) {
                        "Locale $language.$path must be a section"
                    }

                is String -> {
                    require(effectiveValue is String) {
                        "Locale $language.$path must be a scalar string"
                    }
                    validateMiniMessage(
                        language,
                        path,
                        path,
                        effectiveValue,
                        allowedPlaceholdersByPath,
                        placeholderResolver,
                    )
                }

                is List<*> -> {
                    requireStringList("Bundled locale", language, path, bundledValue)
                    requireStringList("Locale", language, path, effectiveValue).forEachIndexed { index, line ->
                        validateMiniMessage(
                            language,
                            "$path[$index]",
                            path,
                            line,
                            allowedPlaceholdersByPath,
                            placeholderResolver,
                        )
                    }
                }

                else -> error("Bundled locale $language.$path must be a string, string list, or section")
            }
        }

        private fun structuredLocaleValue(value: Any): Any =
            when (value) {
                is ConfigurationSection -> value.getValues(false).mapValues { (_, child) -> structuredLocaleValue(child) }
                is List<*> -> value.map { child -> structuredLocaleValue(requireNotNull(child)) }
                is String -> value
                else -> error("Bundled locale value must be a string, string list, or section")
            }

        private fun validateAdditionalLocaleValue(
            language: String,
            path: String,
            value: Any?,
            allowedPlaceholdersByPath: Map<String, Set<String>>,
            placeholderResolver: TagResolver,
        ) {
            when (value) {
                is ConfigurationSection -> Unit
                is String ->
                    validateMiniMessage(
                        language,
                        path,
                        path,
                        value,
                        allowedPlaceholdersByPath,
                        placeholderResolver,
                    )
                is List<*> ->
                    requireStringList("Locale", language, path, value).forEachIndexed { index, line ->
                        validateMiniMessage(
                            language,
                            "$path[$index]",
                            path,
                            line,
                            allowedPlaceholdersByPath,
                            placeholderResolver,
                        )
                    }

                else -> throw IllegalArgumentException("Locale $language.$path must be a string, string list, or section")
            }
        }

        private fun requireStringList(
            label: String,
            language: String,
            path: String,
            value: Any?,
        ): List<String> {
            require(value is List<*>) { "$label $language.$path must be a list of strings" }
            return value.mapIndexed { index, element ->
                require(element is String) { "$label $language.$path[$index] must be a string" }
                element
            }
        }

        private fun validateMiniMessage(
            language: String,
            displayPath: String,
            placeholderPath: String,
            value: String,
            allowedPlaceholdersByPath: Map<String, Set<String>>,
            placeholderResolver: TagResolver,
        ) {
            val allowedPlaceholders = allowedPlaceholdersByPath[placeholderPath].orEmpty() + PREFIX_PLACEHOLDER
            val undeclared = customTagNames(value).filterNot(allowedPlaceholders::contains).toSet()
            require(undeclared.isEmpty()) {
                "Locale $language.$displayPath uses undeclared placeholders: ${undeclared.sorted().joinToString()}"
            }
            try {
                strictMiniMessage.deserialize(value, placeholderResolver)
            } catch (exception: RuntimeException) {
                throw IllegalArgumentException("Locale $language.$displayPath contains invalid MiniMessage", exception)
            }
        }

        private fun YamlConfiguration.localeStrings(path: String): List<String> =
            when (val value = get(path)) {
                is String -> listOf(value)
                is List<*> -> value.filterIsInstance<String>()
                else -> emptyList()
            }

        private fun customTagNames(value: String): List<String> =
            localeTag.findAll(value)
                .map { match -> match.groupValues[1].lowercase(Locale.ROOT) }
                .filterNot(standardTags::has)
                .toList()

        private val strictMiniMessage = MiniMessage.builder().strict(true).build()
        private val standardTags = StandardTags.defaults()
        private val localeTag = Regex("(?<!\\\\)</?([a-z0-9_-]{1,64})(?=[:>])", RegexOption.IGNORE_CASE)
        // A blank bundled message is intentionally disabled, so its supported placeholders
        // cannot be inferred from that template. Keep the optional extension surface explicit.
        private val OPTIONAL_PLACEHOLDERS_BY_PATH =
            mapOf(
                "controller.network-return" to setOf("server"),
            )
        private const val PREFIX_PLACEHOLDER = "prefix"
    }

    private class YamlLocaleCatalog(
        private val bundle: YamlConfiguration,
    ) : LocaleCatalog {
        override fun scalar(path: String): String? = bundle.getString(path)

        override fun lines(path: String): List<String>? = bundle.getStringList(path).takeIf(List<String>::isNotEmpty)
    }
}
