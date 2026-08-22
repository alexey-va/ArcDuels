package ru.ruscrafting.duels.redis

import com.google.gson.Gson
import com.google.gson.JsonParseException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class ArenaAdvertisement(
    val id: ArenaId,
    val displayName: String,
    val loadouts: Set<DuelMode>,
    val objectives: Set<DuelObjectiveType>,
    val available: Boolean,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "Arena display name must contain 1..$MAX_DISPLAY_NAME_LENGTH characters"
        }
        require(displayName.none(Char::isISOControl)) { "Arena display name contains control characters" }
        require(loadouts.isNotEmpty()) { "Advertised arena must support at least one loadout" }
        require(objectives.isNotEmpty()) { "Advertised arena must support at least one objective" }
    }

    fun supports(rules: DuelRules): Boolean = rules.mode in loadouts && rules.objective in objectives

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 64
    }
}

data class ArenaNodeStatus(
    val server: ServerId,
    val arenas: List<ArenaAdvertisement>,
    val queuedPairs: Int,
) {
    init {
        require(arenas.size <= MAX_ARENAS) { "Too many advertised arenas" }
        require(arenas.map(ArenaAdvertisement::id).distinct().size == arenas.size) {
            "Advertised arena ids must be unique per server"
        }
        require(queuedPairs in 0..MAX_QUEUE) { "Invalid arena queue size" }
    }

    fun matching(rules: DuelRules): List<ArenaAdvertisement> = arenas.filter { it.supports(rules) }

    fun total(rules: DuelRules): Int = matching(rules).size

    fun free(rules: DuelRules): Int = matching(rules).count(ArenaAdvertisement::available)

    companion object {
        private const val MAX_ARENAS = 1_000
        private const val MAX_QUEUE = 100_000
    }
}

data class ArenaChoice(
    val selection: ArenaSelection,
    val displayName: String,
    val available: Boolean,
    val queuedPairs: Int,
)

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
        val message = gson.toJson(WireStatus.from(status))
        require(message.length <= MAX_MESSAGE_CHARACTERS) { "Arena status message is too large" }
        nodes[localServer] = ObservedStatus(status, clock.millis())
        redis.publish(CHANNEL, message)
    }

    fun select(
        rules: DuelRules,
        selected: ArenaSelection? = null,
    ): ServerId? {
        val active = activeNodes()
        if (selected != null) {
            return active.firstOrNull { status ->
                status.server == selected.serverId &&
                    status.arenas.any { it.id == selected.arenaId && it.supports(rules) }
            }?.server
        }
        return active
            .asSequence()
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

    fun choices(rules: DuelRules): List<ArenaChoice> =
        activeNodes()
            .flatMap { status ->
                status.matching(rules).map { arena ->
                    ArenaChoice(
                        selection = ArenaSelection(status.server, arena.id),
                        displayName = arena.displayName,
                        available = arena.available,
                        queuedPairs = status.queuedPairs,
                    )
                }
            }
            .sortedWith(
                compareByDescending<ArenaChoice>(ArenaChoice::available)
                    .thenBy(ArenaChoice::queuedPairs)
                    .thenBy { it.selection.serverId.value }
                    .thenBy { it.selection.arenaId.value },
            )

    fun activeNodes(): List<ArenaNodeStatus> {
        val now = clock.millis()
        return nodes.values
            .filter { isFreshObservation(now, it.receivedAtMillis, staleAfter.toMillis()) }
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
            if (wire.version != WIRE_VERSION) {
                logger.debug("Ignored ArcDuels arena status version {} from {}", wire.version, originServer)
                return@runCatching
            }
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
        val arenas: List<WireArena>,
        val queuedPairs: Int,
    ) {
        fun toStatus(): ArenaNodeStatus =
            ArenaNodeStatus(
                server = ServerId(server),
                arenas = arenas.map(WireArena::toAdvertisement),
                queuedPairs = queuedPairs,
            )

        companion object {
            fun from(status: ArenaNodeStatus): WireStatus =
                WireStatus(
                    server = status.server.value,
                    arenas = status.arenas.map(WireArena::from),
                    queuedPairs = status.queuedPairs,
                )
        }
    }

    private data class WireArena(
        val id: String,
        val displayName: String,
        val loadouts: List<String>,
        val objectives: List<String>,
        val available: Boolean,
    ) {
        fun toAdvertisement(): ArenaAdvertisement =
            ArenaAdvertisement(
                id = ArenaId(id),
                displayName = displayName,
                loadouts = loadouts.mapTo(linkedSetOf(), DuelMode::valueOf),
                objectives = objectives.mapTo(linkedSetOf(), DuelObjectiveType::valueOf),
                available = available,
            )

        companion object {
            fun from(arena: ArenaAdvertisement): WireArena =
                WireArena(
                    id = arena.id.value,
                    displayName = arena.displayName,
                    loadouts = arena.loadouts.map(Enum<*>::name).sorted(),
                    objectives = arena.objectives.map(Enum<*>::name).sorted(),
                    available = arena.available,
                )
        }
    }

    companion object {
        const val CHANNEL = "arcduels:v1:arenas"
        private const val WIRE_VERSION = 4
        private const val MAX_MESSAGE_CHARACTERS = 65_536
    }
}
