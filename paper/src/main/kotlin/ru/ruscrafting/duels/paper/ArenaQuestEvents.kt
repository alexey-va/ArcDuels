package ru.ruscrafting.duels.paper

import org.bukkit.Bukkit
import ru.ruscrafting.duels.domain.MatchEndReason
import ru.ruscrafting.ranks.api.ClanArenaQuestApi
import ru.ruscrafting.ranks.api.ClanMembership
import ru.ruscrafting.ranks.api.ClanMembershipProvider
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Native-thread capture at setup and completion; publication follows durable result persistence. */
internal interface ArenaQuestSession {
    fun freeze(participantGroups: List<Set<UUID>>)
    fun publish(matchId: String, reason: MatchEndReason?, completedAt: Instant)
}

/** The always-loaded managers reference only our interface when the optional ArcRanks API is absent. */
internal fun captureArenaQuestSession(playerIds: Set<UUID>): ArenaQuestSession? {
    if (!Bukkit.getPluginManager().isPluginEnabled("ArcRanks")) return null
    val apiPresent = runCatching {
        Class.forName("ru.ruscrafting.ranks.api.ClanArenaQuestApi", false, ArenaQuestSession::class.java.classLoader)
    }.isSuccess
    if (!apiPresent) return null
    return runCatching { RanksArenaQuestSession.capture(playerIds) }.onFailure {
        Bukkit.getLogger().log(Level.WARNING, "[ArcDuels] Could not capture clan quest membership", it)
    }.getOrNull()
}

/** Isolated typed adapter, loaded only after checking the optional provider and its API class. */
internal class RanksArenaQuestSession(
    startMemberships: Map<UUID, ClanMembership>,
    private val membership: (UUID) -> ClanMembership?,
    private val record: (String, List<Map<UUID, ClanMembership>>, Instant) -> CompletableFuture<Int>,
    private val reportFailure: (Throwable) -> Unit = {},
) : ArenaQuestSession {
    private val start = java.util.Map.copyOf(startMemberships)
    private var frozen = emptyList<Map<UUID, ClanMembership>>()

    /** A series overwrites this at every round; only its eventual final round can publish. */
    override fun freeze(participantGroups: List<Set<UUID>>) {
        val current = participantGroups.flatten().distinct().associateWith { playerId ->
            runCatching { membership(playerId) }.onFailure(reportFailure).getOrNull()
        }
        frozen = java.util.List.copyOf(participantGroups.mapNotNull { group ->
            val participants = group.mapNotNull { playerId ->
                start[playerId]?.takeIf { it.clanId() == current[playerId]?.clanId() }?.let { playerId to it }
            }.toMap()
            participants.takeIf { it.size >= 2 }?.let { java.util.Map.copyOf(it) }
        })
    }

    override fun publish(matchId: String, reason: MatchEndReason?, completedAt: Instant) {
        if (reason !in setOf(MatchEndReason.ELIMINATION, MatchEndReason.OBJECTIVE) || frozen.isEmpty()) return
        runCatching { record(matchId, frozen, completedAt) }.onFailure(reportFailure).getOrNull()
            ?.whenComplete { _, failure -> if (failure != null) reportFailure(failure) }
    }

    companion object {
        fun capture(playerIds: Set<UUID>): ArenaQuestSession? {
            val provider = Bukkit.getServicesManager().load(ClanMembershipProvider::class.java) ?: return null
            val start = playerIds.mapNotNull { playerId -> provider.find(playerId)?.let { playerId to it } }.toMap()
            if (start.isEmpty()) return null
            return RanksArenaQuestSession(
                start,
                membership = { playerId -> Bukkit.getServicesManager().load(ClanMembershipProvider::class.java)?.find(playerId) },
                record = { matchId, groups, at ->
                    Bukkit.getServicesManager().load(ClanArenaQuestApi::class.java)?.record(matchId, groups, at)
                        ?: CompletableFuture.completedFuture(0)
                },
                reportFailure = { Bukkit.getLogger().log(Level.WARNING, "[ArcDuels] Could not record clan arena activity", it) },
            )
        }
    }
}
