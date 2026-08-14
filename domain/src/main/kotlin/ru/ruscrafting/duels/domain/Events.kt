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
    val objective: DuelObjectiveType = DuelObjectiveType.ELIMINATION,
) : DuelEvent {
    init {
        require(eventId.matches(EVENT_ID_PATTERN)) { "Unsafe event id" }
        require(winner != loser) { "Winner and loser must be different players" }
        require((mode == DuelMode.KIT) == (kitId != null)) { "Event mode and kit do not agree" }
        require(!ranked || mode == DuelMode.KIT) { "Ranked event must use a kit" }
        require(objective != DuelObjectiveType.SUMO || mode == DuelMode.KIT) { "SUMO event must use a controlled kit" }
        require(!objective.isHitRace || mode == DuelMode.KIT) { "Hit-race event must use a controlled kit" }
        require(winnerRating in 0..RatingCalculator.MAX_RATING) { "Winner rating is outside the supported range" }
    }
}

data class LeaderboardInvalidatedEvent(
    override val eventId: String,
    override val occurredAt: Instant,
    override val sourceServer: ServerId,
    val revision: Long,
) : DuelEvent {
    init {
        require(eventId.matches(EVENT_ID_PATTERN)) { "Unsafe event id" }
        require(revision >= 0) { "Leaderboard revision cannot be negative" }
    }
}

private val EVENT_ID_PATTERN = Regex("[A-Za-z0-9:._-]{1,160}")

fun interface DuelEventPublisher {
    fun publish(event: DuelEvent): CompletableFuture<Unit>
}

object NoOpDuelEventPublisher : DuelEventPublisher {
    override fun publish(event: DuelEvent): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
}
