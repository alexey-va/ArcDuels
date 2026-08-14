package ru.ruscrafting.duels.paper

import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ArenaAllocator
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaReservation
import ru.ruscrafting.duels.domain.DuelRules
import ru.ruscrafting.duels.domain.DuelObjectiveType
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

    fun overlaps(other: ArenaBounds): Boolean =
        worldId == other.worldId &&
            minX <= other.maxX && maxX >= other.minX &&
            minY <= other.maxY && maxY >= other.minY &&
            minZ <= other.maxZ && maxZ >= other.minZ
}

data class PaperArena(
    val id: ArenaId,
    val firstSpawn: Location,
    val secondSpawn: Location,
    val bounds: ArenaBounds,
    val hill: HillZone? = null,
) {
    fun supports(objective: DuelObjectiveType): Boolean =
        objective != DuelObjectiveType.KING_OF_THE_HILL || hill != null
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
        val future = CompletableFuture<ArenaReservation>()
        synchronized(lock) {
            if (arenas.isEmpty()) return CompletableFuture.failedFuture(IllegalStateException("No enabled duel arenas are configured"))
            val compatible = arenas.values.filter { it.supports(rules.objective) }
            if (compatible.isEmpty()) {
                return CompletableFuture.failedFuture(IllegalStateException("No arena supports the selected objective"))
            }
            val arena = compatible.firstOrNull { it.id !in reserved }
            if (arena == null) {
                waiting.addLast(PendingReservation(rules, future))
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

    fun size(): Int = arenas.size

    fun queueSize(): Int = synchronized(lock) { waiting.count { !it.future.isDone } }

    fun reservedCount(): Int = synchronized(lock) { reserved.size }

    fun capacity(objective: DuelObjectiveType): ArenaCapacity =
        synchronized(lock) {
            val compatible = arenas.values.filter { it.supports(objective) }
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
        val loaded = parse(plugin)
        synchronized(lock) {
            check(reserved.isEmpty() && waiting.none { !it.future.isDone }) {
                "Arenas cannot be reloaded while matches or queue entries are active"
            }
            arenas = loaded
        }
        return loaded.size
    }

    private fun reservationFor(id: ArenaId): ArenaReservation = ArenaReservation(id) { release(id) }

    private fun release(id: ArenaId) {
        val assignments = mutableListOf<Pair<CompletableFuture<ArenaReservation>, ArenaReservation>>()
        synchronized(lock) {
            if (!reserved.remove(id)) return
            while (true) {
                val next = waiting.firstOrNull { pending ->
                    !pending.future.isDone && arenas.values.any { it.id !in reserved && it.supports(pending.rules.objective) }
                } ?: break
                waiting.remove(next)
                val arena = requireNotNull(arenas.values.firstOrNull { it.id !in reserved && it.supports(next.rules.objective) })
                reserved += arena.id
                assignments += next.future to reservationFor(arena.id)
            }
        }
        assignments.forEach { (future, reservation) ->
            if (!future.complete(reservation)) reservation.close()
        }
    }

    companion object {
        fun load(plugin: JavaPlugin): PaperArenaCatalog = PaperArenaCatalog(parse(plugin))

        private fun parse(plugin: JavaPlugin): Map<ArenaId, PaperArena> {
            val root = plugin.config.getConfigurationSection("arenas")
                ?: return emptyMap()
            val entries =
                root.getKeys(false).mapNotNull { rawId ->
                    val section = root.getConfigurationSection(rawId) ?: return@mapNotNull null
                    if (!section.getBoolean("enabled", false)) return@mapNotNull null
                    val id = ArenaId(rawId.lowercase())
                    val first = section.readLocation(plugin, "first-spawn")
                    val second = section.readLocation(plugin, "second-spawn")
                    val firstWorld = requireNotNull(first.world)
                    require(second.world?.uid == firstWorld.uid) { "Arena $id spawns must be in the same world" }
                    val bounds = section.readBounds(firstWorld.uid)
                    require(bounds.contains(first) && bounds.contains(second)) { "Arena $id spawns must be inside its bounds" }
                    val hill = section.getConfigurationSection("hill")?.readHill(plugin)
                    require(hill == null || bounds.contains(hill)) { "Arena $id hill zone must be fully inside its bounds" }
                    id to PaperArena(id, first, second, bounds, hill)
                }
            require(entries.map(Pair<ArenaId, PaperArena>::first).distinct().size == entries.size) {
                "Arena ids must be unique after lowercase normalization"
            }
            entries.forEachIndexed { index, (id, arena) ->
                entries.drop(index + 1).forEach { (otherId, otherArena) ->
                    require(!arena.bounds.overlaps(otherArena.bounds)) {
                        "Arena $id bounds overlap arena $otherId"
                    }
                }
            }
            return entries.toMap()
        }

        private fun ConfigurationSection.readBounds(worldId: UUID): ArenaBounds {
            val section = getConfigurationSection("bounds") ?: error("Missing arena bounds $currentPath.bounds")
            val minimum = section.getConfigurationSection("min") ?: error("Missing arena bounds $currentPath.bounds.min")
            val maximum = section.getConfigurationSection("max") ?: error("Missing arena bounds $currentPath.bounds.max")
            minimum.requireCoordinates()
            maximum.requireCoordinates()
            return ArenaBounds(
                worldId = worldId,
                minX = minimum.getDouble("x"),
                minY = minimum.getDouble("y"),
                minZ = minimum.getDouble("z"),
                maxX = maximum.getDouble("x"),
                maxY = maximum.getDouble("y"),
                maxZ = maximum.getDouble("z"),
            )
        }

        private fun ConfigurationSection.readLocation(
            plugin: JavaPlugin,
            path: String,
        ): Location {
            val section = getConfigurationSection(path) ?: error("Missing arena location $currentPath.$path")
            val worldName = section.getString("world") ?: error("Missing world for $currentPath.$path")
            val world = plugin.server.getWorld(worldName) ?: error("Arena world '$worldName' is not loaded")
            section.requireCoordinates()
            val location = Location(
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                section.getDouble("yaw").toFloat(),
                section.getDouble("pitch").toFloat(),
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
                radius = getDouble("radius", 3.5),
                height = getDouble("height", 3.0),
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
        val future: CompletableFuture<ArenaReservation>,
    )
}
