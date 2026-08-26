package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.arc.network.LeasedNetworkDirectory
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonArrayContract
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.OriginBoundRedisBus
import ru.arc.redis.safety.RedisMessageRejection
import ru.arc.redis.safety.RedisWireCodec
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
    private val snapshots = LeasedNetworkDirectory<ServerId, List<NetworkPlayer>>(
        leaseMillis = staleAfter.toMillis(),
        maxEntries = 1,
        clock = clock::millis,
    )
    private val bus =
        OriginBoundRedisBus(
            redis = redis,
            channel = CHANNEL,
            codec = NetworkPlayerSnapshotCodec(),
            originAllowed = { origin -> origin == expectedOrigin.value },
            onMessage = { players, origin -> snapshots.observe(expectedOrigin, origin, players) },
            onRejected = { reason -> logRejection(reason) },
        )

    init {
        require(!staleAfter.isNegative && !staleAfter.isZero) { "Player snapshot TTL must be positive" }
        bus.register()
    }

    fun players(): List<NetworkPlayer> = snapshots.get(expectedOrigin)?.value.orEmpty()

    fun find(uuid: UUID): NetworkPlayer? = players().firstOrNull { it.uuid == uuid }

    fun find(username: String): NetworkPlayer? = players().firstOrNull { it.username.equals(username, ignoreCase = true) }

    override fun close() {
        bus.close()
        snapshots.clear()
    }

    private fun logRejection(reason: RedisMessageRejection) {
        logger.warn("Rejected ProxyARC player snapshot: {}", reason)
    }

    companion object {
        const val CHANNEL = "arc.proxy_player_list"
    }
}

private class NetworkPlayerSnapshotCodec(
    gson: Gson = Gson(),
) : RedisWireCodec<List<NetworkPlayer>> {
    private val wireType = object : TypeToken<List<WirePlayer>>() {}.type
    private val wireCodec =
        BoundedJsonCodec.forType<List<WirePlayer>>(
            gson = gson,
            type = wireType,
            rootContract =
                JsonArrayContract(
                    maxEntries = MAX_PLAYERS,
                    elementContract =
                        JsonObjectContract(
                            allowedFields = setOf("username", "server", "uuid", "joinTime"),
                        ),
                ),
            bounds =
                JsonResourceBounds(
                    maxCharacters = MAX_SNAPSHOT_CHARACTERS,
                    maxDepth = 3,
                    maxContainerEntries = MAX_PLAYERS,
                    maxTotalNodes = 50_001,
                    maxStringCharacters = 64,
                ),
            validate = { players ->
                require(players.size <= MAX_PLAYERS) { "Proxy player snapshot has too many entries" }
                players.forEach { player ->
                    require(player.username.length <= 17) { "Proxy player name is too long" }
                    require(player.server.length <= 48) { "Proxy backend id is too long" }
                    require(player.uuid.length <= 36) { "Proxy player UUID is too long" }
                }
            },
        )

    override fun encode(value: List<NetworkPlayer>): String =
        wireCodec.encode(
            value.map { player -> WirePlayer(player.username, player.server.value, player.uuid.toString(), player.joinedAtMillis) },
        )

    override fun decode(raw: String): List<NetworkPlayer> {
        val seen = HashSet<UUID>()
        return wireCodec.decode(raw).mapNotNull { wire ->
            if (wire.server.isBlank()) return@mapNotNull null
            val uuid = UUID.fromString(wire.uuid)
            require(seen.add(uuid)) { "Duplicate player UUID in proxy snapshot" }
            require(isSafeNetworkPlayerName(wire.username)) { "Unsafe player name in proxy snapshot" }
            require(wire.joinTime >= 0L) { "Negative player join time in proxy snapshot" }
            NetworkPlayer(uuid, wire.username, ServerId(wire.server), wire.joinTime)
        }
    }

    private data class WirePlayer(
        val username: String,
        val server: String,
        val uuid: String,
        val joinTime: Long,
    )

    private companion object {
        const val MAX_PLAYERS = 10_000
        const val MAX_SNAPSHOT_CHARACTERS = 2_000_000
    }
}
