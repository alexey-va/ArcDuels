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
The same receipt stores the complete accepted rules, exact arena, perspective
score, completion reason, and rating results. History, head-to-head aggregation,
exact rematches, and per-player rule presets are read models over those durable
rows; none participates in authoritative match transitions.

Redis is presentation-only. Startup failure closes both bus and client and
falls back to local operation. Event deduplication is scoped by source server,
bounded in size, and expires after one hour.

The arena allocator queues accepted pairs FIFO. Per-arena loadout policies are
evaluated together with objective compatibility locally and in Redis routing;
they do not depend on server names. Redis advertises exact arena identities, so
automatic routing can balance live capacity while a player-selected arena stays
pinned to its server and its own FIFO reservation. The coordinator owns both
players while their request is queued, so racing challenges cannot allocate the
same participant twice. For a cross-server challenge, each origin Paper node
captures and commits its own participant before allowing proxy transfer. The
arena host verifies that both origin rows have the challenge-derived match id
and immutable origin server id before it mutates either player. Same-server
matches retain an atomic two-row commit. A lost COMMIT response is reconciled
against the exact row through fresh connections; an immediate read plus six
delayed empty confirmations over 30 seconds are required before absence is
accepted.

When the node policy selects HuskSync, accepted matches remain in the pre-start state until
both players have emitted its successful synchronization-complete event.
ArcDuels does not capture or mutate player state while that barrier is closed.
Join-time recovery also waits for that event and a configured post-sync delay.
It compares the loaded inventory to the origin snapshot first and applies
nothing when they already match. A `NONE` arena node restores only its local
pre-fight state before moving players to its lobby; it never claims an origin row.
An exact rematch carries that original recovery match id through the challenge
wire message, reuses the same two origin snapshots, and starts directly from the
arena lobby. The snapshots remain active until the players actually return.
After each arena teleport, the assigned spawn remains the server-authoritative
movement anchor for a bounded stabilization window. This is separate from
inventory recovery and from the combat-state command lock.

Completed matches retain player and arena ownership until Paper has applied and
verified and saved both online snapshots. Only then does the coordinator release
the reservation. MySQL atomically moves the exact match-id/checksum snapshot from
active escrow into retained history. The archive is never auto-applied and is
available for explicit administrator replay only; it is purged after its
configured deadline. An unknown archival outcome is
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

The Paper adapter enforces a transient 20 HP ceiling only for `KIT` matches and
revalidates it while the round is live. Arena-owned fluid changes are tracked as
original block states and restored before another round or shutdown.
