# Configuration

## Runtime profiles

The plugin boots with MySQL disabled by default so a fresh installation cannot
write to an unintended database. Commands and configuration remain available,
but matches are fail-closed until `mysql.enabled` is true.

MySQL is mandatory for gameplay. Before a player leaves their origin backend,
ArcDuels captures that backend's complete state on the Paper thread, commits an
origin-owned row, reads the committed bytes back, and checks SHA-256. The proxy
transfer is not requested until that exact row is durable. Same-server matches
still commit both snapshots atomically before the first arena teleport. If any
step fails, the arena is released and the duel never starts.
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
Every ArcDuels node also publishes each objective- and loadout-compatible arena,
its player-facing name, availability, and queue depth. Challenge setup offers
either automatic load-aware routing or an exact server and arena. An exact busy
arena remains pinned and queues the pair there; it never silently falls back to
another backend. No fixed arena server is configured or assumed.
After a network match, players are moved to the configured lobby on the arena
backend and offered a clickable return. The origin row remains unclaimed until
the player returns, the origin synchronizer settles, and the live inventory is
compared with the saved bytes.

Redis remains fail-soft and MySQL remains the durable source of truth. If Redis
is unavailable at startup, ArcDuels closes every partial network resource,
logs a bounded warning, and continues with same-server challenges only. On
RusCrafting nodes, `redis.import-arc-credentials: true` securely reuses the
local ARC Redis endpoint and credentials from `plugins/ARC/modules/redis.yml`
or the legacy `plugins/ARC/config.yml`; the secret is never logged.

Every network node needs a unique `server-id` containing only letters, digits,
dot, underscore, or hyphen.

Keep that id stable for Redis, MySQL, and proxy routing, and configure a
separate player-facing MiniMessage name for every node that can appear in duel
menus or transfer notices:

```yaml
server-id: spawn
server-display-names:
  spawn: '<#ffb347>Спавн</#ffb347>'
  survival: '<#55ff8a>Выживание</#55ff8a>'
  parkour: '<#32d6ff>Паркур</#32d6ff>'
```

An unknown remote id falls back to the technical id and emits one bounded
operator warning; it never prevents recovery or routing.

Declare how each node synchronizes player data instead of encoding server names
in plugin logic:

```yaml
player-data-sync:
  provider: HUSKSYNC # AUTO, HUSKSYNC, or NONE
  settle-delay-ticks: 40
post-match:
  return-policy: PROMPT # or AUTOMATIC
```

`HUSKSYNC` fails startup when HuskSync is absent. `AUTO` detects it. `NONE`
means inventories are isolated on that backend; such a node can advertise kit
arenas but ArcDuels suppresses its cross-server own-inventory capacity.

`PROMPT` keeps players at the arena's configured lobby and offers a return to
their origin server. If the arena has no lobby, each participant is moved to
their assigned arena spawn; ArcDuels never falls back to an unrelated primary
world. Accepting another duel from this waiting state automatically returns the
player through the origin recovery flow before the next arena transfer.

## Localization

ArcDuels ships `lang/ru.yml` and `lang/en.yml`. With
`locale.use-client-locale: true`, Russian clients receive Russian and other
clients receive English; `locale.default` is used for the console and when
client detection is disabled. External locale files can override any bundled
key while newly introduced keys continue to fall back to the JAR defaults.

Successful return feedback is opt-in to keep routine duel cleanup quiet.
`controller.network-return` controls the framed chat notice before a transfer
to the origin server, and `session.restored` controls the inventory-restored
action bar. Both are blank by default; set either locale value to nonblank
MiniMessage text to enable it. Recovery failures and snapshot-safety warnings
remain mandatory and are not suppressed by these settings.

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
`teleport-stabilization-ticks` accepts `0..20` and defaults to `3`. During this
short window ArcDuels treats the assigned arena spawn as authoritative, clears
stale velocity, and reanchors a player only if another movement source displaced
them. Countdown activation is delayed by the same number of ticks, including
when `countdown-seconds` is zero.

Each enabled arena requires two isolated spawns in a loaded world. The bundled
example is disabled deliberately so a fresh install cannot teleport players to
unsafe placeholder coordinates.

