# Changelog

All notable changes to LapisServe. Versions follow [Semantic Versioning](https://semver.org).

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
