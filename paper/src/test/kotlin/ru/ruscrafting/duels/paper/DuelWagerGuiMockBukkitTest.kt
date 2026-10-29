package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.HandlerList
import org.bukkit.event.inventory.ClickType
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.InMemoryStatisticsRepository
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class DuelWagerGuiMockBukkitTest : StringSpec({
    "stake input accepts exact hundredths and rejects rounding signs exponents and overflow" {
        mapOf("0" to 0L, "0.00" to 0L, "0.01" to 1L, " 12,34 " to 1234L,
            "1.2" to 120L, "1000000.00" to 100_000_000L).forEach { (input, expected) ->
            parseDuelStakeMinor(input) shouldBe expected
        }
        listOf("", "-1", "+1", "NaN", "Infinity", "1e3", ".5", "1.", "1.234",
            "1,000,000", "1000000.01", "999999999999999999999999999999").forEach {
            parseDuelStakeMinor(it) shouldBe null
        }
    }

    "custom stake is disclosed before send and survives arena back and preset round trip" {
        withWagerGui { fixture ->
            fixture.openRules()
            fixture.textAt(29).contains("Без денежной ставки") shouldBe true
            fixture.click(12)
            fixture.enterStake("12.34")
            fixture.textAt(40).contains("12.34 💰") shouldBe true
            fixture.textAt(40).contains("24.68 💰") shouldBe true
            fixture.player.openInventory.topInventory.getItem(40)!!.lore()!!.first().plain() shouldBe ""
            fixture.click(16)
            fixture.click(36)
            fixture.textAt(29).contains("12.34 💰") shouldBe true
            fixture.click(38)
            fixture.click(11)
            fixture.statistics.presets(PlayerId(fixture.player.uniqueId)).join().single().rules.stakeMinor shouldBe 1234L
            fixture.click(36)
            fixture.click(29, ClickType.RIGHT)
            fixture.textAt(29).contains("Без денежной ставки") shouldBe true
            fixture.click(38)
            fixture.textAt(11).contains("12.34 💰") shouldBe true
            fixture.click(11)
            fixture.click(40)
            fixture.sent.single().stakeMinor shouldBe 1234L
            fixture.sent.single().bestOf shouldBe 3
        }
    }

    "invalid and cancelled input preserve the draft and expired or abandoned input cannot change it" {
        withWagerGui { fixture ->
            fixture.openRules()
            fixture.enterStake("10")
            fixture.click(29)
            fixture.chat("1.234").isCancelled shouldBe true
            fixture.gui.cancelStakeInput(fixture.player) shouldBe true
            fixture.textAt(29).contains("10 💰") shouldBe true
            fixture.click(29)
            fixture.chat("отмена").isCancelled shouldBe true
            fixture.textAt(29).contains("10 💰") shouldBe true
            fixture.click(29)
            fixture.clock.advanceSeconds(61)
            fixture.chat("99").isCancelled shouldBe true
            fixture.textAt(29).contains("10 💰") shouldBe true
            fixture.click(29)
            fixture.gui.openMain(fixture.player)
            fixture.chat("99").isCancelled shouldBe false
            fixture.openRules()
            fixture.click(29)
            fixture.gui.close()
            fixture.chat("99").isCancelled shouldBe false
            fixture.sent shouldBe emptyList()
        }
    }

    "availability is checked again at input and send while removing a stake keeps free play available" {
        withWagerGui { fixture ->
            fixture.openRules()
            fixture.click(29)
            fixture.available = false
            fixture.chat("100").isCancelled shouldBe true
            fixture.textAt(29).contains("Без денежной ставки") shouldBe true
            fixture.available = true
            fixture.enterStake("0.01")
            fixture.available = false
            fixture.click(40)
            fixture.sent shouldBe emptyList()
            fixture.textAt(40).contains("Ставка недоступна") shouldBe true
            fixture.click(29, ClickType.RIGHT)
            fixture.click(40)
            fixture.sent.single().stakeMinor shouldBe 0L
        }
    }

    "both locales show exact stake pot and receipt amounts with white coin glyphs" {
        withWagerGui { fixture ->
            for (language in listOf("ru", "en")) {
                val offer = fixture.locales.componentForLanguage(language, "wager.offer",
                    LocaleService.component("stake", wagerAmountComponent(1234)),
                    LocaleService.component("pot", wagerAmountComponent(2468)))
                offer.plain().contains("12.34 💰") shouldBe true
                offer.plain().contains("24.68 💰") shouldBe true
                offer.requireWhiteCoins()
                for (key in listOf("wager.paid", "wager.refunded")) {
                    val receipt = fixture.locales.componentForLanguage(language, key,
                        LocaleService.component("amount", wagerAmountComponent(200_000_000)))
                    receipt.plain().contains("2000000 💰") shouldBe true
                    receipt.requireWhiteCoins()
                }
                val prompt = fixture.locales.componentForLanguage(language, "wager.input-prompt",
                    LocaleService.component("maximum", wagerAmountComponent(100_000_000)),
                    LocaleService.text("seconds", 60))
                prompt.requireWhiteCoins()
                prompt.plain().contains("/duel cancel") shouldBe true
            }
        }
    }
})

