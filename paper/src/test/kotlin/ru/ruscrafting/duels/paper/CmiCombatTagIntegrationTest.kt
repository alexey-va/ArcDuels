package ru.ruscrafting.duels.paper

import com.Zrips.CMI.Containers.CMIUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import java.util.UUID

class CmiCombatTagIntegrationTest : StringSpec({
    "reflective bridge expires both CMI combat timestamps and removes only the requested player" {
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
        CMIUser.reset()

        bridge.isTagged(firstId) shouldBe true
        bridge.remove(first) shouldBe true

        bridge.isTagged(firstId) shouldBe false
        bridge.isTagged(secondId) shouldBe true
        manager.timestampResetCount shouldBe 6
        manager.removeCount shouldBe 1
        CMIUser.removedBossBars(firstId) shouldBe listOf("pvptimer")
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
    var timestampResetCount: Int = 0
        private set
    var removeCount: Int = 0
        private set

    @Suppress("unused")
    fun removePlayerFromCombat(user: CMIUser) {
        check(user.playerId in taggedPlayers || timestampResetCount > 0)
        removeCount += 1
    }

    @Suppress("unused")
    fun isInCombatWithPlayer(playerId: UUID): Boolean = playerId in taggedPlayers

    @Suppress("unused")
    fun setGotLastDamageAt(playerId: UUID, timestamp: Long?) = expire(playerId, timestamp)

    @Suppress("unused")
    fun setGotLastDamageFromPlayer(playerId: UUID, timestamp: Long?) = expire(playerId, timestamp)

    @Suppress("unused")
    fun setDidLastDamageToPlayer(playerId: UUID, timestamp: Long?) = expire(playerId, timestamp)

    private fun expire(playerId: UUID, timestamp: Long?) {
        if (timestamp == 0L) {
            timestampResetCount += 1
            taggedPlayers -= playerId
        }
    }
}
