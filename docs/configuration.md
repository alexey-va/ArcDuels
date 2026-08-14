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

Enable `redis.enabled` for the network player picker, cross-server challenges,
live arena routing, win announcements, and leaderboard invalidation. ProxyARC's
authenticated `arc.proxy_player_list` snapshot is the authoritative online
directory; entries expire locally when the proxy heartbeat becomes stale.
Every ArcDuels node also publishes its objective- and loadout-compatible arena
capacity, free slots, and queue depth. When a challenge is accepted, the plugin
chooses a live compatible node by free capacity and load, then transfers both
players there through the proxy. No fixed arena server is configured or assumed. Once
the match result is durable and both inventory snapshots have been restored
and released, each participant is returned to the backend they came from.

Redis remains fail-soft and MySQL remains the durable source of truth. If Redis
is unavailable at startup, ArcDuels closes every partial network resource,
logs a bounded warning, and continues with same-server challenges only. On
RusCrafting nodes, `redis.import-arc-credentials: true` securely reuses the
local ARC Redis endpoint and credentials from `plugins/ARC/modules/redis.yml`
or the legacy `plugins/ARC/config.yml`; the secret is never logged.

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

`countdown-seconds` accepts `0..10`; `0` activates combat immediately after
both durable snapshots, kit application, and arena teleports have completed.

Each enabled arena requires two isolated spawns in a loaded world. The bundled
example is disabled deliberately so a fresh install cannot teleport players to
unsafe placeholder coordinates.

```yaml
arenas:
  colosseum:
    enabled: true
    allowed-loadouts: [OWN_INVENTORY, KIT]
    allowed-objectives: [ELIMINATION, KING_OF_THE_HILL, SUMO, BOXING, COMBO]
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

`allowed-loadouts` is an arena policy, not a server role. It accepts
`OWN_INVENTORY`, `KIT`, or both; omitting it keeps both modes enabled for
backward compatibility. This lets one network dedicate individual arenas to
personal items or kits without ArcDuels knowing server names or topology.

`allowed-objectives` independently restricts the combat modes assigned to an
arena. It accepts `ELIMINATION`, `KING_OF_THE_HILL`, `SUMO`, `BOXING`, and
`COMBO`; omitting it enables all objectives for backward compatibility. KOTH
still requires a valid bounded hill zone. The plugin therefore supports a
network's local arena policy without hard-coding server names.

Arena reservations are exclusive. If all arenas are occupied, accepted pairs
wait in FIFO order without touching either player's inventory. Queue ownership
prevents either participant from entering another duel. Both spawns must be
inside the bounds and use the same loaded world. Leaving the bounds loses the
round; only scoped ArcDuels teleports and in-bounds combat teleports are
accepted while a player owns an arena.

Network arena heartbeats distinguish both objective support and allowed
loadouts. A challenge is never routed to a node without a compatible arena.
Stale or spoofed heartbeats are ignored; a capacity race is still safe because
the destination's normal exclusive FIFO allocator is the final authority.

Operators can configure arenas in game. Any point edit disables the arena until
the full definition validates again; edits and reloads are rejected while a
match owns an arena or a pair is waiting.

```text
/duels admin arena create <id>
/duels admin arena setspawn <id> <1|2>
/duels admin arena setcorner <id> <1|2>
/duels admin arena sethill <id> [radius] [height]
/duels admin arena setloadouts <id> <all|own|kit>
/duels admin arena setobjectives <id> <all|elimination|koth|sumo|boxing|combo...>
/duels admin arena enable|disable <id>
/duels admin arena list
/duels admin arena info <id>
/duels admin arena reload
```

`/duels admin` opens the graphical editor. The commands remain available for
automation and console operation; the arena editor cycles the same loadout
policy and opens a dedicated objective allowlist directly in the arena details
screen.

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
after an upgrade. After exact state application, verification, and a synchronous
save of Paper's playerdata, one InnoDB transaction copies the exact active row
to `arcduels_player_state_archive` and deletes it from active recovery. A lost
transaction response is safe to retry. Archived rows are never auto-applied, so
retention cannot rewind or duplicate a player's later inventory. A crash before
that transaction commits leaves the active row and causes the same idempotent
restore on the next join.

When HuskSync is installed, ArcDuels also waits for its successful login-sync
completion event for both participants before it asks the durable snapshot
service to capture anything. A transfer or fresh login therefore cannot race
duel inventory capture against network player-data application.

Restored snapshots are retained for seven days by default and expired archive
rows are purged in bounded batches once per hour. Both values are configurable:

```yaml
mysql:
  inventory-snapshots:
    retention-days: 7
    cleanup-interval-minutes: 60
```

The retention period is bounded to `1..3650` days. Cleanup can run every
`1..10080` minutes and never touches active recovery rows. A snapshot belonging
to another network node locks duel state and identifies the originating
`server-id` instead of applying world data on the wrong server.

The bundled starter kits are `classic`, `axe`, `archer`, `uhc`, `tank`, `sumo`,
and `boxing`. Sumo and hit-race objectives use their controlled kits only. The
UHC selection starts with natural regeneration disabled; every
setting remains visible before the challenge is sent.

`/duel` opens the main hub. The opponent picker includes players from every
ProxyARC backend and shows their current server. Challenge setup is deliberately hierarchical:
opponent → objective → loadout → rules → confirmation. Rules include
BO1/BO3/BO5, ranked kit matches, sudden-death time, projectiles, consumables,
ender pearls, natural regeneration, and KOTH capture time. The recipient sees
the selected rules before accepting. Boxing ends at a configurable total-hit
target; Combo ends at a configurable unanswered-hit streak, reset by the
opponent's next direct melee hit. Both disable health damage, projectiles,
consumables, pearls, regeneration, and sudden death. Own-inventory matches
remain unranked.

## GUI item roles

Bundled GUI items are ordinary vanilla materials. Deployments can override
`gui.items.<role>` with a matching `material` and `custom-model-data` pair. The
plugin applies model data with Paper's modern data-component API and never
hard-codes resource-pack numbers. Standard roles include `background`, `back`,
`previous`, `next`, `info`, `refresh`, and `confirm`.

## Commands

- `/duel` — open the main hub;
- `/duel <player>` — open objective selection for that player;
- `/duel accept [challenge-id]`, `/duel deny [challenge-id]`;
- `/duel cancel`;
- `/duel leave` — forfeit the current match;
- `/duel stats [network-online-player]`;
- `/duel top` — open the global leaderboard.
- `/duels admin status` — active arenas and FIFO waiters;
- `/duels admin recover <online-player>` — retry an exact pending recovery.

Player commands require `arcduels.use`, granted by default. Administrative
commands require `arcduels.admin`, granted to operators by default.

During a match, chat/reply commands plus `/duel leave` and `/duel stats`
remain available. Other commands are blocked to prevent external
teleport, inventory, and state plugins from breaking match isolation.
