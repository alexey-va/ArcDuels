# Roadmap

## 0.1 — playable duel vertical slice

- challenge, accept, deny and cancel flows;
- kit and own-inventory loadout policies;
- FIFO arena reservation and durable MySQL snapshot/restore;
- countdown, combat, completion and recovery states;
- MySQL-gated gameplay with durable statistics and recovery;
- optional Redis result broadcasts and leaderboard invalidation;
- paginated duel browser, kit picker and global top-100 leaderboard inventories;
- casual/ranked BO1 and BO3 selection;
- titles, action bars, sounds, particles and damage-free winner fireworks;
- bounded arenas, command isolation and exact player-state restoration.
- objective frames wired through the match state machine for future KOTH.

## 0.2 — arena modes and operations

- Lands-inspired main hub and focused challenge, mode, kit, queue/status,
  statistics, leaderboard, help, and administration branches;
- Russian and English client-locale bundles with strict key parity;
- six bundled starter kits plus own-inventory matches;
- elimination, king-of-the-hill, and sumo objectives;
- BO1/BO3/BO5, ranked kit matches, sudden death, and combat modifiers;
- bounded KOTH zones, non-overlapping arena validation, FIFO arena handoff,
  per-arena loadout policies, and in-game arena setup GUI and commands;
- cross-server player discovery, challenge transfer and compatible arena routing;
- HuskSync-aware pre-start readiness gating;
- crash-safe MySQL player-state escrow with retained restore history.

## 0.3 — hit-race modes and configurable presentation

- Boxing total-hit and Combo unanswered-streak objectives;
- per-arena objective allowlists in the admin GUI and Redis capacity routing;
- controlled boxing kit and objective-specific rule targets;
- resource-pack-neutral GUI roles with optional ItemsAdder CustomModelData overlays.

## Next — competitive operations

- ranked queues and ELO seasons;
- rematches, spectators and match history;
- PlaceholderAPI expansion and moderation/audit commands;
- orphaned-match reconciliation and recovery audit history.

## Later

- team formats;
- web/API read model for leaderboards and match history.
