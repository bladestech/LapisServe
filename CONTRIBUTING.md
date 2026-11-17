# Contributing

Thanks for helping. Bug reports, fixes and ideas are all welcome.

## Before you start
- Search existing issues first.
- For anything bigger than a small fix, open an issue to talk it through before writing code.
- By contributing, you agree your work is licensed under the [Apache License 2.0](LICENSE).

## Reporting bugs
Use the bug report form. The most useful details are your **phone model and Android version**, the **server type and version**, and the **console log**
(Console tab, **Share log**). Please remove anything private from logs first.

## Development setup
1. JDK 21 and the Android SDK (API 36). Android Studio is optional.
2. Create `local.properties` with `sdk.dir=...` (Android Studio does this for you).
3. Build and test:
   ```bash
   ./gradlew :app:testDebugUnitTest :app:assembleDebug
   ```

### Testing on a device
Java servers only run on **ARM64 hardware**: the x86_64 emulator can show the UI but Android's seccomp filter kills musl binaries there. Test server
changes on a real phone (wireless debugging works well). Pumpkin is ARM64-only upstream.

## Code style
- Kotlin official style; Jetpack Compose with Material 3 Expressive components.
- Keep UI state hoisted and logic in `engine/`, `modrinth/`, `providers/` and `runtime/` rather than in composables.
- Add unit tests for pure logic (config patching, argument building, parsing).
- Never commit secrets, keystores, downloaded runtimes, server jars, plugins or another project's logos.

## Release signing (maintainers)
Releases can be built by CI from a version tag once you set the repository variable `RELEASE_FROM_CI=true`; until then the release job is skipped and releases are built locally. The signing key is provided through repository secrets (`LAPIS_KEYSTORE_B64`,
`LAPIS_KEYSTORE_PASSWORD`, `LAPIS_KEY_ALIAS`, `LAPIS_KEY_PASSWORD`). Locally, put the same values in a git-ignored `keystore.properties`:

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Pull requests
Keep them focused, describe what changed and how you tested it, and make sure `./gradlew :app:testDebugUnitTest` passes.
