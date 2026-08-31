package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Projectile
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
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
                    participants[1].gameMode shouldBe GameMode.SPECTATOR
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

    "pre-cancelled enemy damage is ignored and cannot eliminate an active participant" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper).use { harness ->
                    harness.registerGameplayListener()
                    harness.startAndArrive(ffaRoster(harness.players))
                    val victim = harness.players[0]
                    val attacker = harness.players[1]
                    val preCancelled = EntityDamageByEntityEvent(
                        attacker,
                        victim,
                        EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                        20.0,
                    ).also { it.isCancelled = true }

                    paper.callEvent(preCancelled)

                    preCancelled.isCancelled shouldBe true
                    victim.gameMode shouldBe GameMode.SURVIVAL
                    requireNotNull(harness.manager.matchFor(victim)).activePlayers.size shouldBe 4
                }
            }
        }
    }

    "active damage eliminates exactly at final-health threshold while a lower hit leaves the player active" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper).use { harness ->
                    harness.registerGameplayListener()
                    harness.startAndArrive(ffaRoster(harness.players))
                    val victim = harness.players[0]
                    victim.health = 1.0
                    val below = EntityDamageEvent(
                        victim,
                        EntityDamageEvent.DamageCause.FALL,
                        DamageSource.builder(DamageType.FALL).build(),
                        0.5,
                    )

                    below.finalDamage shouldBe 0.5
                    paper.callEvent(below).isCancelled shouldBe false
                    victim.gameMode shouldBe GameMode.SURVIVAL

                    val exact = EntityDamageEvent(
                        victim,
                        EntityDamageEvent.DamageCause.FALL,
                        DamageSource.builder(DamageType.FALL).build(),
                        1.0,
                    )
                    exact.finalDamage shouldBe victim.health
                    paper.callEvent(exact).isCancelled shouldBe true
                    victim.gameMode shouldBe GameMode.SPECTATOR
                    requireNotNull(harness.manager.matchFor(harness.players[1])).activePlayers.size shouldBe 3
                }
            }
        }
    }

    "countdown and completing damage are cancelled without additional eliminations" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, countdownSeconds = 1).use { harness ->
                    harness.registerGameplayListener()
                    val roster = ffaRoster(harness.players)
                    harness.manager.start(roster, harness.players.associateBy { ru.ruscrafting.duels.domain.PlayerId(it.uniqueId) })
                    paper.performTicks(4)
                    harness.teleports.completeAll()
                    paper.performTicks(1)
                    val countdownPlayer = harness.players[0]
                    requireNotNull(harness.manager.matchFor(countdownPlayer)).state shouldBe MultiplayerMatchState.COUNTDOWN
                    val countdownDamage = EntityDamageEvent(
                        countdownPlayer,
                        EntityDamageEvent.DamageCause.FALL,
                        DamageSource.builder(DamageType.FALL).build(),
                        1.0,
                    )
                    paper.callEvent(countdownDamage).isCancelled shouldBe true
                    countdownPlayer.gameMode shouldBe GameMode.SURVIVAL

                    paper.performTicks(20)
                    harness.players.drop(1).forEach { player ->
                        paper.callEvent(
                            EntityDamageEvent(
                                player,
                                EntityDamageEvent.DamageCause.FALL,
                                DamageSource.builder(DamageType.FALL).build(),
                                20.0,
                            ),
                        ).isCancelled shouldBe true
                    }
                    val completing = requireNotNull(harness.manager.matchFor(countdownPlayer))
                    completing.state shouldBe MultiplayerMatchState.COMPLETING
                    val activeBefore = completing.activePlayers

                    paper.callEvent(
                        EntityDamageEvent(
                            countdownPlayer,
                            EntityDamageEvent.DamageCause.FALL,
                            DamageSource.builder(DamageType.FALL).build(),
                            1.0,
                        ),
                    ).isCancelled shouldBe true
                    requireNotNull(harness.manager.matchFor(countdownPlayer)).activePlayers shouldBe activeBefore
                    countdownPlayer.gameMode shouldBe GameMode.SURVIVAL
                }
            }
        }
    }

    "locked participants cannot click or drag inventories while outsider inventory events remain available" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("One", "Two", "Three", "Four", "Outsider")).use { harness ->
                    harness.registerGameplayListener()
                    harness.startAndArrive(ffaRoster(harness.players.take(4)))
                    val participant = harness.players[0]
                    val outsider = harness.players[4]
                    val participantView = requireNotNull(participant.openInventory(Bukkit.createInventory(null, 9)))
                    val outsiderView = requireNotNull(outsider.openInventory(Bukkit.createInventory(null, 9)))
                    val item = ItemStack(Material.DIAMOND)

                    paper.callEvent(
                        InventoryClickEvent(
                            participantView,
                            InventoryType.SlotType.CONTAINER,
                            0,
                            ClickType.SHIFT_LEFT,
                            InventoryAction.MOVE_TO_OTHER_INVENTORY,
                        ),
                    ).isCancelled shouldBe true
                    paper.callEvent(
                        InventoryDragEvent(participantView, item, ItemStack(Material.AIR), true, mapOf(0 to item)),
                    ).isCancelled shouldBe true
                    paper.callEvent(
                        InventoryClickEvent(
                            outsiderView,
                            InventoryType.SlotType.CONTAINER,
                            0,
                            ClickType.SHIFT_LEFT,
                            InventoryAction.MOVE_TO_OTHER_INVENTORY,
                        ),
                    ).isCancelled shouldBe false
                    paper.callEvent(
                        InventoryDragEvent(outsiderView, item, ItemStack(Material.AIR), true, mapOf(0 to item)),
                    ).isCancelled shouldBe false
                }
            }
        }
    }

    "locked command parsing permits safe commands, blocks unsafe commands, honors bypass, and recognizes namespaced duel leave" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper).use { harness ->
                    harness.registerGameplayListener()
                    harness.startAndArrive(ffaRoster(harness.players))
                    val player = harness.players[0]

                    paper.callEvent(PlayerCommandPreprocessEvent(player, "/duels stats")).isCancelled shouldBe false
                    paper.callEvent(PlayerCommandPreprocessEvent(player, "/spawn")).isCancelled shouldBe true
                    val bypass = player.addAttachment(harness.plugin, "arcduels.bypass", true)
                    paper.callEvent(PlayerCommandPreprocessEvent(player, "/spawn")).isCancelled shouldBe false
                    player.removeAttachment(bypass)

                    paper.callEvent(PlayerCommandPreprocessEvent(player, "/minecraft:duel leave")).isCancelled shouldBe true
                    player.gameMode shouldBe GameMode.SPECTATOR
                }
            }
        }
    }

    "countdown preserves rotation-only moves and clamps coordinate movement while retaining requested rotation" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, countdownSeconds = 1).use { harness ->
                    harness.registerGameplayListener()
                    val roster = ffaRoster(harness.players)
                    harness.manager.start(roster, harness.players.associateBy { ru.ruscrafting.duels.domain.PlayerId(it.uniqueId) })
                    paper.performTicks(4)
                    harness.teleports.completeAll()
                    paper.performTicks(1)
                    val player = harness.players[0]
                    val anchor = requireNotNull(harness.manager.anchor(player))
                    val rotationOnly = anchor.clone().apply { yaw = 80f; pitch = 25f }

                    val rotationEvent = paper.callEvent(PlayerMoveEvent(player, anchor, rotationOnly))
                    rotationEvent.to.x shouldBe anchor.x
                    rotationEvent.to.yaw shouldBe 80f
                    rotationEvent.to.pitch shouldBe 25f

                    val coordinateEvent = paper.callEvent(
                        PlayerMoveEvent(player, anchor, anchor.clone().add(3.0, 0.0, 0.0).apply { yaw = 135f; pitch = -10f }),
                    )
                    coordinateEvent.to.x shouldBe anchor.x
                    coordinateEvent.to.yaw shouldBe 135f
                    coordinateEvent.to.pitch shouldBe -10f
                }
            }
        }
    }
})
