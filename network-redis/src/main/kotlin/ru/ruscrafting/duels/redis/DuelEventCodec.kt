package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import com.google.gson.JsonParseException
import ru.ruscrafting.duels.domain.DuelEvent
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.LeaderboardInvalidatedEvent
import ru.ruscrafting.duels.domain.MatchCompletedEvent
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.RatingCalculator
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.util.UUID

internal class DuelEventCodec(
    private val gson: Gson = Gson(),
) {
    fun encode(event: DuelEvent): String =
        gson.toJson(
            when (event) {
                is MatchCompletedEvent ->
                    WireEvent(
                        type = MATCH_COMPLETED,
                        eventId = event.eventId,
                        occurredAt = event.occurredAt.toString(),
                        sourceServer = event.sourceServer.value,
                        matchId = event.matchId.toString(),
                        winner = event.winner.toString(),
                        loser = event.loser.toString(),
                        mode = event.mode.name,
                        kitId = event.kitId?.value,
                        ranked = event.ranked,
                        winnerRating = event.winnerRating,
                    )
                is LeaderboardInvalidatedEvent ->
                    WireEvent(
                        type = LEADERBOARD_INVALIDATED,
                        eventId = event.eventId,
                        occurredAt = event.occurredAt.toString(),
                        sourceServer = event.sourceServer.value,
                        revision = event.revision,
                    )
            },
        )

    fun decode(json: String): DuelEvent {
        require(json.length <= MAX_EVENT_CHARACTERS) { "ArcDuels event exceeds $MAX_EVENT_CHARACTERS characters" }
        val wire =
            try {
                gson.fromJson(json, WireEvent::class.java)
            } catch (failure: RuntimeException) {
                throw JsonParseException("Invalid ArcDuels event JSON", failure)
            } ?: throw JsonParseException("ArcDuels event cannot be null")
        require(wire.version == WIRE_VERSION) { "Unsupported ArcDuels event version ${wire.version}" }
        require(wire.eventId.matches(Regex("[A-Za-z0-9:._-]{1,160}"))) { "Unsafe event id" }
        val occurredAt = Instant.parse(wire.occurredAt)
        val sourceServer = ServerId(wire.sourceServer)
        return when (wire.type) {
            MATCH_COMPLETED -> {
                val winner = PlayerId(UUID.fromString(requireNotNull(wire.winner)))
                val loser = PlayerId(UUID.fromString(requireNotNull(wire.loser)))
                val mode = DuelMode.valueOf(requireNotNull(wire.mode))
                val kitId = wire.kitId?.let(::KitId)
                val winnerRating = requireNotNull(wire.winnerRating)
                require(winner != loser) { "Winner and loser must be different players" }
                require((mode == DuelMode.KIT) == (kitId != null)) { "Event mode and kit do not agree" }
                require(!requireNotNull(wire.ranked) || mode == DuelMode.KIT) { "Ranked event must use a kit" }
                require(winnerRating in 0..RatingCalculator.MAX_RATING) { "Winner rating is outside the accepted range" }
                MatchCompletedEvent(
                    eventId = wire.eventId,
                    occurredAt = occurredAt,
                    sourceServer = sourceServer,
                    matchId = MatchId(UUID.fromString(requireNotNull(wire.matchId))),
                    winner = winner,
                    loser = loser,
                    mode = mode,
                    kitId = kitId,
                    ranked = wire.ranked,
                    winnerRating = winnerRating,
                )
            }
            LEADERBOARD_INVALIDATED -> {
                val revision = requireNotNull(wire.revision)
                require(revision >= 0) { "Leaderboard revision cannot be negative" }
                LeaderboardInvalidatedEvent(
                    eventId = wire.eventId,
                    occurredAt = occurredAt,
                    sourceServer = sourceServer,
                    revision = revision,
                )
            }
            else -> error("Unknown ArcDuels event type ${wire.type}")
        }
    }

    private data class WireEvent(
        val version: Int = WIRE_VERSION,
        val type: String,
        val eventId: String,
        val occurredAt: String,
        val sourceServer: String,
        val matchId: String? = null,
        val winner: String? = null,
        val loser: String? = null,
        val mode: String? = null,
        val kitId: String? = null,
        val ranked: Boolean? = null,
        val winnerRating: Int? = null,
        val revision: Long? = null,
    )

    private companion object {
        const val WIRE_VERSION = 1
        const val MAX_EVENT_CHARACTERS = 8_192
        const val MATCH_COMPLETED = "match_completed"
        const val LEADERBOARD_INVALIDATED = "leaderboard_invalidated"
    }
}
