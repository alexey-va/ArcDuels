# Configuration

## Runtime profiles

The plugin boots with MySQL disabled by default so a fresh installation cannot
write to an unintended database. Commands and configuration remain available,
but matches are fail-closed until `mysql.enabled` is true.

MySQL is mandatory for gameplay. Before the first teleport, inventory clear,
kit issue, or state normalization, ArcDuels stores both complete player
snapshots in one InnoDB transaction, reads the committed bytes back, and checks
SHA-256. If any step fails, the arena is released and the duel never starts.
Startup is fail-closed when MySQL is enabled but the pool or checksum-protected
schema migrations cannot be prepared. Match result writes are also fail-closed
and retry by match id, so an unknown network outcome cannot double-count a win.

RusCrafting uses the shared `common` database. ArcDuels owns only namespaced
`arcduels_*` tables, including `arcduels_schema_history`. Operators provision
the database and account; plugin startup creates and migrates every table
automatically. No administrator should run ArcDuels table DDL by hand.

Enable `redis.enabled` independently for cross-server win announcements and
leaderboard invalidation events. Redis transport is presentation-only and
fail-soft; MySQL remains the durable source of truth. If Redis is unavailable
at startup, ArcDuels closes the partial network resources, logs a bounded
warning, and continues locally without cross-server announcements.

Every network node needs a unique `server-id` containing only letters, digits,
dot, underscore, or hyphen.

## Localization

ArcDuels ships `lang/ru.yml` and `lang/en.yml`. With
`locale.use-client-locale: true`, Russian clients receive Russian and other
clients receive English; `locale.default` is used for the console and when
client detection is disabled. External locale files can override any bundled
key while newly introduced keys continue to fall back to the JAR defaults.

## Winner celebration

Winner particles, sounds, and titles are always shown. Real firework rockets
are optional and enabled by default:

```yaml
celebration:
  fireworks:
    enabled: true
    count: 3
```

`count` is bounded to `1..5`. ArcDuels tags its rockets and cancels their entity
damage, so the visual celebration cannot hurt the winner or nearby players.

## MySQL TLS

`mysql.ssl-mode` maps directly to current Connector/J security modes:

- `DISABLED` — private trusted network only;
- `REQUIRED` — encryption without server identity verification;
- `VERIFY_CA` — trusted certificate authority validation;
- `VERIFY_IDENTITY` — CA and hostname validation; recommended default.

Credentials are passed as Hikari properties, never embedded in the JDBC URL or
the redacted connection diagnostics.

ArcDuels retries only transactions that MySQL explicitly rolled back as a
deadlock victim or lock-wait timeout. Retries are bounded and use backoff;
connection loss and unknown commit outcomes are not retried at this layer.

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
    hill:
      center: { world: duels, x: 0.5, y: 65, z: 0.5, yaw: 0, pitch: 0 }
      radius: 3.5
      height: 3.0
```

Arena reservations are exclusive. If all arenas are occupied, accepted pairs
wait in FIFO order without touching either player's inventory. Queue ownership
prevents either participant from entering another duel. Both spawns must be
inside the bounds and use the same loaded world. Leaving the bounds loses the
round; only scoped ArcDuels teleports and in-bounds combat teleports are
accepted while a player owns an arena.

Operators can configure arenas in game. Any point edit disables the arena until
the full definition validates again; edits and reloads are rejected while a
match owns an arena or a pair is waiting.

```text
/duels admin arena create <id>
/duels admin arena setspawn <id> <1|2>
/duels admin arena setcorner <id> <1|2>
/duels admin arena sethill <id> [radius] [height]
/duels admin arena enable|disable <id>
/duels admin arena list
/duels admin arena info <id>
/duels admin arena reload
```

## Kits

Kit inventory entries use `MATERIAL [amount]` and slots `0..35`. Armor has
dedicated helmet, chestplate, leggings, and boots keys. Invalid materials,
oversized stacks, and unknown kits stop startup instead of failing halfway
through a match.

Both own-inventory and kit modes snapshot both players and restore their
original location, inventory, armor, off-hand, health, hunger, experience, game
mode, flight state, cursor item, selected slot, movement state, and potion
effects after completion, disconnect, cancellation, or shutdown. Items use
Paper's versioned NBT byte format so Minecraft data conversion can migrate them
after an upgrade. The active escrow row is deleted only after exact state
application, verification, and a synchronous save of Paper's playerdata; a
crash before acknowledgement simply causes the same idempotent restore on the next join. A snapshot belonging to another
network node locks duel state and identifies the originating `server-id`
instead of applying world data on the wrong server.

The bundled starter kits are `classic`, `axe`, `archer`, `uhc`, `tank`, and
`sumo`. The UHC selection starts with natural regeneration disabled; every
setting remains visible before the challenge is sent.

`/duel` opens the main hub. Challenge setup is deliberately hierarchical:
opponent → objective → loadout → rules → confirmation. Rules include
BO1/BO3/BO5, ranked kit matches, sudden-death time, projectiles, consumables,
ender pearls, natural regeneration, and KOTH capture time. The recipient sees
the selected rules before accepting. Own-inventory matches remain unranked.

## Commands

- `/duel` — open the main hub;
- `/duel <player>` — open objective selection for that player;
- `/duel accept [challenge-id]`, `/duel deny [challenge-id]`;
- `/duel cancel`;
- `/duel leave` — forfeit the current match;
- `/duel stats [online-player]`;
- `/duel top` — open the global leaderboard.
- `/duels admin status` — active arenas and FIFO waiters;
- `/duels admin recover <online-player>` — retry an exact pending recovery.

Player commands require `arcduels.use`, granted by default. Administrative
commands require `arcduels.admin`, granted to operators by default.

During a match, chat/reply commands plus `/duel leave` and `/duel stats`
remain available. Other commands are blocked to prevent external
teleport, inventory, and state plugins from breaking match isolation.
