package ru.ruscrafting.duels.paper

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.ruscrafting.duels.domain.ArenaId

internal class DuelAdminCommand(
    private val plugin: JavaPlugin,
    private val arenas: PaperArenaCatalog,
    private val sessions: DuelSessionManager,
) {
    private val miniMessage = MiniMessage.miniMessage()

    fun execute(
        sender: CommandSender,
        args: List<String>,
    ) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(message("<red>Нет права $ADMIN_PERMISSION.</red>"))
            return
        }
        when (args.firstOrNull()?.lowercase()) {
            null, "help" -> help(sender)
            "status" -> sender.sendMessage(
                message(
                    "<aqua>ArcDuels:</aqua> арен <white>${arenas.size()}</white>, занято <white>${sessions.activeArenaCount()}</white>, " +
                        "пар в очереди <white>${sessions.queueSize()}</white>.",
                ),
            )
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
        if (args.size == 2) return filter(listOf("create", "setspawn", "setcorner", "enable", "disable", "list", "info", "reload"), args[1])
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
                    sender.sendMessage(message("<red>Арена <white>${id.value}</white> уже существует.</red>"))
                    return
                }
                plugin.config.set("$path.enabled", false)
                plugin.saveConfig()
                sender.sendMessage(
                    message(
                        "<green>Арена <white>${id.value}</white> создана выключенной.</green> " +
                            "<gray>Теперь задай spawn 1/2 и corner 1/2.</gray>",
                    ),
                )
                player.sendActionBar(message("<yellow>Редактируется арена ${id.value}</yellow>"))
            }
            "setspawn" -> {
                val player = requirePlayer(sender) ?: return
                if (!requireIdle(sender)) return
                val id = existingId(sender, args.getOrNull(1)) ?: return
                val point = parsePoint(sender, args.getOrNull(2)) ?: return
                val path = "arenas.${id.value}"
                writeLocation("$path.${if (point == 1) "first-spawn" else "second-spawn"}", player.location)
                disableWhileEditing(path)
                sender.sendMessage(message("<green>Точка появления $point арены <white>${id.value}</white> сохранена.</green>"))
            }
            "setcorner" -> {
                val player = requirePlayer(sender) ?: return
                if (!requireIdle(sender)) return
                val id = existingId(sender, args.getOrNull(1)) ?: return
                val spawnWorld = plugin.config.getString("arenas.${id.value}.first-spawn.world")
                if (spawnWorld != null && player.world.name != spawnWorld) {
                    sender.sendMessage(message("<red>Границы нужно отмечать в мире первой точки появления: <white>$spawnWorld</white>.</red>"))
                    return
                }
                val point = parsePoint(sender, args.getOrNull(2)) ?: return
                val path = "arenas.${id.value}"
                val corner = if (point == 1) "min" else "max"
                plugin.config.set("$path.bounds.$corner.x", player.location.x)
                plugin.config.set("$path.bounds.$corner.y", player.location.y)
                plugin.config.set("$path.bounds.$corner.z", player.location.z)
                disableWhileEditing(path)
                sender.sendMessage(
                    message(
                        "<green>Угол $point арены <white>${id.value}</white> сохранён.</green> " +
                            "<gray>При включении координаты автоматически нормализуются.</gray>",
                    ),
                )
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
        if (enabled) normalizeBounds(path)
        plugin.config.set("$path.enabled", enabled)
        plugin.saveConfig()
        val loaded = runCatching { arenas.reload(plugin) }
        if (loaded.isFailure) {
            if (enabled) {
                plugin.config.set("$path.enabled", false)
                plugin.saveConfig()
            }
            sender.sendMessage(message("<red>Арена не применена: ${loaded.exceptionOrNull()?.message}</red>"))
            return
        }
        sender.sendMessage(
            message(
                if (enabled) {
                    "<green>Арена <white>${id.value}</white> проверена и включена. Активных арен: ${loaded.getOrThrow()}.</green>"
                } else {
                    "<yellow>Арена <white>${id.value}</white> выключена. Активных арен: ${loaded.getOrThrow()}.</yellow>"
                },
            ),
        )
    }

    private fun reload(sender: CommandSender) {
        if (!requireIdle(sender)) return
        val result = runCatching {
            plugin.reloadConfig()
            arenas.reload(plugin)
        }
        result.onSuccess { sender.sendMessage(message("<green>Конфигурация перечитана; активных арен: $it.</green>")) }
            .onFailure { sender.sendMessage(message("<red>Ошибка конфигурации арен: ${it.message}</red>")) }
    }

    private fun list(sender: CommandSender) {
        val ids = arenaIds()
        if (ids.isEmpty()) {
            sender.sendMessage(message("<gray>Арены ещё не созданы.</gray>"))
            return
        }
        sender.sendMessage(message("<aqua>Арены:</aqua>"))
        ids.forEach { id ->
            val enabled = plugin.config.getBoolean("arenas.$id.enabled", false)
            sender.sendMessage(message("<dark_gray>•</dark_gray> <white>$id</white> ${if (enabled) "<green>включена</green>" else "<gray>выключена</gray>"}"))
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
        sender.sendMessage(
            message(
                "<aqua>${id.value}</aqua>: ${if (plugin.config.getBoolean("$path.enabled")) "<green>включена</green>" else "<gray>выключена</gray>"}; " +
                    "spawn1=${first != null}, spawn2=${second != null}, corner1=${minimum != null}, corner2=${maximum != null}",
            ),
        )
    }

    private fun recover(
        sender: CommandSender,
        args: List<String>,
    ) {
        val name = args.getOrNull(1)
        if (name == null) {
            sender.sendMessage(message("<red>Использование: /duels admin recover <онлайн-игрок></red>"))
            return
        }
        val player = sender.server.getPlayerExact(name)
        if (player == null) {
            sender.sendMessage(message("<red>Игрок должен быть онлайн.</red>"))
            return
        }
        if (sessions.recover(player)) {
            sender.sendMessage(message("<green>Запущено безопасное восстановление ${player.name}.</green>"))
        } else {
            sender.sendMessage(message("<gray>У ${player.name} нет ожидающего снимка.</gray>"))
        }
    }

    private fun disableWhileEditing(path: String) {
        plugin.config.set("$path.enabled", false)
        plugin.saveConfig()
        if (sessions.activeArenaCount() == 0 && sessions.queueSize() == 0) runCatching { arenas.reload(plugin) }
    }

    private fun requireIdle(sender: CommandSender): Boolean {
        if (sessions.activeArenaCount() == 0 && sessions.queueSize() == 0) return true
        sender.sendMessage(message("<red>Изменение арен запрещено, пока идёт бой или есть очередь.</red>"))
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
            sender.sendMessage(message("<red>Арена <white>${id.value}</white> не найдена.</red>"))
            return null
        }
        return id
    }

    private fun parseId(
        sender: CommandSender,
        raw: String?,
    ): ArenaId? {
        if (raw == null) {
            sender.sendMessage(message("<red>Укажи id арены.</red>"))
            return null
        }
        return runCatching { ArenaId(raw.lowercase()) }
            .onFailure { sender.sendMessage(message("<red>Некорректный id арены: ${it.message}</red>")) }
            .getOrNull()
    }

    private fun parsePoint(
        sender: CommandSender,
        raw: String?,
    ): Int? =
        raw?.toIntOrNull()?.takeIf { it in 1..2 } ?: run {
            sender.sendMessage(message("<red>Номер точки должен быть 1 или 2.</red>"))
            null
        }

    private fun requirePlayer(sender: CommandSender): Player? =
        (sender as? Player) ?: run {
            sender.sendMessage(message("<red>Эта команда требует позицию игрока.</red>"))
            null
        }

    private fun arenaIds(): List<String> =
        plugin.config.getConfigurationSection("arenas")?.getKeys(false)?.sorted() ?: emptyList()

    private fun help(sender: CommandSender) {
        sender.sendMessage(
            message(
                """
                <aqua><bold>ArcDuels admin</bold></aqua>
                <white>/duels admin arena create <id></white>
                <white>/duels admin arena setspawn <id> <1|2></white>
                <white>/duels admin arena setcorner <id> <1|2></white>
                <white>/duels admin arena enable|disable <id></white>
                <white>/duels admin arena list|info <id>|reload</white>
                <white>/duels admin status</white>
                <white>/duels admin recover <онлайн-игрок></white>
                """.trimIndent(),
            ),
        )
    }

    private fun message(input: String) = miniMessage.deserialize(input)

    private fun filter(
        values: Collection<String>,
        prefix: String,
    ): List<String> = values.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()

    private companion object {
        const val ADMIN_PERMISSION = "arcduels.admin"
    }
}
