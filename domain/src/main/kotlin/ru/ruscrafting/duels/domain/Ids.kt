package ru.ruscrafting.duels.domain

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
        require(value.matches(Regex("[A-Za-z0-9_.-]{1,48}"))) { "Unsafe server id" }
    }

    override fun toString(): String = value
}