```yaml
arenas:
  colosseum:
    enabled: true
    display-name: Colosseum
    allowed-loadouts: [OWN_INVENTORY, KIT]
    allowed-objectives: [ELIMINATION, KING_OF_THE_HILL, SUMO, BOXING, COMBO]
    first-spawn: { world: duels, x: -8.5, y: 65, z: 0.5, yaw: -90, pitch: 0 }
    second-spawn: { world: duels, x: 8.5, y: 65, z: 0.5, yaw: 90, pitch: 0 }
    lobby: { world: duels, x: 0.5, y: 65, z: 20.5, yaw: 180, pitch: 0 }
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

`display-name` is plain player-facing text; it falls back to the arena id. The
server name is rendered separately through `server-display-names`.

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
/duels admin arena setlobby <id>
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

Both own-inventory and kit modes snapshot both players on their origin backend and restore their
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

The active row is an unclaimed recovery and appears in the player's `/duel`
menu when no live match owns it. Applying and verifying the exact state, saving
playerdata, and moving the row to the archive claims it. Players cannot replay
claimed rows. `/duels admin recover <online-player>` first claims an active row,
or explicitly replays the newest retained row when no active recovery remains.
Retained replay must run on the snapshot's owning backend so its saved world and
location can be resolved safely.

With `player-data-sync.provider: HUSKSYNC`, ArcDuels waits for the successful
login-sync completion event before capture, arena preparation, comparison, or
recovery. A transfer or fresh login therefore cannot race duel inventory logic
against network player-data application. A `NONE` arena backend never claims
or applies an origin snapshot.

Join-time recovery waits for the same HuskSync completion signal and then an
additional configurable stabilization window before applying an unclaimed
snapshot. This prevents a late network inventory load from replacing the
restored state:

On return, ArcDuels compares storage, armor, off-hand, cursor, and selected slot
after the configured settle delay. If they already match, it performs no
inventory setter, restores only the remaining player state and origin location
when needed, and then claims the exact row. If they differ, it applies, verifies,
and saves the origin snapshot before claiming it.

The value is bounded to `0..1200` ticks. Recovery remains locked and unclaimed
until application, exact verification, playerdata save, and archival succeed.

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

During a graceful Paper shutdown, ArcDuels applies every local online snapshot,
saves playerdata, and waits for already-started active-to-archive transactions
before the storage pool closes. The wait is deliberately bounded; a timeout or
database failure leaves the active snapshot unclaimed and retryable on the next
join instead of discarding recovery authority:

```yaml
shutdown:
  recovery-timeout-ms: 5000 # 100..30000
```

The bundled starter kits are `classic`, `axe`, `archer`, `uhc`, `tank`, `sumo`,
and `boxing`. Sumo and hit-race objectives use their controlled kits only. The
UHC selection starts with natural regeneration disabled; every
setting remains visible before the challenge is sent.

`/duel` opens the main hub. The opponent picker includes players from every
ProxyARC backend and shows their current server. Challenge setup is deliberately hierarchical:
opponent → objective → loadout → rules → arena → confirmation. The arena step
supports automatic routing or one exact arena on one exact backend. Rules include
BO1/BO3/BO5, ranked kit matches, sudden-death time, projectiles, consumables,
ender pearls, natural regeneration, and KOTH capture time. The recipient sees
the selected rules before accepting. Boxing ends at a configurable total-hit
target; Combo ends at a configurable unanswered-hit streak, reset by the
opponent's next direct melee hit. Both disable health damage, projectiles,
consumables, pearls, regeneration, and sudden death. Own-inventory matches
remain unranked.

Player names in duel chat cards are interactive. Hovering shows rating, wins,
losses, win rate, and streak; clicking runs `/duel <player>` and opens that
player's challenge setup. Untrusted names are inserted as plain text, never as
MiniMessage markup or command syntax.

The main menu has one separate history entry. It records the complete accepted
rules, exact server and arena, score, completion reason, ratings, and last-known
names. Selecting a result opens the head-to-head record. For
`rematch-window-seconds` (default `180`, bounded to `30..900`) either participant
can request the exact same rules and arena; the other participant confirms with
the same action. A removed arena never silently falls back to another one.

The final rules screen has an optional saved-setups entry. Each player owns five
MySQL-backed slots. An empty slot saves the current draft; existing slots can be
applied, overwritten, or deleted. A missing kit blocks application. A missing
exact arena requires an explicit right click to switch that setup to automatic
arena selection. The ordinary opponent → objective → loadout → rules → confirm
path receives no extra page or required click.

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
- `/duel return` — accept the pending post-match return to the origin backend;
- `/duel stats [network-online-player]`;
- `/duel top` — open the global leaderboard.
- `/duel history` — open personal match history and head-to-head records;
- `/duel rematch [match-id]` — request the latest or selected exact rematch;
- `/duels admin status` — active arenas and FIFO waiters;
- `/duels admin recover <online-player>` — claim an exact pending recovery, or
  replay the newest claimed snapshot as an administrator.

Player commands require `arcduels.use`, granted by default. Administrative
commands require `arcduels.admin`, granted to operators by default.
`arcduels.bypass` lets trusted staff use otherwise blocked commands while their
duel state is locked; it is granted to operators by default.

During a match, chat/reply commands plus `/duel`, `/duel leave`, and `/duel stats`
remain available. Other commands are blocked to prevent external
teleport, inventory, and state plugins from breaking match isolation.

When WorldGuard is installed, enabled arenas are sampled at load time and a
warning names points covered by a PvP denial. ArcDuels cancels WorldGuard's
denial event only when both players belong to the same active duel and remain
inside that arena. Spawn protection and unrelated combat remain unchanged.
