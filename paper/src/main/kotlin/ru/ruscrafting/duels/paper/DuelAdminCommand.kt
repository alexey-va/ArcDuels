package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ArenaId
import ru.ruscrafting.duels.domain.DuelObjectiveType
import ru.ruscrafting.duels.domain.ServerId
import java.util.Locale

internal class DuelAdminCommand(
    private val plugin: JavaPlugin,
    private val arenas: PaperArenaCatalog,
    private val sessions: DuelSessionManager,
    private val locales: LocaleService? = null,
    private val serverNames: ServerDisplayNames = ServerDisplayNames.load(plugin.config, plugin.logger::warning),
    private val hasReturnOffer: (Player) -> Boolean = { false },
) {
    private val miniMessage = MiniMessage.miniMessage()

    fun execute(
        sender: CommandSender,
        args: List<String>,
    ) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(message(sender, "admin.no-permission", LocaleService.text("permission", ADMIN_PERMISSION)))
            return
        }
        when (args.firstOrNull()?.lowercase()) {
            null, "help" -> help(sender)
            "status" ->
                sender.sendMessage(
                    message(
                        sender,
                        "admin.status",
                        LocaleService.component(
                            "server",
                            serverNames.display(ServerId(plugin.config.getString("server-id", plugin.server.name)!!)),
                        ),
                        LocaleService.text("arenas", arenas.size()),
                        LocaleService.text("active", sessions.activeArenaCount()),
                        LocaleService.text("waiting", sessions.queueSize()),
                        LocaleService.text("recoveries", sessions.pendingRecoveryCount()),
                    ),
                )
            "recover" -> recover(sender, args)
            "arena" -> arena(sender, args.drop(1))
            "debug" -> debug(sender, args.drop(1))
            else -> help(sender)
        }
    }

    fun complete(
        sender: CommandSender,
        args: List<String>,
    ): List<String> {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return emptyList()
        if (args.size <= 1) return filter(listOf("arena", "status", "recover", "debug", "help"), args.lastOrNull().orEmpty())
        if (args[0].equals("recover", true) && args.size == 2) {
            return filter(sender.server.onlinePlayers.map(Player::getName), args[1])
        }
        if (args[0].equals("debug", true)) {
            if (args.size == 2) return filter(listOf("server", "player", "arena"), args[1])
            if (args.size == 3 && args[1].equals("player", true)) {
                return filter(sender.server.onlinePlayers.map(Player::getName), args[2])
            }
            if (args.size == 3 && args[1].equals("arena", true)) return filter(arenaIds(), args[2])
            return emptyList()
        }
        if (!args[0].equals("arena", true)) return emptyList()
        if (args.size == 2) return filter(listOf("create", "setspawn", "setlobby", "setcorner", "sethill", "setloadouts", "setobjectives", "enable", "disable", "list", "info", "reload"), args[1])
        val operation = args[1].lowercase()
        if (args.size == 3 && operation != "create" && operation !in setOf("list", "reload")) {
            return filter(arenaIds(), args[2])
        }
        if (args.size == 4 && operation in setOf("setspawn", "setcorner")) return filter(listOf("1", "2"), args[3])
        if (args.size == 4 && operation == "setloadouts") return filter(listOf("all", "own", "kit"), args[3])
        if (args.size >= 4 && operation == "setobjectives") {
            return filter(listOf("all", "elimination", "koth", "sumo", "boxing", "combo"), args.last())
        }
        return emptyList()
    }

    private fun arena(
        sender: CommandSender,
        args: List<String>,
    ) {
        when (args.firstOrNull()?.lowercase()) {
            "create" -> {
                val player = requirePlayer(sender) ?: return
                val id = parseId(sender, args.getOrNull(1)) ?: return
                val path = "arenas.${id.value}"
                if (plugin.config.contains(path)) {
                    sender.sendMessage(message(sender, "admin.arena-exists", LocaleService.text("arena", id.value)))
                    return
                }
                plugin.config.set("$path.enabled", false)
                plugin.config.set("$path.$ARENA_LOADOUTS_PATH", ArenaLoadoutSelection.ALL.modes.map { it.name })
                plugin.config.set("$path.$ARENA_OBJECTIVES_PATH", DuelObjectiveType.entries.map { it.name })
                plugin.config.set("$path.bounds.min.x", player.location.x - DEFAULT_ARENA_HALF_SIZE)
                plugin.config.set("$path.bounds.min.y", player.world.minHeight.toDouble())
                plugin.config.set("$path.bounds.min.z", player.location.z - DEFAULT_ARENA_HALF_SIZE)
                plugin.config.set("$path.bounds.max.x", player.location.x + DEFAULT_ARENA_HALF_SIZE)
                plugin.config.set("$path.bounds.max.y", player.world.maxHeight.toDouble())
                plugin.config.set("$path.bounds.max.z", player.location.z + DEFAULT_ARENA_HALF_SIZE)
                plugin.saveConfig()
                sender.sendMessage(message(sender, "admin.arena-created", LocaleService.text("arena", id.value)))
                player.sendActionBar(message(player, "admin.arena-editing", LocaleService.text("arena", id.value)))
            }
            "setspawn" -> {
                val player = requirePlayer(sender) ?: return
                if (!requireIdle(sender)) return
                val id = existingId(sender, args.getOrNull(1)) ?: return
                val point = parsePoint(sender, args.getOrNull(2)) ?: return
                val path = "arenas.${id.value}"
                writeLocation("$path.${if (point == 1) "first-spawn" else "second-spawn"}", player.location)
                disableWhileEditing(path, id)
                sender.sendMessage(message(sender, "admin.spawn-saved", LocaleService.text("point", point), LocaleService.text("arena", id.value)))
            }
            "setlobby" -> {
                val player = requirePlayer(sender) ?: return
                if (!requireIdle(sender)) return
                val id = existingId(sender, args.getOrNull(1)) ?: return
                val path = "arenas.${id.value}"
                writeLocation("$path.lobby", player.location)
                disableWhileEditing(path, id)
                sender.sendMessage(message(sender, "admin.lobby-saved", LocaleService.text("arena", id.value)))
            }
            "setcorner" -> {
                val player = requirePlayer(sender) ?: return
                if (!requireIdle(sender)) return
                val id = existingId(sender, args.getOrNull(1)) ?: return
                val spawnWorld = plugin.config.getString("arenas.${id.value}.first-spawn.world")
                if (spawnWorld != null && player.world.name != spawnWorld) {
                    sender.sendMessage(message(sender, "admin.wrong-world", LocaleService.text("world", spawnWorld)))
                    return
                }
                val point = parsePoint(sender, args.getOrNull(2)) ?: return
                val path = "arenas.${id.value}"
                val corner = if (point == 1) "min" else "max"
                plugin.config.set("$path.bounds.$corner.x", player.location.x)
                plugin.config.set("$path.bounds.$corner.y", player.location.y)
                plugin.config.set("$path.bounds.$corner.z", player.location.z)
                disableWhileEditing(path, id)
                sender.sendMessage(message(sender, "admin.corner-saved", LocaleService.text("point", point), LocaleService.text("arena", id.value)))
            }
            "sethill" -> {
                val player = requirePlayer(sender) ?: return
                if (!requireIdle(sender)) return
                val id = existingId(sender, args.getOrNull(1)) ?: return
                val radius = args.getOrNull(2)?.toDoubleOrNull() ?: 3.5
                val height = args.getOrNull(3)?.toDoubleOrNull() ?: 3.0
                if (radius !in 1.0..32.0 || height !in 1.0..32.0) {
                    sender.sendMessage(message(sender, "admin.hill-range"))
                    return
                }
                val path = "arenas.${id.value}"
                writeLocation("$path.hill.center", player.location)
                plugin.config.set("$path.hill.radius", radius)
                plugin.config.set("$path.hill.height", height)
                disableWhileEditing(path, id)
                sender.sendMessage(message(sender, "admin.hill-saved", LocaleService.text("arena", id.value), LocaleService.text("radius", radius), LocaleService.text("height", height)))
            }
            "setloadouts" -> setLoadouts(sender, args.getOrNull(1), args.getOrNull(2))
            "setobjectives" -> setObjectives(sender, args.getOrNull(1), args.drop(2))
            "enable" -> setEnabled(sender, args.getOrNull(1), true)
            "disable" -> setEnabled(sender, args.getOrNull(1), false)
            "list" -> list(sender)
            "info" -> info(sender, args.getOrNull(1))
            "reload" -> reload(sender)
            else -> help(sender)
        }
    }

    private fun setLoadouts(
        sender: CommandSender,
        rawId: String?,
        rawSelection: String?,
    ) {
        if (!requireIdle(sender)) return
        val id = existingId(sender, rawId) ?: return
        val selection = rawSelection?.let(ArenaLoadoutSelection::parse)
        if (selection == null) {
            sender.sendMessage(message(sender, "admin.loadouts-invalid"))
            return
        }
        val path = "arenas.${id.value}"
        plugin.config.set("$path.$ARENA_LOADOUTS_PATH", selection.modes.map { it.name })
        plugin.saveConfig()
        if (plugin.config.getBoolean("$path.enabled")) arenas.reload(plugin)
        sender.sendMessage(
            message(
                sender,
                "admin.loadouts-saved",
                LocaleService.text("arena", id.value),
                LocaleService.component("loadout", message(sender, "admin.loadouts-${selection.name.lowercase()}")),
            ),
        )
    }

    private fun setObjectives(
        sender: CommandSender,
        rawId: String?,
        rawObjectives: List<String>,
    ) {
        if (!requireIdle(sender)) return
        val id = existingId(sender, rawId) ?: return
        val allObjectives = rawObjectives.size == 1 && rawObjectives.single().equals("all", true)
        val objectives =
            if (allObjectives) {
                DuelObjectiveType.entries.toSet()
            } else {
                rawObjectives.mapNotNull(::parseObjective).toSet()
            }
        if (rawObjectives.isEmpty() || objectives.isEmpty() || (!allObjectives && objectives.size != rawObjectives.size)) {
            sender.sendMessage(message(sender, "admin.objectives-invalid"))
            return
        }
        val path = "arenas.${id.value}"
        plugin.config.set("$path.$ARENA_OBJECTIVES_PATH", objectives.map { it.name })
        plugin.saveConfig()
        if (plugin.config.getBoolean("$path.enabled")) arenas.reload(plugin)
        sender.sendMessage(message(sender, "admin.objectives-saved", LocaleService.text("arena", id.value)))
    }

    private fun setEnabled(
        sender: CommandSender,
        rawId: String?,
        enabled: Boolean,
    ) {
        if (!requireIdle(sender)) return
        val id = existingId(sender, rawId) ?: return
        val path = "arenas.${id.value}"
        if (!enabled) {
            plugin.config.set("$path.enabled", false)
            plugin.saveConfig()
            val count = arenas.disable(id)
            sender.sendMessage(message(sender, "admin.arena-disabled", LocaleService.text("arena", id.value), LocaleService.text("count", count)))
            return
        }
        normalizeBounds(path)
        plugin.config.set("$path.enabled", true)
        plugin.saveConfig()
        val loaded = runCatching { arenas.reload(plugin) }
        if (loaded.isFailure) {
            plugin.config.set("$path.enabled", false)
            plugin.saveConfig()
            arenas.disable(id)
            sender.sendMessage(message(sender, "admin.arena-invalid", LocaleService.text("reason", loaded.exceptionOrNull()?.message ?: "unknown")))
            return
        }
        sender.sendMessage(message(sender, "admin.arena-enabled", LocaleService.text("arena", id.value), LocaleService.text("count", loaded.getOrThrow())))
    }

    private fun reload(sender: CommandSender) {
        if (!requireIdle(sender)) return
        val result = runCatching {
            plugin.reloadConfig()
            arenas.reload(plugin)
        }
        result.onSuccess { sender.sendMessage(message(sender, "admin.reloaded", LocaleService.text("count", it))) }
            .onFailure { sender.sendMessage(message(sender, "admin.reload-failed", LocaleService.text("reason", it.message ?: "unknown"))) }
    }

    private fun list(sender: CommandSender) {
        val ids = arenaIds()
        if (ids.isEmpty()) {
            sender.sendMessage(message(sender, "admin.no-arenas"))
            return
        }
        sender.sendMessage(message(sender, "admin.arena-list"))
        ids.forEach { id ->
            val enabled = plugin.config.getBoolean("arenas.$id.enabled", false)
            sender.sendMessage(message(sender, "admin.arena-list-entry", LocaleService.text("arena", id), LocaleService.component("state", message(sender, if (enabled) "admin.state-enabled" else "admin.state-disabled"))))
        }
    }

    private fun info(
        sender: CommandSender,
        rawId: String?,
    ) {
        val id = existingId(sender, rawId) ?: return
        val path = "arenas.${id.value}"
        val first = plugin.config.getConfigurationSection("$path.first-spawn")
        val second = plugin.config.getConfigurationSection("$path.second-spawn")
        val minimum = plugin.config.getConfigurationSection("$path.bounds.min")
        val maximum = plugin.config.getConfigurationSection("$path.bounds.max")
        val hill = plugin.config.getConfigurationSection("$path.hill.center")
        val lobby = plugin.config.getConfigurationSection("$path.lobby")
        val stateKey = if (plugin.config.getBoolean("$path.enabled")) "admin.state-enabled" else "admin.state-disabled"
        sender.sendMessage(message(sender, "admin.arena-info", LocaleService.text("arena", id.value), LocaleService.component("state", message(sender, stateKey)), LocaleService.text("spawn1", first != null), LocaleService.text("spawn2", second != null), LocaleService.text("corner1", minimum != null), LocaleService.text("corner2", maximum != null), LocaleService.text("hill", hill != null), LocaleService.text("lobby", lobby != null)))
    }

    private fun recover(
        sender: CommandSender,
        args: List<String>,
    ) {
        val name = args.getOrNull(1)
        if (name == null) {
            sender.sendMessage(message(sender, "admin.recover-usage"))
            return
        }
        val player = sender.server.getPlayerExact(name)
        if (player == null) {
            sender.sendMessage(message(sender, "admin.player-offline"))
            return
        }
        sessions.adminRecover(player).whenComplete { result, failure ->
            runSync {
                if (failure != null) {
                    sender.sendMessage(message(sender, "admin.recovery-failed", LocaleService.text("player", player.name)))
                    return@runSync
                }
                val key =
                    when (requireNotNull(result).status) {
                        AdminRecoveryStatus.STARTED -> "admin.recovery-started"
                        AdminRecoveryStatus.TRANSFERRED -> "admin.recovery-transferred"
                        AdminRecoveryStatus.REPLAYED -> "admin.recovery-replayed"
                        AdminRecoveryStatus.NO_SNAPSHOT -> "admin.no-snapshot"
                        AdminRecoveryStatus.BUSY -> "admin.recovery-busy"
                        AdminRecoveryStatus.WRONG_SERVER -> "admin.recovery-wrong-server"
                    }
                sender.sendMessage(
                    message(
                        sender,
                        key,
                        LocaleService.text("player", player.name),
                        result.server?.let { LocaleService.component("server", serverNames.display(it)) }
                            ?: LocaleService.text("server", "—"),
                    ),
                )
            }
        }
    }

    private fun debug(
        sender: CommandSender,
        args: List<String>,
    ) {
        when (args.firstOrNull()?.lowercase()) {
            "server" -> debugServer(sender)
            "player" -> debugPlayer(sender, args.getOrNull(1))
            "arena" -> debugArena(sender, args.getOrNull(1))
            else -> sender.sendDebug("error", "code" to "usage", "expected" to "server|player_[name]|arena_<id>")
        }
    }

    private fun debugServer(sender: CommandSender) {
        sender.sendDebug(
            "server",
            "version" to plugin.pluginMeta.version,
            "server" to plugin.config.getString("server-id", plugin.server.name),
            "arenas" to arenas.size(),
            "active" to sessions.activeArenaCount(),
            "queue" to sessions.queueSize(),
            "recoveries" to sessions.pendingRecoveryCount(),
        )
    }

    private fun debugPlayer(
        sender: CommandSender,
        rawName: String?,
    ) {
        val player = rawName?.let(sender.server::getPlayerExact) ?: (sender as? Player).takeIf { rawName == null }
        if (player == null) {
            sender.sendDebug("error", "code" to if (rawName == null) "player_required" else "player_offline")
            return
        }
        val match = sessions.matchFor(player)
        sender.sendDebug(
            "player",
            "name" to player.name,
            "uuid" to player.uniqueId,
            "preparing" to sessions.isPreparing(player),
            "locked" to sessions.isStateLocked(player),
            "post_match" to sessions.isPostMatchWaiting(player),
            "pending_recovery" to sessions.hasPendingRecovery(player),
            "return_offer" to hasReturnOffer(player),
        )
        if (match == null) {
            sender.sendDebug("match", "player" to player.name, "match" to "none")
        } else {
            sender.sendDebug(
                "match",
                "player" to player.name,
                "match" to match.id.value,
                "arena" to match.arenaId.value,
                "state" to match.state,
                "mode" to match.rules.mode,
                "objective" to match.rules.objective,
                "score" to "${match.score.first}:${match.score.second}",
                "best_of" to match.rules.bestOf,
                "modified_blocks" to sessions.modifiedBlockCount(player),
            )
        }
        val maximumHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value
        sender.sendDebug(
            "health",
            "player" to player.name,
            "health" to formatDecimal(player.health),
            "max" to formatDecimal(maximumHealth),
            "absorption" to formatDecimal(player.absorptionAmount),
            "kit_cap" to sessions.isKitHealthCapApplied(player),
        )
        val distance = sessions.boundaryDistance(player, player.location)
        sender.sendDebug(
            "position",
            "player" to player.name,
            "world" to player.world.name,
            "x" to formatDecimal(player.location.x),
            "y" to formatDecimal(player.location.y),
            "z" to formatDecimal(player.location.z),
            "in_bounds" to (match == null || sessions.isInsideArena(player, player.location)),
            "boundary_distance" to (distance?.let(::formatDecimal) ?: if (match == null) "n/a" else "outside"),
        )
    }

    private fun debugArena(
        sender: CommandSender,
        rawId: String?,
    ) {
        val id = rawId?.let { runCatching { ArenaId(it.lowercase()) }.getOrNull() }
        if (id == null) {
            sender.sendDebug("error", "code" to "arena_id_invalid")
            return
        }
        val path = "arenas.${id.value}"
        if (!plugin.config.contains(path)) {
            sender.sendDebug("error", "code" to "arena_not_found", "arena" to id.value)
            return
        }
        val arena = runCatching { arenas.get(id) }.getOrNull()
        val configuredAction = plugin.config.getString("$path.post-match-action") ?: "INHERIT"
        sender.sendDebug(
            "arena",
            "id" to id.value,
            "enabled" to plugin.config.getBoolean("$path.enabled", false),
            "loaded" to (arena != null),
            "reserved" to arenas.isReserved(id),
            "post_match" to (arena?.postMatchAction?.name ?: configuredAction),
            "lobby" to (arena?.lobby != null || plugin.config.isConfigurationSection("$path.lobby")),
        )
        if (arena == null) return
        sender.sendDebug(
            "arena_rules",
            "id" to id.value,
            "loadouts" to arena.allowedLoadouts.map(Enum<*>::name).sorted().joinToString(","),
            "objectives" to arena.allowedObjectives.map(Enum<*>::name).sorted().joinToString(","),
        )
        sender.sendDebug(
            "arena_bounds",
            "id" to id.value,
            "world" to arena.firstSpawn.world?.name,
            "min" to "${formatDecimal(arena.bounds.minX)},${formatDecimal(arena.bounds.minY)},${formatDecimal(arena.bounds.minZ)}",
            "max" to "${formatDecimal(arena.bounds.maxX)},${formatDecimal(arena.bounds.maxY)},${formatDecimal(arena.bounds.maxZ)}",
            "warning_distance" to formatDecimal(plugin.config.getDouble("boundary-warning-distance", 12.0)),
        )
    }

    private fun CommandSender.sendDebug(
        kind: String,
        vararg fields: Pair<String, Any?>,
    ) {
        sendMessage(Component.text(DuelDebugFormatter.line(kind, fields.asList())))
    }

    private fun formatDecimal(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "n/a"

    private fun runSync(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (plugin.server.isPrimaryThread) block() else plugin.server.scheduler.runTask(plugin, Runnable(block))
    }

    private fun disableWhileEditing(
        path: String,
        id: ArenaId,
    ) {
        plugin.config.set("$path.enabled", false)
        plugin.saveConfig()
        arenas.disable(id)
    }

    private fun requireIdle(sender: CommandSender): Boolean {
        if (sessions.activeArenaCount() == 0 && sessions.queueSize() == 0) return true
        sender.sendMessage(message(sender, "admin.busy"))
        return false
    }

    private fun normalizeBounds(path: String) {
        val minimum = plugin.config.getConfigurationSection("$path.bounds.min") ?: return
        val maximum = plugin.config.getConfigurationSection("$path.bounds.max") ?: return
        for (axis in listOf("x", "y", "z")) {
            if (!minimum.contains(axis) || !maximum.contains(axis)) continue
            val first = minimum.getDouble(axis)
            val second = maximum.getDouble(axis)
            plugin.config.set("$path.bounds.min.$axis", minOf(first, second))
            plugin.config.set("$path.bounds.max.$axis", maxOf(first, second))
        }
    }

    private fun writeLocation(
        path: String,
        location: Location,
    ) {
        val world = requireNotNull(location.world)
        plugin.config.set("$path.world", world.name)
        plugin.config.set("$path.x", location.x)
        plugin.config.set("$path.y", location.y)
        plugin.config.set("$path.z", location.z)
        plugin.config.set("$path.yaw", location.yaw)
        plugin.config.set("$path.pitch", location.pitch)
    }

    private fun existingId(
        sender: CommandSender,
        raw: String?,
    ): ArenaId? {
        val id = parseId(sender, raw) ?: return null
        if (!plugin.config.contains("arenas.${id.value}")) {
            sender.sendMessage(message(sender, "admin.arena-not-found", LocaleService.text("arena", id.value)))
            return null
        }
        return id
    }

    private fun parseId(
        sender: CommandSender,
        raw: String?,
    ): ArenaId? {
        if (raw == null) {
            sender.sendMessage(message(sender, "admin.arena-id-required"))
            return null
        }
        return runCatching { ArenaId(raw.lowercase()) }
            .onFailure { sender.sendMessage(message(sender, "admin.arena-id-invalid", LocaleService.text("reason", it.message ?: "unknown"))) }
            .getOrNull()
    }

    private fun parsePoint(
        sender: CommandSender,
        raw: String?,
    ): Int? =
        raw?.toIntOrNull()?.takeIf { it in 1..2 } ?: run {
            sender.sendMessage(message(sender, "admin.point-invalid"))
            null
        }

    private fun requirePlayer(sender: CommandSender): Player? =
        (sender as? Player) ?: run {
            sender.sendMessage(message(sender, "admin.player-required"))
            null
        }

    private fun arenaIds(): List<String> =
        plugin.config.getConfigurationSection("arenas")?.getKeys(false)?.sorted() ?: emptyList()

    private fun help(sender: CommandSender) {
        if (locales == null) sender.sendMessage(miniMessage.deserialize("<aqua><bold>ArcDuels admin</bold></aqua>"))
        else locales.lines(sender, "admin.help").forEach(sender::sendMessage)
    }

    private fun message(sender: CommandSender, key: String, vararg resolvers: LocaleValue) =
        locales?.component(sender, key, *resolvers) ?: miniMessage.deserialize("<gray>[$key]</gray>")

    private fun filter(
        values: Collection<String>,
        prefix: String,
    ): List<String> = values.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()

    private companion object {
        const val DEFAULT_ARENA_HALF_SIZE = 100.0
        const val ADMIN_PERMISSION = "arcduels.admin"
    }
}

internal object DuelDebugFormatter {
    private val renderer = ru.arc.observability.StructuredDebugLine("ARCDUELS_DEBUG")

    fun line(
        kind: String,
        fields: List<Pair<String, Any?>>,
    ): String = renderer.line(listOf("kind" to kind) + fields)
}
