# Changelog

All notable changes to LapisServe. Versions follow [Semantic Versioning](https://semver.org).

## [0.3.1] - 2026-11-17

### Added
- **Animated logo.** The LapisServe logo is now drawn by the app itself, so it is sharp at any size and animates at your screen's
  refresh rate. The S draws itself bar by bar with a glowing pen tip.
- **New launch splash.** The S draws itself, its top bar runs to the edge of the screen, and a bright bar sweeps back to reveal your
  servers, which have loaded underneath. Android's own splash now shows just the background colour so the handoff is seamless.
- **Logo loaders** for the two longest waits: setting up a server (the S fills to match the download) and loading the plugin browser.
- **Two-tone logo** in your accent colour, used in the light theme and on empty screens (no servers, plugins or activity yet).

### Notes
- With reduce motion on, the splash and loaders show the logo without animating.

## [0.3.0] - 2026-11-17

### Performance
- **Tick focus.** Java Minecraft servers run almost all game logic on one thread. LapisServe now reads the phone's core layout (from the
  kernel's per-core capacity, so it works for 1+3+4, 2+6, 1+2+5, 4+4, all-big and uniform chips) and, by default, gives the main game-loop
  thread the fastest core to itself while everything else runs on the other fast cores. Under a fixed load of 450 mobs on a Snapdragon 8+ Gen 1:

  | Core layout | Average tick time |
  | --- | --- |
  | Tick focus (new default) | **~10 ms** |
  | Performance cores only | ~14 ms |
  | All cores (0.2.x behaviour) | ~23.5 ms |
  | Efficiency cores only | ~43 ms |

- **Pinning that sticks.** Android resets an app's core affinity as it moves between foreground and background, so LapisServe now checks and
  re-applies it continuously. When Android parks the fastest core to cool down, the game loop moves to the other fast cores until it returns.
- **CPU boost hints.** On phones whose power HAL supports Android's performance-hint API, the tick thread (or Pumpkin's ticker and simulation
  threads) reports its workload so Android raises clocks before a tick runs late. Not every phone supports it; the console says which.
- **JVM tuning.** GC and JIT thread pools sized to the cores the server may use, fewer JIT threads, a larger starting heap to avoid
  grow-and-collect cycles while exploring, compact object headers on Java 25, and an optional ZGC collector (Advanced).
- **Server config tuning** (switchable per server): Paper/Spigot/Bukkit settings from the common optimisation guides (entity activation
  ranges, item merging, spawn limits, cheaper explosions and pathfinding, Alternate Current redstone) and a smaller entity broadcast range.
  Pumpkin gets faster chunk compression and worker counts that match its cores.
- **Core layout picker** (Advanced): Automatic, Tick focus, Performance cores, All cores or Manual. The per-core picker now labels mid-tier
  cores correctly as performance cores and shows each core's clock.

### Added
- **Simple mode (default).** Server settings show what most people need; cores, Java, GC, JVM arguments, DNS hosts and other expert options
  move behind an **Advanced** switch in Settings. A Performance card explains what was tuned automatically.
- **Friendlier server creation.** Choose by goal ("Play with friends", "Pure vanilla", "Extra gameplay options", "Lightweight & Bedrock"), pick
  how many players (which sets the player cap, view distances and a RAM recommendation), choose Survival, Creative or Hardcore, accept the
  EULA, and **Create & start** in one go. The latest version is picked automatically.

### Changed
- **Calmer look.** The glass design is replaced with solid Material 3 surfaces: no blur, no translucent cards, and a faint static background.
  The moving background is now off by default (it can still be turned on in Settings).
- Pumpkin's anonymous telemetry is switched off in the config LapisServe writes, in line with the app's no-telemetry promise.

### Fixed
- Pumpkin's console showed raw colour codes (`[2m…[0m`).

### Tests
- 62 unit tests (core plans for many chip layouts, YAML patching, Pumpkin tuning, colour-code stripping, RAM recommendations).

## [0.2.1] - 2026-11-17

### Added
- **Restart and Force-stop (kill) buttons** beside Start/Stop on a server's Overview. Force stop asks first and explains the risk; it is also
  available while a server is stuck in "Stopping".
- **Plugin detail page.** Tap a plugin (in Browse or Installed) for its description, license, links and every compatible version, grouped into
  Release, Beta and Alpha with dates, downloads, sizes and changelogs. Install any version; betas and alphas ask for confirmation. Installed
  plugins show "Tap for versions" so you can switch or downgrade.
- **Animations.** A living halo around the server icon (ripples while starting, a breathing ring when running, collapsing ripples while stopping,
  a one-time burst when it becomes ready, red after a crash). The Start/Stop button morphs colour, icon and label; the status pill animates its
  label; server cards reorder smoothly; the "New server" button collapses as you scroll; "Stopping" shows progress.

### Fixed
- **Force stop crashed the app.** Killing a server closed its output pipe while it was being read, and the resulting exception was not caught.
  This affected "Force stop" in the menu and the stop-timeout fallback in 0.1.x and 0.2.0.
- **Force stop left the server shown as Running** with 0 MB of RAM and kept its wake locks. The state, event log and wake locks are now cleaned up
  when the process exits.

### Tests
- 47 unit tests (version parsing for Modrinth channels, and a regression test for the closed-pipe crash).

## [0.2.0] - 2026-11-17

### Added
- **A new look.** A glass design system: animated colour backdrop, frosted cards, floating blurred navigation, spring animations, shared-element
  transitions between a server's card and its page, and haptic feedback.
- **Live server vitals.** TPS and tick time (Paper, Purpur and Vanilla), players online and latency (all Java servers, via the server-list ping),
  memory and CPU rings, uptime, world size, and 6-minute history charts. The server's own reply to `tps`/`mspt` is read and kept out of your console.
- **Health insights.** Plain-language advice when TPS drops, memory or CPU is nearly full, the phone is hot, battery is low, or Android is short on memory.
- **Players tab.** Online players with kick, op, ban and whitelist actions, plus manage-by-name for anyone.
- **Activity tab.** One timeline of starts, stops, crashes, joins and backups across all servers, with 24-hour totals.
- **Backups.** Back up worlds, whitelist, ops and bans; restore with validation so a damaged file can never replace a good world.
- **Crash alerts.** A notification when a server stops unexpectedly.
- **Appearance settings.** Light, dark and AMOLED themes, six accent colours, optional wallpaper colours, animated backdrop and reduce-motion switches.
- **Device screen.** Processor model, core layout, Wi-Fi speed, storage, memory budget and a hosting-readiness score.
- **Purpur** and a **Modrinth plugin browser** (shipped in 0.1.x), now with glass styling.

### Changed
- Navigation is now four tabs (Servers, Activity, Device, Settings) with a floating bar; a server has Overview, Console, Players, Plugins and Settings.
- Status-bar icons follow the app theme instead of the phone's.

### Fixed
- Pumpkin settings were never applied: the app edited the wrong file. It now patches the real `pumpkin.toml`, including the Bedrock section.
- The first letters of some toggle rows were clipped.
- Replies to the app's own `mspt` polling could leak into the console.

### Tests
- 41 unit tests, including the server-list-ping protocol against a fake server, TPS parsing for each server type, backup safety (corrupt zips and path traversal) and health insights.

## [0.1.0] - 2026-11-17
First public release: Vanilla, Paper, Purpur and Pumpkin, per-server resource controls, device tuning, Modrinth plugins.
