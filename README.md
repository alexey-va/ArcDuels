# ArcDuels

Modern Kotlin duel engine for RusCrafting and other Paper networks.

The project is intentionally split so duel rules remain independent from
Paper, MySQL, and Redis:

| Module | Responsibility |
|---|---|
| `domain` | Match state machine, challenges, modes, ratings and ports |
| `storage-mysql` | Durable player-state escrow, statistics and leaderboard queries |
| `network-redis` | Network player discovery, challenges, arena routing, events and cache invalidation |
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
- network-wide player selection, cross-server challenges, automatic load-aware
  routing or an exact player-selected server and arena, leaderboard invalidation,
  and announcements over Redis;
- a Lands-inspired 45-slot main hub with challenge, queue, mode, kit, statistics,
  help, leaderboard, and administrative branches;
- elimination, king-of-the-hill, sumo, boxing, and combo objectives with BO1/BO3/BO5,
  ranked play, sudden death, and per-match combat modifiers;
- seven bundled starter kits and Russian/English client-locale presentation;
- optional countdown titles, action bars, sounds, particles and real damage-free winner fireworks;
- short server-authoritative arena-teleport stabilization that absorbs stale
  movement packets before combat begins;
- bounded arenas with per-arena loadout and objective compatibility, scoped
  internal teleports and protected player inventories;
- FIFO waiting for a free arena and in-game `/duels admin` GUI and commands;
- an explicit per-node player-data synchronization policy (`AUTO`, `HUSKSYNC`, or `NONE`);
- crash-safe, versioned MySQL player-state escrow on each origin backend before
  any cross-server transfer or duel mutation,
  a player recovery entry for unclaimed snapshots, and administrator-only replay
  of claimed snapshots retained for configurable N days;
- post-match arena lobbies with a clickable, player-controlled return to the
  origin backend and compare-before-apply inventory recovery;
- arena-scoped WorldGuard PvP compatibility with startup warnings instead of
  broad changes to a world's region policy;
- real KOTH capture zones with contested progress, particles, action bars, and
  objective-compatible arena allocation;
- resource-pack-neutral GUI roles with optional production ItemsAdder
  material/CustomModelData overlays.
- interactive player names in chat: hover for current duel statistics and click
  to open that player's challenge setup.
- complete match history with head-to-head records, exact-arena rematches, and
  five optional saved rule setups without adding a step to the normal challenge flow.

## Build

Requires Java 25.

```bash
./gradlew testAll :paper:shadowJar
./gradlew :storage-mysql:integrationTest # requires Docker
```

The plugin JAR is produced under `paper/build/libs/`.

See [configuration](docs/configuration.md), [architecture](docs/architecture.md),
[testing](docs/testing.md), and the [roadmap](docs/roadmap.md).
