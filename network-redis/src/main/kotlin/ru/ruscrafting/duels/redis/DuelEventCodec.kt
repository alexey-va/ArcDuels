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
        val wire =
            try {
                gson.fromJson(json, WireEvent::class.java)
            } catch (failure: RuntimeException) {
                throw JsonParseException("Invalid RusDuels event JSON", failure)
            } ?: throw JsonParseException("RusDuels event cannot be null")
        require(wire.version == WIRE_VERSION) { "Unsupported RusDuels event version ${wire.version}" }
        require(wire.eventId.matches(Regex("[A-Za-z0-9:._-]{1,160}"))) { "Unsafe event id" }
        val occurredAt = Instant.parse(wire.occurredAt)
        val sourceServer = ServerId(wire.sourceServer)
        return when (wire.type) {
            MATCH_COMPLETED ->
                MatchCompletedEvent(
                    eventId = wire.eventId,
                    occurredAt = occurredAt,
                    sourceServer = sourceServer,
                    matchId = MatchId(UUID.fromString(requireNotNull(wire.matchId))),
                    winner = PlayerId(UUID.fromString(requireNotNull(wire.winner))),
                    loser = PlayerId(UUID.fromString(requireNotNull(wire.loser))),
                    mode = DuelMode.valueOf(requireNotNull(wire.mode)),
                    kitId = wire.kitId?.let(::KitId),
                    ranked = requireNotNull(wire.ranked),
                    winnerRating = requireNotNull(wire.winnerRating),
                )
            LEADERBOARD_INVALIDATED ->
                LeaderboardInvalidatedEvent(
                    eventId = wire.eventId,
                    occurredAt = occurredAt,
                    sourceServer = sourceServer,
                    revision = requireNotNull(wire.revision),
                )
            else -> error("Unknown RusDuels event type ${wire.type}")
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
        const val MATCH_COMPLETED = "match_completed"
        const val LEADERBOARD_INVALIDATED = "leaderboard_invalidated"
    }
}
