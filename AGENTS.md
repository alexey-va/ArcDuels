# AGENTS.md — ArcDuels

ArcDuels owns duel rules, arena orchestration, its MySQL schema, and player
presentation. Shared infrastructure comes from immutable `arc-core` releases;
an explicit local composite can substitute the same coordinates during core
development.

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

Paper tests depend on `ru.ruscrafting.arc:arc-core-paper-testing` and open
`MockBukkitTestRuntime`. Keep pure domain tests free of Bukkit. The normal gate
is `./gradlew testAll :paper:shadowJar`; the MySQL integration gate is
`./gradlew :storage-mysql:integrationTest` when Docker is available.
Both gates run as separate CI jobs on pull requests and `main`; do not fold the
container suite into unit tests or turn Docker unavailability into a skip.

Standalone and CI builds resolve the pinned public release from RusCrafting
Reposilite and do not require GitHub access to the core repository. To verify an
uncommitted core API without copying sources, opt into the composite with
`-ParcCoreDir=/absolute/path/to/arc-core`; the path must contain its own
`settings.gradle.kts`.
