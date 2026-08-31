package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.HandlerList
import org.bukkit.event.inventory.ClickType
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.simulate.entity.PlayerSimulation
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.loadPlugin
import ru.ruscrafting.duels.domain.ChallengeRegistry
import java.io.File
import java.io.InputStreamReader
import java.time.Clock

class ArcDuelsConfigReloadMockBukkitTest : StringSpec({
    "startup locale loading repairs obsolete values and persists missing bundled keys" {
        withReloadPlugin { _, plugin ->
            val localeFile = File(plugin.dataFolder, "lang/ru.yml")
            val bundled =
                requireNotNull(plugin.getResource("lang/ru.yml")).use {
                    YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))
                }
            editYaml(localeFile) {
                set("menu.main.challenge", "<#92bed8>Пользовательский вызов</#92bed8>")
                set(
                    "multiplayer.start-failed",
                    "<#c42323>Не удалось начать групповой матч:</#c42323> <#8c8c8c><reason></#8c8c8c>",
                )
                set("multiplayer.invite-open", null)
            }

            val locales = LocaleService.load(plugin)
            val repaired = YamlConfiguration.loadConfiguration(localeFile)

            repaired.getString("multiplayer.start-failed")?.contains("<reason>") shouldBe false
            repaired.contains("multiplayer.invite-open") shouldBe true
            repaired.getString("menu.main.challenge") shouldBe
                "<color:#92bed8>Пользовательский вызов</color>"
            PlainTextComponentSerializer.plainText().serialize(
                locales.componentForLanguage("ru", "multiplayer.start-failed"),
            ) shouldBe "Не удалось начать групповой матч. Состояние игроков защищено; попробуй собрать матч ещё раз."

            val afterRepair = localeFile.readText()
            LocaleService.load(plugin)
            localeFile.readText() shouldBe afterRepair
        }
    }

    "startup locale loading restores a missing locale file from the plugin jar" {
        withReloadPlugin { _, plugin ->
            val localeFile = File(plugin.dataFolder, "lang/ru.yml")
            localeFile.delete() shouldBe true

            val locales = LocaleService.load(plugin)

            localeFile.isFile shouldBe true
            PlainTextComponentSerializer.plainText().serialize(
                locales.componentForLanguage("ru", "menu.main.challenge"),
            ) shouldBe "Вызвать на дуэль 1 на 1"
        }
    }

    "valid reload updates runtime locale and GUI without registering listeners again" {
        withReloadPlugin { paper, plugin ->
            val listenerCount = HandlerList.getRegisteredListeners(plugin).size
            editConfig(plugin) {
                set("countdown-seconds", 0)
                set("multiplayer.invitation-timeout-seconds", 120L)
                set("multiplayer.finish-delay-ticks", 5L)
                set("gui.items.background.material", "BLUE_STAINED_GLASS_PANE")
            }
            editYaml(File(plugin.dataFolder, "lang/ru.yml")) {
                set("menu.main.challenge", "<red>Перезагруженная дуэль</red>")
            }

            val report = plugin.reloadConfiguration().getOrThrow()

            report.generation shouldBe 2L
            report.catalogsDeferred shouldBe false
            plugin.currentRuntimeSettings().countdownSeconds shouldBe 0
            plugin.currentRuntimeSettings().multiplayerInvitationTimeout.seconds shouldBe 120L
            plugin.currentRuntimeSettings().multiplayerFinishDelayTicks shouldBe 5L
            HandlerList.getRegisteredListeners(plugin).size shouldBe listenerCount

            val player = paper.server.addPlayer("ReloadViewer")
            player.setLocale(java.util.Locale.forLanguageTag("ru-RU"))
            player.performCommand("duel") shouldBe true
            player.openInventory.topInventory.getItem(0)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
            PlainTextComponentSerializer.plainText().serialize(
                requireNotNull(player.openInventory.topInventory.getItem(11)?.itemMeta?.displayName()),
            ) shouldBe "Перезагруженная дуэль"
        }
    }

    "invalid candidate keeps the entire last known good generation and surfaces" {
        withReloadPlugin { paper, plugin ->
            editConfig(plugin) {
                set("countdown-seconds", 1)
                set("gui.items.background.material", "BLUE_STAINED_GLASS_PANE")
            }
            plugin.reloadConfiguration().getOrThrow().generation shouldBe 2L

            editConfig(plugin) {
                set("countdown-seconds", 99)
                set("gui.items.background.material", "RED_STAINED_GLASS_PANE")
            }
            val rejected = plugin.reloadConfiguration()

            rejected.isFailure shouldBe true
            plugin.currentConfigGeneration() shouldBe 2L
            plugin.currentRuntimeSettings().countdownSeconds shouldBe 1
            plugin.config.getInt("countdown-seconds") shouldBe 1

            val player = paper.server.addPlayer("LastGoodViewer")
            player.performCommand("duel") shouldBe true
            player.openInventory.topInventory.getItem(0)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
        }
    }

    "restart-owned changes are reported by path group without leaking values" {
        withReloadPlugin { _, plugin ->
            val secret = "must-not-appear-in-a-report"
            editConfig(plugin) {
                set("server-id", "duels-next")
                set("mysql.enabled", true)
                set("mysql.password", secret)
                set("redis.enabled", true)
                set("redis.host", "redis-next.internal")
                set("player-data-sync.provider", "NONE")
            }

            val report = plugin.reloadConfiguration().getOrThrow()

            report.restartRequired.toList() shouldContainExactly
                listOf(
                    ArcDuelsRestartOnlyField.SERVER_ID,
                    ArcDuelsRestartOnlyField.MYSQL,
                    ArcDuelsRestartOnlyField.REDIS_CONNECTION,
                )
            report.toString().contains(secret) shouldBe false
            plugin.isEnabled shouldBe true
        }
    }

    "invalid restart-only candidates never replace the startup generation" {
        withReloadPlugin { _, plugin ->
            val configFile = File(plugin.dataFolder, "config.yml")
            val startupConfig = configFile.readText()
            val secret = "candidate-secret-must-stay-redacted"
            val invalidCandidates: List<Triple<String, String, YamlConfiguration.() -> Unit>> =
                listOf(
                    Triple("server-id", "server", { set("server-id", "unsafe server id") }),
                    Triple(
                        "player-data-sync.provider",
                        "player-data-sync.provider",
                        { set("player-data-sync.provider", "HUSKSYNC") },
                    ),
                    Triple(
                        "mysql",
                        "mysql",
                        {
                            set("mysql.enabled", true)
                            set("mysql.password", secret)
                            set("mysql.port", 70_000)
                        },
                    ),
                    Triple(
                        "redis",
                        "redis",
                        {
                            set("redis.enabled", true)
                            set("redis.player-list-ttl-seconds", 31L)
                        },
                    ),
                    Triple(
                        "redis import",
                        "redis.import-arc-credentials",
                        {
                            set("redis.enabled", true)
                            set("redis.import-arc-credentials", true)
                        },
                    ),
                )

            invalidCandidates.forEach { (label, expectedMessage, edit) ->
                configFile.writeText(startupConfig)
                editConfig(plugin, edit)

                val rejected = plugin.reloadConfiguration()

                withClue(label) {
                    rejected.isFailure shouldBe true
                    rejected.exceptionOrNull()?.message.orEmpty().contains(expectedMessage, ignoreCase = true) shouldBe true
                    rejected.exceptionOrNull()?.message.orEmpty().contains(secret) shouldBe false
                    plugin.currentConfigGeneration() shouldBe 1L
                    plugin.config.getString("server-id") shouldBe "duels-1"
                    plugin.isEnabled shouldBe true
                }
            }
        }
    }

    "a group default must reference a kit in the same validated candidate" {
        withReloadPlugin { _, plugin ->
            editConfig(plugin) { set("multiplayer.defaults.kit", "missing-kit") }

            val rejected = plugin.reloadConfiguration()

            rejected.isFailure shouldBe true
            rejected.exceptionOrNull()?.message.orEmpty().contains("multiplayer.defaults.kit") shouldBe true
            plugin.currentConfigGeneration() shouldBe 1L
            plugin.isEnabled shouldBe true
        }
    }

    "only an external ARC Redis password change alters the effective restart fingerprint" {
        withReloadPlugin { _, plugin ->
            editConfig(plugin) {
                set("redis.enabled", true)
                set("redis.import-arc-credentials", true)
            }
            val arcRedis = File(plugin.dataFolder.parentFile, "ARC/modules/redis.yml")
            arcRedis.parentFile.mkdirs()
            editYaml(arcRedis) {
                set("host", "redis.internal")
                set("port", 6379)
                set("username", "classic")
                set("password", "first-secret")
            }
            val candidateConfig = YamlConfiguration().apply { load(File(plugin.dataFolder, "config.yml")) }
            val firstEffective =
                requireNotNull(
                    ArcDuelsRestartOnlySettingsValidator
                        .validateReloadEnvironment(plugin, candidateConfig)
                        .effectiveRedis,
                )
            val first = ArcDuelsRuntimeSettingsParser.parse(candidateConfig, firstEffective)
            val unchangedConfig = File(plugin.dataFolder, "config.yml").readText()

            editYaml(arcRedis) { set("password", "second-secret") }
            File(plugin.dataFolder, "config.yml").readText() shouldBe unchangedConfig
            val secondEffective =
                requireNotNull(
                    ArcDuelsRestartOnlySettingsValidator
                        .validateReloadEnvironment(plugin, candidateConfig)
                        .effectiveRedis,
                )
            val second = ArcDuelsRuntimeSettingsParser.parse(candidateConfig, secondEffective)

            second.restartRequiredComparedTo(first) shouldBe
                setOf(ArcDuelsRestartOnlyField.REDIS_CONNECTION)
            second.toString().contains("second-secret") shouldBe false
            second.restartOnlyFingerprint.toString().contains("second-secret") shouldBe false
        }
    }

    "malformed imported ARC Redis YAML is rejected before live state changes" {
        withReloadPlugin { _, plugin ->
            editConfig(plugin) {
                set("redis.enabled", true)
                set("redis.import-arc-credentials", true)
            }
            val arcRedis = File(plugin.dataFolder.parentFile, "ARC/modules/redis.yml")
            arcRedis.parentFile.mkdirs()
            arcRedis.writeText("redis: [unterminated")

            plugin.reloadConfiguration().isFailure shouldBe true
            plugin.currentConfigGeneration() shouldBe 1L
            plugin.config.getBoolean("redis.enabled") shouldBe false
            plugin.isEnabled shouldBe true
        }
    }

    "wrong imported ARC Redis section shape is rejected before live state changes" {
        withReloadPlugin { _, plugin ->
            editConfig(plugin) {
                set("redis.enabled", true)
                set("redis.import-arc-credentials", true)
            }
            val arcRedis = File(plugin.dataFolder.parentFile, "ARC/modules/redis.yml")
            arcRedis.parentFile.mkdirs()
            editYaml(arcRedis) { set("redis", false) }

            val rejected = plugin.reloadConfiguration()

            rejected.isFailure shouldBe true
            rejected.exceptionOrNull()?.message.orEmpty().contains("redis") shouldBe true
            plugin.currentConfigGeneration() shouldBe 1L
            plugin.config.getBoolean("redis.enabled") shouldBe false
            plugin.isEnabled shouldBe true
        }
    }

    "malformed loadout and locale YAML never replace the last known good generation" {
        withReloadPlugin { _, plugin ->
            val loadouts = File(plugin.dataFolder, "loadouts.yml")
            val originalLoadouts = loadouts.readText()
            loadouts.writeText("kits: [unterminated")

            plugin.reloadConfiguration().isFailure shouldBe true
            plugin.currentConfigGeneration() shouldBe 1L
            loadouts.writeText(originalLoadouts)

            editYaml(loadouts) { set("kits.classic.items.8", null) }
            val loadoutsBeforeRejectedLocale = loadouts.readText()
            val locale = File(plugin.dataFolder, "lang/ru.yml")
            locale.writeText("menu: [unterminated")
            plugin.reloadConfiguration().isFailure shouldBe true
            plugin.currentConfigGeneration() shouldBe 1L
            loadouts.readText() shouldBe loadoutsBeforeRejectedLocale
        }
    }

    "only a visibly open multiplayer draft blocks kit publication and it auto applies after close" {
        withReloadPlugin { paper, plugin ->
            val host = paper.server.addPlayer("DraftReloadHost")
            paper.server.addPlayer("DraftReloadAlpha")
            paper.server.addPlayer("DraftReloadBravo")
            host.performCommand("duel") shouldBe true
            host.clickReload(15)
            editYaml(File(plugin.dataFolder, "loadouts.yml")) {
                set("kits.classic.icon", "BLAZE_ROD")
            }

            val report = plugin.reloadConfiguration().getOrThrow()

            report.catalogsDeferred shouldBe true
            plugin.hasDeferredConfigurationCatalogs() shouldBe true
            host.closeInventory()
            paper.performTicks(20)
            plugin.hasDeferredConfigurationCatalogs() shouldBe false
            host.performCommand("duel") shouldBe true
            host.clickReload(15)
            host.openInventory.topInventory.getItem(32)?.type shouldBe Material.BLAZE_ROD
            plugin.isEnabled shouldBe true
        }
    }

    "a closed multiplayer draft normalizes a kit removed by an immediate reload" {
        withReloadPlugin { paper, plugin ->
            val loadouts = File(plugin.dataFolder, "loadouts.yml")
            editYaml(loadouts) {
                set("kits.aaa_custom.display-name", "<gold>Temporary</gold>")
                set("kits.aaa_custom.icon", "BLAZE_ROD")
                set("kits.aaa_custom.items.0", "STONE_SWORD")
            }
            editConfig(plugin) { set("multiplayer.defaults.kit", "aaa_custom") }
            plugin.reloadConfiguration().getOrThrow().catalogsDeferred shouldBe false
            val host = paper.server.addPlayer("ClosedDraftHost")
            host.performCommand("duel") shouldBe true
            host.clickReload(15)
            host.openInventory.topInventory.getItem(32)?.type shouldBe Material.BLAZE_ROD
            host.closeInventory()

            editYaml(loadouts) { set("kits.aaa_custom", null) }
            editConfig(plugin) { set("multiplayer.defaults.kit", "classic") }
            plugin.reloadConfiguration().getOrThrow().catalogsDeferred shouldBe false

            host.performCommand("duel") shouldBe true
            host.clickReload(15)
            host.openInventory.topInventory.getItem(32)?.type shouldBe Material.DIAMOND_SWORD
        }
    }

    "an open 1v1 rules menu pins its kit catalog until the menu closes" {
        withReloadPlugin { paper, plugin ->
            val loadouts = File(plugin.dataFolder, "loadouts.yml")
            editYaml(loadouts) {
                set("kits.aaa_custom.display-name", "<gold>Temporary</gold>")
                set("kits.aaa_custom.icon", "BLAZE_ROD")
                set("kits.aaa_custom.items.0", "STONE_SWORD")
            }
            plugin.reloadConfiguration().getOrThrow()
            val player = paper.server.addPlayer("RulesCatalogHost")
            paper.server.addPlayer("RulesTarget")
            player.performCommand("duel") shouldBe true
            player.clickReload(11)
            player.clickReload(10)
            player.clickReload(11)
            player.openInventory.topInventory.getItem(10)?.type shouldBe Material.BLAZE_ROD
            player.clickReload(10)
            player.openInventory.topInventory.getItem(40)?.type shouldBe Material.LIME_CONCRETE

            editYaml(loadouts) { set("kits.aaa_custom", null) }
            plugin.reloadConfiguration().getOrThrow().catalogsDeferred shouldBe true
            plugin.hasDeferredConfigurationCatalogs() shouldBe true

            player.closeInventory()
            paper.performTicks(20)
            plugin.hasDeferredConfigurationCatalogs() shouldBe false
            player.performCommand("duel RulesTarget") shouldBe true
            player.clickReload(11)
            player.openInventory.topInventory.getItem(10)?.type shouldBe Material.BOW
        }
    }

    "busy flow defers validated kits and applies the latest candidate automatically when idle" {
        withReloadPlugin { _, plugin ->
            val runtime = ArcDuelsRuntimeSettingsState(ArcDuelsRuntimeSettings.parse(plugin.config))
            val arenas = PaperArenaCatalog.load(plugin)
            val kits = KitRegistry.load(plugin)
            val locales = LocaleService.load(plugin)
            val serverNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning)
            val guiItems = GuiItemCatalog.load(plugin)
            val challenges = ChallengeRegistry(Clock.systemUTC(), runtime.snapshot().settings.challengeTimeout)
            var busy = true
            var catalogReplacementNotifications = 0
            val reloader =
                ArcDuelsConfigReloader(
                    plugin,
                    runtime,
                    arenas,
                    kits,
                    locales,
                    serverNames,
                    guiItems,
                    challenges,
                    afterCatalogReplacement = { catalogReplacementNotifications++ },
                ) {
                    ArcDuelsReloadActivity(
                        reservedArenas = if (busy) 1 else 0,
                        queuedMatches = 0,
                        pendingChallenges = 0,
                        multiplayerSessions = 0,
                        multiplayerFlows = 0,
                        duelGuiFlows = 0,
                        acceptedMatches = 0,
                    )
                }
            val previousIcon = kits.get(ru.ruscrafting.duels.domain.KitId("classic")).icon
            val previousFingerprint = requireNotNull(kits.fingerprint(ru.ruscrafting.duels.domain.KitId("classic")))
            previousFingerprint.matches(Regex("[0-9a-f]{64}")) shouldBe true
            editYaml(File(plugin.dataFolder, "loadouts.yml")) {
                set("kits.classic.icon", "BLAZE_ROD")
                set("kits.classic.items.2", "GOLDEN_APPLE 5")
            }
            editConfig(plugin) { set("multiplayer.defaults.kit", "axe") }

            val report = reloader.reload().getOrThrow()

            report.catalogsDeferred shouldBe true
            reloader.hasDeferredCatalogs() shouldBe true
            catalogReplacementNotifications shouldBe 0
            kits.get(ru.ruscrafting.duels.domain.KitId("classic")).icon shouldBe previousIcon
            kits.fingerprint(ru.ruscrafting.duels.domain.KitId("classic")) shouldBe previousFingerprint
            kits.defaultId() shouldBe ru.ruscrafting.duels.domain.KitId("classic")

            busy = false
            reloader.applyDeferredIfIdle() shouldBe true
            reloader.hasDeferredCatalogs() shouldBe false
            catalogReplacementNotifications shouldBe 1
            kits.get(ru.ruscrafting.duels.domain.KitId("classic")).icon shouldBe Material.BLAZE_ROD
            kits.fingerprint(ru.ruscrafting.duels.domain.KitId("classic")) shouldNotBe previousFingerprint
            kits.defaultId() shouldBe ru.ruscrafting.duels.domain.KitId("axe")
            reloader.applyDeferredIfIdle() shouldBe false
        }
    }

    "a file change between preparation and commit rejects the whole candidate" {
        withReloadPlugin { _, plugin ->
            val runtime = ArcDuelsRuntimeSettingsState(ArcDuelsRuntimeSettings.parse(plugin.config))
            val arenas = PaperArenaCatalog.load(plugin)
            val kits = KitRegistry.load(plugin)
            val locales = LocaleService.load(plugin)
            val serverNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning)
            val guiItems = GuiItemCatalog.load(plugin)
            val challenges = ChallengeRegistry(Clock.systemUTC(), runtime.snapshot().settings.challengeTimeout)
            editConfig(plugin) { set("countdown-seconds", 1) }
            val reloader =
                ArcDuelsConfigReloader(
                    plugin,
                    runtime,
                    arenas,
                    kits,
                    locales,
                    serverNames,
                    guiItems,
                    challenges,
                    beforeCommit = { editConfig(plugin) { set("countdown-seconds", 2) } },
                ) {
                    idleReloadActivity()
                }

            val rejected = reloader.reload()

            rejected.isFailure shouldBe true
            runtime.snapshot().generation shouldBe 1L
            runtime.snapshot().settings.countdownSeconds shouldBe 3
            plugin.config.getInt("countdown-seconds") shouldBe 3
            File(plugin.dataFolder, "config.yml").let(YamlConfiguration::loadConfiguration)
                .getInt("countdown-seconds") shouldBe 2
        }
    }

    "ABA disk edits cannot substitute bytes after the immutable source capture" {
        withReloadPlugin { _, plugin ->
            val runtime = ArcDuelsRuntimeSettingsState(ArcDuelsRuntimeSettings.parse(plugin.config))
            val arenas = PaperArenaCatalog.load(plugin)
            val kits = KitRegistry.load(plugin)
            val locales = LocaleService.load(plugin)
            val serverNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning)
            val guiItems = GuiItemCatalog.load(plugin)
            val challenges = ChallengeRegistry(Clock.systemUTC(), runtime.snapshot().settings.challengeTimeout)
            editConfig(plugin) { set("countdown-seconds", 1) }
            val loadouts = File(plugin.dataFolder, "loadouts.yml")
            val englishLocale = File(plugin.dataFolder, "lang/en.yml")
            editYaml(loadouts) { set("kits.classic.icon", "GOLDEN_SWORD") }
            editYaml(englishLocale) { set("menu.main.challenge", "<gold>ABA source A</gold>") }
            val sourceA = File(plugin.dataFolder, "config.yml").readText()
            val loadoutsA = loadouts.readText()
            val englishLocaleA = englishLocale.readText()
            val reloader =
                ArcDuelsConfigReloader(
                    plugin,
                    runtime,
                    arenas,
                    kits,
                    locales,
                    serverNames,
                    guiItems,
                    challenges,
                    afterSourceCapture = {
                        editConfig(plugin) { set("countdown-seconds", 9) }
                        editYaml(loadouts) { set("kits.classic.icon", "BLAZE_ROD") }
                        editYaml(englishLocale) { set("menu.main.challenge", "<red>ABA source B</red>") }
                    },
                    beforeCommit = {
                        File(plugin.dataFolder, "config.yml").writeText(sourceA)
                        loadouts.writeText(loadoutsA)
                        englishLocale.writeText(englishLocaleA)
                    },
                ) {
                    idleReloadActivity()
                }

            val report = reloader.reload().getOrThrow()

            report.generation shouldBe 2L
            runtime.snapshot().settings.countdownSeconds shouldBe 1
            plugin.config.getInt("countdown-seconds") shouldBe 1
            kits.get(ru.ruscrafting.duels.domain.KitId("classic")).icon shouldBe Material.GOLDEN_SWORD
            PlainTextComponentSerializer.plainText().serialize(
                locales.componentForLanguage("en", "menu.main.challenge"),
            ) shouldBe "ABA source A"
            File(plugin.dataFolder, "config.yml").let(YamlConfiguration::loadConfiguration)
                .getInt("countdown-seconds") shouldBe 1
        }
    }

    "late activity inspection failure leaves every live generation unchanged" {
        withReloadPlugin { _, plugin ->
            val runtime = ArcDuelsRuntimeSettingsState(ArcDuelsRuntimeSettings.parse(plugin.config))
            val arenas = PaperArenaCatalog.load(plugin)
            val kits = KitRegistry.load(plugin)
            val locales = LocaleService.load(plugin)
            val serverNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning)
            val guiItems = GuiItemCatalog.load(plugin)
            val challenges = ChallengeRegistry(Clock.systemUTC(), runtime.snapshot().settings.challengeTimeout)
            val previousArenaCount = arenas.size()
            val previousKitIcon = kits.get(ru.ruscrafting.duels.domain.KitId("classic")).icon
            editConfig(plugin) { set("countdown-seconds", 1) }
            editYaml(File(plugin.dataFolder, "loadouts.yml")) { set("kits.classic.icon", "BLAZE_ROD") }
            val reloader =
                ArcDuelsConfigReloader(
                    plugin,
                    runtime,
                    arenas,
                    kits,
                    locales,
                    serverNames,
                    guiItems,
                    challenges,
                ) {
                    error("planned activity failure")
                }

            val rejected = reloader.reload()

            rejected.isFailure shouldBe true
            runtime.snapshot().generation shouldBe 1L
            runtime.snapshot().settings.countdownSeconds shouldBe 3
            plugin.config.getInt("countdown-seconds") shouldBe 3
            arenas.size() shouldBe previousArenaCount
            kits.get(ru.ruscrafting.duels.domain.KitId("classic")).icon shouldBe previousKitIcon
            reloader.hasDeferredCatalogs() shouldBe false
        }
    }

    "a newer disk edit supersedes rather than being overwritten by deferred catalogs" {
        withReloadPlugin { _, plugin ->
            val runtime = ArcDuelsRuntimeSettingsState(ArcDuelsRuntimeSettings.parse(plugin.config))
            val arenas = PaperArenaCatalog.load(plugin)
            val kits = KitRegistry.load(plugin)
            val locales = LocaleService.load(plugin)
            val serverNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning)
            val guiItems = GuiItemCatalog.load(plugin)
            val challenges = ChallengeRegistry(Clock.systemUTC(), runtime.snapshot().settings.challengeTimeout)
            var busy = true
            val reloader =
                ArcDuelsConfigReloader(
                    plugin,
                    runtime,
                    arenas,
                    kits,
                    locales,
                    serverNames,
                    guiItems,
                    challenges,
                ) {
                    idleReloadActivity().copy(reservedArenas = if (busy) 1 else 0)
                }
            val loadouts = File(plugin.dataFolder, "loadouts.yml")
            editYaml(loadouts) { set("kits.classic.icon", "BLAZE_ROD") }
            reloader.reload().getOrThrow().catalogsDeferred shouldBe true

            // Simulates a newer console/editor or operator file change while A is waiting.
            editYaml(loadouts) { set("kits.classic.icon", "DIAMOND_HOE") }
            busy = false

            reloader.applyDeferredIfIdle() shouldBe true
            reloader.hasDeferredCatalogs() shouldBe false
            runtime.snapshot().generation shouldBe 3L
            kits.get(ru.ruscrafting.duels.domain.KitId("classic")).icon shouldBe Material.DIAMOND_HOE
        }
    }

    "malformed newer disk edit cannot discard the last validated deferred catalogs" {
        withReloadPlugin { _, plugin ->
            val runtime = ArcDuelsRuntimeSettingsState(ArcDuelsRuntimeSettings.parse(plugin.config))
            val arenas = PaperArenaCatalog.load(plugin)
            val kits = KitRegistry.load(plugin)
            val locales = LocaleService.load(plugin)
            val serverNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning)
            val guiItems = GuiItemCatalog.load(plugin)
            val challenges = ChallengeRegistry(Clock.systemUTC(), runtime.snapshot().settings.challengeTimeout)
            var busy = true
            val reloader =
                ArcDuelsConfigReloader(
                    plugin,
                    runtime,
                    arenas,
                    kits,
                    locales,
                    serverNames,
                    guiItems,
                    challenges,
                ) {
                    idleReloadActivity().copy(reservedArenas = if (busy) 1 else 0)
                }
            val loadouts = File(plugin.dataFolder, "loadouts.yml")
            val previousIcon = kits.get(ru.ruscrafting.duels.domain.KitId("classic")).icon
            editYaml(loadouts) { set("kits.classic.icon", "BLAZE_ROD") }
            val validatedA = loadouts.readText()
            reloader.reload().getOrThrow().catalogsDeferred shouldBe true

            loadouts.writeText("kits: [unterminated")
            busy = false

            reloader.applyDeferredIfIdle() shouldBe false
            reloader.hasDeferredCatalogs() shouldBe true
            kits.get(ru.ruscrafting.duels.domain.KitId("classic")).icon shouldBe previousIcon

            loadouts.writeText(validatedA)
            reloader.applyDeferredIfIdle() shouldBe true
            reloader.hasDeferredCatalogs() shouldBe false
            kits.get(ru.ruscrafting.duels.domain.KitId("classic")).icon shouldBe Material.BLAZE_ROD
        }
    }
})

private fun idleReloadActivity(): ArcDuelsReloadActivity =
    ArcDuelsReloadActivity(
        reservedArenas = 0,
        queuedMatches = 0,
        pendingChallenges = 0,
        multiplayerSessions = 0,
        multiplayerFlows = 0,
        duelGuiFlows = 0,
        acceptedMatches = 0,
    )

private fun withReloadPlugin(block: (MockBukkitTestRuntime, ArcDuelsPlugin) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        failOnUnsupportedMockBukkitOperation {
            block(paper, paper.loadPlugin<ArcDuelsPlugin>())
        }
    }
}

private fun PlayerMock.clickReload(slot: Int) =
    PlayerSimulation(this).simulateInventoryClick(openInventory, ClickType.LEFT, slot)

private fun editConfig(
    plugin: ArcDuelsPlugin,
    edit: YamlConfiguration.() -> Unit,
) = editYaml(File(plugin.dataFolder, "config.yml"), edit)

private fun editYaml(
    file: File,
    edit: YamlConfiguration.() -> Unit,
) {
    val configuration = YamlConfiguration.loadConfiguration(file)
    configuration.edit()
    configuration.save(file)
}
