# Notice

LapisServe
Copyright 2026 jollyblade (bladestech)

Licensed under the Apache License, Version 2.0 (see [LICENSE](LICENSE)).

## Not affiliated
LapisServe is an independent, unofficial project. "Minecraft" is a trademark of Mojang Synergies AB. Paper, Purpur, Pumpkin, Modrinth,
Eclipse Temurin/Adoptium and Alpine Linux are the property of their respective owners.

## What this repository does not contain
This repository does not contain Java runtimes, server software, plugins, or any other project's logos. They are downloaded at run time by the
app, directly from:

| Item | Source |
| --- | --- |
| Vanilla server | Mojang (`piston-meta.mojang.com`) |
| Paper server | PaperMC (`fill.papermc.io`) |
| Purpur server | PurpurMC (`api.purpurmc.org`) |
| Pumpkin server | `github.com/Pumpkin-MC/Pumpkin` releases (GPL-3.0) |
| Java runtime | Eclipse Temurin via Adoptium (`api.adoptium.net`) |
| C runtime (musl, libgcc, libstdc++, zlib) | Alpine Linux package repository |
| Plugins | Modrinth (`api.modrinth.com`), subject to each plugin's own license |
| Paper and Pumpkin logos | Each project's official GitHub repository, cached on the device |

LapisServe does not redistribute any of these. Pumpkin is GPL-3.0 and is only downloaded and run as a separate program, never linked into this app.

## Logos
Logos are shown only to identify the software a server runs. They are fetched on the device rather than committed here. Vanilla uses a generic
icon because Mojang publishes no hotlinkable logo and restricts reuse of the Minecraft logo. Purpur currently publishes only an SVG, which the app
does not render, so it uses a generated icon. The LapisServe app icon and files in `branding/` are original artwork by the project author and are
covered by this repository's license.

If an owner objects to the current use of a logo, please open an issue and it will be removed.

## Libraries compiled into the app
All are licensed under the Apache License 2.0 unless noted.

- AndroidX (Core, Activity, Lifecycle) and Jetpack Compose (UI, Foundation, Animation, Material 3, Material Icons Extended), Google
- Kotlin standard library, kotlinx.coroutines and kotlinx.serialization, JetBrains
- Apache Commons Compress, The Apache Software Foundation

## Modrinth
Plugin search and downloads use Modrinth's public API, with a User-Agent that identifies this project. Plugins are third-party software: review a
plugin before installing it, as it runs inside your server.
