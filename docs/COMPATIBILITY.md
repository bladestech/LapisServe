# Device compatibility

LapisServe runs real Java and native servers on Android, and phones differ (chipset, vendor battery management, Android version). This page
records what has actually been tested. **Absence from the table means "unknown", not "doesn't work".**

## How to read the status
| Status | Meaning |
| --- | --- |
| ✅ Works | Started, reached the server's ready message, and ran for a while |
| 🟡 Partial | Starts but with problems (see notes) |
| ❌ Fails | Doesn't start or is killed |
| ➖ Not tested | Nobody has tried this combination |

"Works" here means the server reached its ready state and the app's meters, console and settings behaved. It does **not** yet mean long-session
stability or a verified player join unless the notes say so.

## Tested devices
| Device | Chipset | Android | App | Vanilla | Paper | Purpur | Pumpkin | Tested |
| --- | --- | --- | --- | :---: | :---: | :---: | :---: | --- |
| OnePlus CPH2415 | Snapdragon 8+ Gen 1 (SM8475), 8 cores, 7 GB RAM | 15 | 0.1.0, 0.2.0 | 🟡 | ✅ | ✅ | ✅ | 2026-11 |

### Notes
- **OnePlus CPH2415:** the author's phone and the only device tested so far.
  - **Paper 26.3 and Purpur 26.3:** reached their ready message ("Done"). Paper also had a Modrinth plugin installed. No client join was tested.
  - **Pumpkin 0.2.2:** starts, and settings written to `pumpkin.toml` are applied (Java and Bedrock sections).
  - **Vanilla:** reported by the author as working for the most part; not exhaustively verified, hence 🟡.
  - Java servers needed the app's DNS workaround (a pre-resolved hosts file) because Android has no `/etc/resolv.conf` for the JVM.

## Known not to work
| Environment | Why |
| --- | --- |
| Android emulator on an x86_64 PC | Android's seccomp filter kills musl-based binaries (the Java runtime). The UI works; Java servers don't. |
| Non-ARM64 devices | Pumpkin only publishes an ARM64 Android build |

## Add your device
Open a [device report](../../../issues/new?template=device_report.yml) with your phone, Android version and what you tried. Include the
console log from **Console → Share log** if something failed. Reports with logs make fixes much easier.

Tips that often decide whether a server survives in the background:
- In the app, open the **Device** tab and apply its suggestions.
- Allow unrestricted battery use for LapisServe.
- On Android 12 and newer, turn off *Disable child process restrictions* in Developer options if your phone offers it.
