package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.playerstate.PaperPlayerStateSnapshot
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableRecoveryCompletion
import ru.arc.persistence.DurableRecoveryWorkflow
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.PlayerStateEscrow
import ru.ruscrafting.duels.domain.PlayerStateEscrowRepository
import ru.ruscrafting.duels.domain.ServerId
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal data class StoredPlayerSnapshot(
    val state: RestorablePlayerSnapshot,
    val escrow: PlayerStateEscrow,
)

internal data class RetentionDrainReport(
    val observed: Int,
    val acknowledged: Int,
    val failed: Int,
    val timedOut: Int,
)

internal class DurablePlayerStateService(
    private val plugin: JavaPlugin,
    private val serverId: ServerId,
    private val repository: PlayerStateEscrowRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val retention: Duration = Duration.ofDays(7),
    private val reconciliationDelay: Duration = Duration.ofSeconds(5),
) {
    private val coreCodec = PaperPlayerStateCodec(maxPayloadBytes = MAX_ESCROW_PAYLOAD_BYTES)
    private val pending = ConcurrentHashMap<UUID, PlayerStateEscrow>()
    private val retentions = ConcurrentHashMap<UUID, CompletableFuture<Unit>>()
    private val purgeInFlight = AtomicBoolean()
    private val recoveryWorkflow = DurableRecoveryWorkflow<PlayerStateEscrow, StoredPlayerSnapshot>(
        commit = ::commitExact,
        sameContent = PlayerStateEscrow::sameContent,
        acknowledge = { committed, _ -> acknowledgeExact(committed) },
    )

    init {
        require(!retention.isZero && !retention.isNegative) { "Player snapshot retention must be positive" }
        require(!reconciliationDelay.isNegative) { "MySQL reconciliation delay cannot be negative" }
    }

    fun loadPending(timeoutMillis: Long): Int {
        val loaded = repository.pending(serverId).get(timeoutMillis, TimeUnit.MILLISECONDS)
        loaded.forEach { escrow ->
            verifyChecksum(escrow)
            check(pending.putIfAbsent(escrow.playerId.value, escrow) == null) {
                "Duplicate pending player escrow for ${escrow.playerId}"
            }
        }
        return loaded.size
    }

    fun storePair(
        matchId: MatchId,
        first: Player,
        second: Player,
        inventoryReplaced: Boolean,
    ): CompletableFuture<Map<UUID, StoredPlayerSnapshot>> {
        check(plugin.server.isPrimaryThread) { "Player state must be captured on the Paper primary thread" }
        val firstStored = capture(matchId, first, inventoryReplaced)
        val secondStored = capture(matchId, second, inventoryReplaced)
        return repository.savePair(firstStored.escrow, secondStored.escrow)
            .handle { _, failure ->
                if (failure == null) {
                    CompletableFuture.completedFuture(Unit)
                } else {
                    reconcileUnknownSave(firstStored.escrow, secondStored.escrow, failure.unwrapCompletion())
                }
            }.thenCompose { it }
            .thenApply {
                pending[first.uniqueId] = firstStored.escrow
                pending[second.uniqueId] = secondStored.escrow
                mapOf(first.uniqueId to firstStored, second.uniqueId to secondStored)
            }
    }

    fun store(
        matchId: MatchId,
        player: Player,
        inventoryReplaced: Boolean,
    ): CompletableFuture<StoredPlayerSnapshot> {
        check(plugin.server.isPrimaryThread) { "Player state must be captured on the Paper primary thread" }
        val stored = capture(matchId, player, inventoryReplaced)
        return recoveryWorkflow.commitThenMutate(stored.escrow) { committed ->
            CompletableFuture.completedFuture(
                stored.copy(escrow = committed).also { pending[player.uniqueId] = committed },
            )
        }.thenApply { it.mutation }
    }

    private fun commitExact(expected: PlayerStateEscrow): CompletableFuture<PlayerStateEscrow> =
        repository.save(expected)
            .handle { _, failure ->
                if (failure == null) CompletableFuture.completedFuture(Unit)
                else reconcileUnknownSave(expected, failure.unwrapCompletion())
            }.thenCompose { it }
            .thenApply { expected }

    private fun acknowledgeExact(expected: PlayerStateEscrow): CompletableFuture<DurableAcknowledgementOutcome> {
        val restoredAt = clock.instant()
        return repository.retainRestored(expected, restoredAt, restoredAt.plus(retention)).thenCompose { retained ->
            if (retained) return@thenCompose CompletableFuture.completedFuture(DurableAcknowledgementOutcome.ACKNOWLEDGED)
            repository.findPending(expected.playerId).thenApply { current ->
                when {
                    current == null -> DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED
                    !current.sameContent(expected) -> DurableAcknowledgementOutcome.CONTENT_MISMATCH
                    else -> error("Escrow archival did not match the restored snapshot")
                }
            }
        }
    }
    fun findMatchSnapshots(
        matchId: MatchId,
        origins: Map<PlayerId, ServerId>,
    ): CompletableFuture<Map<PlayerId, PlayerStateEscrow>> {
        require(origins.size == 2) { "A network match requires exactly two origin snapshots" }
        val entries = origins.entries.toList()
        return repository.findPending(entries[0].key)
            .thenCombine(repository.findPending(entries[1].key), ::Pair)
            .thenApply { found ->
                val snapshots = listOfNotNull(found.first, found.second).associateBy(PlayerStateEscrow::playerId)
                check(snapshots.size == origins.size) { "Both origin inventory snapshots must exist before arena transfer" }
                origins.forEach { (playerId, origin) ->
                    val escrow = requireNotNull(snapshots[playerId]) { "Missing origin snapshot for $playerId" }
                    verifyChecksum(escrow)
                    check(escrow.matchId == matchId) { "Origin snapshot for $playerId belongs to another match" }
                    check(escrow.serverId == origin) { "Origin snapshot for $playerId belongs to ${escrow.serverId}, not $origin" }
                }
                snapshots
            }
    }

    fun pending(playerId: UUID): PlayerStateEscrow? = pending[playerId]

    fun isPending(playerId: UUID): Boolean = pending.containsKey(playerId)

    fun pendingCount(): Int = pending.size

    fun discover(playerId: UUID): CompletableFuture<PlayerStateEscrow?> {
        val cached = pending[playerId]
        return repository.findPending(PlayerId(playerId)).handle { escrow, failure ->
            if (failure != null) return@handle cached ?: throw failure
            if (escrow != null) {
                verifyChecksum(escrow)
                pending[playerId] = escrow
            } else {
                pending.remove(playerId)
            }
            escrow
        }
    }

    fun isLocal(escrow: PlayerStateEscrow): Boolean = escrow.serverId == serverId

    fun latestRetained(playerId: UUID): CompletableFuture<PlayerStateEscrow?> =
        repository.findLatestRetained(PlayerId(playerId)).thenApply { escrow ->
            escrow?.also(::verifyChecksum)
        }

    fun decode(escrow: PlayerStateEscrow): StoredPlayerSnapshot {
        verifyChecksum(escrow)
        require(escrow.serverId == serverId) { "Player escrow belongs to ${escrow.serverId}, not $serverId" }
        require(escrow.formatVersion == CORE_ESCROW_FORMAT_VERSION) { "Unsupported player escrow format" }
        val state = PlayerSnapshot.fromCore(coreCodec.decode(escrow.coreEnvelope()))
        return StoredPlayerSnapshot(state, escrow)
    }

    fun decodeForArena(
        escrow: PlayerStateEscrow,
        player: Player,
    ): StoredPlayerSnapshot {
        verifyChecksum(escrow)
        require(escrow.formatVersion == CORE_ESCROW_FORMAT_VERSION) { "Unsupported player escrow format" }
        val state = PlayerSnapshot.fromCore(coreCodec.decode(escrow.coreEnvelope()), player.world)
        return StoredPlayerSnapshot(state, escrow)
    }

    /** Must be called after the exact snapshot was applied, verified, and saved on the primary thread. */
    fun retain(stored: StoredPlayerSnapshot): CompletableFuture<Unit> {
        val playerId = stored.escrow.playerId.value
        val current = pending[playerId]
        if (current == null) return CompletableFuture.completedFuture(Unit)
        if (current != stored.escrow) {
            return CompletableFuture.failedFuture(
                IllegalStateException("A different player escrow replaced the restored snapshot"),
            )
        }
        val archival =
            retentions.computeIfAbsent(playerId) {
                runCatching {
                    recoveryWorkflow.restoreThenAcknowledge(stored.escrow) { committed ->
                        CompletableFuture.completedFuture(stored.copy(escrow = committed))
                    }.thenApply { completion ->
                        check(completion !is DurableRecoveryCompletion.ContentMismatch) {
                            "A different player escrow replaced the restored snapshot"
                        }
                        check(pending.remove(playerId, stored.escrow)) {
                            "Pending escrow changed before archival completed"
                        }
                        Unit
                    }
                }.getOrElse { failure -> CompletableFuture.failedFuture(failure) }
            }
        archival.whenComplete { _, _ -> retentions.remove(playerId, archival) }
        return archival
    }

    fun purgeExpired(): CompletableFuture<Int> {
        if (!purgeInFlight.compareAndSet(false, true)) return CompletableFuture.completedFuture(0)
        val purge =
            runCatching { repository.purgeRetained(clock.instant()) }
                .getOrElse { failure -> CompletableFuture.failedFuture(failure) }
        purge.whenComplete { _, _ -> purgeInFlight.set(false) }
        return purge
    }

    /**
     * Waits only for archival operations that were already started after an
     * exact state apply/save. A timeout never discards the active recovery row.
     */
    fun awaitRetentions(timeout: Duration): RetentionDrainReport {
        require(!timeout.isNegative) { "Retention drain timeout cannot be negative" }
        val observed = retentions.values.toSet()
        val deadline = System.nanoTime() + timeout.toNanos()
        var acknowledged = 0
        var failed = 0
        var timedOut = 0
        observed.forEach { retention ->
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                timedOut++
                return@forEach
            }
            try {
                retention.get(remaining, TimeUnit.NANOSECONDS)
                acknowledged++
            } catch (_: TimeoutException) {
                timedOut++
            } catch (_: ExecutionException) {
                failed++
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                timedOut++
            }
        }
        return RetentionDrainReport(observed.size, acknowledged, failed, timedOut)
    }

    private fun reconcileUnknownSave(
        first: PlayerStateEscrow,
        second: PlayerStateEscrow,
        originalFailure: Throwable,
        emptyConfirmations: Int = 0,
    ): CompletableFuture<Unit> =
        repository.findPending(first.playerId)
            .thenCombine(repository.findPending(second.playerId), ::Pair)
            .handle { actual, lookupFailure ->
                if (lookupFailure != null) {
                    return@handle retryReconciliation(first, second, originalFailure, emptyConfirmations)
                }
                val firstActual = requireNotNull(actual).first
                val secondActual = actual.second
                when {
                    firstActual?.sameContent(first) == true && secondActual?.sameContent(second) == true ->
                        CompletableFuture.completedFuture(Unit)

                    firstActual == null && secondActual == null && emptyConfirmations + 1 >= EMPTY_CONFIRMATIONS_REQUIRED ->
                        CompletableFuture.failedFuture(
                            IllegalStateException("MySQL confirmed that the player state pair was not committed", originalFailure),
                        )

                    firstActual == null && secondActual == null ->
                        retryReconciliation(first, second, originalFailure, emptyConfirmations + 1)

                    (firstActual != null && !firstActual.sameContent(first)) ||
                        (secondActual != null && !secondActual.sameContent(second)) ->
                        CompletableFuture.failedFuture(
                            IllegalStateException("A participant already has a different recovery snapshot", originalFailure),
                        )

                    else -> retryReconciliation(first, second, originalFailure, emptyConfirmations = 0)
                }
            }.thenCompose { it }

    private fun reconcileUnknownSave(
        expected: PlayerStateEscrow,
        originalFailure: Throwable,
        emptyConfirmations: Int = 0,
    ): CompletableFuture<Unit> =
        repository.findPending(expected.playerId)
            .handle { actual, lookupFailure ->
                when {
                    lookupFailure != null -> retryReconciliation(expected, originalFailure, emptyConfirmations)
                    actual?.sameContent(expected) == true -> CompletableFuture.completedFuture(Unit)
                    actual == null && emptyConfirmations + 1 >= EMPTY_CONFIRMATIONS_REQUIRED ->
                        CompletableFuture.failedFuture(
                            IllegalStateException("MySQL confirmed that the player state was not committed", originalFailure),
                        )
                    actual == null -> retryReconciliation(expected, originalFailure, emptyConfirmations + 1)
                    else ->
                        CompletableFuture.failedFuture(
                            IllegalStateException("The player already has a different recovery snapshot", originalFailure),
                        )
                }
            }.thenCompose { it }

    private fun retryReconciliation(
        first: PlayerStateEscrow,
        second: PlayerStateEscrow,
        originalFailure: Throwable,
        emptyConfirmations: Int,
    ): CompletableFuture<Unit> {
        if (!plugin.isEnabled) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Plugin stopped before the unknown MySQL save outcome was reconciled", originalFailure),
            )
        }
        return CompletableFuture.runAsync(
            {},
            CompletableFuture.delayedExecutor(reconciliationDelay.toMillis(), TimeUnit.MILLISECONDS),
        ).thenCompose { reconcileUnknownSave(first, second, originalFailure, emptyConfirmations) }
    }

    private fun retryReconciliation(
        expected: PlayerStateEscrow,
        originalFailure: Throwable,
        emptyConfirmations: Int,
    ): CompletableFuture<Unit> {
        if (!plugin.isEnabled) {
            return CompletableFuture.failedFuture(
                IllegalStateException("Plugin stopped before the unknown MySQL save outcome was reconciled", originalFailure),
            )
        }
        return CompletableFuture.runAsync(
            {},
            CompletableFuture.delayedExecutor(reconciliationDelay.toMillis(), TimeUnit.MILLISECONDS),
        ).thenCompose { reconcileUnknownSave(expected, originalFailure, emptyConfirmations) }
    }

    private fun capture(
        matchId: MatchId,
        player: Player,
        inventoryReplaced: Boolean,
    ): StoredPlayerSnapshot {
        check(!isPending(player.uniqueId)) { "Player ${player.uniqueId} already has pending recovery state" }
        val snapshot = PlayerSnapshot.capture(player, clock.millis().coerceAtLeast(1L))
        val envelope = coreCodec.encode(snapshot.core)
        val payload = Base64.getDecoder().decode(envelope.payloadBase64)
        val escrow =
            PlayerStateEscrow(
                playerId = PlayerId(player.uniqueId),
                matchId = matchId,
                serverId = serverId,
                formatVersion = CORE_ESCROW_FORMAT_VERSION,
                inventoryReplaced = inventoryReplaced,
                payload = payload,
                checksum = sha256(payload),
                createdAt = clock.instant(),
            )
        return StoredPlayerSnapshot(snapshot, escrow)
    }

    private fun verifyChecksum(escrow: PlayerStateEscrow) {
        require(MessageDigest.isEqual(sha256(escrow.payload), escrow.checksum)) {
            "Stored player state checksum mismatch for ${escrow.playerId}"
        }
    }

    private fun PlayerStateEscrow.coreEnvelope(): PaperPlayerStateEnvelope =
        PaperPlayerStateEnvelope(
            formatVersion = PaperPlayerStateSnapshot.CURRENT_FORMAT_VERSION,
            payloadBase64 = Base64.getEncoder().encodeToString(payload),
            sha256 = HexFormat.of().formatHex(checksum),
        )

    private fun sha256(payload: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(payload)

    private fun Throwable.unwrapCompletion(): Throwable {
        var current = this
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = requireNotNull(current.cause)
        }
        return current
    }

    private companion object {
        const val MAX_ESCROW_PAYLOAD_BYTES = 8 * 1024 * 1024
        // A lost COMMIT response can race a fresh connection. Require an
        // immediate read plus six delayed confirmations (30 seconds total)
        // before declaring the atomic pair absent and releasing the players.
        const val EMPTY_CONFIRMATIONS_REQUIRED = 7
        const val CORE_ESCROW_FORMAT_VERSION = 2
    }
}
