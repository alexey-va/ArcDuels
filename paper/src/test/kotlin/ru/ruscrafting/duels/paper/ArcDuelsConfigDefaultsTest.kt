package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files

class ArcDuelsConfigDefaultsTest : StringSpec({
    "bundled config migration adds new multiplayer keys without touching credentials or arenas" {
        val dataRoot = Files.createTempDirectory("arcduels-config-defaults")
        val file = dataRoot.resolve("config.yml")
        Files.writeString(
            file,
            """
            server-id: legacy
            mysql:
              username: Classic
              password: operator-secret
            arenas:
              production:
                enabled: true
            """.trimIndent() + "\n",
        )

        ArcDuelsConfigDefaults.mergeMissing(dataRoot) shouldBe true

        val migrated = YamlConfiguration.loadConfiguration(file.toFile())
        migrated.getString("mysql.username") shouldBe "Classic"
        migrated.getString("mysql.password") shouldBe "operator-secret"
        migrated.getBoolean("arenas.production.enabled") shouldBe true
        migrated.contains("arenas.example") shouldBe false
        migrated.getDouble("multiplayer.spawn-placement.radius-scale") shouldBe 1.0
        migrated.getDouble("multiplayer.spawn-placement.teammate-spacing") shouldBe 3.0
        migrated.getDouble("multiplayer.spawn-placement.minimum-separation") shouldBe 2.0
        migrated.getDouble("multiplayer.spawn-placement.bounds-inset") shouldBe 1.0

        val firstMigration = Files.readString(file)
        ArcDuelsConfigDefaults.mergeMissing(dataRoot) shouldBe false
        Files.readString(file) shouldBe firstMigration
    }
})
