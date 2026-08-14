package ru.ruscrafting.duels.domain

import java.time.Instant
import java.util.Arrays
import java.util.concurrent.CompletableFuture

/**
 * Opaque, versioned player state stored before Paper mutates a duel participant.
 *
 * The domain never interprets [payload]. Its checksum is verified by both the
 * Paper codec and the durable repository so corrupt or mismatched state fails
 * closed instead of being applied to a player.
 */
class PlayerStateEscrow(
    val playerId: PlayerId,
    val matchId: MatchId,
    val serverId: ServerId,
    val formatVersion: Int,
    val inventoryReplaced: Boolean = false,
    payload: ByteArray,
    checksum: ByteArray,
    val createdAt: Instant,
) {
    val payload: ByteArray = payload.copyOf()
    val checksum: ByteArray = checksum.copyOf()

    init {
        require(formatVersion > 0) { "Escrow format version must be positive" }
        require(this.payload.isNotEmpty()) { "Escrow payload must not be empty" }
        require(this.checksum.size == SHA_256_BYTES) { "Escrow checksum must be SHA-256" }
    }

    fun sameContent(other: PlayerStateEscrow): Boolean =
        playerId == other.playerId &&
            matchId == other.matchId &&
            serverId == other.serverId &&
            formatVersion == other.formatVersion &&
            inventoryReplaced == other.inventoryReplaced &&
            payload.contentEquals(other.payload) &&
            checksum.contentEquals(other.checksum)

    override fun equals(other: Any?): Boolean = other is PlayerStateEscrow && sameContent(other) && createdAt == other.createdAt

    override fun hashCode(): Int =
        Arrays.hashCode(
            arrayOf(
                playerId,
                matchId,
                serverId,
                formatVersion,
                inventoryReplaced,
                payload.contentHashCode(),
                checksum.contentHashCode(),
                createdAt,
            ),
        )

    companion object {
        const val SHA_256_BYTES = 32
    }
}

interface PlayerStateEscrowRepository {
    /** Stores both participants atomically and verifies their committed bytes. */
    fun savePair(
        first: PlayerStateEscrow,
        second: PlayerStateEscrow,
    ): CompletableFuture<Unit>

    fun findPending(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?>

    fun pending(serverId: ServerId): CompletableFuture<List<PlayerStateEscrow>>

    /** Returns the most recently claimed snapshot, for explicit administrator replay only. */
    fun findLatestRetained(playerId: PlayerId): CompletableFuture<PlayerStateEscrow?>

    /**
     * Atomically moves the exact applied snapshot out of active recovery and
     * into retained history. A retained snapshot is never auto-applied.
     */
    fun retainRestored(
        snapshot: PlayerStateEscrow,
        restoredAt: Instant,
        purgeAfter: Instant,
    ): CompletableFuture<Boolean>

    /** Deletes only retained snapshots whose durable retention deadline elapsed. */
    fun purgeRetained(cutoff: Instant): CompletableFuture<Int>
}
