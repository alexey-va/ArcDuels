package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader

class LocaleParityTest : StringSpec({
    "Russian and English bundles have identical nonblank translatable leaves" {
        val ru = loadBundle("ru")
        val en = loadBundle("en")
        val ruLeaves = leaves(ru)
        val enLeaves = leaves(en)

        ruLeaves.keys shouldContainExactlyInAnyOrder enLeaves.keys
        val miniMessage = MiniMessage.builder().strict(true).build()
        for ((key, values) in ruLeaves) {
            values.size shouldBe enLeaves.getValue(key).size
            values.any(String::isNotBlank) shouldBe true
            enLeaves.getValue(key).any(String::isNotBlank) shouldBe true
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
            bundle.getString("identity") shouldBe "<#92bed8>⚔</#92bed8> <#666666>•</#666666> "
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
    input.replace(Regex("<(page|pages|active|waiting|player|players|position|rating|wins|losses|value|seconds|state|server|percent|first|second|reason|kit|loadout|winrate|streak|best|winner|loser|permission|arenas|arena|point|world|radius|height|count|spawn1|spawn2|corner1|corner2|hill|id|online-player|objective|bestof|ranked|sudden|projectiles|consumables|pearls|regeneration|hits|score|target|opponent|rules)>"), "value")
