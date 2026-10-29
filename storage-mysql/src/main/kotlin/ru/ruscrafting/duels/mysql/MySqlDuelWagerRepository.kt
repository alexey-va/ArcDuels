package ru.ruscrafting.duels.mysql

import ru.arc.sql.SqlRuntime
import ru.ruscrafting.duels.domain.DuelWager
import ru.ruscrafting.duels.domain.DuelWagerRepository
import ru.ruscrafting.duels.domain.MatchId
import ru.ruscrafting.duels.domain.PlayerId
import ru.ruscrafting.duels.domain.ServerId
import ru.ruscrafting.duels.domain.WagerLeg
import ru.ruscrafting.duels.domain.WagerLegKind
import ru.ruscrafting.duels.domain.WagerLegStatus
import ru.ruscrafting.duels.domain.WagerPhase
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.CompletableFuture

/** Borrows the statistics runtime. Its owner closes SQL after DuelWagerService.closeAsync. */
class MySqlDuelWagerRepository(private val runtime: SqlRuntime) : DuelWagerRepository {
    override fun create(wager: DuelWager): CompletableFuture<DuelWager> = runtime.executor.transaction { connection ->
        require(wager.revision == 0L && wager.phase == WagerPhase.PREPARING)
        val inserted = connection.prepareStatement(
            """
            INSERT IGNORE INTO `arcduels_wagers`
                (`match_id`, `server_id`, `challenger_id`, `target_id`, `stake_minor`, `phase`, `revision`, `updated_at`)
            VALUES (?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP(3))
            """.trimIndent(),
        ).use { statement ->
            statement.setBytes(1, UuidBytes.encode(wager.matchId.value))
            statement.setString(2, wager.serverId.value)
            statement.setBytes(3, UuidBytes.encode(wager.challenger.value))
            statement.setBytes(4, UuidBytes.encode(wager.target.value))
            statement.setLong(5, wager.stakeMinor)
            statement.setString(6, wager.phase.name)
            statement.executeUpdate() == 1
        }
        if (inserted) writeLegs(connection, wager)
        requireNotNull(load(connection, wager.matchId)) { "Wager insert has no readable receipt" }
    }

    override fun find(matchId: MatchId): CompletableFuture<DuelWager?> = runtime.executor.transaction { load(it, matchId) }

    override fun save(expected: DuelWager, updated: DuelWager): CompletableFuture<Boolean> = runtime.executor.transaction { connection ->
        require(updated.matchId == expected.matchId && updated.serverId == expected.serverId &&
            updated.challenger == expected.challenger && updated.target == expected.target && updated.stakeMinor == expected.stakeMinor)
        require(updated.revision == Math.addExact(expected.revision, 1))
        val changed = connection.prepareStatement(
            """
            UPDATE `arcduels_wagers`
            SET `phase` = ?, `winner_id` = ?, `revision` = ?, `reason` = ?, `updated_at` = CURRENT_TIMESTAMP(3)
            WHERE `match_id` = ? AND `revision` = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, updated.phase.name)
            updated.winner?.let { statement.setBytes(2, UuidBytes.encode(it.value)) } ?: statement.setNull(2, Types.BINARY)
            statement.setLong(3, updated.revision)
            statement.setString(4, updated.reason)
            statement.setBytes(5, UuidBytes.encode(updated.matchId.value))
            statement.setLong(6, expected.revision)
            statement.executeUpdate() == 1
        }
        if (changed) writeLegs(connection, updated)
        changed
    }

    override fun incomplete(serverId: ServerId, after: MatchId?, limit: Int): CompletableFuture<List<DuelWager>> {
        require(limit in 1..100)
        return runtime.executor.transaction { connection ->
            val ids = connection.prepareStatement(
                """
                SELECT `match_id` FROM `arcduels_wagers`
                WHERE `server_id` = ? AND `phase` NOT IN ('PAID', 'REFUNDED')
                  ${if (after != null) "AND `match_id` > ?" else ""}
                ORDER BY `match_id` LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, serverId.value)
                if (after != null) statement.setBytes(2, UuidBytes.encode(after.value))
                statement.setInt(if (after != null) 3 else 2, limit)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(MatchId(UuidBytes.decode(rows.getBytes(1)))) } }
            }
            ids.map { requireNotNull(load(connection, it)) }
        }
    }

    private fun load(connection: Connection, matchId: MatchId): DuelWager? = connection.prepareStatement(
        "SELECT * FROM `arcduels_wagers` WHERE `match_id` = ?",
    ).use { statement ->
        statement.setBytes(1, UuidBytes.encode(matchId.value))
        statement.executeQuery().use rows@{ row ->
            if (!row.next()) return@rows null
            val legs = connection.prepareStatement("SELECT * FROM `arcduels_wager_legs` WHERE `match_id` = ?").use { legQuery ->
                legQuery.setBytes(1, UuidBytes.encode(matchId.value))
                legQuery.executeQuery().use { legRows -> buildList { while (legRows.next()) add(legRows.toLeg()) }.sortedBy { it.kind.ordinal } }
            }
            DuelWager(
                matchId = matchId,
                serverId = ServerId(row.getString("server_id")),
                challenger = PlayerId(UuidBytes.decode(row.getBytes("challenger_id"))),
                target = PlayerId(UuidBytes.decode(row.getBytes("target_id"))),
                stakeMinor = row.getLong("stake_minor"),
                phase = WagerPhase.valueOf(row.getString("phase")),
                winner = row.getBytes("winner_id")?.let { PlayerId(UuidBytes.decode(it)) },
                legs = legs,
                revision = row.getLong("revision"),
                reason = row.getString("reason"),
            )
        }
    }

    private fun ResultSet.toLeg(): WagerLeg = WagerLeg(
        kind = WagerLegKind.valueOf(getString("leg_kind")),
        playerId = PlayerId(UuidBytes.decode(getBytes("player_id"))),
        amountMinor = getLong("amount_minor"),
        status = WagerLegStatus.valueOf(getString("status")),
        balanceBefore = getString("balance_before")?.toBigDecimal(),
        balanceAfter = getString("balance_after")?.toBigDecimal(),
        reason = getString("reason"),
    )

    private fun writeLegs(connection: Connection, wager: DuelWager) {
        connection.prepareStatement(
            """
            INSERT INTO `arcduels_wager_legs`
                (`match_id`, `leg_kind`, `player_id`, `amount_minor`, `status`, `balance_before`, `balance_after`, `reason`, `updated_at`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE `status` = VALUES(`status`), `balance_before` = VALUES(`balance_before`),
                `balance_after` = VALUES(`balance_after`), `reason` = VALUES(`reason`), `updated_at` = CURRENT_TIMESTAMP(3)
            """.trimIndent(),
        ).use { statement ->
            wager.legs.forEach { leg ->
                statement.setBytes(1, UuidBytes.encode(wager.matchId.value))
                statement.setString(2, leg.kind.name)
                statement.setBytes(3, UuidBytes.encode(leg.playerId.value))
                statement.setLong(4, leg.amountMinor)
                statement.setString(5, leg.status.name)
                statement.setString(6, leg.balanceBefore?.toPlainString())
                statement.setString(7, leg.balanceAfter?.toPlainString())
                statement.setString(8, leg.reason)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}
