# Compatibility

LapisServe runs real Java and native servers on Android. What decides how well it runs is mostly the **chipset** (speed, core layout,
performance-hint support) and the **Android version** (process and syscall restrictions), not the phone brand, so results are recorded that way.
**Absence from a table means "unknown", not "doesn't work".**

## How to read the status
| Status | Meaning |
| --- | --- |
| ✅ Works | Started, reached the server's ready message, and ran well |
| 🐢 Slow | Works, but too slow to be enjoyable |
| 🟡 Partial | Starts but with problems (see notes) |
| ❌ Fails | Doesn't start or is killed |
| ➖ Not tested | Nobody has tried this combination |

## Chipsets
| Chipset | Android | Vanilla | Paper | Purpur | Pumpkin | CPU boost hints | Tested |
| --- | --- | :---: | :---: | :---: | :---: | :---: | --- |
| Snapdragon 8 Elite Gen 5 | 17 | ✅ | ✅ | ✅ | ✅ | ✅ | 0.3.x, 2026-11 |
| Snapdragon 8+ Gen 1 (SM8475) | 15 | ✅ | ✅ | ✅ | ✅ | ❌ (not offered by the power HAL) | 0.1.0 – 0.4.0, 2026-11 |
| Exynos 8890 | 16 (custom ROM) | 🐢 | 🐢 | 🐢 | ✅ | ❌ (too old) | 0.3.x, 2026-11 |

### Notes
- **Snapdragon 8 Elite Gen 5:** everything works, including CPU boost hints.
- **Snapdragon 8+ Gen 1:** the main development device.
  - Core pinning works without root (1 prime + 3 performance + 4 efficiency). Tick focus measured ~2.3× lower tick time than unpinned
    under load. Android periodically resets affinity and parks the prime core when warm; the app handles both.
  - Older Minecraft versions (0.4.0): Vanilla 1.8.9 (Java 8, with a status ping), Paper 1.12.2 (Java 8, downloads over HTTPS) and
    Vanilla 1.17.1 (Java 17, with a status ping) all reached "Done".
- **Exynos 8890:** everything starts, but the Java servers are very slow on this 2016 chip. Pumpkin, being native and multi-threaded, runs
  decently.

## Minecraft versions
Since 0.4.0 the app picks the Java version each Minecraft version needs and downloads it on first use:

| Minecraft | Java | Source |
| --- | --- | --- |
| 1.16.5 and older | 8 | OpenJDK from Alpine Linux |
| 1.17 – 1.20.4 | 17 | OpenJDK from Alpine Linux |
| 1.20.5 – 1.21.x | 21 | Eclipse Temurin |
| 26.x | 25 | Eclipse Temurin |

Java 11 is also available in a server's settings for software that wants it. Vanilla 1.7 – 1.18 gets Mojang's Log4Shell mitigation automatically.

## Known not to work
| Environment | Why |
| --- | --- |
| Android emulator on an x86_64 PC | Android's syscall filter kills the musl-based Java runtime there. The UI works; Java servers don't. |
| Non-ARM64 devices | Pumpkin only publishes an ARM64 Android build |

## Add your device
Open a [device report](../../../issues/new?template=device_report.yml) with your chipset, Android version and what you tried. Include the
console log from **Console → Share log** if something failed. Reports with logs make fixes much easier.

Tips that often decide whether a server survives in the background:
- In the app, open the **Device** tab and apply its suggestions.
- Allow unrestricted battery use for LapisServe.
- On Android 12 and newer, turn off *Disable child process restrictions* in Developer options if your phone offers it.
