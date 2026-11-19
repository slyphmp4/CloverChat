# CloverChat compatibility: Minecraft 26.3

This port keeps the existing Java 11-compatible plugin bytecode and the
legacy Bukkit/Paper compilation target. A second **Java 25 compilation**
checks the same source code against the Paper 26.3 API without removing
the existing Paper 26.2 API check.

## Automated verification

Run `./gradlew clean build --no-daemon --dependency-verification=strict`.

The `check` task includes:
- JUnit tests, including Adventure-to-plain-text chat conversion.
- Compilation against the Paper 26.2 API (Java 25).
- Compilation against the Paper 26.3 API (Java 25).

GitHub Actions additionally starts real Paper **26.2** and **26.3** servers
with the shaded CloverChat JAR, verifies the plugin's enable/disable lifecycle
and Redis connection, and checks startup logs for linkage and classloading
errors. This is a startup smoke test, **not** an end-to-end player chat test.

## Manual integration matrix

Use a staging server and back up `plugins/CloverChat/` first.

1. On Paper 26.3 with two connected players: send ordinary, colored,
   global, local, and permission-gated group messages; inspect formatting,
   links, hover text, mentions, and chat censorship.
2. Confirm that there are no duplicated or vanished chat messages and that
   plain chat text does not expose Adventure component debug formatting.
3. Exercise `/m`, quick private messages, cooldowns, `/cloverchatreload`,
   and message inspection with and without the associated permissions.
4. Install PlaceholderAPI and CloverBadges, verify placeholders, prefixes
   and badge colors; also test their absence.
5. Connect two servers through each enabled proxy-sync transport. Verify
   delivery exactly once and rejection of invalid/expired messages.
6. Test on **Fabric 26.3 + matching Cardboard** separately. Inspect which
   events Cardboard emits (`AsyncPlayerChatEvent` and/or `PlayerChatEvent`),
   check for duplicate messages, and ensure chat is handled without
   thread violations. A Paper smoke test cannot establish Cardboard support.
7. Restart servers, confirm saved YAML configurations and audit data are
   preserved, and repeat a basic chat test after restart.

## Important compatibility notes

- Keep `plugin.yml`'s old `api-version`: raising it to 26.3 would
  unnecessarily prevent installation on older versions.
- The Paper 26.3 library belongs only to the compile-time verification
  configuration, **not** inside the shaded JAR. Minecraft/Paper API classes
  must continue to be supplied by the server.
- Redis/Lettuce, database drivers, and their transitive dependencies
  remain shaded from explicit build dependencies; do not upgrade them
  without refreshing Gradle dependency locks, checksums, and CI coverage.
- Do not publish a stable release until the Cardboard-specific manual
  integration checks have also passed for the deployment target.
