package ru.ruscrafting.duels.domain

import java.time.Instant
import java.util.concurrent.CompletableFuture

sealed interface DuelEvent {
    val eventId: String
    val occurredAt: Instant
    val sourceServer: ServerId
}

data class MatchCompletedEvent(
    override val eventId: String,
    override val occurredAt: Instant,
    override val sourceServer: ServerId,
    val matchId: MatchId,
    val winner: PlayerId,
    val loser: PlayerId,
    val mode: DuelMode,
    val kitId: KitId?,
    val ranked: Boolean,
    val winnerRating: Int,
) : DuelEvent

data class LeaderboardInvalidatedEvent(
    override val eventId: String,
    override val occurredAt: Instant,
    override val sourceServer: ServerId,
    val revision: Long,
) : DuelEvent

fun interface DuelEventPublisher {
    fun publish(event: DuelEvent): CompletableFuture<Unit>
}

object NoOpDuelEventPublisher : DuelEventPublisher {
    override fun publish(event: DuelEvent): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
}
