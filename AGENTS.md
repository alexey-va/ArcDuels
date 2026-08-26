# AGENTS.md — ArcDuels

ArcDuels owns duel rules, arena orchestration, its MySQL schema, and player
presentation. Shared infrastructure comes from the pinned `arc-core` composite
build.

Before adding network, lifecycle, filesystem, Paper recovery, transfer,
teleport, localization, or platform-test infrastructure, search
`arc-core/docs/shared-primitives.md` and use its typed owner. A feature-local
regex, replay map, raw Gson listener, Bungee payload encoder, teleport exception,
player snapshot format, or MockBukkit coordinate is a duplication defect.

Code should be agentic-first: one searchable owner per mechanism, explicit
types for identifiers/outcomes, KDoc for lifecycle/thread/failure ordering,
bounded diagnostics without raw payloads, and tests named after observable
contracts. Preserve wire/database compatibility explicitly; legacy readers are
decode-only and new writes use the current shared format.

Paper tests depend on `ru.arc:arc-core-paper-testing` and open
`MockBukkitTestRuntime`. Keep pure domain tests free of Bukkit. The normal gate
is `./gradlew testAll :paper:shadowJar`; the MySQL integration gate is
`./gradlew :storage-mysql:integrationTest` when Docker is available.
