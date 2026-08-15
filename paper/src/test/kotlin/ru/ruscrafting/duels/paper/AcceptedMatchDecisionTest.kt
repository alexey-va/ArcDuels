package ru.ruscrafting.duels.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.duels.domain.ServerId

class AcceptedMatchDecisionTest : StringSpec({
    val ready = AcceptedParticipantReadiness(stateLocked = false, playerDataReady = true, engaged = false)

    "accepted match starts only when exactly two players are fully ready" {
        acceptedMatchDecision(listOf(ready, ready)) shouldBe AcceptedMatchDecision.START
        acceptedMatchDecision(listOf(ready)) shouldBe AcceptedMatchDecision.WAIT
        acceptedMatchDecision(listOf(ready, ready, ready)) shouldBe AcceptedMatchDecision.WAIT
    }

    "accepted match waits while either player data or duel state is locked" {
        val awaitingSync = ready.copy(playerDataReady = false)
        val restoring = ready.copy(stateLocked = true)

        acceptedMatchDecision(listOf(ready, awaitingSync)) shouldBe AcceptedMatchDecision.WAIT
        acceptedMatchDecision(listOf(restoring, ready)) shouldBe AcceptedMatchDecision.WAIT
    }

    "accepted match is cancelled if either player became engaged elsewhere" {
        acceptedMatchDecision(listOf(ready, ready.copy(engaged = true))) shouldBe AcceptedMatchDecision.CANCEL_BUSY
    }

    "recorded challenge origin wins over mutable network presence" {
        val recorded = ServerId("survival")

        selectOriginServer(recorded, ServerId("arena-host"), ServerId("fallback")) shouldBe recorded
        selectOriginServer(null, ServerId("observed"), ServerId("fallback")) shouldBe ServerId("observed")
    }

    "a rematch accepted from the arena lobby must pass through origin recovery" {
        val local = ServerId("parkour")

        shouldStartDirectLocalMatch(true, false, local, local) shouldBe true
        shouldStartDirectLocalMatch(true, true, local, local) shouldBe false
        shouldStartDirectLocalMatch(false, false, local, local) shouldBe false
    }
})
