package ru.ruscrafting.duels.paper

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.staffspells.api.ArcStaffSpellService
import ru.arc.staffspells.api.StaffSpellDuelContext
import ru.arc.staffspells.api.StaffSpellDuelPolicy
import ru.ruscrafting.duels.domain.DuelMode
import ru.ruscrafting.duels.domain.MatchState
import ru.ruscrafting.duels.domain.MultiplayerMatchState
import ru.ruscrafting.duels.domain.PlayerId
import java.util.UUID

/** Typed, provider-owned boundary between ArcDuels matches and ARC StaffSpells. */
internal class MagicDuelCombatBridge(
    private val plugin: JavaPlugin,
    private val spells: ArcStaffSpellService,
    private val duels: DuelSessionManager,
    private val multiplayer: MultiplayerSessionManager,
    private val kits: KitRegistry,
) : StaffSpellDuelPolicy, AutoCloseable {
    private val duelResetRegistration = duels.attachMagicDuelStateReset(::resetPlayers)
    private val multiplayerResetRegistration = multiplayer.attachMagicDuelStateReset(::resetPlayers)
    private val policyRegistration = try {
        spells.registerDuelPolicy(plugin, this)
    } catch (failure: Throwable) {
        duelResetRegistration.close()
        multiplayerResetRegistration.close()
        throw failure
    }
    private var closed = false

    /** Returns a context only for a living participant using a magic kit in an active round. */
    fun activeContext(player: Player): StaffSpellDuelContext? {
        if (closed || !serviceAvailable() || !player.isOnline || player.isDead || player.health <= 0.0) return null
        val duel = duels.matchFor(player)
        if (duel != null) {
            if (duel.state != MatchState.ACTIVE || duel.rules.mode != DuelMode.KIT) return null
            val kit = duel.rules.kitId?.let(kits::get) ?: return null
            if (!kit.magic) return null
            val round = duels.magicDuelRound(player) ?: return null
            return StaffSpellDuelContext(duel.id.value, round, kit.magicDamageMultiplier)
        }

        val match = multiplayer.matchFor(player) ?: return null
        if (match.state != MultiplayerMatchState.ACTIVE) return null
        val playerId = PlayerId(player.uniqueId)
        if (playerId !in match.activePlayers) return null
        val kit = kits.get(match.roster.participant(playerId).kitId)
        if (!kit.magic || multiplayer.magicDuelRound(player) == null) return null
        return StaffSpellDuelContext(match.id.value, 1, kit.magicDamageMultiplier)
    }

    override fun context(caster: Player): StaffSpellDuelContext? = activeContext(caster)

    override fun canHit(
        caster: Player,
        target: Player,
        context: StaffSpellDuelContext,
    ): Boolean {
        if (caster.uniqueId == target.uniqueId ||
            !caster.isOnline ||
            !target.isOnline ||
            caster.isDead ||
            target.isDead ||
            caster.health <= 0.0 ||
            target.health <= 0.0
        ) return false
        if (activeContext(caster) != context) return false

        val duel = duels.matchFor(caster)
        if (duel != null) {
            if (duel.state != MatchState.ACTIVE || duels.matchFor(target)?.id != duel.id) return false
            return (duel.firstPlayer.value == caster.uniqueId && duel.secondPlayer.value == target.uniqueId) ||
                (duel.secondPlayer.value == caster.uniqueId && duel.firstPlayer.value == target.uniqueId)
        }

        val match = multiplayer.matchFor(caster) ?: return false
        if (match.state != MultiplayerMatchState.ACTIVE || multiplayer.matchFor(target)?.id != match.id) return false
        val casterId = PlayerId(caster.uniqueId)
        val targetId = PlayerId(target.uniqueId)
        return casterId in match.activePlayers &&
            targetId in match.activePlayers &&
            match.isEnemy(casterId, targetId)
    }

    fun isInsideArena(player: Player, destination: Location): Boolean {
        if (activeContext(player) == null || destination.world == null) return false
        return if (duels.matchFor(player) != null) {
            duels.isInsideArena(player, destination)
        } else {
            multiplayer.isInsideArena(player, destination)
        }
    }

    /** Synchronous so the current round token is checked at the actual teleport boundary. */
    fun teleportBlink(player: Player, destination: Location): Boolean {
        val context = activeContext(player) ?: return false
        val world = destination.world ?: return false
        if (!world.isChunkLoaded(destination.blockX shr 4, destination.blockZ shr 4)) return false
        if (!isInsideArena(player, destination) || activeContext(player) != context) return false
        val teleported = if (duels.matchFor(player) != null) {
            duels.teleportMagicBlink(player, destination)
        } else {
            multiplayer.teleportMagicBlink(player, destination)
        }
        return teleported && activeContext(player) == context
    }

    private fun resetPlayers(playerIds: Collection<UUID>) {
        playerIds.forEach { playerId ->
            runCatching { spells.resetPlayer(playerId) }
                .onFailure { plugin.logger.warning("Could not reset ARC StaffSpells state for $playerId: ${it.message}") }
        }
    }

    private fun serviceAvailable(): Boolean =
        try {
            spells.isAvailable()
        } catch (_: LinkageError) {
            false
        }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { policyRegistration.close() }
            .onFailure { plugin.logger.warning("Could not unregister ARC StaffSpells duel policy: ${it.message}") }
        duelResetRegistration.close()
        multiplayerResetRegistration.close()
    }

    companion object {
        /** Call only from the guarded optional-provider boundary in bootstrap or kit loading. */
        fun provider(plugin: JavaPlugin): ArcStaffSpellService? {
            if (!plugin.server.pluginManager.isPluginEnabled("ARC")) return null
            return try {
                availableProvider(plugin.server.servicesManager.load(ArcStaffSpellService::class.java))
            } catch (_: LinkageError) {
                null
            }
        }

        internal fun availableProvider(service: ArcStaffSpellService?): ArcStaffSpellService? =
            try {
                service?.takeIf { it.isAvailable() }
            } catch (_: LinkageError) {
                null
            }
    }
}
