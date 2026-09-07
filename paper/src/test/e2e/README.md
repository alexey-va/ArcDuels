# Real Paper invitation tests

Run `./gradlew :paper:plugwrightTest` with Java 25. Gradle downloads Paper
1.21.11, Node 22.14.0 and Plugwright 2.0.4. The temporary server binds to
127.0.0.1:25565 and is recreated under `paper/build/plugwright` on each run.
Run local Paper suites sequentially to avoid port conflicts.

Two clients exercise objective selection, loadout selection, confirmation,
delivery to the opponent, decline, cancellation, challenge expiry and a fresh
invitation after each terminal outcome. Assertions use new messages when
repeating actions.

The fixture uses a 10-second challenge timeout so expiry coverage stays fast
without depending on scheduler timing at the five-second lower bound.

MySQL and Redis are disabled for these local invitation flows. Match startup,
inventory restoration and cross-server transfers need durable storage and are
not claimed as covered here. The existing JVM and storage integration suites
remain in place. GitHub Actions runs this suite separately and retains logs.
