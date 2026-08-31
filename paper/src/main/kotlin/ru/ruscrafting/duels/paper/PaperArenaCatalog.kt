package ru.ruscrafting.duels.paper

import org.bukkit.Location
import org.bukkit.configuration.Configuration
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ArenaAllocator
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaReservation
import ru.ruscrafting.duels.domain.ArenaSelection
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.redis.ArenaAdvertisement
import ru.ruscrafting.duels.redis.ArenaChoice
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class ArenaBounds(
    val worldId: UUID,
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    init {
        require(listOf(minX, minY, minZ, maxX, maxY, maxZ).all(Double::isFinite)) {
            "Arena bounds must contain only finite coordinates"
        }
        require(minX <= maxX && minY <= maxY && minZ <= maxZ) {
            "Arena bounds minimum must not exceed maximum"
        }
    }

    fun contains(location: Location): Boolean {
        val world = location.world ?: return false
        return contains(world.uid, location.x, location.y, location.z)
    }

    fun contains(
        candidateWorldId: UUID,
        x: Double,
        y: Double,
        z: Double,
    ): Boolean =
        candidateWorldId == worldId &&
            x in minX..maxX &&
            y in minY..maxY &&
            z in minZ..maxZ

    fun contains(hill: HillZone): Boolean {
        val worldId = hill.center.world?.uid ?: return false
        return contains(worldId, hill.center.x - hill.radius, hill.center.y, hill.center.z - hill.radius) &&
            contains(worldId, hill.center.x + hill.radius, hill.center.y + hill.height, hill.center.z + hill.radius)
    }

    fun distanceToEdge(location: Location): Double? {
        val world = location.world ?: return null
        if (world.uid != worldId || !contains(location)) return null
        return minOf(
            location.x - minX,
            maxX - location.x,
            location.y - minY,
            maxY - location.y,
            location.z - minZ,
            maxZ - location.z,
        )
    }

    fun horizontalBoundaryPoints(
        location: Location,
        halfSpan: Double = 6.0,
    ): List<Location> {
        val world = location.world ?: return emptyList()
        if (world.uid != worldId || !contains(location)) return emptyList()
        require(halfSpan in 1.0..16.0) { "Boundary particle half-span must be between 1 and 16 blocks" }

        val edge =
            listOf(
                HorizontalEdge.MIN_X to location.x - minX,
                HorizontalEdge.MAX_X to maxX - location.x,
                HorizontalEdge.MIN_Z to location.z - minZ,
                HorizontalEdge.MAX_Z to maxZ - location.z,
            ).minBy { it.second }.first
        val points = ArrayList<Location>()
        val minY = maxOf(this.minY, location.y - 1.0)
        val maxY = minOf(this.maxY, location.y + 3.0)
        val yValues = sampledRange(minY, maxY, 1.0)
        when (edge) {
            HorizontalEdge.MIN_X, HorizontalEdge.MAX_X -> {
                val x = if (edge == HorizontalEdge.MIN_X) this.minX else this.maxX
                val zValues = sampledRange(maxOf(minZ, location.z - halfSpan), minOf(maxZ, location.z + halfSpan), 1.5)
                zValues.forEach { z -> yValues.forEach { y -> points += Location(world, x, y, z) } }
            }
            HorizontalEdge.MIN_Z, HorizontalEdge.MAX_Z -> {
                val z = if (edge == HorizontalEdge.MIN_Z) this.minZ else this.maxZ
                val xValues = sampledRange(maxOf(minX, location.x - halfSpan), minOf(maxX, location.x + halfSpan), 1.5)
                xValues.forEach { x -> yValues.forEach { y -> points += Location(world, x, y, z) } }
            }
        }
        return points
    }

    fun overlaps(other: ArenaBounds): Boolean =
        worldId == other.worldId &&
            minX <= other.maxX && maxX >= other.minX &&
            minY <= other.maxY && maxY >= other.minY &&
            minZ <= other.maxZ && maxZ >= other.minZ
}

private enum class HorizontalEdge {
    MIN_X,
    MAX_X,
    MIN_Z,
    MAX_Z,
}

private fun sampledRange(
    minimum: Double,
    maximum: Double,
    step: Double,
): List<Double> {
    if (minimum > maximum) return emptyList()
    val values = ArrayList<Double>()
    var value = minimum
    while (value <= maximum + 1.0e-6) {
        values += value
        value += step
    }
    if (values.last() < maximum - 1.0e-6) values += maximum
    return values
}

