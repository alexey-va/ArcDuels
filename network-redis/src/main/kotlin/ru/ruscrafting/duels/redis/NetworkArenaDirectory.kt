package ru.ruscrafting.duels.redis

import com.google.gson.Gson
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
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.ServerId
import java.time.Clock
import java.time.Duration

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
    private val nodes = LeasedNetworkDirectory<ServerId, ArenaNodeStatus>(
        leaseMillis = staleAfter.toMillis(),
        maxEntries = MAX_NETWORK_NODES,
        clock = clock::millis,
    )
    private val codec = ArenaStatusCodec()
    private val bus =
        OriginBoundRedisBus(
            redis = redis,
            channel = CHANNEL,
            codec = codec,
            originAllowed = { origin -> runCatching { ServerId(origin) }.isSuccess },
            embeddedOrigin = { message -> message.server.value },
            onMessage = { message, origin -> receive(message, origin) },
            onRejected = { reason -> logRejection(reason) },
        )

    init {
        require(!staleAfter.isNegative && !staleAfter.isZero) { "Arena status TTL must be positive" }
        bus.register()
    }

    fun publish(status: ArenaNodeStatus) {
        require(status.server == localServer) { "Cannot publish arena status owned by another server" }
        val message = ArenaWireMessage.current(status)
        codec.encode(message)
        nodes.observe(localServer, localServer.value, status)
        bus.publish(message)
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
        return nodes.snapshot()
            .map { it.value }
            .sortedBy { it.server.value }
    }

    fun activeLeaseCount(): Int = nodes.size()

    override fun close() {
        bus.close()
        nodes.clear()
    }

    private fun receive(message: ArenaWireMessage, originServer: String) {
        nodes.observe(message.status.server, originServer, message.status)
    }

    private fun logRejection(reason: RedisMessageRejection) {
        logger.warn("Rejected ArcDuels arena status: {}", reason)
    }

    companion object {
        const val CHANNEL = "arcduels:v1:arenas"
        private const val MAX_NETWORK_NODES = 10_000
    }
}

private data class ArenaWireMessage(
    val version: Int,
    val server: ServerId,
    val status: ArenaNodeStatus,
) {
    init {
        require(status.server == server) { "Arena wire server does not match its status" }
    }

    companion object {
        fun current(status: ArenaNodeStatus): ArenaWireMessage =
            ArenaWireMessage(ARENA_WIRE_VERSION, status.server, status)
    }
}

private class ArenaStatusCodec(
    gson: Gson = Gson(),
) : RedisWireCodec<ArenaWireMessage> {
    private val wireCodec =
        BoundedJsonCodec(
            gson = gson,
            type = WireStatus::class.java,
            rootContract =
                JsonObjectContract(
                    allowedFields = setOf("version", "server", "arenas", "queuedPairs"),
                    requiredFields = setOf("version", "server", "arenas", "queuedPairs"),
                    fieldContracts =
                        mapOf(
                            "arenas" to
                                JsonArrayContract(
                                    maxEntries = MAX_ARENAS_PER_NODE,
                                    elementContract =
                                        JsonObjectContract(
                                            allowedFields = setOf("id", "displayName", "loadouts", "objectives", "available"),
                                        ),
                                ),
                        ),
                ),
            bounds =
                JsonResourceBounds(
                    maxCharacters = MAX_ARENA_MESSAGE_CHARACTERS,
                    maxDepth = 6,
                    maxContainerEntries = MAX_ARENAS_PER_NODE,
                    maxTotalNodes = 50_000,
                    maxStringCharacters = 64,
                ),
            validate = { wire ->
                require(wire.version == ARENA_WIRE_VERSION) { "Unsupported arena wire version ${wire.version}" }
                ServerId(wire.server)
                requireNotNull(wire.arenas) { "Current arena status is missing arenas" }
                require(wire.arenas.size <= MAX_ARENAS_PER_NODE) { "Too many wire arenas" }
            },
        )

    override fun encode(value: ArenaWireMessage): String {
        require(value.version == ARENA_WIRE_VERSION) {
            "Only the current arena wire version can be published"
        }
        return wireCodec.encode(WireStatus.from(value.status))
    }

    override fun decode(raw: String): ArenaWireMessage {
        val wire = wireCodec.decode(raw)
        return ArenaWireMessage(wire.version, ServerId(wire.server), wire.toStatus())
    }

    private data class WireStatus(
        val version: Int = ARENA_WIRE_VERSION,
        val server: String,
        val arenas: List<WireArena>? = null,
        val queuedPairs: Int,
    ) {
        fun toStatus(): ArenaNodeStatus =
            ArenaNodeStatus(
                server = ServerId(server),
                arenas = requireNotNull(arenas).map(WireArena::toAdvertisement),
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
}

private const val ARENA_WIRE_VERSION = 4
private const val MAX_ARENAS_PER_NODE = 1_000
private const val MAX_ARENA_MESSAGE_CHARACTERS = 65_536
