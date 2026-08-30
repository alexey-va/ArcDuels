package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader

class LocaleParityTest : StringSpec({
    "Russian and English bundles have identical leaves with only declared feedback blank" {
        val ru = loadBundle("ru")
        val en = loadBundle("en")
        val ruLeaves = leaves(ru)
        val enLeaves = leaves(en)

        ruLeaves.keys shouldContainExactlyInAnyOrder enLeaves.keys
        val miniMessage = MiniMessage.builder().strict(true).build()
        for ((key, values) in ruLeaves) {
            values.size shouldBe enLeaves.getValue(key).size
            if (key in OPTIONAL_FEEDBACK_KEYS) {
                values shouldBe listOf("")
                enLeaves.getValue(key) shouldBe listOf("")
            } else {
                values.any(String::isNotBlank) shouldBe true
                enLeaves.getValue(key).any(String::isNotBlank) shouldBe true
            }
            (values + enLeaves.getValue(key)).forEach { value ->
                if (value.isNotBlank()) miniMessage.deserialize(replacePlaceholders(value))
            }
        }
    }

    "all player-facing copy stays calm without gradients emphasis tags or shouting" {
        for (language in listOf("ru", "en")) {
            for ((key, values) in leaves(loadBundle(language))) {
                values.forEach { value ->
                    withClue("$language:$key") {
                        ("<gradient" in value) shouldBe false
                        ("<italic" in value) shouldBe false
                        ("<bold" in value) shouldBe false
                        Regex("\\b[\\p{Lu}]{4,}\\b").containsMatchIn(value.replace(Regex("<[^>]+>"), " ")) shouldBe false
                    }
                }
            }
        }
    }

    "chat identity is calm and legacy bracket prefixes cannot return" {
        for (language in listOf("ru", "en")) {
            val bundle = loadBundle(language)
            bundle.getString("identity") shouldBe "<#32d6ff>⚔</#32d6ff> "
            for ((key, values) in leaves(bundle)) {
                values.forEach { value ->
                    withClue("$language:$key") {
                        Regex("\\[(Дуэли|Duels)]", RegexOption.IGNORE_CASE).containsMatchIn(value) shouldBe false
                        ("\\n" in value) shouldBe false
                    }
                }
            }
        }
    }

    "GUI click hints use the canonical button footer" {
        val physicalInput = Regex("(?:Shift \\+ )?(?:ЛКМ|ПКМ|Left click|Right click|left click|right click)")
        val allowedPrefixes =
            listOf(
                "<#8c8c8c>[<#2bba43>▶</#2bba43>]</#8c8c8c> <#2bba43>",
                "<#8c8c8c>[<#c42323>▶</#c42323>]</#8c8c8c> <#c42323>",
            )
        for (language in listOf("ru", "en")) {
            val bundle = loadBundle(language)
            for (key in bundle.getKeys(true)) {
                val values = bundle.getList(key) ?: continue
                values.filterIsInstance<String>().forEach { value ->
                    if (!physicalInput.containsMatchIn(value.replace(Regex("<[^>]+>"), ""))) return@forEach
                    withClue("$language:$key") {
                        allowedPrefixes.any(value::startsWith) shouldBe true
                        ("<#e6fff3> — " in value) shouldBe true
                    }
                }
            }
        }
    }

    "GUI prose stays flush without decorative pseudo bullets" {
        for (language in listOf("ru", "en")) {
            val bundle = loadBundle(language)
            for (key in bundle.getKeys(true)) {
                val values = bundle.getList(key) ?: continue
                values.filterIsInstance<String>().forEach { value ->
                    val plain = value.replace(Regex("<[^>]+>"), "")
                    withClue("$language:$key") {
                        plain.startsWith(" ") shouldBe false
                        ("✖" in plain) shouldBe false
                    }
                }
            }
        }
    }

    "main menu description zones use one body style" {
        for (language in listOf("ru", "en")) {
            val bundle = loadBundle(language)
            for (key in MAIN_MENU_DESCRIPTION_KEYS) {
                bundle.getStringList(key).takeWhile(String::isNotEmpty).forEach { value ->
                    withClue("$language:$key") {
                        value.startsWith("<#e6fff3>") shouldBe true
                        value.endsWith("</#e6fff3>") shouldBe true
                    }
                }
            }
        }
    }

    "player-facing copy does not expose storage implementation details" {
        val forbidden = Regex("(?i)mysql|mariadb|jdbc|redis|database|escrow|баз[а-я]*\\s+данн")
        for (language in listOf("ru", "en")) {
            for ((key, values) in leaves(loadBundle(language))) {
                values.forEach { value ->
                    withClue("$language:$key") {
                        forbidden.containsMatchIn(value) shouldBe false
                    }
                }
            }
        }
    }

    "fallback kit names use the same calm style" {
        val stream = requireNotNull(LocaleParityTest::class.java.getResourceAsStream("/config.yml"))
        val configuration = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
        configuration.getKeys(true)
            .filter { it.startsWith("kits.") && it.endsWith(".display-name") }
            .mapNotNull(configuration::getString)
            .forEach { value ->
                ("<gradient" in value) shouldBe false
                ("<italic" in value) shouldBe false
                ("<bold" in value) shouldBe false
            }
    }
})

private val OPTIONAL_FEEDBACK_KEYS =
    setOf(
        "controller.network-return",
        "session.restored",
    )

private val MAIN_MENU_DESCRIPTION_KEYS =
    setOf(
        "menu.main.challenge-lore",
        "menu.main.leaderboard-lore",
        "menu.main.modes-lore",
        "menu.main.multiplayer-lore",
        "menu.main.kits-lore",
        "menu.main.stats-lore",
        "menu.main.history-lore",
        "menu.main.help-lore",
        "menu.main.recovery-lore",
        "menu.main.admin-lore",
    )

private fun loadBundle(language: String): YamlConfiguration {
    val stream = requireNotNull(LocaleParityTest::class.java.getResourceAsStream("/lang/$language.yml"))
    return stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
}

private fun leaves(configuration: YamlConfiguration): Map<String, List<String>> =
    configuration.getKeys(true).mapNotNull { key ->
        when (val value = configuration.get(key)) {
            is String -> key to listOf(value)
            is List<*> -> key to value.map { requireNotNull(it).toString() }
            else -> null
        }
    }.toMap()

private fun replacePlaceholders(input: String): String =
    input.replace(Regex("<(page|pages|active|waiting|recoveries|player|players|position|rating|wins|losses|value|layout|seconds|state|server|percent|first|second|reason|kit|loadout|winrate|streak|best|winner|loser|permission|arenas|arena|point|world|radius|height|count|spawn1|spawn2|corner1|corner2|hill|id|online-player|objective|bestof|ranked|sudden|projectiles|consumables|pearls|regeneration|hits|score|target|opponent|rules|own|enemy|time|result|actions)>"), "value")
