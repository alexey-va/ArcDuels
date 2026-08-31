package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.MemoryConfiguration

class ValidatedConfigurationPublisherTest : StringSpec({
    "publishing copies one validated explicit tree and its defaults without retaining stale keys" {
        val old =
            MemoryConfiguration().apply {
                set("stale.value", 7)
                set("server-id", "old")
            }
        val defaults = MemoryConfiguration().apply { set("countdown-seconds", 3) }
        val candidate =
            MemoryConfiguration().apply {
                set("server-id", "new")
                createSection("empty-section")
                setDefaults(defaults)
            }

        publishValidatedConfiguration(old, candidate)

        old.contains("stale.value", true) shouldBe false
        old.getString("server-id") shouldBe "new"
        old.isConfigurationSection("empty-section") shouldBe true
        old.getInt("countdown-seconds") shouldBe 3
        old.isSet("countdown-seconds") shouldBe false
    }
})
