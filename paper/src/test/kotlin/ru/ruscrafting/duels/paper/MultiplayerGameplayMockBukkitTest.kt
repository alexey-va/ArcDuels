package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.duels.domain.MultiplayerMatchState

@Suppress("DEPRECATION")
class MultiplayerGameplayMockBukkitTest : StringSpec({
    "active participants cannot drop pickup swap or mutate arena blocks while outsiders can" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("TeamOneA", "TeamTwoA", "TeamOneB", "TeamTwoB", "Outsider")).use { harness ->
                    harness.registerGameplayListener()
                    harness.startAndArrive(teamRoster(harness.players.take(4)))
                    val participant = harness.players[0]
                    val outsider = harness.players[4]
                    val world = requireNotNull(participant.world)
                    val item = world.dropItem(participant.location, ItemStack(Material.DIAMOND))
                    val block = world.getBlockAt(3, 70, 3).also { it.type = Material.STONE }
                    val outsideBlock = world.getBlockAt(4, 70, 4).also { it.type = Material.STONE }

                    paper.callEvent(PlayerDropItemEvent(participant, item)).isCancelled shouldBe true
                    paper.callEvent(EntityPickupItemEvent(participant, item, 0)).isCancelled shouldBe true
                    paper.callEvent(PlayerSwapHandItemsEvent(participant, ItemStack(Material.STONE), ItemStack(Material.DIRT))).isCancelled shouldBe true
                    requireNotNull(participant.simulateBlockBreak(block)).isCancelled shouldBe true
                    requireNotNull(participant.simulateBlockPlace(Material.COBBLESTONE, block.location.clone().add(1.0, 0.0, 0.0))).isCancelled shouldBe true

                    paper.callEvent(PlayerDropItemEvent(outsider, item)).isCancelled shouldBe false
                    paper.callEvent(EntityPickupItemEvent(outsider, item, 0)).isCancelled shouldBe false
                    paper.callEvent(PlayerSwapHandItemsEvent(outsider, ItemStack(Material.STONE), ItemStack(Material.DIRT))).isCancelled shouldBe false
                    requireNotNull(outsider.simulateBlockBreak(outsideBlock)).isCancelled shouldBe false
                    requireNotNull(outsider.simulateBlockPlace(Material.COBBLESTONE, outsideBlock.location.clone().add(1.0, 0.0, 0.0))).isCancelled shouldBe false
                }
            }
        }
    }

    "bucket and block interactions are frozen during countdown then use items without mutating blocks when active" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, countdownSeconds = 1).use { harness ->
                    harness.registerGameplayListener()
                    val roster = ffaRoster(harness.players)
                    harness.manager.start(roster, harness.players.associateBy { ru.ruscrafting.duels.domain.PlayerId(it.uniqueId) })
                    paper.performTicks(4)
                    harness.teleports.completeAll()
                    paper.performTicks(1)
                    val player = harness.players.first()
                    val block = requireNotNull(player.world).getBlockAt(2, 70, 2).also { it.type = Material.STONE }
                    requireNotNull(harness.manager.matchFor(player)).state shouldBe MultiplayerMatchState.COUNTDOWN

                    val countdownInteract = PlayerInteractEvent(
                        player,
                        Action.RIGHT_CLICK_BLOCK,
                        ItemStack(Material.GOLDEN_APPLE),
                        block,
                        org.bukkit.block.BlockFace.UP,
                        EquipmentSlot.HAND,
                    )
                    paper.callEvent(countdownInteract).isCancelled shouldBe true
                    paper.callEvent(
                        PlayerBucketEmptyEvent(
                            player,
                            block,
                            block,
                            org.bukkit.block.BlockFace.UP,
                            Material.WATER,
                            ItemStack(Material.WATER_BUCKET),
                            EquipmentSlot.HAND,
                        ),
                    ).isCancelled shouldBe true
                    paper.callEvent(
                        PlayerBucketFillEvent(
                            player,
                            block,
                            block,
                            org.bukkit.block.BlockFace.UP,
                            Material.WATER,
                            ItemStack(Material.BUCKET),
                            EquipmentSlot.HAND,
                        ),
                    ).isCancelled shouldBe true

                    paper.performTicks(20)
                    requireNotNull(harness.manager.matchFor(player)).state shouldBe MultiplayerMatchState.ACTIVE
                    val activeInteract = PlayerInteractEvent(
                        player,
                        Action.RIGHT_CLICK_BLOCK,
                        ItemStack(Material.GOLDEN_APPLE),
                        block,
                        org.bukkit.block.BlockFace.UP,
                        EquipmentSlot.HAND,
                    )
                    paper.callEvent(activeInteract).isCancelled shouldBe true
                    activeInteract.useInteractedBlock() shouldBe Event.Result.DENY
                    activeInteract.useItemInHand() shouldBe Event.Result.DEFAULT

                    val airInteract = PlayerInteractEvent(
                        player,
                        Action.RIGHT_CLICK_AIR,
                        ItemStack(Material.GOLDEN_APPLE),
                        null,
                        org.bukkit.block.BlockFace.SELF,
                        EquipmentSlot.HAND,
                    )
                    paper.callEvent(airInteract)
                    airInteract.useItemInHand() shouldBe Event.Result.DEFAULT
                }
            }
        }
    }

    "projectiles follow team hostility and environmental lethal damage eliminates exactly once" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(
                    paper,
                    listOf("TeamOneA", "TeamTwoA", "TeamOneB", "TeamTwoB", "Outsider"),
                ).use { harness ->
                    harness.registerGameplayListener()
                    val participants = harness.players.take(4)
                    harness.startAndArrive(teamRoster(participants))
                    fun arrowDamage(attackerIndex: Int): EntityDamageByEntityEvent {
                        val arrow = mockk<Projectile>(relaxed = true)
                        every { arrow.shooter } returns harness.players[attackerIndex]
                        return EntityDamageByEntityEvent(
                            arrow,
                            participants[0],
                            EntityDamageEvent.DamageCause.PROJECTILE,
                            4.0,
                        )
                    }

                    paper.callEvent(arrowDamage(2)).isCancelled shouldBe true
                    paper.callEvent(arrowDamage(1)).isCancelled shouldBe false
                    paper.callEvent(arrowDamage(4)).isCancelled shouldBe true

                    val lethal = EntityDamageEvent(
                        participants[1],
                        EntityDamageEvent.DamageCause.FALL,
                        DamageSource.builder(DamageType.FALL).build(),
                        20.0,
                    )
                    paper.callEvent(lethal).isCancelled shouldBe true
                    participants[1].gameMode shouldBe org.bukkit.GameMode.SPECTATOR
                    val activeAfterFirst = requireNotNull(harness.manager.matchFor(participants[0])).activePlayers

                    paper.callEvent(
                        EntityDamageEvent(
                            participants[1],
                            EntityDamageEvent.DamageCause.FALL,
                            DamageSource.builder(DamageType.FALL).build(),
                            20.0,
                        ),
                    ).isCancelled shouldBe true
                    requireNotNull(harness.manager.matchFor(participants[0])).activePlayers shouldBe activeAfterFirst
                }
            }
        }
    }
})
