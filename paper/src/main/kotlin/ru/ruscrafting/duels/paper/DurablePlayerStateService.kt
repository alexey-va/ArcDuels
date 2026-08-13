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
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class StoredPlayerSnapshot(
    val snapshot: PlayerSnapshot,
    val escrow: PlayerStateEscrow,
)

internal class DurablePlayerStateService(
    private val plugin: JavaPlugin,
    private val serverId: ServerId,
    private val repository: PlayerStateEscrowRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val codec = PlayerSnapshotCodec(plugin.server)
    private val pending = ConcurrentHashMap<UUID, PlayerStateEscrow>()
    private val acknowledgements = ConcurrentHashMap<UUID, CompletableFuture<Unit>>()

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
    ): CompletableFuture<Map<UUID, StoredPlayerSnapshot>> {
        check(plugin.server.isPrimaryThread) { "Player state must be captured on the Paper primary thread" }
        val firstStored = capture(matchId, first)
        val secondStored = capture(matchId, second)
        return repository.savePair(firstStored.escrow, secondStored.escrow).thenApply {
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

    fun decode(escrow: PlayerStateEscrow): StoredPlayerSnapshot {
        verifyChecksum(escrow)
        require(escrow.serverId == serverId) { "Player escrow belongs to ${escrow.serverId}, not $serverId" }
        require(escrow.formatVersion == PlayerSnapshotCodec.FORMAT_VERSION) { "Unsupported player escrow format" }
        return StoredPlayerSnapshot(codec.decode(escrow.payload), escrow)
    }

    /** Must be called after the exact snapshot was applied and verified on the primary thread. */
    fun acknowledge(stored: StoredPlayerSnapshot): CompletableFuture<Unit> {
        val playerId = stored.escrow.playerId.value
        val current = pending[playerId]
        if (current == null) return CompletableFuture.completedFuture(Unit)
        if (current != stored.escrow) {
            return CompletableFuture.failedFuture(
                IllegalStateException("A different player escrow replaced the restored snapshot"),
            )
        }
        val acknowledgement =
            acknowledgements.computeIfAbsent(playerId) {
                repository.acknowledgeRestored(stored.escrow).thenApply { acknowledged ->
                    check(acknowledged) { "Escrow acknowledgement did not match the restored snapshot" }
                    check(pending.remove(playerId, stored.escrow)) {
                        "Pending escrow changed before acknowledgement completed"
                    }
                    Unit
                }
            }
        acknowledgement.whenComplete { _, _ -> acknowledgements.remove(playerId, acknowledgement) }
        return acknowledgement
    }

    private fun capture(
        matchId: MatchId,
        player: Player,
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
}
