package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.duels.domain.PlayerId
import java.util.UUID
import java.util.concurrent.Executor

/** Its dedicated test JVM excludes both RedisEconomy and VaultAPI, including listener discovery. */
class ArcDuelsWithoutEconomyApiTest : StringSpec({
    "free duel plugin bootstrap and wager factory are binary-safe without optional economy APIs" {
        val loader = DuelWagerWallets::class.java.classLoader
        shouldThrow<ClassNotFoundException> { loader.loadClass("dev.unnm3d.rediseconomy.api.RedisEconomyAPI") }
        shouldThrow<ClassNotFoundException> { loader.loadClass("net.milkbowl.vault.economy.EconomyResponse") }
        val wallet = DuelWagerWallets.create(Executor { error("Absent provider must not dispatch") }) {
            error("Absent provider must not request a native callback")
        }
        wallet.available shouldBe false
        wallet.balance(PlayerId(UUID.randomUUID())).join() shouldBe null
        MockBukkitTestRuntime.open().use { paper ->
            multiplayerHarness(paper).use { harness ->
                harness.plugin.isEnabled shouldBe true
                ArcDuelsPlugin::class.java.declaredMethods.isNotEmpty() shouldBe true
                DuelGameplayListener::class.java.declaredMethods.isNotEmpty() shouldBe true
                DuelController::class.java.declaredMethods.isNotEmpty() shouldBe true
            }
        }
    }
})
