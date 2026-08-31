package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.HandlerList
import org.bukkit.event.inventory.ClickType
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.duels.domain.DuelPresetRepository
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.StatisticsRepository
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class ArcDuelsGuiLiveConfigMockBukkitTest : StringSpec({
    "leaderboard and history queries use the current live limit generation" {
        withGuiPlugin { paper, plugin ->
            val leaderboardLimits = mutableListOf<Int>()
            val historyLimits = mutableListOf<Int>()
            val statistics = mockk<StatisticsRepository>()
            every { statistics.leaderboard(any()) } answers {
                leaderboardLimits += firstArg<Int>()
                CompletableFuture.completedFuture(emptyList())
            }
            every { statistics.recentMatches(any(), any()) } answers {
                historyLimits += secondArg<Int>()
                CompletableFuture.completedFuture(emptyList())
            }
            val gui = guiFixture(plugin, statistics).gui
            val player = paper.server.addPlayer("LiveQueryViewer")

            gui.openLeaderboard(player)
            gui.openHistory(player)

            editGuiConfig(plugin) {
                set("gui.leaderboard-limit", 2)
                set("gui.history-limit", 3)
            }
            plugin.reloadConfiguration().getOrThrow()
            gui.openLeaderboard(player)
            gui.openHistory(player)

            leaderboardLimits shouldBe listOf(100, 2)
            historyLimits shouldBe listOf(100, 3)
        }
    }

    "arena name prompts enforce their captured wall clock deadline across reload" {
        withGuiPlugin { paper, plugin ->
            editGuiConfig(plugin) { set("gui.arena-name-input-timeout-seconds", 5L) }
            plugin.reloadConfiguration().getOrThrow()

            val clock = MutableGuiClock(Instant.parse("2026-08-31T12:00:00Z"))
            val fixture = guiFixture(plugin, mockk(relaxed = true), clock)
            HandlerList.unregisterAll(plugin)
            plugin.server.pluginManager.registerEvents(fixture.gui, plugin)

            val expired = paper.server.addPlayer("ExpiredArenaAdm").apply {
                isOp = true
                setLocale(java.util.Locale.ENGLISH)
            }
            fixture.gui.openAdmin(expired)
            expired.simulateInventoryClick(expired.openInventory, ClickType.LEFT, 29)
            requireNotNull(expired.nextComponentMessage())

            editGuiConfig(plugin) { set("gui.arena-name-input-timeout-seconds", 10L) }
            plugin.reloadConfiguration().getOrThrow()
            clock.advanceSeconds(6)

            val expiredEvent = arenaNameEvent(expired, "expired_arena")
            paper.server.scheduler.executeAsyncEvent(expiredEvent).get(5, TimeUnit.SECONDS)
            paper.performTicks(2)

            expiredEvent.isCancelled shouldBe true
            expired.nextPlainMessage().contains("timed out") shouldBe true
            verify(exactly = 0) { fixture.admin.execute(expired, any()) }

            val current = paper.server.addPlayer("CurrentArenaAdm").apply {
                isOp = true
                setLocale(java.util.Locale.ENGLISH)
            }
            fixture.gui.openAdmin(current)
            current.simulateInventoryClick(current.openInventory, ClickType.LEFT, 29)
            requireNotNull(current.nextComponentMessage())
            clock.advanceSeconds(6)

            val currentEvent = arenaNameEvent(current, "current_arena")
            paper.server.scheduler.executeAsyncEvent(currentEvent).get(5, TimeUnit.SECONDS)
            paper.performTicks(2)

            currentEvent.isCancelled shouldBe true
            verify(exactly = 1) {
                fixture.admin.execute(current, listOf("arena", "create", "current_arena"))
            }
        }
    }
})

private data class GuiFixture(
    val gui: DuelGuiService,
    val admin: DuelAdminCommand,
)

private fun guiFixture(
    plugin: ArcDuelsPlugin,
    statistics: StatisticsRepository,
    clock: Clock = Clock.systemUTC(),
): GuiFixture {
    val admin = mockk<DuelAdminCommand>(relaxed = true)
    val gui =
        DuelGuiService(
            plugin = plugin,
            kits = KitRegistry.load(plugin),
            statistics = statistics,
            presets = mockk<DuelPresetRepository>(relaxed = true),
            sessions = mockk(relaxed = true),
            locales = LocaleService.load(plugin),
            admin = admin,
            targets = DuelTargetDirectory(plugin, ServerId("duels-1"), null),
            challengeAction = { _, _, _, _ -> },
            statisticsAction = { _, _ -> },
            runtimeSettings = plugin::currentRuntimeSettings,
            clock = clock,
        )
    return GuiFixture(gui, admin)
}

private fun arenaNameEvent(
    player: PlayerMock,
    value: String,
): AsyncChatEvent {
    val message = Component.text(value)
    return AsyncChatEvent(
        true,
        player,
        mutableSetOf<Audience>(player),
        ChatRenderer.defaultRenderer(),
        message,
        message,
        SignedMessage.system(value, message),
    )
}

private fun PlayerMock.nextPlainMessage(): String =
    PlainTextComponentSerializer.plainText().serialize(requireNotNull(nextComponentMessage()))

private fun editGuiConfig(
    plugin: ArcDuelsPlugin,
    edit: YamlConfiguration.() -> Unit,
) {
    val file = File(plugin.dataFolder, "config.yml")
    val configuration = YamlConfiguration().apply { load(file) }
    configuration.edit()
    configuration.save(file)
}

private fun withGuiPlugin(block: (MockBukkitTestRuntime, ArcDuelsPlugin) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation {
            block(paper, paper.loadPlugin<ArcDuelsPlugin>())
        }
    }
}

private class MutableGuiClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableGuiClock(current, zone)

    override fun instant(): Instant = current

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
