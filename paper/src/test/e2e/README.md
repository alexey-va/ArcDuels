# Real Paper match tests

GitHub Actions runs `TEST_TIMEOUT=120000 ./gradlew :paper:plugwrightTest` on
Java 25 with Paper 1.21.11, Node 22.14.0 and Plugwright 2.0.4. A disposable
MySQL 8.4 service uses the synthetic credentials in `fixtures/config.yml`.
The plugin applies its real migrations and persists both inventories before
starting a match. Redis is unnecessary for these same-server matches.

The fixture has one enabled arena on the flat world's grass surface at y=-60.
Players return to their original locations after the normal countdown and
celebration. Three invitation scenarios cover decline, cancellation and expiry.
Three match scenarios cover:

- Classic kit selection, acceptance, real melee damage and elimination, exact
  original inventories (including armor and offhand), XP and position recovery.
- Own-inventory acceptance, consuming a golden apple and forfeiting; recovery
  preserves the consumption instead of refunding the item.
- Disconnecting during a kit fight, reconnecting with the original inventory,
  and reconnecting again without retaining kit equipment or duplicating items.

All matches use the same arena sequentially, proving it becomes available again.
After Paper shuts down, CI checks three durable results with the corresponding
end reasons, six archived player snapshots (four with inventory replacement),
and zero pending snapshots. Skipped or silently failed match tests cannot satisfy
these database assertions. Fast JVM and MySQL/Redis integration suites remain
separate jobs; Paper logs are retained on every outcome.

Paper binds to localhost:25565 and recreates its world/plugin data beneath
`paper/build/plugwright`. The database must also be disposable and empty for
each full run. Run this stateful fixture in GitHub CI; do not start local
Docker/Testcontainers or connect it to a managed database. Cross-server
HuskSync/Redis transfers, server-crash recovery and other objectives are not
covered by this same-server suite.

Known client limitation (verified 2026-09-07): minecraft-data 3.116.0 describes
1.21.11 dust particles as four floats, while the pinned Paper runtime encodes
an integer color and a float scale (`DustParticleOptions.STREAM_CODEC`). The
bot logs `PartialReadError` for arena boundary dust. Combat damage, results,
exact inventory recovery and SQL assertions pass with particles still enabled;
this suite does not certify particle rendering. Do not suppress other decoder
errors or weaken gameplay to hide this client protocol mismatch.
