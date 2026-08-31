package ru.ruscrafting.duels.domain

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class ChallengeRegistry(
    private val clock: Clock,
    ttl: Duration = Duration.ofSeconds(45),
) {
    private val lock = Any()
    private var ttlForNewChallenges = DuelChallenge.validateTtl(ttl)
    private val challenges = ConcurrentHashMap<ChallengeId, DuelChallenge>()
    private val pendingByPlayer = ConcurrentHashMap<PlayerId, ChallengeId>()

    /**
     * Atomically changes the expiry used by challenges created after this call.
     * Existing challenges keep the deadline captured in their [DuelChallenge.expiresAt].
     */
    fun updateTtl(ttl: Duration) {
        val validatedTtl = DuelChallenge.validateTtl(ttl)
        synchronized(lock) {
            ttlForNewChallenges = validatedTtl
        }
    }

    /** Number of currently pending challenges after applying due expirations. */
    val pendingCount: Int
        get() =
            synchronized(lock) {
                expirePending()
                challenges.values.count { it.status == ChallengeStatus.PENDING }
            }

    fun create(
        challenger: PlayerId,
        target: PlayerId,
        rules: DuelRules,
        arenaSelection: ArenaSelection? = null,
    ): DuelChallenge =
        synchronized(lock) {
            expirePending()
            check(pendingByPlayer[challenger] == null) { "The challenger already has a pending challenge" }
            check(pendingByPlayer[target] == null) { "The target already has a pending challenge" }
            DuelChallenge.create(challenger, target, rules, clock.instant(), ttlForNewChallenges, arenaSelection).also { challenge ->
                challenges[challenge.id] = challenge
                claimPendingPlayers(challenge)
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
            if (challenge.status == ChallengeStatus.PENDING) {
                check(pendingByPlayer[challenge.challenger] == null) { "The challenger already has a pending challenge" }
                check(pendingByPlayer[challenge.target] == null) { "The target already has a pending challenge" }
                claimPendingPlayers(challenge)
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
                require(
                    existing.rules == challenge.rules &&
                        existing.arenaSelection == challenge.arenaSelection &&
                        existing.createdAt == challenge.createdAt &&
                        existing.expiresAt == challenge.expiresAt,
                ) {
                    "Challenge resolution does not match the registered challenge"
                }
                require(existing.status == ChallengeStatus.PENDING || existing == challenge) {
                    "Challenge already has a different terminal resolution"
                }
            }
            challenges[challenge.id] = challenge
            releasePendingPlayers(challenge)
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
            releasePendingPlayers(challenge)
            pruneTerminalChallenges()
            resolved
        }

    fun find(id: ChallengeId): DuelChallenge? = expireIfDue(id)

    /**
     * Atomically expires one challenge when its deadline has passed.
     *
     * Controllers use this from a scheduled task so expiry is observable even
     * when neither player runs another duel command.
     */
    fun expireIfDue(id: ChallengeId): DuelChallenge? =
        synchronized(lock) {
            val challenge = challenges[id] ?: return@synchronized null
            if (challenge.status != ChallengeStatus.PENDING || clock.instant().isBefore(challenge.expiresAt)) {
                return@synchronized challenge
            }
            val expired = challenge.resolve(ChallengeStatus.EXPIRED, clock.instant())
            challenges[id] = expired
            releasePendingPlayers(challenge)
            pruneTerminalChallenges()
            expired
        }

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
                releasePendingPlayers(challenge)
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

    private fun claimPendingPlayers(challenge: DuelChallenge) {
        pendingByPlayer[challenge.challenger] = challenge.id
        pendingByPlayer[challenge.target] = challenge.id
    }

    private fun releasePendingPlayers(challenge: DuelChallenge) {
        pendingByPlayer.remove(challenge.challenger, challenge.id)
        pendingByPlayer.remove(challenge.target, challenge.id)
    }

    private companion object {
        const val MAX_RETAINED_TERMINAL = 1_024
    }
}
