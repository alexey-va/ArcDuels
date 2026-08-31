package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.Locale
import java.util.UUID

class MultiplayerLobbyRaceMockBukkitTest : StringSpec({
    "host quit invalidates every invitation and immediately releases invitees" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "Alpha", "Bravo")).use { harness ->
                    val gui = harness.registerGui()
                    val host = harness.players[0].apply { setLocale(Locale.ENGLISH) }
                    val alpha = harness.players[1]
                    val bravo = harness.players[2]

                    gui.open(host)
                    host.click(10)
                    host.click(11)
                    host.click(34)
                    val lobbyId = alpha.takeInvitationLobbyId()
                    bravo.takeInvitationLobbyId() shouldBe lobbyId

                    host.disconnect() shouldBe true
                    listOf(alpha, bravo).forEach { invitee ->
                        gui.openInvitation(invitee, lobbyId)
                        invitee.openInventory.topInventory shouldBe null
                        gui.open(invitee)
                        invitee.openInventory.topInventory.getItem(36)?.type shouldBe Material.BLUE_STAINED_GLASS_PANE
                    }
                    gui.close()
                }
            }
        }
    }

    "offline selection is pruned while the remaining draft survives and can invite a replacement" {
        MockBukkitTestRuntime.open().use { paper ->
            failOnUnsupportedMockBukkitOperation {
                multiplayerHarness(paper, listOf("GroupHost", "Alpha", "Bravo", "Charlie")).use { harness ->
                    val gui = harness.registerGui()
                    val host = harness.players[0].apply { setLocale(Locale.ENGLISH) }
                    val alpha = harness.players[1]
                    val bravo = harness.players[2]
                    val charlie = harness.players[3]

                    gui.open(host)
                    host.click(10)
                    host.click(11)
                    alpha.disconnect() shouldBe true
                    host.click(34)

                    requireNotNull(
                        host.openInventory.topInventory.contents.single { it.plainName() == "Bravo" },
                    ).type shouldBe Material.PLAYER_HEAD
                    val charlieSlot = host.openInventory.topInventory.contents.indexOfFirst { it.plainName() == "Charlie" }
                    host.click(charlieSlot)
                    host.click(34)

                    val lobbyId = bravo.takeInvitationLobbyId()
                    charlie.takeInvitationLobbyId() shouldBe lobbyId
                    host.openInventory.topInventory.getItem(34)?.type shouldBe Material.CLOCK
                    gui.close()
                }
            }
        }
    }
})

private fun PlayerMock.takeInvitationLobbyId(): UUID =
    UUID.fromString(
        requireNotNull(nextComponentMessage()).runCommands()
            .first { it.startsWith("/duel group open ") }
            .substringAfterLast(' '),
    )
