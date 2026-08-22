package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.event.inventory.ClickType
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.bukkit.util.Vector
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextDecoration
import io.papermc.paper.datacomponent.DataComponentTypes
import ru.ruscrafting.duels.domain.ChallengeId
import ru.ruscrafting.duels.domain.ChallengeRegistry
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.InMemoryStatisticsRepository
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchEndReason
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchOutcome
import ru.ruscrafting.duels.domain.MatchScore
import ru.ruscrafting.duels.domain.MatchState
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord

@Suppress("DEPRECATION")
class ArcDuelsPluginTest : StringSpec({
    lateinit var server: ServerMock
    lateinit var plugin: ArcDuelsPlugin

    beforeSpec {
        server = MockBukkit.mock()
    }

    afterSpec {
        MockBukkit.unmock()
    }

    "default plugin configuration enables without MySQL or Redis" {
        plugin = MockBukkit.load(ArcDuelsPlugin::class.java)

        plugin.isEnabled shouldBe true
        plugin.pluginMeta.name shouldBe "ArcDuels"
        plugin.config.getInt("countdown-seconds") shouldBe 3
        plugin.config.getLong("teleport-stabilization-ticks") shouldBe 3L
        plugin.config.getLong("rematch-window-seconds") shouldBe 180L
        plugin.config.getLong("celebration.duration-ticks") shouldBe 80L
        plugin.config.getString("player-data-sync.provider") shouldBe "AUTO"
        plugin.config.getLong("player-data-sync.settle-delay-ticks") shouldBe 40L
        plugin.config.getString("post-match.return-policy") shouldBe "PROMPT"
        plugin.config.getString("server-display-names.survival") shouldBe "<#55ff8a>Выживание</#55ff8a>"
        plugin.config.getLong("shutdown.recovery-timeout-ms") shouldBe 5_000L
        plugin.getCommand("duel")?.executor?.javaClass shouldBe DuelCommand::class.java
        KitRegistry.load(plugin).all().map { it.id.value } shouldBe listOf("archer", "axe", "boxing", "classic", "sumo", "tank", "uhc")
        java.io.File(plugin.dataFolder, "lang/ru.yml").isFile shouldBe true
        java.io.File(plugin.dataFolder, "lang/en.yml").isFile shouldBe true

        val localeWarnings = mutableListOf<String>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    localeWarnings += record.message
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        plugin.logger.addHandler(handler)
        try {
            LocaleService.load(plugin)
        } finally {
            plugin.logger.removeHandler(handler)
        }
        localeWarnings.none { "already exists" in it } shouldBe true
    }

    "network arena routing prevents a false no-arena startup failure" {
        hasUsableArenaRoute(localArenaCount = 0, networkArenaRoutingEnabled = true) shouldBe true
        hasUsableArenaRoute(localArenaCount = 0, networkArenaRoutingEnabled = false) shouldBe false
        hasUsableArenaRoute(localArenaCount = 1, networkArenaRoutingEnabled = false) shouldBe true
    }

    "duel timing helpers clamp bossbar progress and schedule expiry on tick boundaries" {
        formatDuelTime(0) shouldBe "0:00"
        formatDuelTime(65) shouldBe "1:05"
        remainingBossBarProgress(0, 60) shouldBe 1f
        remainingBossBarProgress(600, 60) shouldBe 0.5f
        remainingBossBarProgress(1_400, 60) shouldBe 0f
        challengeExpiryDelayTicks(1_000, 1_001) shouldBe 1L
        challengeExpiryDelayTicks(1_000, 1_051) shouldBe 2L
        activeBossBarLocaleKey(1) shouldBe "session.bossbar-active-single"
        activeBossBarLocaleKey(3) shouldBe "session.bossbar-active"
        activeBossBarLocaleKey(5) shouldBe "session.bossbar-active"
    }

    "chat notices use one calm identity real blank lines and indented continuation rows" {
        val locales = LocaleService.load(plugin)
        val player = server.addPlayer("NoticeTester")
        player.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
        val action = Component.text("Вернуться").clickEvent(ClickEvent.runCommand("/duel return"))
        val body = Component.text("Первая строка").append(Component.newline()).append(action)

        val notice = locales.frameNotice(player, body)
        val plain = PlainTextComponentSerializer.plainText().serialize(notice)

        plain shouldBe "\n  ⚔ Первая строка\n  Вернуться\n"
        ("\\n" in plain) shouldBe false
        notice.containsRunCommand("/duel return") shouldBe true
    }

    "blank optional feedback stays silent and a nonblank locale override enables it" {
        val player = server.addPlayer("OptNotice")
        player.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
        val defaults = LocaleService.load(plugin)

        defaults.optionalNotice(
            player,
            "controller.network-return",
            LocaleService.text("server", "Выживание"),
        ) shouldBe null
        defaults.optionalComponent(player, "session.restored") shouldBe null

        val localeFile = java.io.File(plugin.dataFolder, "lang/ru.yml")
        val locale = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(localeFile)
        try {
            locale.set("controller.network-return", "<#e6fff3>Возврат на <server>.</#e6fff3>")
            locale.save(localeFile)

            val overridden = LocaleService.load(plugin)
            val notice = requireNotNull(
                overridden.optionalNotice(
                    player,
                    "controller.network-return",
                    LocaleService.text("server", "Выживание"),
                ),
            )
            PlainTextComponentSerializer.plainText().serialize(notice) shouldBe "\n  ⚔ Возврат на Выживание.\n"
        } finally {
            locale.set("controller.network-return", "")
            locale.save(localeFile)
        }
    }

    "challenge card is readable and both players are told when it expires" {
        var now = Instant.parse("2026-08-15T00:00:00Z")
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC
                override fun withZone(zone: ZoneId): Clock = this
                override fun instant(): Instant = now
            }
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.onCompleted(any()) } returns AutoCloseable { }
        val locales = LocaleService.load(plugin)
        val localServer = ServerId("test")
        val targets = DuelTargetDirectory(plugin, localServer, null)
        val challenger = server.addPlayer("Challenger")
        val target = server.addPlayer("Target")
        challenger.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
        target.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
        val controller =
            DuelController(
                plugin,
                ChallengeRegistry(clock, Duration.ofSeconds(1)),
                sessions,
                InMemoryStatisticsRepository(),
                locales,
                targets,
                localServer,
                clock = clock,
            )

        controller.challenge(
            challenger,
            targets.local(target),
            DuelRules(DuelMode.OWN_INVENTORY),
            ru.ruscrafting.duels.domain.ArenaSelection(ServerId("spawn"), ru.ruscrafting.duels.domain.ArenaId("kit-test")),
        )
        val invitationComponent = requireNotNull(target.nextComponentMessage())
        val invitation = PlainTextComponentSerializer.plainText().serialize(invitationComponent)
        invitation.contains("\n  ⚔ Challenger предлагает дуэль\n") shouldBe true
        invitation.contains("Арена kit-test · Спавн") shouldBe true
        invitation.contains("\n  ✔ Принять\n  ✕ Отклонить\n") shouldBe true
        invitationComponent.containsRunCommand("/duel Challenger") shouldBe true
        invitationComponent.containsHoverText("Рейтинг: 1000") shouldBe true
        invitationComponent.containsHoverText("предметами, которые сейчас находятся") shouldBe true
        invitationComponent.containsHoverText("BO1 — одна победа") shouldBe true
        invitationComponent.containsHoverText("именно на этой арене") shouldBe true
        invitationComponent.containsHoverText("постоянно терять здоровье") shouldBe true
        invitationComponent.containsHoverText("урон луками") shouldBe true
        val sent = requireNotNull(challenger.nextComponentMessage())
        PlainTextComponentSerializer.plainText().serialize(sent).contains("Вызов отправлен") shouldBe true
        sent.containsRunCommand("/duel Target") shouldBe true
        sent.containsHoverText("Победы: 0") shouldBe true

        now = now.plusSeconds(1)
        server.scheduler.performTicks(20)

        PlainTextComponentSerializer.plainText().serialize(requireNotNull(challenger.nextComponentMessage())).contains("Target истёк") shouldBe true
        PlainTextComponentSerializer.plainText().serialize(requireNotNull(target.nextComponentMessage())).contains("Challenger истёк") shouldBe true
        controller.close()
    }

    "match participants receive one combined result and rematch card" {
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        val completion = slot<(DuelMatch) -> Unit>()
        every { sessions.onCompleted(capture(completion)) } returns AutoCloseable { }
        val winner = server.addPlayer("SummaryWinner")
        val loser = server.addPlayer("SummaryLoser")
        winner.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
        loser.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
        val controller =
            DuelController(
                plugin,
                ChallengeRegistry(Clock.systemUTC()),
                sessions,
                InMemoryStatisticsRepository(),
                LocaleService.load(plugin),
                DuelTargetDirectory(plugin, ServerId("spawn"), null),
                ServerId("spawn"),
            )
        val match =
            DuelMatch(
                id = MatchId.random(),
                firstPlayer = PlayerId(winner.uniqueId),
                secondPlayer = PlayerId(loser.uniqueId),
                arenaId = ArenaId("kit-test"),
                serverId = ServerId("spawn"),
                rules = DuelRules(DuelMode.KIT, KitId("classic"), bestOf = 3),
                state = MatchState.COMPLETED,
                score = MatchScore(2, 1),
                createdAt = Instant.parse("2026-08-15T12:00:00Z"),
                completedAt = Instant.parse("2026-08-15T12:01:00Z"),
                winner = PlayerId(winner.uniqueId),
                endReason = MatchEndReason.ELIMINATION,
            )

        completion.captured(match)

        val winnerCard = requireNotNull(winner.nextComponentMessage())
        val loserCard = requireNotNull(loser.nextComponentMessage())
        PlainTextComponentSerializer.plainText().serialize(winnerCard).contains("Победа над SummaryLoser · счёт 2:1") shouldBe true
        PlainTextComponentSerializer.plainText().serialize(loserCard).contains("Поражение от SummaryWinner · счёт 1:2") shouldBe true
        winnerCard.containsRunCommand("/duel rematch ${match.id}") shouldBe true
        loserCard.containsRunCommand("/duel rematch ${match.id}") shouldBe true
        isMatchParticipant(winner.uniqueId, winner.uniqueId, loser.uniqueId) shouldBe true
        isMatchParticipant(UUID.randomUUID(), winner.uniqueId, loser.uniqueId) shouldBe false
        viewerScore(match, PlayerId(winner.uniqueId)) shouldBe "2:1"
        viewerScore(match, PlayerId(loser.uniqueId)) shouldBe "1:2"

        val singleRound = match.copy(id = MatchId.random(), rules = match.rules.copy(bestOf = 1), score = MatchScore(1, 0))
        completion.captured(singleRound)
        val singleRoundCard = requireNotNull(winner.nextComponentMessage())
        val singleRoundPlain = PlainTextComponentSerializer.plainText().serialize(singleRoundCard)
        singleRoundPlain.contains("Победа над SummaryLoser") shouldBe true
        singleRoundPlain.contains("счёт") shouldBe false
        singleRoundPlain.contains("\n  \n  ● Предложить реванш") shouldBe true
        controller.close()
    }

    "GUI item specs preserve the configured ItemsAdder material and modern model data" {
        val item = GuiItemSpec(Material.BLUE_STAINED_GLASS_PANE, 11_013).create()

        item.type shouldBe Material.BLUE_STAINED_GLASS_PANE
        item.getData(DataComponentTypes.CUSTOM_MODEL_DATA)?.floats() shouldBe listOf(11_013f)
        shouldThrow<IllegalArgumentException> { GuiItemSpec(Material.STONE, 0) }
    }

    "player snapshot restores cursor slot experience and movement state" {
        val player = server.addPlayer()
        val world = server.addSimpleWorld("snapshot-world")
        val savedLocation = Location(world, 12.5, 70.0, -8.5, 45f, 10f)
        player.teleport(savedLocation)
        player.inventory.setItem(0, ItemStack(Material.DIAMOND_SWORD))
        player.setItemOnCursor(ItemStack(Material.GOLD_INGOT, 3))
        player.inventory.heldItemSlot = 4
        player.foodLevel = 17
        player.saturation = 3.5f
        player.exhaustion = 1.25f
        player.totalExperience = 321
        player.level = 12
        player.exp = 0.4f
        player.gameMode = GameMode.CREATIVE
        player.allowFlight = true
        player.isFlying = true
        player.health = 18.0
        player.velocity = Vector(0.2, 0.3, -0.1)
        val snapshot = PlayerSnapshot.capture(player)

        player.inventory.clear()
        player.setItemOnCursor(ItemStack.empty())
        player.inventory.heldItemSlot = 0
        player.totalExperience = 0
        player.level = 0
        player.exp = 0f
        player.gameMode = GameMode.SURVIVAL
        player.allowFlight = false
        player.teleport(Location(world, 0.0, 64.0, 0.0))

        snapshot.restore(player) { restored, destination ->
            val teleported = restored.teleport(destination)
            // Mirrors destination plugins that normalize health during teleport.
            restored.health = 1.0
            teleported
        }

        player.inventory.getItem(0)?.type shouldBe Material.DIAMOND_SWORD
        player.itemOnCursor.type shouldBe Material.GOLD_INGOT
        player.itemOnCursor.amount shouldBe 3
        player.inventory.heldItemSlot shouldBe 4
        player.totalExperience shouldBe 321
        player.level shouldBe 12
        player.exp shouldBe 0.4f
        player.gameMode shouldBe GameMode.CREATIVE
        player.isFlying shouldBe true
        player.health shouldBe 18.0
        player.location.x shouldBe savedLocation.x
        player.location.z shouldBe savedLocation.z
        player.velocity shouldBe Vector(0.2, 0.3, -0.1)
    }

    "own-inventory restoration preserves every item acquired during a duel" {
        val player = server.addPlayer()
        val world = server.addSimpleWorld("own-inventory-world")
        val savedLocation = Location(world, 10.5, 70.0, 10.5)
        player.teleport(savedLocation)
        player.gameMode = GameMode.CREATIVE
        player.inventory.setItem(0, ItemStack(Material.STONE))
        val snapshot = PlayerSnapshot.capture(player)

        val acquiredSword = ItemStack(Material.NETHERITE_SWORD)
        player.inventory.setItem(5, acquiredSword)
        player.setItemOnCursor(ItemStack(Material.DIAMOND_SWORD))
        player.gameMode = GameMode.SURVIVAL
        player.teleport(Location(world, 0.5, 64.0, 0.5))

        snapshot.restoreWithoutInventory(player) { restored, destination -> restored.teleport(destination) }

        player.inventory.getItem(0)?.type shouldBe Material.STONE
        player.inventory.getItem(5) shouldBe acquiredSword
        player.itemOnCursor.type shouldBe Material.DIAMOND_SWORD
        player.gameMode shouldBe GameMode.CREATIVE
        player.location.x shouldBe savedLocation.x
        player.location.z shouldBe savedLocation.z
    }

    "kit restoration still replaces temporary combat items with the protected inventory" {
        val player = server.addPlayer()
        val snapshot = PlayerSnapshot.capture(player)
        player.inventory.setItem(0, ItemStack(Material.NETHERITE_SWORD))
        player.setItemOnCursor(ItemStack(Material.GOLDEN_APPLE))

        snapshot.restore(player) { restored, destination -> restored.teleport(destination) }

        player.inventory.getItem(0) shouldBe null
        player.itemOnCursor.isEmpty shouldBe true
    }

    "versioned snapshot codec round trips Paper item bytes and rejects corrupt framing" {
        val player = server.addPlayer()
        val world = server.addSimpleWorld("codec-world")
        player.teleport(Location(world, 2.5, 72.0, -3.5, 30f, -5f))
        player.inventory.setItem(0, ItemStack(Material.DIAMOND_SWORD))
        player.inventory.setItem(8, ItemStack(Material.GOLDEN_APPLE, 7))
        player.inventory.helmet = ItemStack(Material.NETHERITE_HELMET)
        player.setItemOnCursor(ItemStack(Material.EMERALD, 11))
        val snapshot = PlayerSnapshot.capture(player)
        val codec = PlayerSnapshotCodec(server)

        val payload = codec.encode(snapshot)
        val decoded = codec.decode(payload)
        player.inventory.clear()
        player.inventory.armorContents = arrayOfNulls(4)
        player.setItemOnCursor(ItemStack.empty())
        player.teleport(Location(world, 0.0, 64.0, 0.0))

        decoded.restore(player) { restored, destination -> restored.teleport(destination) }

        player.inventory.getItem(0)?.type shouldBe Material.DIAMOND_SWORD
        player.inventory.getItem(8)?.amount shouldBe 7
        player.inventory.helmet?.type shouldBe Material.NETHERITE_HELMET
        player.itemOnCursor.amount shouldBe 11
        val corrupt = payload.copyOf().also { it[0] = (it[0].toInt() xor 0x7f).toByte() }
        shouldThrow<IllegalArgumentException> { codec.decode(corrupt) }
    }

    "enabled arena requires valid bounds containing both spawns" {
        server.addSimpleWorld("world")
        plugin.config.set("arenas.example.enabled", true)
        plugin.config.set("arenas.example.post-match-action", "RETURN_TO_ORIGIN")

        val arena = PaperArenaCatalog.load(plugin).get(ArenaId("example"))
        arena.postMatchAction shouldBe ArenaPostMatchAction.RETURN_TO_ORIGIN

        plugin.config.set("arenas.example.bounds.min.x", 20.0)
        shouldThrow<IllegalArgumentException> { PaperArenaCatalog.load(plugin) }
        plugin.config.set("arenas.example.bounds.min.x", -15.0)
        plugin.config.set("arenas.example.enabled", false)
    }

    "arena loading runs the environment inspector for every enabled arena" {
        plugin.config.set("arenas.example.enabled", true)
        var inspected: PaperArena? = null

        val catalog = PaperArenaCatalog.load(plugin, ArenaEnvironmentInspector { inspected = it })

        catalog.size() shouldBe 1
        inspected?.id?.value shouldBe "example"
        plugin.config.set("arenas.example.enabled", false)
    }

    "invalid explicit challenge ids never fall back to another pending challenge" {
        val controller = mockk<DuelController>(relaxed = true)
        val gui = mockk<DuelGuiService>(relaxed = true)
        val admin = mockk<DuelAdminCommand>(relaxed = true)
        val executor = DuelCommand(controller, gui, admin)
        val player = server.addPlayer()
        val command = requireNotNull(plugin.getCommand("duel"))

        executor.onCommand(player, command, "duel", arrayOf("accept", "not-a-uuid"))

        verify(exactly = 0) { controller.accept(player, any()) }

        val id = ChallengeId(UUID.randomUUID())
        executor.onCommand(player, command, "duel", arrayOf("accept", id.toString()))
        verify(exactly = 1) { controller.accept(player, id) }

        executor.onCommand(player, command, "duel", arrayOf("rematch", "not-a-uuid"))
        verify(exactly = 0) { controller.rematch(player, any()) }
    }

    "rematch keeps the exact rules and arena without adding setup clicks" {
        val now = Instant.parse("2026-08-15T13:00:30Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val registry = ChallengeRegistry(clock)
        val statistics = InMemoryStatisticsRepository()
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.onCompleted(any()) } returns AutoCloseable { }
        val first = server.addPlayer("RematchFirst")
        val second = server.addPlayer("RematchSecond")
        val rules = DuelRules(DuelMode.KIT, KitId("classic"), ranked = true, bestOf = 3, objective = DuelObjectiveType.ELIMINATION)
        val outcome =
            MatchOutcome(
                MatchId(UUID.randomUUID()),
                PlayerId(first.uniqueId),
                PlayerId(second.uniqueId),
                rules.mode,
                rules.kitId,
                rules.ranked,
                ServerId("spawn"),
                now.minusSeconds(30),
                rules.objective,
                ArenaId("kit-test"),
                rules.bestOf,
                rules.modifiers,
                2,
                1,
                MatchEndReason.ELIMINATION,
            )
        statistics.record(outcome).get()
        val controller =
            DuelController(
                plugin,
                registry,
                sessions,
                statistics,
                LocaleService.load(plugin),
                DuelTargetDirectory(plugin, ServerId("spawn"), null),
                ServerId("spawn"),
                clock = clock,
            )

        controller.rematch(first, outcome.matchId)

        registry.pendingFor(PlayerId(first.uniqueId)).single().let { challenge ->
            challenge.challenger shouldBe PlayerId(first.uniqueId)
            challenge.target shouldBe PlayerId(second.uniqueId)
            challenge.rules shouldBe rules
            challenge.arenaSelection shouldBe ArenaSelection(ServerId("spawn"), ArenaId("kit-test"))
        }
        controller.close()

        val missingRegistry = ChallengeRegistry(clock)
        val missingArenaController =
            DuelController(
                plugin,
                missingRegistry,
                sessions,
                statistics,
                LocaleService.load(plugin),
                DuelTargetDirectory(plugin, ServerId("spawn"), null),
                ServerId("spawn"),
                clock = clock,
                arenaChoices = { emptyList() },
            )
        missingArenaController.rematch(first, outcome.matchId)
        missingRegistry.pendingFor(PlayerId(first.uniqueId)) shouldBe emptyList()
        missingArenaController.close()
    }

    "participant routing keeps the current server distinct from the recovery origin" {
        val route = participantRoute(ServerId("spawn"), ServerId("survival"))

        route.currentServer shouldBe ServerId("spawn")
        route.originServer shouldBe ServerId("survival")
        participantRoute(ServerId("parkour"), null).originServer shouldBe ServerId("parkour")
    }

    "case-normalized kit and arena ids cannot silently overwrite each other" {
        plugin.config.set("kits.Classic.icon", "STONE")
        shouldThrow<IllegalArgumentException> { KitRegistry.load(plugin) }
        plugin.config.set("kits.Classic", null)

        plugin.config.set("arenas.example.bounds.min.x", -15.0)
        val originalArena = requireNotNull(plugin.config.getConfigurationSection("arenas.example"))
        originalArena.getValues(true).forEach { (key, value) -> plugin.config.set("arenas.Example.$key", value) }
        plugin.config.set("arenas.example.enabled", true)
        plugin.config.set("arenas.Example.enabled", true)

        shouldThrow<IllegalArgumentException> { PaperArenaCatalog.load(plugin) }
        plugin.config.set("arenas.Example", null)
        plugin.config.set("arenas.example.enabled", false)
    }

    "main hub follows the challenge submenu path and renders the client language" {
        val player = server.addPlayer("MenuTester")
        server.addPlayer("Opponent")
        player.setLocale(java.util.Locale.ENGLISH)

        player.performCommand("duel") shouldBe true
        player.openInventory.topInventory.size shouldBe 45
        player.openInventory.topInventory.getItem(11)?.type shouldBe Material.NETHERITE_SWORD
        player.openInventory.topInventory.getItem(38)?.type shouldBe Material.BOOK
        val challengeName = requireNotNull(player.openInventory.topInventory.getItem(11)?.itemMeta?.displayName())
        PlainTextComponentSerializer.plainText().serialize(challengeName) shouldBe "Challenge a player"
        challengeName.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE

        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 38)
        player.openInventory.topInventory.getItem(22)?.type shouldBe Material.PAPER
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 36)
        player.openInventory.topInventory.getItem(11)?.type shouldBe Material.NETHERITE_SWORD

        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 11)
        player.openInventory.topInventory.getItem(10)?.type shouldBe Material.PLAYER_HEAD
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 10)
        player.openInventory.topInventory.getItem(13)?.type shouldBe Material.BEACON
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 13)
        player.openInventory.topInventory.getItem(10)?.type shouldBe Material.BOW
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 10)
        player.openInventory.topInventory.getItem(31)?.type shouldBe Material.BEACON
        player.openInventory.topInventory.getItem(38)?.type shouldBe Material.ENCHANTED_BOOK
        player.openInventory.topInventory.getItem(40)?.type shouldBe Material.LIME_CONCRETE
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 38)
        player.openInventory.topInventory.getItem(11)?.type shouldBe Material.LIGHT_GRAY_DYE
        player.simulateInventoryClick(player.openInventory, ClickType.RIGHT, 11)
        player.openInventory.topInventory.getItem(11)?.type shouldBe Material.LIGHT_GRAY_DYE
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 11)
        player.openInventory.topInventory.getItem(11)?.type shouldBe Material.ENCHANTED_BOOK
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 11)
        player.openInventory.topInventory.getItem(40)?.type shouldBe Material.LIME_CONCRETE

        player.closeInventory()
        player.performCommand("duel Opponent") shouldBe true
        player.openInventory.topInventory.getItem(29)?.type shouldBe Material.LEATHER_BOOTS
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 29)
        player.openInventory.topInventory.getItem(10)?.type shouldBe Material.LEATHER_BOOTS
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 10)
        player.openInventory.topInventory.getItem(14)?.type shouldBe Material.TARGET
        player.openInventory.topInventory.getItem(19)?.type shouldBe Material.GRAY_DYE
        player.openInventory.topInventory.getItem(16)?.type shouldBe Material.COMPASS
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 16)
        player.openInventory.topInventory.getItem(10)?.type shouldBe Material.COMPASS
        player.openInventory.topInventory.getItem(22)?.type shouldBe Material.BARRIER
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 36)
        player.openInventory.topInventory.getItem(16)?.type shouldBe Material.COMPASS

        player.closeInventory()
        player.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
        player.performCommand("duel") shouldBe true
        PlainTextComponentSerializer.plainText().serialize(requireNotNull(player.openInventory.topInventory.getItem(11)?.itemMeta?.displayName())) shouldBe "Вызвать на бой"
    }

    "admin command opens a real arena editor and its actions use the current position" {
        val player = server.addPlayer("ArenaAdmin")
        player.isOp = true
        player.setLocale(java.util.Locale.ENGLISH)
        val world = requireNotNull(server.getWorld("world"))

        player.performCommand("duels admin") shouldBe true
        player.openInventory.topInventory.getItem(11)?.type shouldBe Material.FILLED_MAP
        player.openInventory.topInventory.getItem(29)?.type shouldBe Material.NAME_TAG
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 11)
        player.openInventory.topInventory.getItem(10)?.type shouldBe Material.YELLOW_BANNER
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 10)
        player.openInventory.topInventory.getItem(12)?.type shouldBe Material.COMPASS
        player.openInventory.topInventory.getItem(31)?.type shouldBe Material.LIME_CONCRETE
        player.openInventory.topInventory.getItem(27)?.type shouldBe Material.CLOCK
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 27)
        player.openInventory.topInventory.getItem(29)?.type shouldBe Material.LEATHER_BOOTS
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 29)
        plugin.config.getStringList("arenas.example.allowed-objectives").contains("BOXING") shouldBe false
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 36)

        player.teleport(Location(world, 7.5, 82.0, -4.5, 45f, 5f))
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 12)

        plugin.config.getDouble("arenas.example.first-spawn.x") shouldBe 7.5
        plugin.config.getDouble("arenas.example.first-spawn.y") shouldBe 82.0
        player.openInventory.topInventory.getItem(12)?.type shouldBe Material.COMPASS

        player.performCommand("duels admin") shouldBe true
        player.simulateInventoryClick(player.openInventory, ClickType.LEFT, 29)
        player.chat("gui_arena")
        server.scheduler.performTicks(2)
        plugin.config.isConfigurationSection("arenas.gui_arena") shouldBe true
        player.openInventory.topInventory.getItem(12)?.type shouldBe Material.COMPASS
        plugin.config.set("arenas.gui_arena", null)
    }

    "admin status is a readable health card with the configured server name and pending recoveries" {
        val player = server.addPlayer("StatusAdmin")
        player.isOp = true
        player.setLocale(java.util.Locale.forLanguageTag("ru-RU"))

        player.performCommand("duels admin status") shouldBe true

        val plain = PlainTextComponentSerializer.plainText().serialize(requireNotNull(player.nextComponentMessage()))
        plain.contains("Состояние ArcDuels") shouldBe true
        plain.contains("Сервер: Арена") shouldBe true
        plain.contains("Ожидают восстановления: 0") shouldBe true
    }
})

private fun Component.containsRunCommand(command: String): Boolean =
    clickEvent() == ClickEvent.runCommand(command) || children().any { it.containsRunCommand(command) }

private fun Component.containsHoverText(fragment: String): Boolean {
    val hovered = hoverEvent()?.value() as? Component
    return (hovered != null && PlainTextComponentSerializer.plainText().serialize(hovered).contains(fragment)) ||
        children().any { it.containsHoverText(fragment) }
}
