package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Player

class KitHealthIsolationTest : StringSpec({
    "kit health is capped at twenty without removing external modifiers and restored afterwards" {
        val key = NamespacedKey("arcduels", "kit_health_cap")
        val attribute = mockk<AttributeInstance>()
        val player = mockk<Player>()
        var cap: AttributeModifier? = null
        var health = 40.0
        var absorption = 8.0
        every { player.getAttribute(Attribute.MAX_HEALTH) } returns attribute
        every { attribute.getModifier(key) } answers { cap }
        every { attribute.removeModifier(key) } answers { cap = null }
        every { attribute.addTransientModifier(any()) } answers { cap = arg<AttributeModifier>(0) }
        every { attribute.value } answers { 40.0 * (1.0 + (cap?.amount ?: 0.0)) }
        every { player.health } answers { health }
        every { player.health = any() } answers { health = arg<Double>(0) }
        every { player.absorptionAmount } answers { absorption }
        every { player.absorptionAmount = any() } answers { absorption = arg<Double>(0) }
        val isolation = KitHealthIsolation(key)

        isolation.enforce(player) shouldBe true

        requireNotNull(cap).operation shouldBe AttributeModifier.Operation.MULTIPLY_SCALAR_1
        attribute.value.shouldBeExactly(20.0)
        health.shouldBeExactly(20.0)
        absorption.shouldBeExactly(0.0)

        isolation.clear(player)
        attribute.value.shouldBeExactly(40.0)
    }
})
