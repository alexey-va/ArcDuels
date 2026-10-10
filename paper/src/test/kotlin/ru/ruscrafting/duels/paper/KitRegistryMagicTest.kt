package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.staffspells.api.ArcStaffSpellService
import ru.arc.staffspells.api.StaffSpellDuelContext
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MatchScore
import ru.ruscrafting.duels.domain.MatchState
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import java.io.InputStreamReader
import java.time.Instant
import java.util.UUID

class KitRegistryMagicTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: ArcDuelsPlugin

    beforeSpec {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.loadPlugin()
    }

    afterSpec {
        paper.close()
    }

    "magic kits are unavailable without ARC StaffSpells while ordinary kits stay loaded" {
        val registry = KitRegistry.loadCandidate(
            plugin,
            plugin.config,
            loadoutsWithMagicKit(plugin),
            staffItems = null,
        )

        registry.contains(KitId("registry_magic_test")) shouldBe false
        registry.contains(KitId("classic")) shouldBe true
        registry.get(KitId("classic")).items.getValue(0).type shouldBe Material.DIAMOND_SWORD
        registry.defaultId() shouldBe KitId("classic")
    }

    "provider materializes staff items and only changes magic fingerprints" {
        val loadouts = loadoutsWithMagicKit(plugin)
        val first = KitRegistry.loadCandidate(
            plugin,
            plugin.config,
            loadouts,
            StaffSpellItemProvider({ ItemStack(Material.BLAZE_ROD) }, "staff-provider-v1"),
        )
        val compatibleUpdate = KitRegistry.loadCandidate(
            plugin,
            plugin.config,
            loadoutsWithMagicKit(plugin),
            StaffSpellItemProvider({ ItemStack(Material.BLAZE_ROD) }, "staff-provider-v2"),
        )

        val magic = first.get(KitId("registry_magic_test"))
        magic.magic shouldBe true
        magic.staffSpells shouldBe mapOf(0 to "chain")
        magic.items.getValue(0).type shouldBe Material.BLAZE_ROD
        (first.fingerprint(KitId("registry_magic_test")) == compatibleUpdate.fingerprint(KitId("registry_magic_test"))) shouldBe false
        first.fingerprint(KitId("classic")) shouldBe compatibleUpdate.fingerprint(KitId("classic"))

        val changedMultiplier = loadoutsWithMagicKit(plugin).apply { set("kits.registry_magic_test.magic-damage-multiplier", 0.5) }
        val changed = KitRegistry.loadCandidate(
            plugin,
            plugin.config,
            changedMultiplier,
            StaffSpellItemProvider({ ItemStack(Material.BLAZE_ROD) }, "staff-provider-v1"),
        )
        (changed.fingerprint(KitId("registry_magic_test")) == first.fingerprint(KitId("registry_magic_test"))) shouldBe false
        changed.fingerprint(KitId("classic")) shouldBe first.fingerprint(KitId("classic"))
    }

    "missing provider rejects a magic default and invalid spell ids fail validation" {
        val defaultMagic = YamlConfiguration().apply {
            loadFromString(plugin.config.saveToString())
            set("multiplayer.defaults.kit", "registry_magic_test")
        }
        val failure = shouldThrow<IllegalArgumentException> {
            KitRegistry.loadCandidate(plugin, defaultMagic, loadoutsWithMagicKit(plugin), staffItems = null)
        }
        failure.message?.contains("requires the ARC StaffSpells duel API") shouldBe true

        val invalidSpell = loadoutsWithMagicKit(plugin).apply { set("kits.registry_magic_test.items.0.staff-spell", "unknown") }
        shouldThrow<IllegalArgumentException> {
            KitRegistry.loadCandidate(plugin, plugin.config, invalidSpell, staffItems = null)
        }
    }

    "bridge binds hits to the current active duel and rejects the previous round context" {
        val caster = paper.server.addPlayer("MagicCaster")
        val target = paper.server.addPlayer("MagicTarget")
        val outsider = paper.server.addPlayer("MagicOutsider")
        val match = DuelMatch(
            id = MatchId(UUID.randomUUID()),
            firstPlayer = PlayerId(caster.uniqueId),
            secondPlayer = PlayerId(target.uniqueId),
            arenaId = ArenaId("magic_test"),
            serverId = ServerId("duels-1"),
            rules = DuelRules(DuelMode.KIT, KitId("registry_magic_test")),
            state = MatchState.ACTIVE,
            score = MatchScore(),
            createdAt = Instant.now(),
        )
        val duelSessions = mockk<DuelSessionManager>()
        val multiplayerSessions = mockk<MultiplayerSessionManager>()
        val spellService = mockk<ArcStaffSpellService>()
        every { duelSessions.attachMagicDuelStateReset(any()) } returns AutoCloseable { }
        every { multiplayerSessions.attachMagicDuelStateReset(any()) } returns AutoCloseable { }
        every { spellService.isAvailable() } returns true
        every { duelSessions.matchFor(caster) } returns match
        every { duelSessions.matchFor(target) } returns match
        every { duelSessions.matchFor(outsider) } returns null
        var currentRound = 2
        every { duelSessions.magicDuelRound(caster) } answers { currentRound }
        every { multiplayerSessions.matchFor(any<Player>()) } returns null
        every { spellService.registerDuelPolicy(plugin, any()) } returns AutoCloseable { }

        val kits = KitRegistry.loadCandidate(
            plugin,
            plugin.config,
            loadoutsWithMagicKit(plugin),
            StaffSpellItemProvider({ ItemStack(Material.BLAZE_ROD) }, "staff-provider-v1"),
        )
        val bridge = MagicDuelCombatBridge(plugin, spellService, duelSessions, multiplayerSessions, kits)
        val firstRound = StaffSpellDuelContext(match.id.value, 2, 0.4)

        bridge.activeContext(caster) shouldBe firstRound
        bridge.canHit(caster, target, firstRound) shouldBe true
        bridge.canHit(caster, outsider, firstRound) shouldBe false

        currentRound = 3
        val nextRound = bridge.activeContext(caster)
        nextRound shouldBe StaffSpellDuelContext(match.id.value, 3, 0.4)
        bridge.canHit(caster, target, firstRound) shouldBe false
        bridge.canHit(caster, target, nextRound!!) shouldBe true
        bridge.close()
    }

    "unavailable ARC spell providers are ignored" {
        val service = mockk<ArcStaffSpellService>()
        every { service.isAvailable() } returns false

        MagicDuelCombatBridge.availableProvider(service) shouldBe null
    }
})

private fun loadoutsWithMagicKit(plugin: ArcDuelsPlugin): YamlConfiguration = YamlConfiguration().apply {
    requireNotNull(plugin.getResource("loadouts.yml")).use { input ->
        load(InputStreamReader(input, Charsets.UTF_8))
    }
    set("kits.registry_magic_test.display-name", "<gold>Магическая дуэль</gold>")
    set("kits.registry_magic_test.icon", "BLAZE_ROD")
    set("kits.registry_magic_test.magic", true)
    set("kits.registry_magic_test.magic-damage-multiplier", 0.4)
    set("kits.registry_magic_test.items.0.staff-spell", "chain")
}
