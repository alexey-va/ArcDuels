package ru.ruscrafting.duels.domain

import ru.arc.network.BackendServerId
import ru.arc.network.BackendServerIdPolicy
import java.util.UUID

@JvmInline
value class PlayerId(val value: UUID) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class MatchId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): MatchId = MatchId(UUID.randomUUID())
    }
}

@JvmInline
value class ChallengeId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): ChallengeId = ChallengeId(UUID.randomUUID())
    }
}

@JvmInline
value class ArenaId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9_-]{1,48}"))) { "Unsafe arena id" }
    }

    override fun toString(): String = value
}

@JvmInline
value class KitId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9_-]{1,48}"))) { "Unsafe kit id" }
    }

    override fun toString(): String = value
}

@JvmInline
value class ServerId(val value: String) {
    init {
        BackendServerId.of(value, POLICY)
    }

    override fun toString(): String = value

    fun toBackendServerId(): BackendServerId = BackendServerId.of(value, POLICY)

    companion object {
        @JvmField
        val POLICY = BackendServerIdPolicy(maxLength = 48, allowUppercase = true, allowDot = true)
    }
}
