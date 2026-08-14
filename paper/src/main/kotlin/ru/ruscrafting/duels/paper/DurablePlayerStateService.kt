package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.PlayerStateEscrow
import ru.ruscrafting.duels.domain.PlayerStateEscrowRepository
import ru.ruscrafting.duels.domain.ServerId
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class StoredPlayerSnapshot(
    val snapshot: PlayerSnapshot,
    val escrow: PlayerStateEscrow,
)

internal class DurablePlayerStateService(
    private val plugin: JavaPlugin,
    private val serverId: ServerId,
    private val repository: PlayerStateEscrowRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val retention: Duration = Duration.ofDays(7),
    private val reconciliationDelay: Duration = Duration.ofSeconds(5),
) {
    private val codec = PlayerSnapshotCodec(plugin.server)
    private val pending = ConcurrentHashMap<UUID, PlayerStateEscrow>()
    private val retentions = ConcurrentHashMap<UUID, CompletableFuture<Unit>>()
    private val purgeInFlight = AtomicBoolean()

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

    fun pending(playerId: UUID): PlayerStateEscrow? = pending[playerId]

    fun isPending(playerId: UUID): Boolean = pending.containsKey(playerId)

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
        require(escrow.formatVersion == PlayerSnapshotCodec.FORMAT_VERSION) { "Unsupported player escrow format" }
        return StoredPlayerSnapshot(codec.decode(escrow.payload), escrow)
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
                    val restoredAt = clock.instant()
                    repository.retainRestored(stored.escrow, restoredAt, restoredAt.plus(retention)).thenApply { retained ->
                        check(retained) { "Escrow archival did not match the restored snapshot" }
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

    private fun capture(
        matchId: MatchId,
        player: Player,
        inventoryReplaced: Boolean,
    ): StoredPlayerSnapshot {
        check(!isPending(player.uniqueId)) { "Player ${player.uniqueId} already has pending recovery state" }
        val snapshot = PlayerSnapshot.capture(player)
        val payload = codec.encode(snapshot)
        val escrow =
            PlayerStateEscrow(
                playerId = PlayerId(player.uniqueId),
                matchId = matchId,
                serverId = serverId,
                formatVersion = PlayerSnapshotCodec.FORMAT_VERSION,
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

    private fun sha256(payload: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(payload)

    private fun Throwable.unwrapCompletion(): Throwable {
        var current = this
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = requireNotNull(current.cause)
        }
        return current
    }

    private companion object {
        // A lost COMMIT response can race a fresh connection. Require an
        // immediate read plus six delayed confirmations (30 seconds total)
        // before declaring the atomic pair absent and releasing the players.
        const val EMPTY_CONFIRMATIONS_REQUIRED = 7
    }
}
