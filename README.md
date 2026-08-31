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

`arc-core` is pinned to immutable public Maven release `2.2.5`; agents can opt
into a local composite with `-ParcCoreDir=/absolute/path/to/arc-core` while
developing both repositories. Redis is optional at runtime. MySQL is mandatory for starting matches
because ArcDuels will not mutate a player's inventory or location without a
committed, checksum-verified recovery snapshot.

Reusable infrastructure is owned by arc-core: typed network identifiers,
bounded Redis codecs and origin/replay handling, Bungee transfer, scoped
teleports, complete Paper player state, and the shared MockBukkit test runtime.
ArcDuels keeps duel state machines and its MySQL schema; it does not fork those
cross-plugin mechanisms.

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
- thirteen merge-forward starter kits, exact item/enchantment manifests in every
  kit picker, and Russian/English client-locale presentation;
- local 3–12-player matches with free-for-all, two-team, or three-team layouts,
  automatic balanced team assignment, and either one shared kit or an explicit
  kit choice from every participant;
- optional countdown titles, action bars, sounds, particles and real damage-free winner fireworks;
- short server-authoritative arena-teleport stabilization that absorbs stale
  movement packets before combat begins;
- bounded arenas with per-arena loadout and objective compatibility, scoped
  internal teleports and protected player inventories;
- FIFO waiting for a free arena and in-game `/duels admin` GUI and commands;
- permission-gated `ARCDUELS_DEBUG` readback for deterministic bot QA of server,
  player/match, health, boundary, recovery, and arena state;
- an explicit per-node player-data synchronization policy (`AUTO`, `HUSKSYNC`, or `NONE`);
- crash-safe, versioned MySQL player-state escrow on each origin backend before
  any cross-server transfer or duel mutation,
  a player recovery entry for unclaimed snapshots, and administrator-only replay
  of claimed snapshots retained for configurable N days;
- per-arena post-match routing to either the local lobby or the original
  backend, with compare-before-apply inventory recovery and direct lobby rematches;
- arena-scoped WorldGuard PvP and temporary fluid compatibility with startup
  warnings instead of broad changes to a world's region policy;
- visible particle-wall boundary warnings with a non-lethal movement clamp, and a temporary
  20 HP cap for controlled kit matches without changing own-inventory health;
- real KOTH capture zones with contested progress, particles, action bars, and
  objective-compatible arena allocation;
- resource-pack-neutral GUI roles with optional production ItemsAdder
  material/CustomModelData overlays.
- interactive player names in chat: hover for current duel statistics and click
  to open that player's challenge setup.
- complete match history with head-to-head records, exact-arena rematches, and
  five optional saved rule setups without adding a step to the normal challenge flow.
- optional BattlePass progress emitted after durable match completion:
  `arcduels-match` for both participants, `arcduels-win` for the winner, and
  `arcduels-ranked-win` for a ranked winner. The action variable is the
  lower-case objective id (`elimination`, `sumo`, `boxing`, and so on).

## Build

Requires Java 25.

```bash
./gradlew testAll :paper:shadowJar
./gradlew :storage-mysql:integrationTest # requires Docker
```

The plugin JAR is produced under `paper/build/libs/`.

See [configuration](docs/configuration.md), [architecture](docs/architecture.md),
[testing](docs/testing.md), and the [roadmap](docs/roadmap.md).
