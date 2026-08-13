# ArcDuels

Modern Kotlin duel engine for RusCrafting and other Paper networks.

The project is intentionally split so duel rules remain independent from
Paper, MySQL, and Redis:

| Module | Responsibility |
|---|---|
| `domain` | Match state machine, challenges, modes, ratings and ports |
| `storage-mysql` | Durable player-state escrow, statistics and leaderboard queries |
| `network-redis` | Optional cross-server events and cache invalidation |
| `paper` | Commands, inventories, arena runtime and player presentation |

`arc-core` is pinned as a Git submodule and consumed through a Gradle composite
build. Redis is optional at runtime. MySQL is mandatory for starting matches
because ArcDuels will not mutate a player's inventory or location without a
committed, checksum-verified recovery snapshot.

## Status

This repository is under active development. The first vertical slice targets:

- kit and own-inventory duels;
- validated challenges and one active match per player;
- local and MySQL-backed statistics;
- global leaderboard invalidation and cross-server announcements over Redis;
- casual/ranked BO1 and BO3 selection in the inventory GUI;
- paginated player, kit and global top-100 leaderboard inventories;
- countdown titles, action bars, sounds, particles and real damage-free winner fireworks;
- bounded arenas, scoped internal teleports and protected player inventories;
- FIFO waiting for a free arena and in-game `/duels admin arena` setup;
- crash-safe, versioned MySQL player-state escrow before any duel mutation;
- a state-machine-wired objective boundary for future KOTH modes.

## Build

Requires Java 25.

```bash
./gradlew testAll :paper:shadowJar
./gradlew :storage-mysql:integrationTest # requires Docker
```

The plugin JAR is produced under `paper/build/libs/`.

See [configuration](docs/configuration.md), [architecture](docs/architecture.md),
[testing](docs/testing.md), and the [roadmap](docs/roadmap.md).
