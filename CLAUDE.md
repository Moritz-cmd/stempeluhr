# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Stempeluhr is a Paper (Minecraft server) plugin for tracking players' work time ("time clock"). Players are clocked in automatically on join and clocked out on quit, via `/ausstempeln`, after 5 minutes without moving, or when the server stops. Everyone can see a leaderboard with `/arbeitszeit rangliste`; admins can view or set a player's total time with `/arbeitszeit get|set`.

The codebase is written in **German**: identifiers, comments, player-facing messages, log lines, test names. New code should follow the same convention (e.g. `einstempeln`/`ausstempeln`, `Eintrag`, `speichern`/`laden`).

## Commands

Gradle wrapper (Gradle 9.1), Java 25 toolchain. On Windows use `gradlew.bat` (or `./gradlew` in Git Bash).

- Build the plugin jar (also runs tests and SpotBugs): `./gradlew build` → `build/libs/`
- Run all tests: `./gradlew test`
- Run a single test class or method: `./gradlew test --tests "de.shyrshi.stempeluhr.StempeluhrTest"` or `--tests "*StempeluhrTest\$Arbeitszeit.adminSetzt"` (nested classes use `$`)
- Static/security analysis: `./gradlew spotbugsMain` (report at `build/reports/spotbugs/main.html`)

SpotBugs runs with max effort, `Confidence.LOW`, the FindSecBugs plugin, and `ignoreFailures = false`, so **any finding fails `build`/`check`**. Exclusions go in `config/spotbugs/exclude.xml`, and each one needs a justification comment. Never exclude security findings wholesale. Test sources are not analyzed (`spotbugsTest` is disabled).

The `paper-api` version (`26.3.build.<nr>-<channel>`) has to match the Paper version MockBukkit (`mockbukkit-v26.3`) was built against. Bump them together.

## Architecture

Three classes in `de.shyrshi.stempeluhr`:

- **`Stempeluhr`**: the `JavaPlugin` main class. It is also the `Listener` (join/quit/move) and the `CommandExecutor` for the player commands `ausstempeln`, `einstempeln` and `schicht`, which are dispatched by `cmd.getName()` in a single `onCommand`. It holds the core logic (`einstempeln`, `ausstempeln`, `formatiere`) and the transient, non-persisted state: `letzteBewegung` (last position change per player), `bewegungErforderlich` (players who must move before clocking in again), and `nachAbsturzBeendet` (players to notify about a crash-closed shift).
- **`ArbeitszeitBefehl`**: a separate `TabExecutor` for `/arbeitszeit`, registered explicitly in `onEnable`. `rangliste` (top 10 by total time including running shifts, plus the sender's own place if lower) needs only `stempeluhr.use`; `set`/`get` are gated by `stempeluhr.admin` in code, also work from the console, and log every manual change to the server log. Subcommands are matched case-sensitively on purpose (FindSecBugs `IMPROPER_UNICODE`).
- **`StempelDaten`**: in-memory `Map<UUID, Eintrag>` persisted to `plugins/Stempeluhr/daten.yml` under `spieler.<uuid>`. `Eintrag` fields are package-private, and the other classes mutate them directly. A player counts as clocked in iff `schichtStart > 0`. All times are epoch ms; `-1` means "unset".

Key behaviors that span files:

- **Crash recovery:** a repeating task (every 60s) writes a `lebenszeichen` (heartbeat) timestamp for every clocked-in, non-idle player and saves. Any shift still open in the file must come from a crash. `onEnable` closes those shifts at `max(lebenszeichen, schichtStart)` (`schliesseVerwaisteSchichten`) and tells the player on their next join. `onJoin` repeats the same check as a fallback.
- **Inactivity clock-out:** only a position change counts as movement. Camera rotation does not. A shift ends exactly `INAKTIVITAET_MS` (5 min) after the last movement, i.e. it is backdated to `letzteBewegung + 5 min`, not "now". The check runs from a 1-second task and also lazily in `onMove`, the heartbeat, and each player command, so a command or move after the deadline cannot extend the shift. `onQuit` and `onDisable` cap the end time the same way. Once a player is timed out, they are put in `bewegungErforderlich`: `/einstempeln` and auto-clock-in on join are refused until the player moves.
- **Plugin reload:** `onEnable` clocks in players who are already online. `onDisable` clocks everyone out and saves.
- **Time source:** `Stempeluhr` reads time through a swappable `LongSupplier uhr`. Tests inject a controllable clock via `setUhr`. Always use `uhr.getAsLong()` in plugin code, never `System.currentTimeMillis()`.
- **Messages:** all player output goes through MiniMessage with the shared `PREFIX`. User-controlled values (player names, command args) must be passed with `Placeholder.unparsed(...)`, never concatenated into the template, to prevent tag injection (a test covers this).
- **Persistence safety:** `laden()` is strict. A YAML parse error, a wrong structure, a missing `gesamt`, or a non-integer time value throws `IllegalStateException` and sets `speichernGesperrt`, so a damaged file is never overwritten with empty data. (`YamlConfiguration.loadConfiguration` is avoided on purpose because it swallows parse errors.) Only invalid UUID keys are skipped, and negative `gesamt` values are clamped to 0. `speichern()` writes to a temp file, copies the previous file to `daten.yml.bak`, then does an atomic move. Shift durations are clamped to ≥ 0 when the clock goes backwards.
- `plugin.yml` is filtered by Gradle (`${version}` ← `project.version`). Commands and permissions (`stempeluhr.use` default true, `stempeluhr.admin` default op) are declared there.

## Tests

`Stempeluhr` must not be `final`: MockBukkit subclasses the plugin class when loading it.

All tests use MockBukkit + JUnit 6. Each test creates a fresh `ServerMock`, loads the plugin and injects an `AtomicLong` clock. Scheduled tasks are not relied on: tests call `pruefeInaktivitaet()`, `lebenszeichen()` or `schliesseVerwaisteSchichten()` directly (all package-private for this reason).

- `StempeluhrTest`: main behavior, grouped into `@Nested` classes (`Funktion`, `Robustheit`, `Arbeitszeit`, `Rangliste`) with German `@DisplayName`s. Use `vergehen(sekunden)` to advance time and `nachrichten(player)` to drain received chat messages as plain text.
- `InaktivitaetTest`: idle clock-out. Its `bewegen(player, position)` fires a `PlayerMoveEvent` (`position=false` sends a rotation-only move).
- `StempelDatenPersistenzTest`: corrupt or invalid `daten.yml` handling and the save lock.

CI (`.github/workflows/build.yml`) runs `./gradlew build` on pushes to `main` and on PRs. Pushing a `v*` tag also publishes `Stempeluhr.jar` to a GitHub release, so bump `version` in `build.gradle.kts` before tagging.