data class PaperArena(
    val id: ArenaId,
    val displayName: String,
    val firstSpawn: Location,
    val secondSpawn: Location,
    val bounds: ArenaBounds,
    val hill: HillZone? = null,
    val allowedLoadouts: Set<DuelMode> = DuelMode.entries.toSet(),
    val allowedObjectives: Set<DuelObjectiveType> = DuelObjectiveType.entries.toSet(),
    val lobby: Location? = null,
    val postMatchAction: ArenaPostMatchAction? = null,
    val multiplayerPlacement: MultiplayerSpawnPlacementSettings = MultiplayerSpawnPlacementSettings(),
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 64 && displayName.none(Char::isISOControl)) {
            "Arena display name must contain 1..64 visible characters"
        }
        require(allowedLoadouts.isNotEmpty()) { "Arena must support at least one loadout mode" }
        require(allowedObjectives.isNotEmpty()) { "Arena must support at least one objective" }
    }

    fun supports(rules: DuelRules): Boolean = supports(rules.mode, rules.objective)

    fun supports(
        mode: DuelMode,
        objective: DuelObjectiveType,
    ): Boolean =
        mode in allowedLoadouts &&
            objective in allowedObjectives &&
            (objective != DuelObjectiveType.KING_OF_THE_HILL || hill != null)

    fun supports(roster: MultiplayerRoster): Boolean {
        if (DuelMode.KIT !in allowedLoadouts || DuelObjectiveType.ELIMINATION !in allowedObjectives) return false
        return MultiplayerSpawnPlanner.plan(this, roster) != null
    }

    fun assignSpawns(roster: MultiplayerRoster): Map<PlayerId, Location> {
        require(supports(roster)) { "Arena $id cannot safely fit this multiplayer roster" }
        return requireNotNull(MultiplayerSpawnPlanner.plan(this, roster)) {
            "Arena $id cannot procedurally place this multiplayer roster"
        }
    }
}

class MultiplayerArenaReservation internal constructor(
    val arenaId: ArenaId,
    val spawns: Map<PlayerId, Location>,
    private val release: () -> Unit,
) : AutoCloseable {
    override fun close() = release()
}

data class HillZone(
    val center: Location,
    val radius: Double,
    val height: Double,
) {
    init {
        require(radius in 1.0..32.0) { "Hill radius must be between 1 and 32 blocks" }
        require(height in 1.0..32.0) { "Hill height must be between 1 and 32 blocks" }
    }

    fun contains(location: Location): Boolean {
        if (location.world?.uid != center.world?.uid) return false
        val dx = location.x - center.x
        val dz = location.z - center.z
        return dx * dx + dz * dz <= radius * radius && location.y in center.y..(center.y + height)
    }
}

data class ArenaCapacity(
    val total: Int,
    val free: Int,
)

