# Testing

ArcDuels separates fast deterministic checks from disposable-service tests.

## Fast suite

```bash
./gradlew testAll :paper:shadowJar
```

The suite covers:

- challenge authorization, expiry, duplicate pairs, and bounded TTL;
- every match transition, BO1/BO3 scoring, forfeits, objectives, and cleanup;
- exact KOTH capture thresholds, contested-progress pauses, compatible-arena
  selection, and invalid SUMO/loadout combinations;
- queued player ownership, FIFO arena handoff, cancellation, and overlapping persistence completion;
- local statistics idempotency under concurrent duplicate writes;
- long result sequences, rating bounds, streaks, revisions, and leaderboards;
- Redis event and challenge codec validation, authenticated origins, listener
  isolation, dedupe, source collisions, player-snapshot TTL expiry, blank
  backend handling, and dynamic objective-compatible arena-node selection;
- Paper bootstrap metadata, admin arena editing, command parsing, safe command policy, versioned snapshots,
  arena and kit validation, pagination matrices, teleport authorization, and
  damage-free celebration entities, default-kit presence, and strict Russian /
  English locale-key and MiniMessage parity.

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
matching, and rollback on a participant conflict. It also deletes a committed
migration-history row and proves that replay converges when MySQL DDL committed
before its journal write. It is intentionally not part of a fake JDBC test
double.

## Artifact gate

The deployable artifact is `paper/build/libs/ArcDuels-<version>.jar`.
Before distribution, verify it is a valid shadow JAR containing ArcDuels,
Kotlin, `arc-core-sql`, `arc-core-redis`, HikariCP, Connector/J, and Jedis while
excluding Paper and MockBukkit classes.
