package ru.ruscrafting.duels.paper

import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.MultiplayerParticipant
import ru.ruscrafting.duels.domain.MultiplayerRoster
import ru.ruscrafting.duels.domain.MultiplayerRules
import ru.ruscrafting.duels.domain.defaultMultiplayerModifiers
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.redis.CrossServerGroupBus
import ru.ruscrafting.duels.redis.CrossServerGroupMessage
import ru.ruscrafting.duels.redis.GroupLobbyMessageType
import java.time.Clock
import java.util.UUID

/** Arena-side preparation; the invitation owner stays on its original server. */
internal class RemoteGroupArena(
    private val localServer: ServerId,
    private val bus: CrossServerGroupBus,
    private val sessions: MultiplayerSessionManager,
    private val duelSessions: DuelSessionManager,
    private val kits: KitRegistry,
    private val tasks: LifecycleTaskScope,
    private val player: (UUID) -> Player?,
    private val playerDataReady: (Player) -> Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private class Preparation(val message: CrossServerGroupMessage) {
        val ready = mutableSetOf<PlayerId>()
        var starting = false
    }
    private val pending = mutableMapOf<UUID, Preparation>()
    private val subscription = bus.subscribe { message -> tasks.runSync { receive(message) } }

    private fun receive(message: CrossServerGroupMessage) {
        if (message.arenaServer != localServer || message.hostServer == localServer) return
        when (message.type) {
            GroupLobbyMessageType.PREPARE -> {
                if (clock.millis() >= message.expiresAtEpochMillis || pending.containsKey(message.lobbyId)) return
                val roster = runCatching { roster(message) }.getOrNull()
                if (roster == null || !sessions.hasArenaCapacity(roster) || roster.participants.any { !kits.contains(it.kitId) }) {
                    report(message, GroupLobbyMessageType.START_FAILED)
                    return
                }
                val preparation = Preparation(message)
                pending[message.lobbyId] = preparation
                duelSessions.expectNetworkPlayers(message.participants.filter { it.originServer != localServer }.map { it.playerId })
                tick(preparation)
            }
            GroupLobbyMessageType.READY -> pending[message.lobbyId]?.takeIf { sameContract(it.message, message) }
                ?.ready?.add(requireNotNull(message.targetId))
            GroupLobbyMessageType.CANCEL -> pending[message.lobbyId]?.takeIf { sameContract(it.message, message) }
                ?.let { cancel(it) }
            else -> Unit
        }
    }

    private fun tick(preparation: Preparation) {
        val message = preparation.message
        if (pending[message.lobbyId] !== preparation || preparation.starting) return
        if (clock.millis() >= message.expiresAtEpochMillis) {
            cancel(preparation)
            report(message, GroupLobbyMessageType.START_FAILED)
            return
        }
        val online = message.participants.mapNotNull { member -> player(member.playerId.value)?.takeIf(Player::isOnline) }
        if (preparation.ready.size != message.participants.size || online.size != message.participants.size || !online.all(playerDataReady)) {
            tasks.runLater(1L) { tick(preparation) }
            return
        }
        preparation.starting = true
        val origins = message.participants.associate { it.playerId to it.originServer }
        runCatching {
            sessions.startNetwork(MatchId(message.lobbyId), roster(message), online.associateBy { PlayerId(it.uniqueId) }, origins) {
                pending[message.lobbyId] === preparation && clock.millis() < message.expiresAtEpochMillis
            }
        }.getOrElse { failure -> java.util.concurrent.CompletableFuture.failedFuture(failure) }
            .whenCompleteSync(tasks) { _, failure ->
                val current = pending.remove(message.lobbyId, preparation)
                duelSessions.stopExpectingNetworkPlayers(origins.keys)
                if (!current) {
                    if (!sessions.cancelStartingMatch(MatchId(message.lobbyId))) recover(message)
                    return@whenCompleteSync
                }
                if (failure == null) {
                    report(message, GroupLobbyMessageType.STARTED)
                } else {
                    DuelLog.warn("multiplayer-start-failed", MatchId(message.lobbyId), "arena_server={} error={}", localServer.value, failure.multiplayerRootCause().message)
                    recover(message)
                    report(message, GroupLobbyMessageType.START_FAILED)
                }
            }
    }

    private fun roster(message: CrossServerGroupMessage): MultiplayerRoster = MultiplayerRoster(
        MultiplayerRules(message.layout, message.kitPolicy, message.sharedKitId, modifiers = defaultMultiplayerModifiers(message.objective), objective = message.objective),
        message.participants.mapIndexed { index, participant -> MultiplayerParticipant(
            participant.playerId,
            message.layout.teamCount?.let { index % it + 1 },
            requireNotNull(message.participantKits[participant.playerId]),
        ) },
    )

    private fun cancel(preparation: Preparation) {
        if (!pending.remove(preparation.message.lobbyId, preparation)) return
        duelSessions.stopExpectingNetworkPlayers(preparation.message.participants.map { it.playerId })
        if (!sessions.cancelStartingMatch(MatchId(preparation.message.lobbyId))) recover(preparation.message)
    }

    private fun recover(message: CrossServerGroupMessage) {
        message.participants.forEach { member ->
            player(member.playerId.value)?.takeIf(Player::isOnline)?.let {
                if (!duelSessions.requestRecovery(it)) duelSessions.handleJoin(it)
            }
        }
    }

    private fun report(message: CrossServerGroupMessage, type: GroupLobbyMessageType) {
        runCatching { bus.publish(message.copy(
            messageId = "${message.lobbyId}:${type.name.lowercase()}:${localServer.value}",
            sourceServer = localServer,
            type = type,
            targetId = null,
            response = null,
            kitId = null,
        )) }.onFailure { failure ->
            DuelLog.warn("multiplayer-status-publish-failed", MatchId(message.lobbyId), "type={} error={}", type, failure.message)
        }
    }

    private fun sameContract(first: CrossServerGroupMessage, second: CrossServerGroupMessage): Boolean =
        first.lobbyId == second.lobbyId && first.hostId == second.hostId && first.hostServer == second.hostServer &&
            first.arenaServer == second.arenaServer && first.participants == second.participants &&
            first.layout == second.layout && first.kitPolicy == second.kitPolicy && first.sharedKitId == second.sharedKitId &&
            first.objective == second.objective && first.expiresAtEpochMillis == second.expiresAtEpochMillis

    override fun close() {
        subscription.close()
        pending.values.toList().forEach(::cancel)
    }
}