class PaperArenaCatalog private constructor(
    initialArenas: Map<ArenaId, PaperArena>,
) : ArenaAllocator {
    private val lock = Any()
    @Volatile
    private var arenas: Map<ArenaId, PaperArena> = initialArenas
    private val reserved = mutableSetOf<ArenaId>()
    private val waiting = ArrayDeque<PendingReservation>()

    override fun reserve(rules: DuelRules): CompletableFuture<ArenaReservation> {
        return reserve(rules, null)
    }

    override fun reserve(
        rules: DuelRules,
        arenaId: ArenaId?,
    ): CompletableFuture<ArenaReservation> {
        val future = CompletableFuture<ArenaReservation>()
        synchronized(lock) {
            if (arenas.isEmpty()) return CompletableFuture.failedFuture(IllegalStateException("No enabled duel arenas are configured"))
            val compatible =
                if (arenaId == null) {
                    arenas.values.filter { it.supports(rules) }
                } else {
                    listOfNotNull(arenas[arenaId]).filter { it.supports(rules) }
                }
            if (compatible.isEmpty()) {
                val reason = if (arenaId == null) "No arena supports the selected objective" else "Selected arena is missing or incompatible"
                return CompletableFuture.failedFuture(IllegalStateException(reason))
            }
            val arena = compatible.firstOrNull { it.id !in reserved }
            if (arena == null) {
                waiting.addLast(PendingReservation(rules, arenaId, future))
            } else {
                reserved += arena.id
                future.complete(reservationFor(arena.id))
            }
        }
        future.whenComplete { _, _ ->
            if (future.isCancelled) synchronized(lock) { waiting.removeAll { it.future === future } }
        }
        return future
    }

    fun get(id: ArenaId): PaperArena = arenas[id] ?: error("Arena $id disappeared from the catalog")

    fun reserveMultiplayer(roster: MultiplayerRoster): CompletableFuture<MultiplayerArenaReservation> =
        synchronized(lock) {
            val compatible = arenas.values.filter { it.supports(roster) }
            if (compatible.isEmpty()) {
                return@synchronized CompletableFuture.failedFuture(MultiplayerArenaCapacityException())
            }
            val arena = compatible.firstOrNull { it.id !in reserved }
                ?: return@synchronized CompletableFuture.failedFuture(MultiplayerArenasBusyException())
            reserved += arena.id
            CompletableFuture.completedFuture(
                MultiplayerArenaReservation(arena.id, arena.assignSpawns(roster)) { release(arena.id) },
            )
        }

    fun hasMultiplayerCapacity(roster: MultiplayerRoster): Boolean =
        synchronized(lock) { arenas.values.any { it.supports(roster) } }

    fun size(): Int = arenas.size

    fun queueSize(): Int = synchronized(lock) { waiting.count { !it.future.isDone } }

    fun reservedCount(): Int = synchronized(lock) { reserved.size }

    fun isReserved(id: ArenaId): Boolean = synchronized(lock) { id in reserved }

    fun choices(
        serverId: ServerId,
        rules: DuelRules,
    ): List<ArenaChoice> =
        synchronized(lock) {
            arenas.values
                .filter { it.supports(rules) }
                .map { arena ->
                    ArenaChoice(
                        selection = ArenaSelection(serverId, arena.id),
                        displayName = arena.displayName,
                        available = arena.id !in reserved,
                        queuedPairs = waiting.count { !it.future.isDone },
                    )
                }
                .sortedWith(compareByDescending<ArenaChoice>(ArenaChoice::available).thenBy { it.selection.arenaId.value })
        }

    fun advertisements(allowOwnInventory: Boolean): List<ArenaAdvertisement> =
        synchronized(lock) {
            arenas.values.mapNotNull { arena ->
                val loadouts =
                    arena.allowedLoadouts.filterTo(linkedSetOf()) { mode ->
                        mode != DuelMode.OWN_INVENTORY || allowOwnInventory
                    }
                if (loadouts.isEmpty()) return@mapNotNull null
                val objectives =
                    arena.allowedObjectives.filterTo(linkedSetOf()) { objective ->
                        objective != DuelObjectiveType.KING_OF_THE_HILL || arena.hill != null
                    }
                ArenaAdvertisement(
                    id = arena.id,
                    displayName = arena.displayName,
                    loadouts = loadouts,
                    objectives = objectives,
                    available = arena.id !in reserved,
                )
            }
        }

    fun capacity(
        mode: DuelMode,
        objective: DuelObjectiveType,
    ): ArenaCapacity =
        synchronized(lock) {
            val compatible = arenas.values.filter { it.supports(mode, objective) }
            ArenaCapacity(
                total = compatible.size,
                free = compatible.count { it.id !in reserved },
            )
        }

    fun disable(id: ArenaId): Int =
        synchronized(lock) {
            check(reserved.isEmpty() && waiting.none { !it.future.isDone }) {
                "An arena cannot be disabled while matches or queue entries are active"
            }
            arenas = arenas - id
            arenas.size
        }

    fun reload(plugin: JavaPlugin): Int {
        val loaded = load(plugin)
        return replaceWith(loaded)
    }

    /** Publishes an already validated catalog without exposing a partially parsed state. */
    internal fun replaceWith(replacement: PaperArenaCatalog): Int {
        synchronized(lock) {
            check(reserved.isEmpty() && waiting.none { !it.future.isDone }) {
                "Arenas cannot be reloaded while matches or queue entries are active"
            }
            arenas = replacement.arenas
        }
        return arenas.size
    }

    /** Non-throwing live-reload publication; a concurrent lease simply defers the candidate. */
    internal fun tryReplaceWith(replacement: PaperArenaCatalog): Int? =
        synchronized(lock) {
            if (reserved.isNotEmpty() || waiting.any { !it.future.isDone }) return@synchronized null
            arenas = replacement.arenas
            arenas.size
        }

    private fun reservationFor(id: ArenaId): ArenaReservation = ArenaReservation(id) { release(id) }

    private fun release(id: ArenaId) {
        val assignments = mutableListOf<Pair<CompletableFuture<ArenaReservation>, ArenaReservation>>()
        synchronized(lock) {
            if (!reserved.remove(id)) return
            while (true) {
                val next = waiting.firstOrNull { pending ->
                    !pending.future.isDone && arenas.values.any { arena ->
                        arena.id !in reserved &&
                            (pending.arenaId == null || arena.id == pending.arenaId) &&
                            arena.supports(pending.rules)
                    }
                } ?: break
                waiting.remove(next)
                val arena =
                    requireNotNull(
                        arenas.values.firstOrNull { arena ->
                            arena.id !in reserved &&
                                (next.arenaId == null || arena.id == next.arenaId) &&
                                arena.supports(next.rules)
                        },
                    )
                reserved += arena.id
                assignments += next.future to reservationFor(arena.id)
            }
        }
        assignments.forEach { (future, reservation) ->
            if (!future.complete(reservation)) reservation.close()
        }
    }

    companion object {
        fun load(plugin: JavaPlugin): PaperArenaCatalog =
            load(plugin, plugin.config, ArenaEnvironmentInspector.create(plugin))

        internal fun load(
            plugin: JavaPlugin,
            inspector: ArenaEnvironmentInspector,
        ): PaperArenaCatalog = load(plugin, plugin.config, inspector)

        internal fun load(
            plugin: JavaPlugin,
            configuration: Configuration,
            inspector: ArenaEnvironmentInspector = ArenaEnvironmentInspector.create(plugin),
        ): PaperArenaCatalog = PaperArenaCatalog(parse(plugin, configuration, inspector))

        private fun parse(
            plugin: JavaPlugin,
            configuration: Configuration,
            inspector: ArenaEnvironmentInspector,
        ): Map<ArenaId, PaperArena> {
            val root = configuration.strictConfigurationSection("arenas")
                ?: return emptyMap()
            val multiplayerPlacement = MultiplayerSpawnPlacementSettings.load(configuration)
            val entries =
                root.getKeys(false).mapNotNull { rawId ->
                    val section = requireNotNull(root.strictConfigurationSection(rawId))
                    if (!section.strictBoolean("enabled", false)) return@mapNotNull null
                    val id = ArenaId(rawId.lowercase())
                    val displayName = section.strictString("display-name", rawId).trim()
                    val first = section.readLocation(plugin, "first-spawn")
                    val second = section.readLocation(plugin, "second-spawn")
                    val firstWorld = requireNotNull(first.world)
                    require(second.world?.uid == firstWorld.uid) { "Arena $id spawns must be in the same world" }
                    val bounds = section.readBounds(firstWorld.uid)
                    require(bounds.contains(first) && bounds.contains(second)) { "Arena $id spawns must be inside its bounds" }
                    val hill = section.strictConfigurationSection("hill")?.readHill(plugin)
                    val lobby = section.strictConfigurationSection("lobby")?.let { section.readLocation(plugin, "lobby") }
                    val postMatchAction =
                        section.getString("post-match-action")
                            ?.takeIf(String::isNotBlank)
                            ?.let(ArenaPostMatchAction::parse)
                    require(hill == null || bounds.contains(hill)) { "Arena $id hill zone must be fully inside its bounds" }
                    id to
                        PaperArena(
                            id,
                            displayName,
                            first,
                            second,
                            bounds,
                            hill,
                            readArenaAllowedLoadouts(section),
                            readArenaAllowedObjectives(section),
                            lobby,
                            postMatchAction,
                            multiplayerPlacement,
                        )
                }
            require(entries.map(Pair<ArenaId, PaperArena>::first).distinct().size == entries.size) {
                "Arena ids must be unique after lowercase normalization"
            }
            entries.forEach { (_, arena) -> inspector.inspect(arena) }
            return entries.toMap()
        }

        private fun ConfigurationSection.readBounds(worldId: UUID): ArenaBounds {
            val section = strictConfigurationSection("bounds") ?: error("Missing arena bounds $currentPath.bounds")
            val minimum = section.strictConfigurationSection("min") ?: error("Missing arena bounds $currentPath.bounds.min")
            val maximum = section.strictConfigurationSection("max") ?: error("Missing arena bounds $currentPath.bounds.max")
            minimum.requireCoordinates()
            maximum.requireCoordinates()
            return ArenaBounds(
                worldId = worldId,
                minX = minimum.strictNumber("x"),
                minY = minimum.strictNumber("y"),
                minZ = minimum.strictNumber("z"),
                maxX = maximum.strictNumber("x"),
                maxY = maximum.strictNumber("y"),
                maxZ = maximum.strictNumber("z"),
            )
        }

        private fun ConfigurationSection.readLocation(
            plugin: JavaPlugin,
            path: String,
        ): Location {
            val section = strictConfigurationSection(path) ?: error("Missing arena location $currentPath.$path")
            val worldName = section.strictString("world", "").takeIf(String::isNotBlank)
                ?: error("Missing world for $currentPath.$path")
            val world = plugin.server.getWorld(worldName) ?: error("Arena world '$worldName' is not loaded")
            section.requireCoordinates()
            val location = Location(
                world,
                section.strictNumber("x"),
                section.strictNumber("y"),
                section.strictNumber("z"),
                section.strictNumber("yaw", 0.0).toFloat(),
                section.strictNumber("pitch", 0.0).toFloat(),
            )
            require(listOf(location.x, location.y, location.z).all(Double::isFinite)) {
                "Arena location $currentPath.$path must contain finite coordinates"
            }
            return location
        }

        private fun ConfigurationSection.readHill(plugin: JavaPlugin): HillZone {
            val center = readLocation(plugin, "center")
            return HillZone(
                center = center,
                radius = strictNumber("radius", 3.5),
                height = strictNumber("height", 3.0),
            )
        }

        private fun ConfigurationSection.requireCoordinates() {
            require(contains("x") && contains("y") && contains("z")) {
                "Missing x, y, or z coordinate in $currentPath"
            }
        }
    }

    private data class PendingReservation(
        val rules: DuelRules,
        val arenaId: ArenaId?,
        val future: CompletableFuture<ArenaReservation>,
    )
}

