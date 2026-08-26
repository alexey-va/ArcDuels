package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
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
    private val defaultLanguage: String,
    private val useClientLocale: Boolean,
    private val bundles: Map<String, YamlConfiguration>,
    private val renderer: LocalizedMiniMessage,
) {
    fun component(
        audience: CommandSender?,
        key: String,
        vararg values: LocaleValue,
    ): Component = renderer.render(key, language(audience), values.asMap())

    fun componentForLanguage(
        language: String,
        key: String,
        vararg values: LocaleValue,
    ): Component = renderer.render(key, normalize(language), values.asMap())

    /** A blank locale value deliberately disables this feedback surface. */
    fun optionalComponent(
        audience: CommandSender?,
        key: String,
        vararg values: LocaleValue,
    ): Component? = renderer.renderOptional(key, language(audience), values.asMap())

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
    ): List<Component> =
        renderer.renderLines(key, language(audience), values.asMap())
            .ifEmpty { listOf(component(audience, key, *values)) }

    fun language(audience: CommandSender?): String =
        if (useClientLocale && audience is Player) normalize(audience.locale().language) else defaultLanguage

    fun hasKey(language: String, key: String): Boolean = bundles[normalize(language)]?.contains(key) == true

    private fun Array<out LocaleValue>.asMap(): Map<String, Component> {
        require(map(LocaleValue::name).distinct().size == size) { "Duplicate locale placeholder" }
        return associate { it.name to it.component }
    }

    private fun normalize(language: String): String = if (language.lowercase(Locale.ROOT).startsWith("ru")) "ru" else "en"

    companion object {
        fun load(plugin: JavaPlugin): LocaleService {
            val languages = listOf("ru", "en")
            languages.forEach { language ->
                val localeFile = File(plugin.dataFolder, "lang/$language.yml")
                if (!localeFile.isFile) plugin.saveResource("lang/$language.yml", false)
            }
            val bundles =
                languages.associateWith { language ->
                    val external = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, "lang/$language.yml"))
                    val bundled =
                        requireNotNull(plugin.getResource("lang/$language.yml")) { "Missing bundled ArcDuels locale $language" }
                            .use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
                    external.setDefaults(bundled)
                    external
                }
            val configured = plugin.config.getString("locale.default", "ru").orEmpty().lowercase(Locale.ROOT)
            val defaultLanguage = if (configured in bundles) configured else "ru"
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
            return LocaleService(defaultLanguage, plugin.config.getBoolean("locale.use-client-locale", true), bundles, renderer)
        }

        fun text(key: String, value: Any): LocaleValue = LocaleValue(key, Component.text(value.toString()))

        fun component(key: String, value: Component): LocaleValue = LocaleValue(key, value)
    }

    private class YamlLocaleCatalog(
        private val bundle: YamlConfiguration,
    ) : LocaleCatalog {
        override fun scalar(path: String): String? = bundle.getString(path)

        override fun lines(path: String): List<String>? = bundle.getStringList(path).takeIf(List<String>::isNotEmpty)
    }
}
