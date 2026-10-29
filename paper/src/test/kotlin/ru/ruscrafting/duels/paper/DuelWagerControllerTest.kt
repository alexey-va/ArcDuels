package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.duels.domain.*
import java.time.Clock
import java.util.concurrent.CompletableFuture

class DuelWagerControllerTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: ArcDuelsPlugin
    beforeSpec {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.loadReadyDuelsPlugin()
    }
    afterSpec { paper.close() }

    "leaving during funding waits for the result then refunds without starting combat" {
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        val funding = CompletableFuture<WagerPrepareResult>()
        val money = mockk<DuelWagerService>()
        every { money.prepare(any()) } returns funding
        every { money.settle(any(), null) } returns CompletableFuture.completedFuture(WagerSettlementResult.Refunded)
        val first = paper.addReadyDuelsPlayer(plugin, "StakeSender")
        val second = paper.addReadyDuelsPlayer(plugin, "StakeTarget")
        val registry = ChallengeRegistry(Clock.systemUTC())
        val targets = DuelTargetDirectory(plugin, ServerId("test"), null)
        DuelController(plugin, registry, sessions, InMemoryStatisticsRepository(), LocaleService.load(plugin), targets,
            ServerId("test"), wagers = money).use { controller ->
            controller.challenge(first, targets.local(second), DuelRules(DuelMode.KIT, KitId("classic"), stakeMinor = 10000L))
            val challenge = registry.pendingFor(PlayerId(first.uniqueId)).single()
            controller.accept(second, challenge.id)
            paper.performTicks(1)
            verify(exactly = 1) { money.prepare(match { it.rules.stakeMinor == 10000L }) }
            controller.leave(first)
            funding.complete(WagerPrepareResult.Ready)
            paper.performTicks(1)
            verify(exactly = 0) { sessions.start(any()) }
            verify(exactly = 1) { money.settle(MatchId(challenge.id.value), null) }
        }
        first.disconnect()
        second.disconnect()
    }

    "insufficient funds cannot start a duel and free challenges do not call the wallet" {
        val sessions = mockk<DuelSessionManager>(relaxed = true)
        every { sessions.start(any()) } returns CompletableFuture.failedFuture(IllegalStateException("fixture start refused"))
        val money = mockk<DuelWagerService>()
        every { money.prepare(any()) } returns CompletableFuture.completedFuture(WagerPrepareResult.Rejected("insufficient_funds"))
        every { money.settle(any(), null) } returns CompletableFuture.completedFuture(WagerSettlementResult.Refunded)
        val first = paper.addReadyDuelsPlayer(plugin, "PoorSender")
        val second = paper.addReadyDuelsPlayer(plugin, "PoorTarget")
        val registry = ChallengeRegistry(Clock.systemUTC())
        val targets = DuelTargetDirectory(plugin, ServerId("test"), null)
        DuelController(plugin, registry, sessions, InMemoryStatisticsRepository(), LocaleService.load(plugin), targets,
            ServerId("test"), wagers = money).use { controller ->
            val rules = DuelRules(DuelMode.KIT, KitId("classic"), stakeMinor = 10000L)
            controller.challenge(first, targets.local(second), rules)
            controller.accept(second)
            paper.performTicks(1)
            verify(exactly = 0) { sessions.start(any()) }
            controller.challenge(first, targets.local(second), rules.copy(stakeMinor = 0L))
            controller.accept(second)
            paper.performTicks(1)
            verify(exactly = 1) { sessions.start(match { it.rules.stakeMinor == 0L }) }
            verify(exactly = 1) { money.prepare(any()) }
            registry.pendingCount shouldBe 0
        }
        first.disconnect()
        second.disconnect()
    }
})