@Suppress("DEPRECATION")
private class WagerGuiFixture(val paper: MockBukkitTestRuntime, plugin: ArcDuelsPlugin) {
    val player = paper.server.addPlayer("StakeOwner")
    private val target = paper.server.addPlayer("StakeOpponent")
    val statistics = InMemoryStatisticsRepository()
    val locales = LocaleService.load(plugin)
    val sent = mutableListOf<DuelRules>()
    val clock = WagerInputClock()
    var available = true
    val gui = DuelGuiService(
        plugin = plugin,
        kits = KitRegistry.load(plugin),
        statistics = statistics,
        presets = statistics,
        sessions = mockk(relaxed = true),
        locales = locales,
        admin = mockk(relaxed = true),
        targets = DuelTargetDirectory(plugin, ServerId("duels-1"), null),
        challengeAction = { _, _, rules, _ -> sent += rules },
        statisticsAction = { _, _ -> },
        clock = clock,
        wagersAvailable = { available },
    )

    init {
        HandlerList.unregisterAll(plugin)
        plugin.server.pluginManager.registerEvents(gui, plugin)
    }

    fun openRules() {
        gui.openChallenge(player, DuelTarget(target.uniqueId, target.name, ServerId("duels-1"), true))
        click(11)
        click(4)
    }

    fun click(slot: Int, type: ClickType = ClickType.LEFT) {
        player.simulateInventoryClick(player.openInventory, type, slot)
    }

    fun textAt(slot: Int): String {
        val item = requireNotNull(player.openInventory.topInventory.getItem(slot))
        return (listOfNotNull(item.itemMeta.displayName()) + item.lore().orEmpty()).joinToString("\n") { it.plain() }
    }

    fun enterStake(value: String) {
        click(29)
        chat(value).isCancelled shouldBe true
    }

    fun chat(value: String): AsyncChatEvent {
        val message = Component.text(value)
        val event = AsyncChatEvent(true, player, mutableSetOf<Audience>(player), ChatRenderer.defaultRenderer(),
            message, message, SignedMessage.system(value, message))
        paper.server.scheduler.executeAsyncEvent(event).get(5, TimeUnit.SECONDS)
        paper.performTicks(2)
        return event
    }
}

private fun withWagerGui(block: (WagerGuiFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation {
            val fixture = WagerGuiFixture(paper, paper.loadReadyDuelsPlugin())
            fixture.gui.use { block(fixture) }
        }
    }
}

private fun Component.plain(): String = PlainTextComponentSerializer.plainText().serialize(this)

private fun Component.requireWhiteCoins(inheritedColor: TextColor? = null) {
    val effectiveColor = color() ?: inheritedColor
    if (this is TextComponent && content().contains("💰")) effectiveColor shouldBe NamedTextColor.WHITE
    children().forEach { it.requireWhiteCoins(effectiveColor) }
}

private class WagerInputClock(private var now: Instant = Instant.parse("2026-10-29T12:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
}
