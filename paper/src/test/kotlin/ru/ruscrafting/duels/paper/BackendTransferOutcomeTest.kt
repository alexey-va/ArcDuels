package ru.ruscrafting.duels.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.paper.network.BackendTransferResult
import ru.ruscrafting.duels.domain.ServerId

class BackendTransferOutcomeTest : StringSpec({
    "only SENT is a successful backend transfer" {
        isSuccessfulBackendTransfer(BackendTransferResult.SENT) shouldBe true
        listOf(
            null,
            BackendTransferResult.PLAYER_OFFLINE,
            BackendTransferResult.TRANSFER_CLOSED,
            BackendTransferResult.SEND_FAILED,
        ).forEach { result ->
            isSuccessfulBackendTransfer(result) shouldBe false
        }
    }

    "automatic requests remain retryable after every non-sent outcome" {
        listOf(
            null,
            BackendTransferResult.PLAYER_OFFLINE,
            BackendTransferResult.TRANSFER_CLOSED,
            BackendTransferResult.SEND_FAILED,
        ).forEach { result ->
            val requests = mutableSetOf("player-transfer")

            retainSuccessfulTransferRequest(requests, "player-transfer", result) shouldBe false

            requests shouldBe emptySet()
            requests.add("player-transfer") shouldBe true
        }
    }

    "a sent automatic request remains recorded exactly once" {
        val requests = mutableSetOf("player-transfer")

        retainSuccessfulTransferRequest(requests, "player-transfer", BackendTransferResult.SENT) shouldBe true

        requests.shouldContainExactly("player-transfer")
        requests.add("player-transfer") shouldBe false
    }

    "Unit callbacks turn non-sent outcomes into bounded failures" {
        val destination = ServerId("parkour")
        requireBackendTransferSent(BackendTransferResult.SENT, destination)

        listOf(
            null to "UNAVAILABLE",
            BackendTransferResult.PLAYER_OFFLINE to "PLAYER_OFFLINE",
            BackendTransferResult.TRANSFER_CLOSED to "TRANSFER_CLOSED",
            BackendTransferResult.SEND_FAILED to "SEND_FAILED",
        ).forEach { (result, label) ->
            val failure = shouldThrow<IllegalStateException> {
                requireBackendTransferSent(result, destination)
            }

            failure.message shouldBe "Backend transfer failed: destination=parkour result=$label"
        }
    }
})
