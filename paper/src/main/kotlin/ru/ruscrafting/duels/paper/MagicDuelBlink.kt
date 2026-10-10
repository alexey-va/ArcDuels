package ru.ruscrafting.duels.paper

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.configuration.Configuration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.staffspells.api.StaffSpellDuelContext
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

internal data class MagicDuelBlinkSettings(
    val distance: Double = 7.0,
    val rechargeSeconds: Long = 8L,
    val doubleShiftMillis: Long = 350L,
) {
    init {
        require(distance.isFinite() && distance in 1.0..16.0) {
            "magic-duel.blink.distance must be between 1 and 16"
        }
        require(rechargeSeconds in 1L..3_600L) {
            "magic-duel.blink.recharge-seconds must be between 1 and 3600"
        }
        require(doubleShiftMillis in 100L..1_000L) {
            "magic-duel.blink.double-shift-millis must be between 100 and 1000"
        }
    }

    companion object {
        fun parse(configuration: Configuration): MagicDuelBlinkSettings = MagicDuelBlinkSettings(
            distance = configuration.strictNumber("magic-duel.blink.distance", 7.0),
            rechargeSeconds = configuration.strictLong("magic-duel.blink.recharge-seconds", 8L),
            doubleShiftMillis = configuration.strictLong("magic-duel.blink.double-shift-millis", 350L),
        )
    }
}

internal data class MagicDuelBlinkRoundToken(val matchId: UUID, val round: Int)

internal enum class MagicDuelBlinkAttempt {
    TELEPORTED,
    FAILED,
    EMPTY,
}

