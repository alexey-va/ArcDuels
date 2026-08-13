package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.redis.RedisManager
import ru.ruscrafting.duels.redis.CrossServerDuelBus

class NetworkLifecycleTest : StringSpec({
    "failed Redis initialization closes every resource and remains fail-soft" {
        val manager = mockk<RedisManager>(relaxed = true)
        val bus = mockk<CrossServerDuelBus>(relaxed = true)
        val failure = IllegalStateException("redis unavailable")
        val observed = mutableListOf<Throwable>()
        every { manager.init() } throws failure

        NetworkLifecycle.initialize(manager, bus, observed::add) shouldBe false

        observed shouldBe listOf(failure)
        verify(exactly = 1) { bus.close() }
        verify(exactly = 1) { manager.close() }
    }

    "cleanup attempts the manager even when bus cleanup fails" {
        val manager = mockk<RedisManager>(relaxed = true)
        val bus = mockk<CrossServerDuelBus>(relaxed = true)
        every { bus.close() } throws IllegalStateException("bus close failed")

        NetworkLifecycle.close(bus, manager).isFailure shouldBe true

        verify(exactly = 1) { bus.close() }
        verify(exactly = 1) { manager.close() }
    }
})
