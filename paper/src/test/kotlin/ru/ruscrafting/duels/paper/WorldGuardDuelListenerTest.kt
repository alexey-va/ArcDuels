package ru.ruscrafting.duels.paper

import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.DuelMatch
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MultiplayerKitPolicy
import ru.ruscrafting.duels.domain.MultiplayerLayout
import ru.ruscrafting.duels.domain.MultiplayerMatch
import ru.ruscrafting.duels.domain.MultiplayerParticipant
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.MultiplayerRules
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

class WorldGuardDuelListenerTest : StringSpec({
    "WorldGuard precursor denial permits an active in-bounds lava bucket" {
        val player = mockk<Player>()
        val clicked = mockk<Block>()
        val target = mockk<Block>()
        every { clicked.blockData } returns mockk()
        every { clicked.getRelative(BlockFace.UP) } returns target
        val sessions = mockk<DuelSessionManager>()
        every { sessions.allowsFluidPlacement(player, target, Material.LAVA_BUCKET) } returns true
        val event = mockk<PlayerInteractEvent>(relaxed = true)
        every { event.player } returns player
        every { event.action } returns Action.RIGHT_CLICK_BLOCK
        every { event.item } returns mockk<ItemStack> { every { type } returns Material.LAVA_BUCKET }
        every { event.clickedBlock } returns clicked
        every { event.blockFace } returns BlockFace.UP

        DuelFluidListener(sessions, overrideWorldGuard = true).onFluidInteract(event)

        verify(exactly = 1) { event.setUseItemInHand(Event.Result.ALLOW) }
    }

    "WorldGuard precursor denial stays in force outside the active arena" {
        val player = mockk<Player>()
        val clicked = mockk<Block>()
        val target = mockk<Block>()
        every { clicked.blockData } returns mockk()
        every { clicked.getRelative(BlockFace.UP) } returns target
        val sessions = mockk<DuelSessionManager>()
        every { sessions.allowsFluidPlacement(player, target, Material.WATER_BUCKET) } returns false
        val event = mockk<PlayerInteractEvent>(relaxed = true)
        every { event.player } returns player
        every { event.action } returns Action.RIGHT_CLICK_BLOCK
        every { event.item } returns mockk<ItemStack> { every { type } returns Material.WATER_BUCKET }
        every { event.clickedBlock } returns clicked
        every { event.blockFace } returns BlockFace.UP

        DuelFluidListener(sessions, overrideWorldGuard = true).onFluidInteract(event)

        verify(exactly = 0) { event.setUseItemInHand(any()) }
    }

    "WorldGuard bucket denial is overridden only for a tracked active-arena fluid source" {
        val player = mockk<Player>()
        val block = mockk<Block>()
        val sessions = mockk<DuelSessionManager>()
        every { sessions.allowsFluidPlacement(player, block, Material.LAVA_BUCKET) } returns true
        every { sessions.trackFluidPlacement(player, block) } returns true
        val event = mockk<PlayerBucketEmptyEvent>(relaxed = true)
        every { event.player } returns player
        every { event.block } returns block
        every { event.bucket } returns Material.LAVA_BUCKET
        every { event.isCancelled } returns true

        DuelFluidListener(sessions, overrideWorldGuard = true).onBucketEmpty(event)

        verify(exactly = 1) { event.isCancelled = false }
        verify(exactly = 1) { sessions.trackFluidPlacement(player, block) }
    }

    "tracked duel fluid cannot flow beyond its arena bounds" {
        val source = mockk<Block>()
        val destination = mockk<Block>()
        val sessions = mockk<DuelSessionManager>()
        every { sessions.trackFluidFlow(source, destination) } returns false
        val event = mockk<BlockFromToEvent>(relaxed = true)
        every { event.block } returns source
        every { event.toBlock } returns destination

        DuelFluidListener(sessions, overrideWorldGuard = true).onFluidFlow(event)

        verify(exactly = 1) { event.isCancelled = true }
    }

    "tracked lava and fluid reactions are admitted for rollback despite WorldGuard" {
        val source = mockk<Block>()
        val affected = mockk<Block>()
        every { source.type } returns Material.FIRE
        val sessions = mockk<DuelSessionManager>()
        every { sessions.trackFluidSideEffect(source, affected) } returns true
        every { sessions.trackFluidSideEffect(null, affected) } returns true
        val listener = DuelFluidListener(sessions, overrideWorldGuard = true)
        val form = mockk<BlockFormEvent>(relaxed = true)
        every { form.block } returns affected
        every { form.isCancelled } returns true
        val ignite = mockk<BlockIgniteEvent>(relaxed = true)
        every { ignite.cause } returns BlockIgniteEvent.IgniteCause.LAVA
        every { ignite.ignitingBlock } returns source
        every { ignite.block } returns affected
        every { ignite.isCancelled } returns true
        val spread = mockk<BlockSpreadEvent>(relaxed = true)
        every { spread.source } returns source
        every { spread.block } returns affected
        every { spread.isCancelled } returns true
        val burn = mockk<BlockBurnEvent>(relaxed = true)
        every { burn.ignitingBlock } returns source
        every { burn.block } returns affected
        every { burn.isCancelled } returns true

        listener.onFluidForm(form)
        listener.onLavaIgnite(ignite)
        listener.onFluidFireSpread(spread)
        listener.onFluidBurn(burn)

        verify(exactly = 1) { form.isCancelled = false }
        verify(exactly = 1) { ignite.isCancelled = false }
        verify(exactly = 1) { spread.isCancelled = false }
        verify(exactly = 1) { burn.isCancelled = false }
    }

    "WorldGuard denial is overridden only for participants of the same active arena match" {
        val attacker = mockk<Player>()
        val defender = mockk<Player>()
        val attackerId = PlayerId(UUID.randomUUID())
        val defenderId = PlayerId(UUID.randomUUID())
        val attackerLocation = mockk<Location>()
        val defenderLocation = mockk<Location>()
        every { attacker.uniqueId } returns attackerId.value
        every { defender.uniqueId } returns defenderId.value
        every { attacker.location } returns attackerLocation
        every { defender.location } returns defenderLocation
        val match =
            DuelMatch.reserve(
                attackerId,
                defenderId,
                ArenaId("arena"),
                ServerId("test"),
                DuelRules(DuelMode.OWN_INVENTORY),
                Instant.EPOCH,
            ).beginCountdown().activate(Instant.EPOCH)
        val sessions = mockk<DuelSessionManager>()
        every { sessions.matchFor(attacker) } returns match
        every { sessions.matchFor(defender) } returns match
        every { sessions.isInsideArena(attacker, attackerLocation) } returns true
        every { sessions.isInsideArena(defender, defenderLocation) } returns true
        val event = DisallowedPVPEvent(attacker, defender, mockk<Event>())

        WorldGuardDuelListener(sessions, Logger.getAnonymousLogger()).onDisallowedPvp(event)

        event.isCancelled shouldBe true
    }

    "WorldGuard denial remains in force outside the configured arena bounds" {
        val attacker = mockk<Player>()
        val defender = mockk<Player>()
        val attackerId = PlayerId(UUID.randomUUID())
        val defenderId = PlayerId(UUID.randomUUID())
        val attackerLocation = mockk<Location>()
        val defenderLocation = mockk<Location>()
        every { attacker.uniqueId } returns attackerId.value
        every { defender.uniqueId } returns defenderId.value
        every { attacker.location } returns attackerLocation
        every { defender.location } returns defenderLocation
        val match =
            DuelMatch.reserve(
                attackerId,
                defenderId,
                ArenaId("arena"),
                ServerId("test"),
                DuelRules(DuelMode.OWN_INVENTORY),
                Instant.EPOCH,
            ).beginCountdown().activate(Instant.EPOCH)
        val sessions = mockk<DuelSessionManager>()
        every { sessions.matchFor(attacker) } returns match
        every { sessions.matchFor(defender) } returns match
        every { sessions.isInsideArena(attacker, attackerLocation) } returns true
        every { sessions.isInsideArena(defender, defenderLocation) } returns false
        val event = DisallowedPVPEvent(attacker, defender, mockk<Event>())

        WorldGuardDuelListener(sessions, Logger.getAnonymousLogger()).onDisallowedPvp(event)

        event.isCancelled shouldBe false
    }

    "WorldGuard denial is overridden for one active in-bounds multiplayer match" {
        val attacker = mockk<Player>()
        val defender = mockk<Player>()
        val third = PlayerId(UUID.randomUUID())
        val attackerId = PlayerId(UUID.randomUUID())
        val defenderId = PlayerId(UUID.randomUUID())
        val attackerLocation = mockk<Location>()
        val defenderLocation = mockk<Location>()
        every { attacker.uniqueId } returns attackerId.value
        every { defender.uniqueId } returns defenderId.value
        every { attacker.location } returns attackerLocation
        every { defender.location } returns defenderLocation
        val kit = KitId("classic")
        val match =
            MultiplayerMatch.reserve(
                MatchId.random(),
                ArenaId("group-arena"),
                ServerId("test"),
                MultiplayerRoster(
                    MultiplayerRules(MultiplayerLayout.FREE_FOR_ALL, MultiplayerKitPolicy.SHARED, kit),
                    listOf(attackerId, defenderId, third).map { MultiplayerParticipant(it, kitId = kit) },
                ),
                Instant.EPOCH,
            ).beginCountdown().activate(Instant.EPOCH)
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        val multiplayer = mockk<MultiplayerSessionManager>()
        every { multiplayer.matchFor(attacker) } returns match
        every { multiplayer.matchFor(defender) } returns match
        every { multiplayer.isInsideArena(attacker, attackerLocation) } returns true
        every { multiplayer.isInsideArena(defender, defenderLocation) } returns true
        val event = DisallowedPVPEvent(attacker, defender, mockk<Event>())

        WorldGuardDuelListener(sessions, Logger.getAnonymousLogger(), multiplayer).onDisallowedPvp(event)

        event.isCancelled shouldBe true
    }
})
