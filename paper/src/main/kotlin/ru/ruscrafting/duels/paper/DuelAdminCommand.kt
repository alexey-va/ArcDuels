package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ArenaId

internal class DuelAdminCommand(
    private val plugin: JavaPlugin,
    private val arenas: PaperArenaCatalog,
    private val sessions: DuelSessionManager,
    private val locales: LocaleService? = null,
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
            "status" -> sender.sendMessage(message(sender, "admin.status", LocaleService.text("arenas", arenas.size()), LocaleService.text("active", sessions.activeArenaCount()), LocaleService.text("waiting", sessions.queueSize())))
            "recover" -> recover(sender, args)
            "arena" -> arena(sender, args.drop(1))
            else -> help(sender)
        }
    }

    fun complete(
        sender: CommandSender,
        args: List<String>,
    ): List<String> {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return emptyList()
        if (args.size <= 1) return filter(listOf("arena", "status", "recover", "help"), args.lastOrNull().orEmpty())
        if (args[0].equals("recover", true) && args.size == 2) {
            return filter(sender.server.onlinePlayers.map(Player::getName), args[1])
        }
        if (!args[0].equals("arena", true)) return emptyList()
        if (args.size == 2) return filter(listOf("create", "setspawn", "setcorner", "sethill", "enable", "disable", "list", "info", "reload"), args[1])
        val operation = args[1].lowercase()
        if (args.size == 3 && operation != "create" && operation !in setOf("list", "reload")) {
            return filter(arenaIds(), args[2])
        }
        if (args.size == 4 && operation in setOf("setspawn", "setcorner")) return filter(listOf("1", "2"), args[3])
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
            "enable" -> setEnabled(sender, args.getOrNull(1), true)
            "disable" -> setEnabled(sender, args.getOrNull(1), false)
            "list" -> list(sender)
            "info" -> info(sender, args.getOrNull(1))
            "reload" -> reload(sender)
            else -> help(sender)
        }
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
        val stateKey = if (plugin.config.getBoolean("$path.enabled")) "admin.state-enabled" else "admin.state-disabled"
        sender.sendMessage(message(sender, "admin.arena-info", LocaleService.text("arena", id.value), LocaleService.component("state", message(sender, stateKey)), LocaleService.text("spawn1", first != null), LocaleService.text("spawn2", second != null), LocaleService.text("corner1", minimum != null), LocaleService.text("corner2", maximum != null), LocaleService.text("hill", hill != null)))
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
        if (sessions.recover(player)) {
            sender.sendMessage(message(sender, "admin.recovery-started", LocaleService.text("player", player.name)))
        } else {
            sender.sendMessage(message(sender, "admin.no-snapshot", LocaleService.text("player", player.name)))
        }
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

    private fun message(sender: CommandSender, key: String, vararg resolvers: TagResolver) =
        locales?.component(sender, key, *resolvers) ?: miniMessage.deserialize("<gray>[$key]</gray>")

    private fun filter(
        values: Collection<String>,
        prefix: String,
    ): List<String> = values.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()

    private companion object {
        const val ADMIN_PERMISSION = "arcduels.admin"
    }
}
