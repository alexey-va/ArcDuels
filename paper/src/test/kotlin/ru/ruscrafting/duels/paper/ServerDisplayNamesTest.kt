package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.minimessage.ParsingException
import org.bukkit.configuration.file.YamlConfiguration
import ru.ruscrafting.duels.domain.ServerId

class ServerDisplayNamesTest : StringSpec({
    "configured server ids render as player-facing Adventure components" {
        val configuration = YamlConfiguration()
        configuration.set("server-display-names.spawn", "<#ffb347>Спавн</#ffb347>")
        configuration.set("server-display-names.survival", "<#55ff8a>Выживание</#55ff8a>")
        val warnings = mutableListOf<String>()
        val names = ServerDisplayNames.load(configuration, warnings::add)

        PlainTextComponentSerializer.plainText().serialize(names.display(ServerId("survival"))) shouldBe "Выживание"
        warnings shouldBe emptyList()
    }

    "unknown server ids fall back safely and warn only once" {
        val warnings = mutableListOf<String>()
        val names = ServerDisplayNames.load(YamlConfiguration(), warnings::add)
        val unknown = ServerId("event-1")

        PlainTextComponentSerializer.plainText().serialize(names.display(unknown)) shouldBe "event-1"
        PlainTextComponentSerializer.plainText().serialize(names.display(unknown)) shouldBe "event-1"
        warnings shouldBe listOf("Missing player-facing display name for ArcDuels server id 'event-1'")
    }

    "invalid player-facing MiniMessage fails startup instead of leaking markup" {
        val configuration = YamlConfiguration()
        configuration.set("server-display-names.spawn", "<gold>Спавн")

        shouldThrow<ParsingException> {
            ServerDisplayNames.load(configuration) { }
        }
    }
})
