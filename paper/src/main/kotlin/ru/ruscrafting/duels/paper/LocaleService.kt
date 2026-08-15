package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader
import java.util.Locale

class LocaleService private constructor(
    private val plugin: JavaPlugin,
    private val defaultLanguage: String,
    private val useClientLocale: Boolean,
    private val bundles: Map<String, YamlConfiguration>,
) {
    private val miniMessage = MiniMessage.miniMessage()

    fun component(
        audience: CommandSender?,
        key: String,
        vararg resolvers: TagResolver,
    ): Component = miniMessage.deserialize(raw(language(audience), key), *resolvers)

    fun componentForLanguage(
        language: String,
        key: String,
        vararg resolvers: TagResolver,
    ): Component = miniMessage.deserialize(raw(normalize(language), key), *resolvers)

    /**
     * Renders opt-in feedback without producing an empty Adventure component.
     * A blank locale value deliberately disables that feedback surface.
     */
    fun optionalComponent(
        audience: CommandSender?,
        key: String,
        vararg resolvers: TagResolver,
    ): Component? =
        raw(language(audience), key)
            .takeIf(String::isNotBlank)
            ?.let { miniMessage.deserialize(it, *resolvers) }

    /**
     * Renders a top-level player-facing chat notice. GUI labels, titles and
     * action bars intentionally keep using [component] so the chat frame never
     * leaks into other Adventure surfaces.
     */
    fun notice(
        audience: CommandSender?,
        key: String,
        vararg resolvers: TagResolver,
    ): Component = frameNotice(audience, component(audience, key, *resolvers))

    fun optionalNotice(
        audience: CommandSender?,
        key: String,
        vararg resolvers: TagResolver,
    ): Component? = optionalComponent(audience, key, *resolvers)?.let { frameNotice(audience, it) }

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
        vararg resolvers: TagResolver,
    ): List<Component> {
        val language = language(audience)
        val bundle = bundles[language] ?: bundles.getValue(defaultLanguage)
        val values = bundle.getStringList(key).ifEmpty { bundles.getValue(defaultLanguage).getStringList(key) }
        if (values.isEmpty()) return listOf(component(audience, key, *resolvers))
        return values.map { miniMessage.deserialize(it, *resolvers) }
    }

    fun language(audience: CommandSender?): String =
        if (useClientLocale && audience is Player) normalize(audience.locale().language) else defaultLanguage

    fun hasKey(language: String, key: String): Boolean = bundles[normalize(language)]?.contains(key) == true

    private fun raw(language: String, key: String): String =
        bundles[language]?.getString(key)
            ?: bundles.getValue(defaultLanguage).getString(key)
            ?: run {
                plugin.logger.warning("Missing ArcDuels locale key '$key'")
                "<red>[$key]</red>"
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
            return LocaleService(
                plugin = plugin,
                defaultLanguage = defaultLanguage,
                useClientLocale = plugin.config.getBoolean("locale.use-client-locale", true),
                bundles = bundles,
            )
        }

        fun text(key: String, value: Any): TagResolver = Placeholder.unparsed(key, value.toString())

        fun component(key: String, value: Component): TagResolver = Placeholder.component(key, value)
    }
}
