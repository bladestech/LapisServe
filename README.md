<p align="center">
  <img src="branding/icon-512.png" width="120" alt="LapisServe logo"/>
</p>

<h1 align="center">LapisServe</h1>

<p align="center">
  Host Minecraft servers on an Android phone.<br/>
  Material 3 Expressive UI · per-server resource controls · nothing bundled.
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: Apache-2.0" src="https://img.shields.io/badge/license-Apache%202.0-blue.svg"/></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-green.svg"/>
</p>

LapisServe downloads everything it needs at run time from the official sources: server software, Java, plugins and logos are never stored
in this repository or bundled in the APK.

**Supported servers:** Vanilla (Mojang) · Paper · Purpur · Pumpkin (experimental, Rust, ARM64 only)

## Install
Grab the latest APK from the [Releases](../../releases) page and install it (you'll need to allow installs from your browser or file manager).
Releases include a SHA-256 checksum. LapisServe is not on the Play Store; see "Why `targetSdk 28`" below.

You'll want a phone with an ARM64 CPU and at least 4 GB of RAM. Open **Device tuning** inside the app on first run: it checks the Android
settings that decide whether your server survives in the background.

## Features
- **Per-server settings:** RAM (capped at 90% of device memory; the slider turns amber then red toward unsafe values and warns when current usage
  plus your allocation exceeds free RAM), per-core pinning, scheduling priority, garbage collector, Java version, view and simulation distance,
  network, behaviour, and one-tap presets.
- **Live meters:** memory and CPU shown against what is allocated to each server.
- **Device tuning:** checks developer options and battery settings, and shows your processor model and core layout.
- **Plugins (optional):** search and install plugins from [Modrinth](https://modrinth.com) for Paper and Purpur, with required dependencies and
  SHA-512 verification. The rest of the app works without it.
- **Console:** filters, command history, copy and share log.

## How it works
- Server jars come from Mojang, PaperMC, PurpurMC and Pumpkin's GitHub releases, with checksums verified.
- **Java:** Android's libc can't run stock JVMs, so LapisServe downloads Eclipse Temurin for Linux/musl from Adoptium and a small musl C runtime
  from Alpine's package repository, then starts the JVM through the musl loader.
- **DNS:** musl has no resolver configuration on Android, so the app resolves common hostnames itself and gives the JVM a hosts file
  (`-Djdk.net.hosts.file`, Java 9+). Extra hostnames for plugins can be added per server.
- **Pumpkin config:** Pumpkin writes a full `pumpkin.toml` on first run; LapisServe lets it do that, then patches only the keys it manages.
- **Why `targetSdk 28`:** Android 10+ blocks executing downloaded files for apps targeting 29 or higher. This is the same trade-off Termux makes,
  and it's why the app is distributed outside the Play Store.

## Known limitations
- The Java runtime works on ARM64 phones. On the x86_64 emulator, musl binaries are killed by Android's seccomp filter, so the emulator can show
  the UI but not run Java servers. Arm64 emulation on x86_64 hosts isn't supported by the Android emulator.
- Online-mode authentication depends on the pre-resolved hostname list; unusual hosts must be added in the server's network settings.
- Java 8 servers (Minecraft 1.16 and older) aren't supported by the DNS workaround.
- Pumpkin is experimental upstream and only publishes an ARM64 Android build.

## Build
Requires JDK 17+ (21 recommended) and the Android SDK (API 36).

```bash
./gradlew :app:assembleDebug        # debug APK in app/build/outputs/apk/debug
./gradlew :app:testDebugUnitTest    # unit tests
```

Release signing reads `keystore.properties` (git-ignored) or the `LAPIS_KEYSTORE_*` environment variables; without them the release build falls
back to the debug key. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Privacy
LapisServe has no analytics or telemetry and no account. It only contacts the sources listed in [NOTICE.md](NOTICE.md), when you create or start a
server, browse plugins, or when the app fetches a project logo.

## License and trademarks
LapisServe is licensed under the [Apache License 2.0](LICENSE). It is an independent project and is not affiliated with or endorsed by Mojang,
Microsoft, PaperMC, PurpurMC, the Pumpkin project or Modrinth. See [NOTICE.md](NOTICE.md) for third-party content, logos and trademarks.
