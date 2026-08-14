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

    /**
     * Imports a challenge received from another server.
     *
     * Re-delivery of the exact same challenge is intentionally idempotent. A
     * conflicting payload with the same id, or a second pending challenge for
     * the same pair, is rejected instead of silently replacing local state.
     */
    fun register(challenge: DuelChallenge): DuelChallenge =
        synchronized(lock) {
            expirePending()
            val existing = challenges[challenge.id]
            if (existing != null) {
                require(existing == challenge) { "Challenge id is already registered with different data" }
                return@synchronized existing
            }
            val pair = setOf(challenge.challenger, challenge.target)
            if (challenge.status == ChallengeStatus.PENDING) {
                check(pendingByPair[pair] == null) { "These players already have a pending challenge" }
                pendingByPair[pair] = challenge.id
            }
            challenges[challenge.id] = challenge
            pruneTerminalChallenges()
            challenge
        }

    /** Applies an authenticated terminal state received from another server. */
    fun registerResolution(challenge: DuelChallenge): DuelChallenge =
        synchronized(lock) {
            require(challenge.status != ChallengeStatus.PENDING) { "A network resolution must be terminal" }
            val existing = challenges[challenge.id]
            if (existing != null) {
                require(existing.challenger == challenge.challenger && existing.target == challenge.target) {
                    "Challenge participants do not match the registered challenge"
                }
                require(existing.rules == challenge.rules && existing.createdAt == challenge.createdAt && existing.expiresAt == challenge.expiresAt) {
                    "Challenge resolution does not match the registered challenge"
                }
                require(existing.status == ChallengeStatus.PENDING || existing == challenge) {
                    "Challenge already has a different terminal resolution"
                }
            }
            challenges[challenge.id] = challenge
            pendingByPair.remove(setOf(challenge.challenger, challenge.target), challenge.id)
            pruneTerminalChallenges()
            challenge
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
            pruneTerminalChallenges()
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

    internal fun retainedChallengeCount(): Int = synchronized(lock) { challenges.size }

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
        pruneTerminalChallenges()
    }

    private fun pruneTerminalChallenges() {
        val terminal = challenges.values.filter { it.status != ChallengeStatus.PENDING }
        if (terminal.size <= MAX_RETAINED_TERMINAL) return
        terminal.sortedBy(DuelChallenge::createdAt)
            .take(terminal.size - MAX_RETAINED_TERMINAL)
            .forEach { challenges.remove(it.id, it) }
    }

    private fun DuelChallenge.refreshExpiry(): DuelChallenge {
        if (status != ChallengeStatus.PENDING || clock.instant().isBefore(expiresAt)) return this
        val expired = resolve(ChallengeStatus.EXPIRED, clock.instant())
        expirePending()
        return challenges[id] ?: expired
    }

    private companion object {
        const val MAX_RETAINED_TERMINAL = 1_024
    }
}
