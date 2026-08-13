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

## 0.2 — competitive operations

- ranked queues and ELO seasons;
- rematches, spectators and match history;
- arena administration GUI (the command editor is complete);
- PlaceholderAPI expansion and moderation/audit commands;
- orphaned-match reconciliation and recovery audit history.

## Later

- KOTH objectives and team formats;
- Velocity-aware matchmaking and server transfer handoff;
- web/API read model for leaderboards and match history.
