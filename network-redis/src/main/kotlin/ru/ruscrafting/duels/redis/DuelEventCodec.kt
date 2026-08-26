package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisWireCodec
import ru.ruscrafting.duels.domain.DuelEvent
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
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
) : RedisWireCodec<DuelEvent> {
    private val wireCodec =
        BoundedJsonCodec(
            gson = gson,
            type = WireEvent::class.java,
            rootContract = JsonObjectContract(
                allowedFields = WIRE_FIELDS,
                requiredFields = setOf("version", "type", "eventId", "occurredAt", "sourceServer"),
            ),
            bounds = JsonResourceBounds(MAX_EVENT_CHARACTERS, maxStringCharacters = 160),
            validate = { wire ->
                require(wire.version == WIRE_VERSION) { "Unsupported ArcDuels event version ${wire.version}" }
                require(wire.eventId.matches(EVENT_ID_PATTERN)) { "Unsafe event id" }
                require(wire.type.length in 1..32) { "Unsafe event type" }
                require(wire.occurredAt.length in 1..64) { "Unsafe event timestamp" }
                ServerId(wire.sourceServer)
            },
        )

    override fun encode(value: DuelEvent): String =
        wireCodec.encode(
            when (value) {
                is MatchCompletedEvent ->
                    WireEvent(
                        type = MATCH_COMPLETED,
                        eventId = value.eventId,
                        occurredAt = value.occurredAt.toString(),
                        sourceServer = value.sourceServer.value,
                        matchId = value.matchId.toString(),
                        winner = value.winner.toString(),
                        loser = value.loser.toString(),
                        mode = value.mode.name,
                        objective = value.objective.name,
                        kitId = value.kitId?.value,
                        ranked = value.ranked,
                        winnerRating = value.winnerRating,
                    )
                is LeaderboardInvalidatedEvent ->
                    WireEvent(
                        type = LEADERBOARD_INVALIDATED,
                        eventId = value.eventId,
                        occurredAt = value.occurredAt.toString(),
                        sourceServer = value.sourceServer.value,
                        revision = value.revision,
                    )
            },
        )

    override fun decode(raw: String): DuelEvent {
        val wire = wireCodec.decode(raw)
        val occurredAt = Instant.parse(wire.occurredAt)
        val sourceServer = ServerId(wire.sourceServer)
        return when (wire.type) {
            MATCH_COMPLETED -> {
                val winner = PlayerId(UUID.fromString(requireNotNull(wire.winner)))
                val loser = PlayerId(UUID.fromString(requireNotNull(wire.loser)))
                val mode = DuelMode.valueOf(requireNotNull(wire.mode))
                val objective = wire.objective?.let(DuelObjectiveType::valueOf) ?: DuelObjectiveType.ELIMINATION
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
                    objective = objective,
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
        val objective: String? = null,
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
        val EVENT_ID_PATTERN = Regex("[A-Za-z0-9:._-]{1,160}")
        val WIRE_FIELDS =
            setOf(
                "version",
                "type",
                "eventId",
                "occurredAt",
                "sourceServer",
                "matchId",
                "winner",
                "loser",
                "mode",
                "objective",
                "kitId",
                "ranked",
                "winnerRating",
                "revision",
            )
    }
}
