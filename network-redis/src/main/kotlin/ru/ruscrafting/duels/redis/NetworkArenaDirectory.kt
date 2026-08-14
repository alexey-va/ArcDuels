package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import com.google.gson.JsonParseException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class ArenaModeCapacity(
    val generalTotal: Int,
    val generalFree: Int,
    val kingOfTheHillTotal: Int,
    val kingOfTheHillFree: Int,
) {
    init {
        require(generalTotal in 0..MAX_ARENAS && generalFree in 0..generalTotal) { "Invalid general arena capacity" }
        require(kingOfTheHillTotal in 0..generalTotal && kingOfTheHillFree in 0..kingOfTheHillTotal) {
            "Invalid king-of-the-hill arena capacity"
        }
    }

    fun total(objective: DuelObjectiveType): Int =
        if (objective == DuelObjectiveType.KING_OF_THE_HILL) kingOfTheHillTotal else generalTotal

    fun free(objective: DuelObjectiveType): Int =
        if (objective == DuelObjectiveType.KING_OF_THE_HILL) kingOfTheHillFree else generalFree

    private companion object {
        const val MAX_ARENAS = 10_000
    }
}

data class ArenaNodeStatus(
    val server: ServerId,
    val ownInventory: ArenaModeCapacity,
    val kit: ArenaModeCapacity,
    val queuedPairs: Int,
) {
    init {
        require(queuedPairs in 0..MAX_QUEUE) { "Invalid arena queue size" }
    }

    fun capacity(mode: DuelMode): ArenaModeCapacity =
        if (mode == DuelMode.OWN_INVENTORY) ownInventory else kit

    fun total(rules: DuelRules): Int = capacity(rules.mode).total(rules.objective)

    fun free(rules: DuelRules): Int = capacity(rules.mode).free(rules.objective)

    companion object {
        private const val MAX_QUEUE = 100_000
    }
}

/** Live Redis directory used to route accepted duels to an actual compatible arena node. */
class NetworkArenaDirectory(
    private val redis: RedisOperations,
    private val localServer: ServerId,
    private val clock: Clock = Clock.systemUTC(),
    private val staleAfter: Duration = Duration.ofSeconds(6),
    private val logger: Logger = LoggerFactory.getLogger(NetworkArenaDirectory::class.java),
) : AutoCloseable {
    private val gson = Gson()
    private val nodes = ConcurrentHashMap<ServerId, ObservedStatus>()
    private val listener = ChannelListener(::consume)

    init {
        require(!staleAfter.isNegative && !staleAfter.isZero) { "Arena status TTL must be positive" }
        redis.registerChannelUnique(CHANNEL, listener)
    }

    fun publish(status: ArenaNodeStatus) {
        require(status.server == localServer) { "Cannot publish arena status owned by another server" }
        nodes[localServer] = ObservedStatus(status, clock.millis())
        redis.publish(CHANNEL, gson.toJson(WireStatus.from(status)))
    }

    fun select(rules: DuelRules): ServerId? {
        val now = clock.millis()
        return nodes.values
            .asSequence()
            .filter { now - it.receivedAtMillis < staleAfter.toMillis() }
            .map(ObservedStatus::status)
            .filter { it.total(rules) > 0 }
            .sortedWith(
                compareByDescending<ArenaNodeStatus> { it.free(rules) > 0 }
                    .thenBy(ArenaNodeStatus::queuedPairs)
                    .thenBy { status -> status.total(rules) - status.free(rules) }
                    .thenByDescending { it.free(rules) }
                    .thenBy { it.server.value },
            )
            .firstOrNull()
            ?.server
    }

    fun activeNodes(): List<ArenaNodeStatus> {
        val now = clock.millis()
        return nodes.values
            .filter { now - it.receivedAtMillis < staleAfter.toMillis() }
            .map(ObservedStatus::status)
            .sortedBy { it.server.value }
    }

    override fun close() {
        redis.unregisterChannel(CHANNEL, listener)
        nodes.clear()
    }

    private fun consume(channel: String, message: String, originServer: String) {
        if (channel != CHANNEL) return
        runCatching {
            require(message.length <= MAX_MESSAGE_CHARACTERS) { "Arena status message is too large" }
            val wire =
                try {
                    gson.fromJson(message, WireStatus::class.java)
                } catch (failure: RuntimeException) {
                    throw JsonParseException("Invalid arena status JSON", failure)
                } ?: throw JsonParseException("Arena status cannot be null")
            require(wire.version == WIRE_VERSION) { "Unsupported arena status version ${wire.version}" }
            val status = wire.toStatus()
            require(status.server.value == originServer) { "Redis origin does not match arena status server" }
            nodes[status.server] = ObservedStatus(status, clock.millis())
        }.onFailure { failure -> logger.warn("Rejected ArcDuels arena status from {}", originServer, failure) }
    }

    private data class ObservedStatus(
        val status: ArenaNodeStatus,
        val receivedAtMillis: Long,
    )

    private data class WireStatus(
        val version: Int = WIRE_VERSION,
        val server: String,
        val ownInventory: WireModeCapacity,
        val kit: WireModeCapacity,
        val queuedPairs: Int,
    ) {
        fun toStatus(): ArenaNodeStatus =
            ArenaNodeStatus(
                ServerId(server),
                ownInventory.toCapacity(),
                kit.toCapacity(),
                queuedPairs,
            )

        companion object {
            fun from(status: ArenaNodeStatus): WireStatus =
                WireStatus(
                    server = status.server.value,
                    ownInventory = WireModeCapacity.from(status.ownInventory),
                    kit = WireModeCapacity.from(status.kit),
                    queuedPairs = status.queuedPairs,
                )
        }
    }

    private data class WireModeCapacity(
        val generalTotal: Int,
        val generalFree: Int,
        val kingOfTheHillTotal: Int,
        val kingOfTheHillFree: Int,
    ) {
        fun toCapacity(): ArenaModeCapacity =
            ArenaModeCapacity(generalTotal, generalFree, kingOfTheHillTotal, kingOfTheHillFree)

        companion object {
            fun from(capacity: ArenaModeCapacity): WireModeCapacity =
                WireModeCapacity(
                    capacity.generalTotal,
                    capacity.generalFree,
                    capacity.kingOfTheHillTotal,
                    capacity.kingOfTheHillFree,
                )
        }
    }

    companion object {
        const val CHANNEL = "arcduels:v1:arenas"
        private const val WIRE_VERSION = 2
        private const val MAX_MESSAGE_CHARACTERS = 4_096
    }
}