/** Per-player, per-round blink input and charge state; time values use System.nanoTime semantics. */
internal class MagicDuelBlinkRoundState(
    val token: MagicDuelBlinkRoundToken,
    val settings: MagicDuelBlinkSettings,
) {
    var charges: Int = MAX_CHARGES
        private set
    private var lastSneakPressNanos: Long? = null
    private var nextChargeAtNanos: Long? = null
    private var feedbackUntilNanos: Long? = null

    fun doublePress(nowNanos: Long): Boolean {
        val previous = lastSneakPressNanos
        lastSneakPressNanos = null
        if (previous != null && nowNanos - previous in 0L..doublePressWindowNanos) return true
        lastSneakPressNanos = nowNanos
        return false
    }

    fun refreshCharges(nowNanos: Long) {
        if (charges >= MAX_CHARGES) return
        val dueAt = nextChargeAtNanos ?: return
        if (nowNanos - dueAt < 0L) return
        val elapsedIntervals = (nowNanos - dueAt) / rechargeNanos + 1L
        val restored = minOf(MAX_CHARGES - charges, elapsedIntervals.coerceAtMost(MAX_CHARGES.toLong()).toInt())
        charges += restored
        nextChargeAtNanos = if (charges < MAX_CHARGES) dueAt + restored * rechargeNanos else null
    }

    fun attemptTeleport(nowNanos: Long, teleport: () -> Boolean): MagicDuelBlinkAttempt {
        refreshCharges(nowNanos)
        if (charges == 0) return MagicDuelBlinkAttempt.EMPTY
        if (!teleport()) return MagicDuelBlinkAttempt.FAILED
        charges--
        if (charges < MAX_CHARGES && nextChargeAtNanos == null) nextChargeAtNanos = nowNanos + rechargeNanos
        return MagicDuelBlinkAttempt.TELEPORTED
    }

    fun nextRechargeSeconds(nowNanos: Long): Long? {
        refreshCharges(nowNanos)
        val dueAt = nextChargeAtNanos ?: return null
        val remainingNanos = (dueAt - nowNanos).coerceAtLeast(0L)
        return (remainingNanos + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND
    }

    fun pauseStatus(nowNanos: Long) {
        feedbackUntilNanos = nowNanos + STATUS_FEEDBACK_MILLIS * NANOS_PER_MILLI
    }

    fun mayShowStatus(nowNanos: Long): Boolean = feedbackUntilNanos?.let { nowNanos - it >= 0L } ?: true

    private val doublePressWindowNanos: Long
        get() = settings.doubleShiftMillis * NANOS_PER_MILLI
    private val rechargeNanos: Long
        get() = settings.rechargeSeconds * NANOS_PER_SECOND

    private companion object {
        const val MAX_CHARGES = 2
        const val STATUS_FEEDBACK_MILLIS = 2_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

internal class MagicDuelBlinkStateStore {
    private val byPlayer = mutableMapOf<UUID, MagicDuelBlinkRoundState>()

    fun stateFor(
        playerId: UUID,
        token: MagicDuelBlinkRoundToken,
        settings: () -> MagicDuelBlinkSettings,
    ): MagicDuelBlinkRoundState {
        val current = byPlayer[playerId]
        if (current?.token == token) return current
        return MagicDuelBlinkRoundState(token, settings()).also { byPlayer[playerId] = it }
    }

    fun entries(): List<Pair<UUID, MagicDuelBlinkRoundState>> = byPlayer.toList()

    fun remove(playerId: UUID) {
        byPlayer.remove(playerId)
    }

    fun clear() {
        byPlayer.clear()
    }
}

/** Double-sneak blink for active magic duel rounds. Bukkit access stays on the Paper thread. */
internal class MagicDuelBlink(
    tasks: LifecycleTaskScope,
    private val locales: LocaleService,
    private val settingsForNewRound: () -> MagicDuelBlinkSettings,
    private val activeContext: (Player) -> StaffSpellDuelContext?,
    private val isInsideArena: (Player, Location) -> Boolean,
    private val teleportBlink: (Player, Location) -> Boolean,
    private val nowNanos: () -> Long = System::nanoTime,
    private val localCollisionBoxes: (Block) -> Collection<BoundingBox> = { block ->
        block.blockData.getCollisionShape(block.location).boundingBoxes
    },
) : Listener, AutoCloseable {
    private val states = MagicDuelBlinkStateStore()
    private val teleporting = mutableSetOf<UUID>()
    private var closed = false
    private val statusTask: ScheduledTask? = tasks.runTimer(1L, 20L, ::refreshStatus)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSneakToggle(event: PlayerToggleSneakEvent) {
        if (closed || !event.isSneaking) return
        val player = event.player
        val playerId = player.uniqueId
        if (playerId in teleporting) return

        val context = activeContext(player)
        val token = context?.roundToken()
        if (token == null) {
            states.remove(playerId)
            return
        }

        val now = nowNanos()
        val state = states.stateFor(playerId, token, settingsForNewRound)
        state.refreshCharges(now)
        if (!state.doublePress(now)) {
            showStatus(player, state, now)
            return
        }

        if (state.charges == 0) {
            state.pauseStatus(now)
            player.sendActionBar(
                locales.component(
                    player,
                    "magic.blink.empty",
                    LocaleService.text("seconds", state.nextRechargeSeconds(now) ?: 0L),
                ),
            )
            return
        }

        val destination = safeDestination(player, state.settings.distance)
        if (destination == null) {
            showBlocked(player, state, now)
            return
        }
        if (activeContext(player)?.roundToken() != token || !isInsideArena(player, destination)) {
            states.remove(playerId)
            return
        }

        val outcome = state.attemptTeleport(now) {
            if (!teleporting.add(playerId)) {
                false
            } else {
                try {
                    if (!player.isOnline || activeContext(player)?.roundToken() != token || !isInsideArena(player, destination)) {
                        false
                    } else {
                        teleportBlink(player, destination) &&
                            activeContext(player)?.roundToken() == token &&
                            isExactDestination(player.location, destination)
                    }
                } finally {
                    teleporting.remove(playerId)
                }
            }
        }
        when (outcome) {
            MagicDuelBlinkAttempt.TELEPORTED -> showStatus(player, state, nowNanos())
            MagicDuelBlinkAttempt.FAILED -> showBlocked(player, state, nowNanos())
            MagicDuelBlinkAttempt.EMPTY -> {
                state.pauseStatus(nowNanos())
                player.sendActionBar(
                    locales.component(
                        player,
                        "magic.blink.empty",
                        LocaleService.text("seconds", state.nextRechargeSeconds(nowNanos()) ?: 0L),
                    ),
                )
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        states.remove(event.player.uniqueId)
        teleporting.remove(event.player.uniqueId)
    }

    override fun close() {
        if (closed) return
        closed = true
        statusTask?.cancel()
        states.clear()
        teleporting.clear()
    }

    private fun refreshStatus() {
        if (closed) return
        val now = nowNanos()
        val activePlayers = mutableSetOf<UUID>()
        Bukkit.getOnlinePlayers().forEach { player ->
            val context = activeContext(player)
            if (context == null) {
                states.remove(player.uniqueId)
                return@forEach
            }
            activePlayers += player.uniqueId
            val state = states.stateFor(player.uniqueId, context.roundToken(), settingsForNewRound)
            state.refreshCharges(now)
            if (state.mayShowStatus(now)) showStatus(player, state, now)
        }
        states.entries().forEach { (playerId, _) ->
            if (playerId !in activePlayers || Bukkit.getPlayer(playerId) == null) states.remove(playerId)
        }
    }

    private fun safeDestination(player: Player, distance: Double): Location? {
        val origin = player.location
        val world = origin.world ?: return null
        val direction = origin.direction.setY(0.0)
        if (direction.lengthSquared() < MIN_DIRECTION_LENGTH_SQUARED) return null
        direction.normalize()
        val originBox = player.boundingBox

        return MagicDuelBlinkPath.resolve(origin, direction, distance) { candidate ->
            if (!isInsideArena(player, candidate)) return@resolve false
            val candidateBox = originBox.clone().shift(
                candidate.x - origin.x,
                candidate.y - origin.y,
                candidate.z - origin.z,
            )
            if (!chunksLoaded(world, candidateBox)) return@resolve false
            if (!standable(world, candidate)) return@resolve false
            if (hasHazardOrBlockCollision(world, candidateBox)) return@resolve false
            world.getNearbyEntities(candidateBox).none { entity ->
                entity is Player && entity.uniqueId != player.uniqueId && entity.boundingBox.overlaps(candidateBox)
            }
        }
    }

    private fun showStatus(player: Player, state: MagicDuelBlinkRoundState, now: Long) {
        if (!state.mayShowStatus(now)) return
        val charges = buildString {
            repeat(2) { index -> append(if (index < state.charges) '●' else '○') }
        }
        val recharge = state.nextRechargeSeconds(now)
        val (key, values) = if (recharge == null) {
            "magic.blink.ready" to arrayOf(LocaleService.text("charges", charges))
        } else {
            "magic.blink.status" to arrayOf(
                LocaleService.text("charges", charges),
                LocaleService.text("recharge", recharge),
            )
        }
        player.sendActionBar(
            locales.component(player, key, *values),
        )
    }

    private fun showBlocked(player: Player, state: MagicDuelBlinkRoundState, now: Long) {
        state.pauseStatus(now)
        player.sendActionBar(locales.component(player, "magic.blink.blocked"))
    }

    private fun StaffSpellDuelContext.roundToken(): MagicDuelBlinkRoundToken =
        MagicDuelBlinkRoundToken(matchId(), round())

    private fun isExactDestination(actual: Location, expected: Location): Boolean {
        val world = actual.world ?: return false
        val expectedWorld = expected.world ?: return false
        return world.uid == expectedWorld.uid &&
            kotlin.math.abs(actual.x - expected.x) <= TELEPORT_COORDINATE_TOLERANCE &&
            kotlin.math.abs(actual.y - expected.y) <= TELEPORT_COORDINATE_TOLERANCE &&
            kotlin.math.abs(actual.z - expected.z) <= TELEPORT_COORDINATE_TOLERANCE &&
            kotlin.math.abs(actual.yaw - expected.yaw) <= TELEPORT_ANGLE_TOLERANCE &&
            kotlin.math.abs(actual.pitch - expected.pitch) <= TELEPORT_ANGLE_TOLERANCE
    }

    private fun chunksLoaded(world: org.bukkit.World, box: BoundingBox): Boolean {
        val minChunkX = floor(box.minX).toInt() shr 4
        val maxChunkX = floor(box.maxX - BOX_EDGE_EPSILON).toInt() shr 4
        val minChunkZ = floor(box.minZ).toInt() shr 4
        val maxChunkZ = floor(box.maxZ - BOX_EDGE_EPSILON).toInt() shr 4
        for (chunkX in minChunkX..maxChunkX) {
            for (chunkZ in minChunkZ..maxChunkZ) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) return false
            }
        }
        return true
    }

    private fun standable(world: org.bukkit.World, location: Location): Boolean {
        val x = location.blockX
        val z = location.blockZ
        val floorBlock = world.getBlockAt(x, floor(location.y - BOX_EDGE_EPSILON).toInt(), z)
        if (!floorBlock.type.isSolid || floorBlock.isLiquid || isHazard(floorBlock.type)) return false
        return MagicDuelBlinkBlockShapes.supports(location, worldCollisionBoxes(floorBlock), SUPPORT_HEIGHT_EPSILON)
    }

    private fun hasHazardOrBlockCollision(world: org.bukkit.World, box: BoundingBox): Boolean {
        val minX = floor(box.minX).toInt()
        val maxX = floor(box.maxX - BOX_EDGE_EPSILON).toInt()
        val minY = floor(box.minY).toInt()
        val maxY = floor(box.maxY - BOX_EDGE_EPSILON).toInt()
        val minZ = floor(box.minZ).toInt()
        val maxZ = floor(box.maxZ - BOX_EDGE_EPSILON).toInt()
        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val block = world.getBlockAt(x, y, z)
            if (block.isLiquid || isHazard(block.type)) return true
            if (MagicDuelBlinkBlockShapes.overlaps(box, worldCollisionBoxes(block))) return true
        }
        return false
    }

    private fun worldCollisionBoxes(block: Block): List<BoundingBox> =
        MagicDuelBlinkBlockShapes.toWorld(block.x, block.y, block.z, localCollisionBoxes(block))

    private fun isHazard(material: Material): Boolean = material in HAZARDS

    private companion object {
        const val MIN_DIRECTION_LENGTH_SQUARED = 1.0e-8
        const val TELEPORT_COORDINATE_TOLERANCE = 1.0e-7
        const val TELEPORT_ANGLE_TOLERANCE = 1.0e-4f
        const val BOX_EDGE_EPSILON = 1.0e-7
        const val SUPPORT_HEIGHT_EPSILON = 1.0e-4
        val HAZARDS = setOf(
            Material.CACTUS,
            Material.CAMPFIRE,
            Material.FIRE,
            Material.MAGMA_BLOCK,
            Material.POINTED_DRIPSTONE,
            Material.POWDER_SNOW,
            Material.SOUL_CAMPFIRE,
            Material.SOUL_FIRE,
            Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE,
        )
    }
}

/** Paper exposes block collision boxes in block-local coordinates; translate before world tests. */
internal object MagicDuelBlinkBlockShapes {
    fun toWorld(
        blockX: Int,
        blockY: Int,
        blockZ: Int,
        localBoxes: Collection<BoundingBox>,
    ): List<BoundingBox> =
        localBoxes.map { box ->
            box.clone().shift(blockX.toDouble(), blockY.toDouble(), blockZ.toDouble())
        }

    fun supports(location: Location, worldBoxes: Collection<BoundingBox>, heightTolerance: Double): Boolean =
        worldBoxes.any { shape ->
            kotlin.math.abs(shape.maxY - location.y) <= heightTolerance &&
                location.x >= shape.minX - SUPPORT_EDGE_EPSILON &&
                location.x <= shape.maxX + SUPPORT_EDGE_EPSILON &&
                location.z >= shape.minZ - SUPPORT_EDGE_EPSILON &&
                location.z <= shape.maxZ + SUPPORT_EDGE_EPSILON
        }

    fun overlaps(playerBox: BoundingBox, worldBoxes: Collection<BoundingBox>): Boolean =
        worldBoxes.any { it.overlaps(playerBox) }

    private const val SUPPORT_EDGE_EPSILON = 1.0e-7
}

/** Samples at most 64 player positions and stops at the first unsafe point. */
internal object MagicDuelBlinkPath {
    fun resolve(
        origin: Location,
        horizontalDirection: Vector,
        distance: Double,
        isSafe: (Location) -> Boolean,
    ): Location? {
        if (!distance.isFinite() || distance <= 0.0) return null
        val direction = horizontalDirection.clone().setY(0.0)
        if (direction.lengthSquared() < 1.0e-8) return null
        direction.normalize()
        val steps = ceil(distance / MAX_STEP).toInt().coerceIn(1, MAX_STEPS)
        var lastSafe: Location? = null
        for (step in 1..steps) {
            val traveled = distance * step / steps
            val candidate = origin.clone().add(direction.clone().multiply(traveled)).apply {
                y = origin.y
            }
            if (!isSafe(candidate)) break
            lastSafe = candidate
        }
        val result = lastSafe ?: return null
        return result.takeIf { hypot(it.x - origin.x, it.z - origin.z) >= MIN_USEFUL_MOVEMENT }
    }

    private const val MAX_STEP = 0.25
    private const val MAX_STEPS = 64
    private const val MIN_USEFUL_MOVEMENT = 1.0
}

