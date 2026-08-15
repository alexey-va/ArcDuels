package ru.ruscrafting.duels.paper

import com.Zrips.CMI.Containers.CMIUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import java.util.UUID

class CmiCombatTagIntegrationTest : StringSpec({
    "reflective bridge matches CMI combat-manager contract and removes only the requested player" {
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()
        val manager = FakePlayerCombatManager(mutableSetOf(firstId, secondId))
        val bridge =
            CmiCombatTagIntegration.ReflectiveBridge.create(
                FakeCmi(manager),
                CMIUser::class.java.classLoader,
            )
        val first = mockk<Player>()
        every { first.uniqueId } returns firstId

        bridge.isTagged(firstId) shouldBe true
        bridge.remove(first)

        bridge.isTagged(firstId) shouldBe false
        bridge.isTagged(secondId) shouldBe true
    }
})

private class FakeCmi(
    private val manager: FakePlayerCombatManager,
) {
    @Suppress("unused")
    fun getPlayerCombatManager(): FakePlayerCombatManager = manager
}

private class FakePlayerCombatManager(
    private val taggedPlayers: MutableSet<UUID>,
) {
    @Suppress("unused")
    fun removePlayerFromCombat(user: CMIUser) {
        taggedPlayers -= user.playerId
    }

    @Suppress("unused")
    fun isInCombatWithPlayer(playerId: UUID): Boolean = playerId in taggedPlayers
}
