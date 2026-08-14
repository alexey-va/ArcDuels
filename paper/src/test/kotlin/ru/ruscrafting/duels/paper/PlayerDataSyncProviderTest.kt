package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PlayerDataSyncProviderTest : StringSpec({
    "auto detects HuskSync and an explicit none node stays isolated" {
        PlayerDataSyncProvider.resolve("AUTO", huskSyncEnabled = true) shouldBe PlayerDataSyncProvider.HUSKSYNC
        PlayerDataSyncProvider.resolve("auto", huskSyncEnabled = false) shouldBe PlayerDataSyncProvider.NONE
        PlayerDataSyncProvider.resolve("NONE", huskSyncEnabled = true) shouldBe PlayerDataSyncProvider.NONE
    }

    "an explicit HuskSync contract fails closed when the plugin is absent" {
        shouldThrow<IllegalArgumentException> {
            PlayerDataSyncProvider.resolve("HUSKSYNC", huskSyncEnabled = false)
        }
    }
})
