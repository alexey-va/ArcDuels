# Testing

ArcDuels separates fast deterministic checks from disposable-service tests.

## Fast suite

```bash
./gradlew testAll :paper:shadowJar
```

The suite covers:

- challenge authorization, expiry, duplicate pairs, and bounded TTL;
- every match transition, BO1/BO3 scoring, forfeits, objectives, and cleanup;
- concurrent arena reservations and overlapping persistence completion;
- local statistics idempotency under concurrent duplicate writes;
- long result sequences, rating bounds, streaks, revisions, and leaderboards;
- Redis codec validation, authenticated origins, listener isolation, dedupe,
  source collisions, and TTL expiry;
- Paper bootstrap metadata, command parsing, safe command policy, snapshots,
  arena and kit validation, pagination matrices, teleport authorization, and
  damage-free celebration entities.

## MySQL integration suite

```bash
./gradlew :storage-mysql:integrationTest
```

The suite starts the pinned `mysql:8.4.10` image and exercises real migrations,
ranked and unranked persistence, name lookup, leaderboards, match-id collision
protection, timestamp precision, 32 concurrent copies of one result, and 24
concurrent unique matches sharing the same player rows. It is intentionally not
part of a fake JDBC test double.

## Artifact gate

The deployable artifact is `paper/build/libs/ArcDuels-0.1.0-SNAPSHOT.jar`.
Before distribution, verify it is a valid shadow JAR containing ArcDuels,
Kotlin, `arc-core-sql`, `arc-core-redis`, HikariCP, Connector/J, and Jedis while
excluding Paper and MockBukkit classes.