internal fun readArenaAllowedLoadouts(section: ConfigurationSection): Set<DuelMode> {
    if (!section.contains(ARENA_LOADOUTS_PATH)) return DuelMode.entries.toSet()
    val configured = section.strictStringList(ARENA_LOADOUTS_PATH)
    require(configured.isNotEmpty()) { "Arena ${section.currentPath} must allow at least one loadout" }
    return configured.map { raw ->
        runCatching { DuelMode.valueOf(raw.trim().uppercase()) }
            .getOrElse { throw IllegalArgumentException("Arena ${section.currentPath} has invalid loadout '$raw'") }
    }.toSet()
}

internal const val ARENA_LOADOUTS_PATH = "allowed-loadouts"
internal const val ARENA_OBJECTIVES_PATH = "allowed-objectives"

internal fun readArenaAllowedObjectives(section: ConfigurationSection): Set<DuelObjectiveType> {
    if (!section.contains(ARENA_OBJECTIVES_PATH)) return DuelObjectiveType.entries.toSet()
    val configured = section.strictStringList(ARENA_OBJECTIVES_PATH)
    require(configured.isNotEmpty()) { "Arena ${section.currentPath} must allow at least one objective" }
    return configured.mapTo(linkedSetOf()) { raw ->
        parseObjective(raw)
            ?: throw IllegalArgumentException("Arena ${section.currentPath} has invalid objective '$raw'")
    }
}

