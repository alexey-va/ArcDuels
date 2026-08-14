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

    "inventory GUI copy stays calm without gradients italic tags or shouting" {
        for (language in listOf("ru", "en")) {
            val guiLeaves = leaves(loadBundle(language)).filterKeys { key -> key.startsWith("menu.") || key.startsWith("objective.") || key.startsWith("kit.") }
            for ((key, values) in guiLeaves) {
                values.forEach { value ->
                    withClue("$language:$key") {
                        ("<gradient" in value) shouldBe false
                        ("<italic" in value) shouldBe false
                        Regex("\\b[\\p{Lu}]{4,}\\b").containsMatchIn(value.replace(Regex("<[^>]+>"), " ")) shouldBe false
                    }
                }
            }
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
    input.replace(Regex("<(page|pages|active|waiting|player|players|position|rating|wins|losses|value|seconds|state|server|percent|first|second|reason|kit|loadout|winrate|streak|best|winner|loser|permission|arenas|arena|point|world|radius|height|count|spawn1|spawn2|corner1|corner2|hill|id|online-player|objective|bestof|ranked|sudden|projectiles|consumables|pearls|regeneration)>"), "value")
