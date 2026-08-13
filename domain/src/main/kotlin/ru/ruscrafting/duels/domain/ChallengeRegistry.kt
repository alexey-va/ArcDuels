package ru.ruscrafting.duels.domain

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class ChallengeRegistry(
    private val clock: Clock,
    private val ttl: Duration = Duration.ofSeconds(45),
) {
    private val lock = Any()
    private val challenges = ConcurrentHashMap<ChallengeId, DuelChallenge>()
    private val pendingByPair = ConcurrentHashMap<Set<PlayerId>, ChallengeId>()

    fun create(
        challenger: PlayerId,
        target: PlayerId,
        rules: DuelRules,
    ): DuelChallenge =
        synchronized(lock) {
            expirePending()
            val pair = setOf(challenger, target)
            check(pendingByPair[pair] == null) { "These players already have a pending challenge" }
            DuelChallenge.create(challenger, target, rules, clock.instant(), ttl).also { challenge ->
                challenges[challenge.id] = challenge
                pendingByPair[pair] = challenge.id
            }
        }

    fun resolve(
        id: ChallengeId,
        actor: PlayerId,
        status: ChallengeStatus,
    ): DuelChallenge =
        synchronized(lock) {
            val challenge = challenges[id] ?: error("Unknown challenge")
            when (status) {
                ChallengeStatus.ACCEPTED, ChallengeStatus.DENIED ->
                    require(actor == challenge.target) { "Only the challenged player can accept or deny" }
                ChallengeStatus.CANCELLED ->
                    require(actor == challenge.challenger) { "Only the challenger can cancel" }
                ChallengeStatus.PENDING, ChallengeStatus.EXPIRED ->
                    error("Unsupported explicit challenge resolution")
            }
            val resolved = challenge.resolve(status, clock.instant())
            challenges[id] = resolved
            pendingByPair.remove(setOf(challenge.challenger, challenge.target), id)
            resolved
        }

    fun find(id: ChallengeId): DuelChallenge? = synchronized(lock) { challenges[id]?.refreshExpiry() }

    fun pendingFor(playerId: PlayerId): List<DuelChallenge> =
        synchronized(lock) {
            expirePending()
            challenges.values
                .filter { it.status == ChallengeStatus.PENDING && (it.challenger == playerId || it.target == playerId) }
                .sortedBy(DuelChallenge::createdAt)
        }

    private fun expirePending() {
        val now = clock.instant()
        challenges.replaceAll { _, challenge ->
            if (challenge.status == ChallengeStatus.PENDING && !now.isBefore(challenge.expiresAt)) {
                pendingByPair.remove(setOf(challenge.challenger, challenge.target), challenge.id)
                challenge.resolve(ChallengeStatus.EXPIRED, now)
            } else {
                challenge
            }
        }
    }

    private fun DuelChallenge.refreshExpiry(): DuelChallenge {
        if (status != ChallengeStatus.PENDING || clock.instant().isBefore(expiresAt)) return this
        expirePending()
        return challenges.getValue(id)
    }
}
