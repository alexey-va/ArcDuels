# Architecture

## Boundaries

The domain module owns duel truth. Platform code adapts Paper events to domain
commands and domain events back to player-facing effects. Storage and network
modules implement ports and never decide match outcomes.

```text
Paper commands/events -> domain services -> domain events -> Paper presentation
                              |                    |
                              v                    v
                       statistics port       network event port
                              |                    |
                          optional MySQL       optional Redis
```

## Runtime modes

- `LOCAL`: no external services; in-memory statistics and local announcements.
- `MYSQL`: durable statistics and leaderboards shared through the database.
- `NETWORK`: MySQL plus Redis fan-out for fast invalidation and announcements.

Optional infrastructure is fail-soft for presentation and cache invalidation,
but match-result persistence is fail-closed when durable statistics are enabled.
This avoids showing a win that was silently lost from the global ranking.

## Match modes

`KIT` and `OWN_INVENTORY` are launch modes. KOTH is modeled as a future match
objective rather than another storage or transport concern. An objective may
observe ticks and eliminations and request completion through the same state
machine; it cannot mutate player statistics directly.
