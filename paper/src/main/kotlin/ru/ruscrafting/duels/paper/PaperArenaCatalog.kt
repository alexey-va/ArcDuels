package ru.ruscrafting.duels.paper

import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ArenaAllocator
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.ArenaReservation
import ru.ruscrafting.duels.domain.DuelRules
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class PaperArena(
    val id: ArenaId,
    val firstSpawn: Location,
    val secondSpawn: Location,
)

class PaperArenaCatalog private constructor(
    private val arenas: Map<ArenaId, PaperArena>,
) : ArenaAllocator {
    private val reserved = ConcurrentHashMap.newKeySet<ArenaId>()

    override fun reserve(rules: DuelRules): CompletableFuture<ArenaReservation> {
        val arena = arenas.values.firstOrNull { reserved.add(it.id) }
            ?: return CompletableFuture.failedFuture(IllegalStateException("Нет свободных арен для дуэли"))
        return CompletableFuture.completedFuture(ArenaReservation(arena.id) { reserved.remove(arena.id) })
    }

    fun get(id: ArenaId): PaperArena = arenas[id] ?: error("Arena $id disappeared from the catalog")

    fun size(): Int = arenas.size

    companion object {
        fun load(plugin: JavaPlugin): PaperArenaCatalog {
            val root = plugin.config.getConfigurationSection("arenas")
                ?: return PaperArenaCatalog(emptyMap())
            val loaded =
                root.getKeys(false).mapNotNull { rawId ->
                    val section = root.getConfigurationSection(rawId) ?: return@mapNotNull null
                    if (!section.getBoolean("enabled", false)) return@mapNotNull null
                    val id = ArenaId(rawId.lowercase())
                    val first = section.readLocation(plugin, "first-spawn")
                    val second = section.readLocation(plugin, "second-spawn")
                    id to PaperArena(id, first, second)
                }.toMap()
            return PaperArenaCatalog(loaded)
        }

        private fun ConfigurationSection.readLocation(
            plugin: JavaPlugin,
            path: String,
        ): Location {
            val section = getConfigurationSection(path) ?: error("Missing arena location $currentPath.$path")
            val worldName = section.getString("world") ?: error("Missing world for $currentPath.$path")
            val world = plugin.server.getWorld(worldName) ?: error("Arena world '$worldName' is not loaded")
            return Location(
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                section.getDouble("yaw").toFloat(),
                section.getDouble("pitch").toFloat(),
            )
        }
    }
}