internal fun parseObjective(raw: String): DuelObjectiveType? =
    when (raw.trim().lowercase()) {
        "elimination", "classic" -> DuelObjectiveType.ELIMINATION
        "king_of_the_hill", "king-of-the-hill", "koth" -> DuelObjectiveType.KING_OF_THE_HILL
        "sumo" -> DuelObjectiveType.SUMO
        "boxing" -> DuelObjectiveType.BOXING
        "combo" -> DuelObjectiveType.COMBO
        else -> null
    }

internal enum class ArenaLoadoutSelection(
    val modes: Set<DuelMode>,
) {
    ALL(DuelMode.entries.toSet()),
    OWN_INVENTORY(setOf(DuelMode.OWN_INVENTORY)),
    KIT(setOf(DuelMode.KIT)),
    ;

    fun next(): ArenaLoadoutSelection =
        when (this) {
            ALL -> OWN_INVENTORY
            OWN_INVENTORY -> KIT
            KIT -> ALL
        }

    val commandValue: String
        get() =
            when (this) {
                ALL -> "all"
                OWN_INVENTORY -> "own"
                KIT -> "kit"
            }

    companion object {
        fun from(section: ConfigurationSection): ArenaLoadoutSelection {
            val modes = readArenaAllowedLoadouts(section)
            return entries.firstOrNull { it.modes == modes }
                ?: throw IllegalArgumentException("Arena ${section.currentPath} has an unsupported loadout combination")
        }

        fun parse(raw: String): ArenaLoadoutSelection? =
            when (raw.lowercase()) {
                "all", "both" -> ALL
                "own", "own_inventory" -> OWN_INVENTORY
                "kit", "kits" -> KIT
                else -> null
            }
    }
}
