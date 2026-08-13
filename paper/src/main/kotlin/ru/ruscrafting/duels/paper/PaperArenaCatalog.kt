package ru.ruscrafting.duels.paper

import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ArenaAllocator
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaReservation
import ru.ruscrafting.duels.domain.DuelRules
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
}

data class PaperArena(
    val id: ArenaId,
    val firstSpawn: Location,
    val secondSpawn: Location,
    val bounds: ArenaBounds,
)

class PaperArenaCatalog private constructor(
    initialArenas: Map<ArenaId, PaperArena>,
) : ArenaAllocator {
    private val lock = Any()
    @Volatile
    private var arenas: Map<ArenaId, PaperArena> = initialArenas
    private val reserved = mutableSetOf<ArenaId>()
    private val waiting = ArrayDeque<CompletableFuture<ArenaReservation>>()

    override fun reserve(rules: DuelRules): CompletableFuture<ArenaReservation> {
        val future = CompletableFuture<ArenaReservation>()
        synchronized(lock) {
            if (arenas.isEmpty()) return CompletableFuture.failedFuture(IllegalStateException("Нет настроенных арен для дуэли"))
            val arena = arenas.values.firstOrNull { it.id !in reserved }
            if (arena == null) {
                waiting.addLast(future)
            } else {
                reserved += arena.id
                future.complete(reservationFor(arena.id))
            }
        }
        future.whenComplete { _, _ ->
            if (future.isCancelled) synchronized(lock) { waiting.remove(future) }
        }
        return future
    }

    fun get(id: ArenaId): PaperArena = arenas[id] ?: error("Arena $id disappeared from the catalog")

    fun size(): Int = arenas.size

    fun queueSize(): Int = synchronized(lock) { waiting.count { !it.isDone } }

    fun reservedCount(): Int = synchronized(lock) { reserved.size }

    fun reload(plugin: JavaPlugin): Int {
        val loaded = parse(plugin)
        synchronized(lock) {
            check(reserved.isEmpty() && waiting.none { !it.isDone }) {
                "Нельзя перезагрузить арены, пока идут бои или есть очередь"
            }
            arenas = loaded
        }
        return loaded.size
    }

    private fun reservationFor(id: ArenaId): ArenaReservation = ArenaReservation(id) { release(id) }

    private fun release(id: ArenaId) {
        var assignment: Pair<CompletableFuture<ArenaReservation>, ArenaReservation>? = null
        synchronized(lock) {
            if (!reserved.remove(id)) return
            while (waiting.isNotEmpty()) {
                val next = waiting.removeFirst()
                if (next.isDone) continue
                val arena = arenas.values.firstOrNull { it.id !in reserved } ?: run {
                    waiting.addFirst(next)
                    break
                }
                reserved += arena.id
                assignment = next to reservationFor(arena.id)
                break
            }
        }
        assignment?.let { (future, reservation) ->
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
                    id to PaperArena(id, first, second, bounds)
                }
            require(entries.map(Pair<ArenaId, PaperArena>::first).distinct().size == entries.size) {
                "Arena ids must be unique after lowercase normalization"
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

        private fun ConfigurationSection.requireCoordinates() {
            require(contains("x") && contains("y") && contains("z")) {
                "Missing x, y, or z coordinate in $currentPath"
            }
        }
    }
}
