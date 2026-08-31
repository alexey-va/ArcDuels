package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisWireCodec
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
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import java.time.Instant
import java.util.UUID

internal class ChallengeMessageCodec(
    private val gson: Gson = Gson(),
) : RedisWireCodec<CrossServerChallengeMessage> {
    private val wireCodec =
        BoundedJsonCodec(
            gson = gson,
            type = WireMessage::class.java,
            rootContract = JsonObjectContract(
                allowedFields = WIRE_FIELDS,
                requiredFields = REQUIRED_WIRE_FIELDS,
            ),
            bounds = JsonResourceBounds(MAX_MESSAGE_CHARACTERS, maxStringCharacters = 160),
            validate = { wire ->
                require(wire.version == WIRE_VERSION) { "Unsupported challenge wire version ${wire.version}" }
                require(wire.messageId.matches(MESSAGE_ID_PATTERN)) { "Unsafe challenge message id" }
                ServerId(wire.sourceServer)
                require(wire.type.length in 1..32) { "Unsafe challenge message type" }
                requireKitFingerprintForMode(DuelMode.valueOf(wire.mode), wire.kitFingerprint)
            },
        )

    override fun encode(value: CrossServerChallengeMessage): String =
        wireCodec.encode(
            WireMessage(
                type = value.type.name,
                messageId = value.messageId,
                sourceServer = value.sourceServer.value,
                challengeId = value.challenge.id.toString(),
                kitFingerprint = value.kitFingerprint,
                challenger = value.challenge.challenger.toString(),
                target = value.challenge.target.toString(),
                challengerName = value.challengerName,
                targetName = value.targetName,
                challengerServer = value.challengerServer.value,
                targetServer = value.targetServer.value,
                challengerCurrentServer = value.challengerCurrentServer.value,
                targetCurrentServer = value.targetCurrentServer.value,
                recoveryMatchId = value.recoveryMatchId?.toString(),
                matchServer = value.matchServer?.value,
                selectedArenaServer = value.challenge.arenaSelection?.serverId?.value,
                selectedArenaId = value.challenge.arenaSelection?.arenaId?.value,
                createdAt = value.challenge.createdAt.toString(),
                expiresAt = value.challenge.expiresAt.toString(),
                status = value.challenge.status.name,
                mode = value.challenge.rules.mode.name,
                kitId = value.challenge.rules.kitId?.value,
                ranked = value.challenge.rules.ranked,
                bestOf = value.challenge.rules.bestOf,
                objective = value.challenge.rules.objective.name,
                projectiles = value.challenge.rules.modifiers.projectiles,
                consumables = value.challenge.rules.modifiers.consumables,
                enderPearls = value.challenge.rules.modifiers.enderPearls,
                naturalRegeneration = value.challenge.rules.modifiers.naturalRegeneration,
                suddenDeathAfterSeconds = value.challenge.rules.modifiers.suddenDeathAfterSeconds,
                kingOfTheHillCaptureSeconds = value.challenge.rules.modifiers.kingOfTheHillCaptureSeconds,
                boxingHitsToWin = value.challenge.rules.modifiers.boxingHitsToWin,
                comboHitsToWin = value.challenge.rules.modifiers.comboHitsToWin,
            ),
        )

    override fun decode(raw: String): CrossServerChallengeMessage {
        val wire = wireCodec.decode(raw)
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
            kitFingerprint = wire.kitFingerprint,
            challengerName = wire.challengerName,
            targetName = wire.targetName,
            challengerServer = ServerId(wire.challengerServer),
            targetServer = ServerId(wire.targetServer),
            matchServer = wire.matchServer?.let(::ServerId),
            challengerCurrentServer = ServerId(wire.challengerCurrentServer),
            targetCurrentServer = ServerId(wire.targetCurrentServer),
            recoveryMatchId = wire.recoveryMatchId?.let { MatchId(UUID.fromString(it)) },
        )
    }

    @Suppress("LongParameterList")
    private data class WireMessage(
        val version: Int = WIRE_VERSION,
        val type: String,
        val messageId: String,
        val sourceServer: String,
        val challengeId: String,
        val kitFingerprint: String?,
        val challenger: String,
        val target: String,
        val challengerName: String,
        val targetName: String,
        val challengerServer: String,
        val targetServer: String,
        val challengerCurrentServer: String,
        val targetCurrentServer: String,
        val recoveryMatchId: String? = null,
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
        const val WIRE_VERSION = 5
        const val MAX_MESSAGE_CHARACTERS = 16_384
        val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9:._-]{1,160}")
        val WIRE_FIELDS =
            setOf(
                "version",
                "type",
                "messageId",
                "sourceServer",
                "challengeId",
                "kitFingerprint",
                "challenger",
                "target",
                "challengerName",
                "targetName",
                "challengerServer",
                "targetServer",
                "challengerCurrentServer",
                "targetCurrentServer",
                "recoveryMatchId",
                "matchServer",
                "selectedArenaServer",
                "selectedArenaId",
                "createdAt",
                "expiresAt",
                "status",
                "mode",
                "kitId",
                "ranked",
                "bestOf",
                "objective",
                "projectiles",
                "consumables",
                "enderPearls",
                "naturalRegeneration",
                "suddenDeathAfterSeconds",
                "kingOfTheHillCaptureSeconds",
                "boxingHitsToWin",
                "comboHitsToWin",
            )
        val REQUIRED_WIRE_FIELDS =
            WIRE_FIELDS -
                setOf(
                    "recoveryMatchId",
                    "matchServer",
                    "selectedArenaServer",
                    "selectedArenaId",
                    "kitId",
                    "kitFingerprint",
                )
    }
}

private fun decodeArenaSelection(server: String?, arenaId: String?): ArenaSelection? {
    require((server == null) == (arenaId == null)) { "Selected arena server and id must either both be present or both be absent" }
    return if (server == null) null else ArenaSelection(ServerId(server), ArenaId(requireNotNull(arenaId)))
}
