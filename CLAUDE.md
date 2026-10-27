# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Stempeluhr is a Paper (Minecraft server) plugin for tracking players' work time ("time clock"). Players are clocked in automatically on join and clocked out on quit, via `/ausstempeln`, or when the server stops. Everyone can see a leaderboard with `/arbeitszeit rangliste`; admins can view or set a player's total time with `/arbeitszeit get|set`.

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

- **`Stempeluhr`**: the `JavaPlugin` main class. It is also the `Listener` (join/quit) and the `CommandExecutor` for the player commands `ausstempeln`, `einstempeln` and `schicht`, which are dispatched by `cmd.getName()` in a single `onCommand`. It holds the core logic (`einstempeln`, `ausstempeln`, `formatiere`).
- **`ArbeitszeitBefehl`**: a separate `TabExecutor` for `/arbeitszeit`, registered explicitly in `onEnable`. `rangliste` (top 10 by total time including running shifts, plus the sender's own place if lower) needs only `stempeluhr.use`; `set`/`get` are gated by `stempeluhr.admin` in code, also work from the console, and log every manual change to the server log. Subcommands are matched case-sensitively on purpose (FindSecBugs `IMPROPER_UNICODE`).
- **`StempelDaten`**: in-memory `Map<UUID, Eintrag>` persisted to `plugins/Stempeluhr/daten.yml` under `spieler.<uuid>`. `Eintrag` fields are package-private, and the other classes mutate them directly. A player counts as clocked in iff `schichtStart > 0`. All times are epoch ms; `-1` means "unset".

Key behaviors that span files:

- **Crash recovery:** a repeating task (every 60s) writes a `lebenszeichen` (heartbeat) timestamp for every clocked-in player and saves. If a player joins while their stored entry is still clocked in, the server must have crashed, so the shift is closed at the last heartbeat rather than at "now".
- **Plugin reload:** `onEnable` clocks in players who are already online. `onDisable` clocks everyone out and saves.
- **Time source:** `Stempeluhr` reads time through a swappable `LongSupplier uhr`. Tests inject a controllable clock via `setUhr`. Always use `uhr.getAsLong()` in plugin code, never `System.currentTimeMillis()`.
- **Messages:** all player output goes through MiniMessage with the shared `PREFIX`. User-controlled values (player names, command args) must be passed with `Placeholder.unparsed(...)`, never concatenated into the template, to prevent tag injection (a test covers this).
- **Defensive loading:** invalid UUID keys are skipped, negative `gesamt` values are clamped to 0, and shift durations are clamped to ≥ 0 when the clock goes backwards.
- `plugin.yml` is filtered by Gradle (`${version}` ← `project.version`). Commands and permissions (`stempeluhr.use` default true, `stempeluhr.admin` default op) are declared there.

## Tests

`Stempeluhr` must not be `final`: MockBukkit subclasses the plugin class when loading it.

`StempeluhrTest` uses MockBukkit + JUnit 6. Each test creates a fresh `ServerMock`, loads the plugin and injects an `AtomicLong` clock. Use `vergehen(sekunden)` to advance time and `nachrichten(player)` to drain received chat messages as plain text. Tests are grouped into `@Nested` classes (`Funktion`, `Robustheit`, `Arbeitszeit`, `Rangliste`) and use German `@DisplayName`s.
