# Security policy

## What the app does that you should know about
LapisServe downloads and **executes** programs on your device: a Java runtime, server software (Vanilla, Paper, Purpur, Pumpkin) and, if you
choose, plugins from Modrinth. Servers listen on your local network. Please:

- Only install plugins you trust: a plugin runs with the server's permissions.
- Don't expose a server to the internet without understanding the risks (port forwarding, online-mode, whitelist).
- Downloads are verified against checksums published by their sources (SHA-1/256/512 or MD5, whichever the source provides) before use.

## Supported versions
Only the latest release receives fixes.

## Reporting a vulnerability
Please **don't** open a public issue for security problems. Use GitHub's private reporting: open the repository's **Security** tab and choose
**Report a vulnerability**. Include the app version, your device and Android version, and steps to reproduce. You should get a response within
a week.

## Scope
In scope: this app's code (download verification, file handling, command construction, DNS hosts handling, plugin installation).
Out of scope: vulnerabilities in Minecraft server software, Java, Android itself or Modrinth plugins. Report those to the respective projects.
