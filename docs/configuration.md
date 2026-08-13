# Configuration

## Runtime profiles

The plugin boots in local mode by default. This is useful for development and
single-server installations, but statistics disappear after a restart.

Enable `mysql.enabled` for durable statistics and global leaderboards. Startup
is fail-closed in this profile: RusDuels disables itself if it cannot validate
the pool or apply checksum-protected schema migrations. Match result writes are
also fail-closed and retry by match id, so an unknown network outcome cannot
double-count a win.

Enable `redis.enabled` independently for cross-server win announcements and
leaderboard invalidation events. Redis transport is presentation-only and
fail-soft; MySQL remains the durable source of truth.

Every network node needs a unique `server-id` containing only letters, digits,
dot, underscore, or hyphen.

## Winner celebration

Winner particles, sounds, and titles are always shown. Real firework rockets
are optional and enabled by default:

```yaml
celebration:
  fireworks:
    enabled: true
    count: 3
```

`count` is bounded to `1..5`. RusDuels tags its rockets and cancels their entity
damage, so the visual celebration cannot hurt the winner or nearby players.

## MySQL TLS

`mysql.ssl-mode` maps directly to current Connector/J security modes:

- `DISABLED` — private trusted network only;
- `REQUIRED` — encryption without server identity verification;
- `VERIFY_CA` — trusted certificate authority validation;
- `VERIFY_IDENTITY` — CA and hostname validation; recommended default.

Credentials are passed as Hikari properties, never embedded in the JDBC URL or
the redacted connection diagnostics.

## Arenas

Each enabled arena requires two isolated spawns in a loaded world. The bundled
example is disabled deliberately so a fresh install cannot teleport players to
unsafe placeholder coordinates.

```yaml
arenas:
  colosseum:
    enabled: true
    first-spawn: { world: duels, x: -8.5, y: 65, z: 0.5, yaw: -90, pitch: 0 }
    second-spawn: { world: duels, x: 8.5, y: 65, z: 0.5, yaw: 90, pitch: 0 }
    bounds:
      min: { x: -12, y: 60, z: -12 }
      max: { x: 12, y: 85, z: 12 }
```

Arena reservations are exclusive. If all arenas are occupied, challenge
acceptance fails without touching either player's inventory. Both spawns must
be inside the bounds and use the same loaded world. Leaving the bounds loses
the round; only scoped RusDuels teleports and in-bounds combat teleports are
accepted while a player owns an arena.

## Kits

Kit inventory entries use `MATERIAL [amount]` and slots `0..35`. Armor has
dedicated helmet, chestplate, leggings, and boots keys. Invalid materials,
oversized stacks, and unknown kits stop startup instead of failing halfway
through a match.

Own-inventory mode snapshots both players and restores their original location,
inventory, armor, off-hand, health, hunger, experience, game mode, flight state,
cursor item, selected slot, movement state, and potion effects after completion,
disconnect, cancellation, or shutdown.

The target browser shows 45 players per page, the kit picker shows 28 kits per
page, and the leaderboard exposes the global top 100 in pages of 45 entries.

Kit buttons use left click for BO1 and right click for BO3. Holding Shift makes
the selected kit duel ranked, so it updates ELO. Own-inventory matches are
always unranked.

## Commands

- `/duel` — open the player browser;
- `/duel <player>` — open the mode and kit picker;
- `/duel accept [challenge-id]`, `/duel deny [challenge-id]`;
- `/duel cancel`;
- `/duel leave` — forfeit the current match;
- `/duel stats [online-player]`;
- `/duel top` — open the global leaderboard.

All commands require `rusduels.use`, granted by default.

During a match, chat/reply commands plus `/duel leave` and `/duel stats`
remain available. Other commands are blocked to prevent external
teleport, inventory, and state plugins from breaking match isolation.
