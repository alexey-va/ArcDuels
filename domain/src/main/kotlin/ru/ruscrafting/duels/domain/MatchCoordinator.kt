package ru.ruscrafting.duels.domain

import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class MatchCoordinator(
    private val serverId: ServerId,
    private val arenaAllocator: ArenaAllocator,
    private val statistics: StatisticsRepository,
    private val eventPublisher: DuelEventPublisher = NoOpDuelEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val lock = Any()
    private val matches = ConcurrentHashMap<MatchId, DuelMatch>()
    private val matchByPlayer = ConcurrentHashMap<PlayerId, MatchId>()
    private val pendingPlayers = ConcurrentHashMap.newKeySet<PlayerId>()
    private val reservations = ConcurrentHashMap<MatchId, ArenaReservation>()

    fun reserve(
        firstPlayer: PlayerId,
        secondPlayer: PlayerId,
        rules: DuelRules,
        matchId: MatchId = MatchId.random(),
        arenaId: ArenaId? = null,
    ): CompletableFuture<DuelMatch> {
        require(firstPlayer != secondPlayer) { "A player cannot duel themselves" }
        val arenaFuture =
            synchronized(lock) {
                check(matchByPlayer[firstPlayer] == null && firstPlayer !in pendingPlayers) {
                    "First player is already queued or in a match"
                }
                check(matchByPlayer[secondPlayer] == null && secondPlayer !in pendingPlayers) {
                    "Second player is already queued or in a match"
                }
                pendingPlayers += firstPlayer
                pendingPlayers += secondPlayer
                try {
                    arenaAllocator.reserve(rules, arenaId)
                } catch (failure: Throwable) {
                    pendingPlayers -= firstPlayer
                    pendingPlayers -= secondPlayer
                    throw failure
                }
            }
        val result = CompletableFuture<DuelMatch>()
        arenaFuture.whenComplete { reservation, failure ->
            if (failure != null) {
                synchronized(lock) {
                    pendingPlayers -= firstPlayer
                    pendingPlayers -= secondPlayer
                }
                result.completeExceptionally(failure)
                return@whenComplete
            }
            try {
                val match = synchronized(lock) {
                    check(firstPlayer in pendingPlayers) { "First player's queued reservation was cancelled" }
                    check(secondPlayer in pendingPlayers) { "Second player's queued reservation was cancelled" }
                    check(matchByPlayer[firstPlayer] == null) { "First player joined another match" }
                    check(matchByPlayer[secondPlayer] == null) { "Second player joined another match" }
                    val match =
                        DuelMatch.reserve(
                            id = matchId,
                            firstPlayer = firstPlayer,
                            secondPlayer = secondPlayer,
                            arenaId = reservation.arenaId,
                            serverId = serverId,
                            rules = rules,
                            now = clock.instant(),
                        )
                    matches[match.id] = match
                    matchByPlayer[firstPlayer] = match.id
                    matchByPlayer[secondPlayer] = match.id
                    pendingPlayers -= firstPlayer
                    pendingPlayers -= secondPlayer
                    reservations[match.id] = reservation
                    match
                }
                if (!result.complete(match)) {
                    synchronized(lock) {
                        matches[match.id]?.takeIf { it.state == MatchState.RESERVED }?.let { reserved ->
                            val cancelled = reserved.cancel(clock.instant(), MatchEndReason.ADMIN_CANCEL)
                            matches[match.id] = cancelled
                            releaseLocked(cancelled)
                        }
                    }
                }
            } catch (failure: Throwable) {
                reservation.close()
                synchronized(lock) {
                    pendingPlayers -= firstPlayer
                    pendingPlayers -= secondPlayer
                }
                result.completeExceptionally(failure)
            }
        }
        result.whenComplete { _, _ ->
            if (result.isCancelled) {
                synchronized(lock) {
                    pendingPlayers -= firstPlayer
                    pendingPlayers -= secondPlayer
                }
                arenaFuture.cancel(false)
            }
        }
        return result
    }

    fun beginCountdown(matchId: MatchId): DuelMatch =
        update(matchId) { it.beginCountdown() }

    fun activate(matchId: MatchId): DuelMatch =
        update(matchId) { it.activate(clock.instant()) }

    fun recordRoundWinner(
        matchId: MatchId,
        winner: PlayerId,
        reason: MatchEndReason = MatchEndReason.ELIMINATION,
    ): CompletableFuture<DuelMatch> {
        val updated = update(matchId) { it.recordRoundWinner(winner, clock.instant(), reason) }
        return if (updated.state == MatchState.COMPLETING) persistCompletion(updated) else CompletableFuture.completedFuture(updated)
    }

    /** Evaluates a platform-provided objective frame and applies its result atomically. */
    fun evaluateObjective(
        matchId: MatchId,
        objective: MatchObjective,
        frame: ObjectiveFrame,
    ): CompletableFuture<DuelMatch> {
        val updated =
            synchronized(lock) {
                val current = getRequired(matchId)
                check(current.state == MatchState.ACTIVE) { "Objectives can only be evaluated for an active match" }
                require((frame.contenders + frame.progress.keys).all { it == current.firstPlayer || it == current.secondPlayer }) {
                    "Objective frame contains a player who is not in the match"
                }
                when (val decision = objective.evaluate(current, frame)) {
                    ObjectiveDecision.Continue -> current
                    is ObjectiveDecision.Complete ->
                        current.recordRoundWinner(decision.winner, clock.instant(), MatchEndReason.OBJECTIVE).also {
                            matches[matchId] = it
                        }
                }
            }
        return if (updated.state == MatchState.COMPLETING) persistCompletion(updated) else CompletableFuture.completedFuture(updated)
    }

    /** Retries an unknown-commit-result failure without duplicating statistics. */
    fun retryCompletion(matchId: MatchId): CompletableFuture<DuelMatch> {
        val match = getRequired(matchId)
        check(match.state == MatchState.COMPLETING) { "Match is not awaiting persistence" }
        return persistCompletion(match)
    }

    fun forfeit(
        matchId: MatchId,
        losingPlayer: PlayerId,
        reason: MatchEndReason,
    ): CompletableFuture<DuelMatch> {
        val updated = update(matchId) { it.forfeit(losingPlayer, clock.instant(), reason) }
        return persistCompletion(updated)
    }

    fun cancel(
        matchId: MatchId,
        reason: MatchEndReason,
    ): DuelMatch {
        val cancelled = update(matchId) { it.cancel(clock.instant(), reason) }
        release(cancelled)
        return cancelled
    }

    fun find(matchId: MatchId): DuelMatch? = matches[matchId]

    fun findByPlayer(playerId: PlayerId): DuelMatch? = matchByPlayer[playerId]?.let(matches::get)

    fun isQueuedOrMatched(playerId: PlayerId): Boolean = playerId in pendingPlayers || matchByPlayer[playerId] != null

    fun activeMatches(): List<DuelMatch> =
        matches.values.filter { it.state !in setOf(MatchState.COMPLETED, MatchState.CANCELLED) }.sortedBy(DuelMatch::createdAt)

    private fun persistCompletion(match: DuelMatch): CompletableFuture<DuelMatch> {
        val winner = requireNotNull(match.winner)
        val outcome =
            MatchOutcome(
                matchId = match.id,
                winner = winner,
                loser = match.opponentOf(winner),
                mode = match.rules.mode,
                kitId = match.rules.kitId,
                ranked = match.rules.ranked,
                serverId = match.serverId,
                completedAt = requireNotNull(match.completedAt).truncatedTo(ChronoUnit.MILLIS),
                objective = match.rules.objective,
            )
        return statistics.record(outcome).thenApply { persisted ->
            val (completed, firstCompletion) =
                synchronized(lock) {
                    val current = getRequired(match.id)
                    if (current.state == MatchState.COMPLETED) {
                        current to false
                    } else {
                        current.markPersisted().also { matches[it.id] = it } to true
                    }
                }
            if (firstCompletion) publishCompletion(completed, persisted)
            completed
        }
    }

    /**
     * Releases player and arena ownership only after the platform restored both players.
     * This prevents another match from entering an arena during presentation cleanup.
     */
    fun releaseCompleted(matchId: MatchId): Boolean =
        synchronized(lock) {
            val match = matches[matchId] ?: return@synchronized false
            check(match.state == MatchState.COMPLETED) { "Only a completed match can release gameplay resources" }
            releaseLocked(match)
        }

    private fun publishCompletion(
        match: DuelMatch,
        persisted: PersistedMatchResult,
    ) {
        val winner = requireNotNull(match.winner)
        val occurredAt = requireNotNull(match.completedAt)
        val events =
            listOf(
                MatchCompletedEvent(
                    eventId = "${match.id}:completed",
                    occurredAt = occurredAt,
                    sourceServer = serverId,
                    matchId = match.id,
                    winner = winner,
                    loser = match.opponentOf(winner),
                    mode = match.rules.mode,
                    kitId = match.rules.kitId,
                    ranked = match.rules.ranked,
                    winnerRating = persisted.winnerRatingAfter,
                    objective = match.rules.objective,
                ),
                LeaderboardInvalidatedEvent(
                    eventId = "${match.id}:leaderboard",
                    occurredAt = occurredAt,
                    sourceServer = serverId,
                    revision = persisted.leaderboardRevision,
                ),
            )
        for (event in events) {
            runCatching { eventPublisher.publish(event) }
                .onSuccess { publication -> publication.exceptionally { Unit } }
        }
    }

    private fun update(
        matchId: MatchId,
        transform: (DuelMatch) -> DuelMatch,
    ): DuelMatch =
        synchronized(lock) {
            val updated = transform(getRequired(matchId))
            matches[matchId] = updated
            updated
        }

    private fun getRequired(matchId: MatchId): DuelMatch = matches[matchId] ?: error("Unknown match")

    private fun release(match: DuelMatch) {
        synchronized(lock) {
            releaseLocked(match)
        }
    }

    private fun releaseLocked(match: DuelMatch): Boolean {
        val reservation = reservations.remove(match.id) ?: return false
        matchByPlayer.remove(match.firstPlayer, match.id)
        matchByPlayer.remove(match.secondPlayer, match.id)
        try {
            reservation.close()
        } finally {
            matches.remove(match.id, match)
        }
        return true
    }
}
