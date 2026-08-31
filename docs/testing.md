# Testing

ArcDuels separates fast deterministic checks from disposable-service tests.

Paper tests receive the Paper API and pinned MockBukkit version through
`ru.ruscrafting.arc:arc-core-paper-testing:2.2.0`. New lifecycle tests should use
`MockBukkitTestRuntime` so server/plugin teardown is owned by one closeable
fixture and cannot leak into the next spec. Do not pin MockBukkit separately in
this repository; update the shared testkit when the network Paper version moves.

## Fast suite

```bash
./gradlew testAll :paper:shadowJar
```

The suite covers:

- challenge authorization, expiry, single-pending-challenge player ownership,
  and bounded TTL;
- every match transition, BO1/BO3 scoring, forfeits, objectives, and cleanup;
- 3–12-player FFA/two-team/three-team roster validation, balanced team winners,
  placement ordering, cancellation, and shared/per-player kit contracts;
- exact KOTH capture thresholds, contested-progress pauses, boxing totals,
  combo resets, per-arena objective and loadout selection, and invalid
  controlled-objective/loadout combinations;
- queued player ownership, FIFO arena handoff, cancellation, and overlapping persistence completion;
- local statistics idempotency under concurrent duplicate writes;
- complete in-memory and MySQL match-history round trips, perspective scoring,
  head-to-head aggregation, exact-arena rematches, and five-slot preset CRUD;
- long result sequences, rating bounds, streaks, revisions, and leaderboards;
- Redis event and challenge codec validation, authenticated origins, listener
  isolation, publish-before-local fail-closed behavior, dedupe, source collisions,
  Floodgate-prefixed names, wall-clock rollback rejection, player-snapshot TTL expiry, blank
  backend handling, schema-version rejection, dynamic objective/loadout-compatible
  arena-node selection, exact kit-fingerprint routing across catalog reloads,
  host-side fingerprint revalidation, exact server/arena pinning, arena-lobby rematches with
  distinct current and recovery-origin servers, and no-fallback FIFO reservation;
- Paper bootstrap metadata, admin arena editing, command parsing, safe command policy, versioned snapshots,
  finite snapshot encoding, queued network-reservation cleanup,
  arena and kit validation, exact runtime-stack kit manifests, multiplayer spawn
  assignment and 1v1/group lease exclusion, full multiplayer GUI setup across
  pagination, three-team/per-player-kit readiness and start, invite timeout and
  cancellation cleanup, team-aware damage and lethal completion, countdown
  tick boundaries, arena movement, inventory, teleport, and command isolation,
  explicit sync-provider detection, origin-before-transfer
  single-row escrow, HuskSync readiness, recovery-delay gating, compare-before-apply claims, pagination matrices,
  modern GUI CustomModelData components, accepted melee-hit scoring, teleport authorization,
  server-authoritative countdown anchoring and pre-countdown teleport stabilization,
  WorldGuard participant-only PvP, precursor-interaction and tracked fluid/fire/reaction overrides,
  stale asynchronous-menu suppression, immutable return routing, command
  bypass behavior, damage-free celebration entities, default-kit presence,
  player-facing network server names, BO1 boss bars without a meaningless
  `0:0`, bounded graceful-shutdown retention draining that preserves timed-out
  active snapshots, direct arena-lobby rematch chains, per-arena post-match
  overrides, non-lethal particle-wall boundary warnings, 200x200 arena defaults, kit-only 20 HP isolation,
  combined result/rematch cards, contextual rule hover help,
  interactive player-name hover/click events, and strict
  Russian / English locale-key and MiniMessage parity;
- atomic live-configuration generations, immutable multi-file/ABA source
  capture, strict nested section/list and MiniMessage locale validation,
  last-known-good rollback, restart-only reporting without secret disclosure,
  immediate locale/GUI refresh, listener-cardinality stability, deferred
  arena/kit publication with immediate network re-advertisement, configurable group invitation and finish timing,
  catalog-coherent group draft defaults, automatic-return and BO-series policy snapshots,
  bounded network deadlines, cancel/expiry during durable group preparation,
  and per-session policy snapshots across a runtime reload.

The separate `scripts/player-bot` suite recognizes the BO1 opponent/time boss
bar, BO3/BO5 score boss bar, and hit-race target boss bar. Production QA must
still exercise a real kit-only arena with two bots and compare their complete
inventory summaries before and after the match.

## MySQL integration suite

```bash
./gradlew :storage-mysql:integrationTest
```

The suite starts the pinned `mysql:8.4.10` image and exercises real migrations,
ranked and unranked persistence, name lookup, leaderboards, match-id collision
protection, timestamp precision, 32 concurrent copies of one result, and 24
concurrent unique matches sharing the same player rows, atomic two-player
escrow, exact active-to-archive transfer, idempotent replay after an unknown
archival outcome, retention deadlines, bounded expiry cleanup, checksum
matching, claimed-snapshot lookup, recovery metadata, atomic 3–12-player
escrow, durable group-result participants, and rollback on a participant conflict. It also deletes a committed
migration-history row and proves that replay converges when MySQL DDL committed
before its journal write, including the replayable-history and preset migrations.
A crash-boundary scenario closes the writer after the
pair commit, reopens a fresh repository, acknowledges only one participant,
closes again, and proves another fresh repository still exposes exactly the
unclaimed participant while retaining the claimed snapshot idempotently. It is
intentionally not part of a fake JDBC test double.

## Artifact gate

The deployable artifact is `paper/build/libs/ArcDuels-<version>.jar`.
Before distribution, verify it is a valid shadow JAR containing ArcDuels,
Kotlin, `arc-core-sql`, `arc-core-redis`, HikariCP, Connector/J, and Jedis while
excluding Paper and MockBukkit classes.
