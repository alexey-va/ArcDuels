package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.time.Duration
import java.util.UUID

data class NetworkPlayer(
    val uuid: UUID,
    val username: String,
    val server: ServerId,
    val joinedAtMillis: Long,
)

/**
 * Validated, expiring view of the player snapshot published by ProxyARC.
 * A stale proxy snapshot is treated as unavailable instead of showing ghosts.
 */
class NetworkPlayerDirectory(
    private val redis: RedisOperations,
    private val expectedOrigin: ServerId = ServerId("proxy"),
    private val clock: Clock = Clock.systemUTC(),
    private val staleAfter: Duration = Duration.ofSeconds(5),
    private val logger: Logger = LoggerFactory.getLogger(NetworkPlayerDirectory::class.java),
) : AutoCloseable {
    private val gson = Gson()
    private val wireType = object : TypeToken<List<WirePlayer>>() {}.type
    private val listener = ChannelListener(::consume)

    @Volatile
    private var snapshot = Snapshot(emptyList(), Long.MIN_VALUE)

    init {
        require(!staleAfter.isNegative && !staleAfter.isZero) { "Player snapshot TTL must be positive" }
        redis.registerChannelUnique(CHANNEL, listener)
    }

    fun players(): List<NetworkPlayer> {
        val current = snapshot
        if (!isFreshObservation(clock.millis(), current.receivedAtMillis, staleAfter.toMillis())) return emptyList()
        return current.players
    }

    fun find(uuid: UUID): NetworkPlayer? = players().firstOrNull { it.uuid == uuid }

    fun find(username: String): NetworkPlayer? = players().firstOrNull { it.username.equals(username, ignoreCase = true) }

    override fun close() {
        redis.unregisterChannel(CHANNEL, listener)
        snapshot = Snapshot(emptyList(), Long.MIN_VALUE)
    }

    private fun consume(channel: String, message: String, originServer: String) {
        if (channel != CHANNEL || originServer != expectedOrigin.value) return
        runCatching { decode(message) }
            .onSuccess { players -> snapshot = Snapshot(players, clock.millis()) }
            .onFailure { failure -> logger.warn("Rejected ProxyARC player snapshot from {}", originServer, failure) }
    }

    private fun decode(json: String): List<NetworkPlayer> {
        require(json.length <= MAX_SNAPSHOT_CHARACTERS) { "Proxy player snapshot is too large" }
        val wirePlayers =
            try {
                gson.fromJson<List<WirePlayer>?>(json, wireType)
            } catch (failure: RuntimeException) {
                throw JsonParseException("Invalid ProxyARC player snapshot", failure)
            } ?: throw JsonParseException("ProxyARC player snapshot cannot be null")
        require(wirePlayers.size <= MAX_PLAYERS) { "Proxy player snapshot exceeds $MAX_PLAYERS entries" }
        val seen = HashSet<UUID>()
        return wirePlayers.mapNotNull { wire ->
            if (wire.server.isBlank()) return@mapNotNull null
            val uuid = UUID.fromString(wire.uuid)
            require(seen.add(uuid)) { "Duplicate player UUID in proxy snapshot" }
            require(isSafeNetworkPlayerName(wire.username)) { "Unsafe player name in proxy snapshot" }
            require(wire.joinTime >= 0L) { "Negative player join time in proxy snapshot" }
            NetworkPlayer(uuid, wire.username, ServerId(wire.server), wire.joinTime)
        }
    }

    private data class Snapshot(
        val players: List<NetworkPlayer>,
        val receivedAtMillis: Long,
    )

    private data class WirePlayer(
        val username: String,
        val server: String,
        val uuid: String,
        val joinTime: Long,
    )

    companion object {
        const val CHANNEL = "arc.proxy_player_list"
        private const val MAX_PLAYERS = 10_000
        private const val MAX_SNAPSHOT_CHARACTERS = 2_000_000
    }
}
