package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import com.google.gson.JsonParseException
import ru.ruscrafting.duels.domain.ChallengeId
import ru.ruscrafting.duels.domain.ChallengeStatus
import ru.ruscrafting.duels.domain.CombatModifiers
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.DuelChallenge
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.KitId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.util.UUID

internal class ChallengeMessageCodec(
    private val gson: Gson = Gson(),
) {
    fun encode(message: CrossServerChallengeMessage): String =
        gson.toJson(
            WireMessage(
                type = message.type.name,
                messageId = message.messageId,
                sourceServer = message.sourceServer.value,
                challengeId = message.challenge.id.toString(),
                challenger = message.challenge.challenger.toString(),
                target = message.challenge.target.toString(),
                challengerName = message.challengerName,
                targetName = message.targetName,
                challengerServer = message.challengerServer.value,
                targetServer = message.targetServer.value,
                challengerCurrentServer = message.challengerCurrentServer.value,
                targetCurrentServer = message.targetCurrentServer.value,
                matchServer = message.matchServer?.value,
                selectedArenaServer = message.challenge.arenaSelection?.serverId?.value,
                selectedArenaId = message.challenge.arenaSelection?.arenaId?.value,
                createdAt = message.challenge.createdAt.toString(),
                expiresAt = message.challenge.expiresAt.toString(),
                status = message.challenge.status.name,
                mode = message.challenge.rules.mode.name,
                kitId = message.challenge.rules.kitId?.value,
                ranked = message.challenge.rules.ranked,
                bestOf = message.challenge.rules.bestOf,
                objective = message.challenge.rules.objective.name,
                projectiles = message.challenge.rules.modifiers.projectiles,
                consumables = message.challenge.rules.modifiers.consumables,
                enderPearls = message.challenge.rules.modifiers.enderPearls,
                naturalRegeneration = message.challenge.rules.modifiers.naturalRegeneration,
                suddenDeathAfterSeconds = message.challenge.rules.modifiers.suddenDeathAfterSeconds,
                kingOfTheHillCaptureSeconds = message.challenge.rules.modifiers.kingOfTheHillCaptureSeconds,
                boxingHitsToWin = message.challenge.rules.modifiers.boxingHitsToWin,
                comboHitsToWin = message.challenge.rules.modifiers.comboHitsToWin,
            ),
        )

    fun decode(json: String): CrossServerChallengeMessage {
        require(json.length <= MAX_MESSAGE_CHARACTERS) { "ArcDuels challenge message is too large" }
        val wire =
            try {
                gson.fromJson(json, WireMessage::class.java)
            } catch (failure: RuntimeException) {
                throw JsonParseException("Invalid ArcDuels challenge JSON", failure)
            } ?: throw JsonParseException("ArcDuels challenge message cannot be null")
        require(wire.version == WIRE_VERSION) { "Unsupported challenge wire version ${wire.version}" }
        val rules =
            DuelRules(
                mode = DuelMode.valueOf(wire.mode),
                kitId = wire.kitId?.let(::KitId),
                ranked = wire.ranked,
                bestOf = wire.bestOf,
                objective = DuelObjectiveType.valueOf(wire.objective),
                modifiers =
                    CombatModifiers(
                        projectiles = wire.projectiles,
                        consumables = wire.consumables,
                        enderPearls = wire.enderPearls,
                        naturalRegeneration = wire.naturalRegeneration,
                        suddenDeathAfterSeconds = wire.suddenDeathAfterSeconds,
                        kingOfTheHillCaptureSeconds = wire.kingOfTheHillCaptureSeconds,
                        boxingHitsToWin = wire.boxingHitsToWin,
                        comboHitsToWin = wire.comboHitsToWin,
                    ),
            )
        val challenge =
            DuelChallenge(
                id = ChallengeId(UUID.fromString(wire.challengeId)),
                challenger = PlayerId(UUID.fromString(wire.challenger)),
                target = PlayerId(UUID.fromString(wire.target)),
                rules = rules,
                createdAt = Instant.parse(wire.createdAt),
                expiresAt = Instant.parse(wire.expiresAt),
                status = ChallengeStatus.valueOf(wire.status),
                arenaSelection = decodeArenaSelection(wire.selectedArenaServer, wire.selectedArenaId),
            )
        return CrossServerChallengeMessage(
            messageId = wire.messageId,
            sourceServer = ServerId(wire.sourceServer),
            type = ChallengeMessageType.valueOf(wire.type),
            challenge = challenge,
            challengerName = wire.challengerName,
            targetName = wire.targetName,
            challengerServer = ServerId(wire.challengerServer),
            targetServer = ServerId(wire.targetServer),
            matchServer = wire.matchServer?.let(::ServerId),
            challengerCurrentServer = ServerId(wire.challengerCurrentServer ?: wire.challengerServer),
            targetCurrentServer = ServerId(wire.targetCurrentServer ?: wire.targetServer),
        )
    }

    @Suppress("LongParameterList")
    private data class WireMessage(
        val version: Int = WIRE_VERSION,
        val type: String,
        val messageId: String,
        val sourceServer: String,
        val challengeId: String,
        val challenger: String,
        val target: String,
        val challengerName: String,
        val targetName: String,
        val challengerServer: String,
        val targetServer: String,
        val challengerCurrentServer: String? = null,
        val targetCurrentServer: String? = null,
        val matchServer: String?,
        val selectedArenaServer: String?,
        val selectedArenaId: String?,
        val createdAt: String,
        val expiresAt: String,
        val status: String,
        val mode: String,
        val kitId: String?,
        val ranked: Boolean,
        val bestOf: Int,
        val objective: String,
        val projectiles: Boolean,
        val consumables: Boolean,
        val enderPearls: Boolean,
        val naturalRegeneration: Boolean,
        val suddenDeathAfterSeconds: Int,
        val kingOfTheHillCaptureSeconds: Int,
        val boxingHitsToWin: Int,
        val comboHitsToWin: Int,
    )

    private companion object {
        const val WIRE_VERSION = 3
        const val MAX_MESSAGE_CHARACTERS = 16_384
    }
}

private fun decodeArenaSelection(server: String?, arenaId: String?): ArenaSelection? {
    require((server == null) == (arenaId == null)) { "Selected arena server and id must either both be present or both be absent" }
    return if (server == null) null else ArenaSelection(ServerId(server), ArenaId(requireNotNull(arenaId)))
}
