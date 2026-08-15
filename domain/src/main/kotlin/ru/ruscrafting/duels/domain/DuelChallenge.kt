package ru.ruscrafting.duels.domain

import java.time.Duration
import java.time.Instant

enum class ChallengeStatus {
    PENDING,
    ACCEPTED,
    DENIED,
    CANCELLED,
    EXPIRED,
}

data class DuelChallenge(
    val id: ChallengeId,
    val challenger: PlayerId,
    val target: PlayerId,
    val rules: DuelRules,
    val createdAt: Instant,
    val expiresAt: Instant,
    val status: ChallengeStatus = ChallengeStatus.PENDING,
    val arenaSelection: ArenaSelection? = null,
) {
    init {
        require(challenger != target) { "A player cannot challenge themselves" }
        require(expiresAt.isAfter(createdAt)) { "Challenge expiry must be after creation" }
    }

    fun resolve(
        next: ChallengeStatus,
        now: Instant,
    ): DuelChallenge {
        require(status == ChallengeStatus.PENDING) { "Challenge is already resolved" }
        require(next in TERMINAL_STATUSES) { "Challenge can only transition to a terminal status" }
        val effective = if (!now.isBefore(expiresAt)) ChallengeStatus.EXPIRED else next
        return copy(status = effective)
    }

    companion object {
        private val TERMINAL_STATUSES = ChallengeStatus.entries.toSet() - ChallengeStatus.PENDING

        fun create(
            challenger: PlayerId,
            target: PlayerId,
            rules: DuelRules,
            now: Instant,
            ttl: Duration,
            arenaSelection: ArenaSelection? = null,
        ): DuelChallenge {
            require(!ttl.isNegative && !ttl.isZero && ttl <= Duration.ofMinutes(10)) {
                "Challenge TTL must be between 1 ms and 10 minutes"
            }
            return DuelChallenge(
                id = ChallengeId.random(),
                challenger = challenger,
                target = target,
                rules = rules,
                createdAt = now,
                expiresAt = now.plus(ttl),
                arenaSelection = arenaSelection,
            )
        }
    }
}
