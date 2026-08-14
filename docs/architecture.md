# Architecture

## Boundaries

The domain module owns duel truth. Platform code adapts Paper events to domain
commands and domain events back to player-facing effects. Storage and network
modules implement ports and never decide match outcomes.

```text
Paper commands/events -> domain services -> domain events -> Paper presentation
                              |                    |
                              v                    v
                 statistics + escrow ports   network event port
                              |                    |
                              MySQL             optional Redis
```

## Runtime modes

- `CONFIG_ONLY`: MySQL is disabled; configuration works but matches are locked.
- `MYSQL`: durable player recovery, statistics and leaderboards.
- `NETWORK`: MySQL plus Redis fan-out for announcements.

Optional Redis infrastructure is fail-soft for presentation and cache
invalidation. Player-state escrow and match-result persistence are fail-closed.
This avoids showing a win that was silently lost from the global ranking.
The MySQL adapter uses the shared `arc-core-sql` runtime for Hikari pooling,
TLS policy, bounded asynchronous execution, transactions, and checksum-locked
migrations. Last-known player names are stored separately from statistics so a
leaderboard rendered on another Paper node does not fall back to UUIDs.
Concurrent result writes lock both player rows in deterministic order. InnoDB
deadlock victims and lock-wait rollbacks receive a bounded asynchronous retry
with backoff; connection failures and other SQL errors remain visible and are
never retried blindly. Match timestamps are canonicalized to MySQL's declared
millisecond precision before idempotency comparison.

Redis is presentation-only. Startup failure closes both bus and client and
falls back to local operation. Event deduplication is scoped by source server,
bounded in size, and expires after one hour.

The arena allocator queues accepted pairs FIFO. Per-arena loadout policies are
evaluated together with objective compatibility locally and in Redis routing;
they do not depend on server names. The coordinator owns both
players while their request is queued, so racing challenges cannot allocate the
same participant twice. Once an arena is available, Paper freezes inventory and
movement mutations, captures both states on the primary thread, and commits the
pair to MySQL atomically. No gameplay mutation happens before that future
completes successfully. If the client loses the COMMIT response, ArcDuels keeps
both players frozen and reconciles the exact rows through fresh connections. It
requires an immediate read plus six delayed empty confirmations over 30 seconds
before treating the pair as absent; lookup failure or a partial pair stays
fail-closed.

If HuskSync is present, accepted matches remain in the pre-start state until
both players have emitted its successful synchronization-complete event.
ArcDuels does not capture or mutate player state while that barrier is closed.

Completed matches retain player and arena ownership until Paper has applied and
verified and saved both online snapshots. Only then does the coordinator release
the reservation. MySQL atomically moves the exact match-id/checksum snapshot from
active escrow into retained history. The archive is never auto-applied and is
purged only after its configured deadline. An unknown archival outcome is
idempotent: retry observes either the still-active snapshot or the exact retained
copy.

## Match objectives and loadouts

`KIT` and `OWN_INVENTORY` describe loadout ownership, independently of the
`ELIMINATION`, `KING_OF_THE_HILL`, `SUMO`, `BOXING`, and `COMBO` objectives. Combat modifiers are
immutable challenge data, so both participants accept the same validated
rules. KOTH arenas declare a bounded hill zone; incompatible arenas are never
reserved for that objective. The Paper adapter submits capture progress through
`ObjectiveFrame`, and `MatchCoordinator.evaluateObjective` applies a winner
atomically through the normal round, persistence, rating, and event path.
Boxing and Combo use the same path with event-driven score frames: Boxing counts
total accepted direct melee hits, while Combo resets the struck player's streak.
Both require the controlled boxing kit and cannot deal health damage. Objectives
cannot mutate statistics directly.
