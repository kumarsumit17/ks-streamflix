---
type: Policy
title: Constraints
description: The platform constraints every change to KS StreamFlix must keep.
tags: [constraints, policy]
status: draft
---

# Constraints

## Android and Google TV apps

- **D-pad first.** Every screen must be usable with the remote's arrow, select, and back keys. Restore focus to the last item when returning to a screen. Back from the player returns to the exact row and item.
- **Lean means lean.** Keep the release APK small (its size is [reported](quality-budgets.md), not limited). Ask before adding a dependency. Prefer what's already in Media3, Compose, and Room.
- **Low-RAM TVs.** Load images at their display size. Never keep a full-resolution bitmap in a row. Cap the decoded-image cache.
- **10-foot UI.** Overscan-safe margins are 48dp horizontal, 27dp vertical.
- **Separate phone and Google TV apps** (KS-Suite [platform folders](https://github.com/kumasumit/ks-suite/blob/main/specs/platform-folders.md)): `mobile/android` and `tv/google-tv`, same package name, version and keystore, the TV versionCode plus 1,000,000,000. The TV app requires `android.software.leanback`, does not require `android.hardware.touchscreen`, has a banner (320×180) for the TV home row and the `com.ks.streamflix.FORM_FACTOR` meta-data; the layout follows the app, not the device.
- Watch progress and favourites live on the server, per user. Any local database in the apps needs a `Migration` plus an exported schema for every schema change.

## Server

- The database schema has a version number and a migration step for every change; a library is never lost on upgrade.
- Passwords are stored only as PBKDF2 hashes; session tokens come from `SecureRandom`. No telemetry: the server contacts nothing the owner has not switched on.
- Files are served by item id only, and only files inside a library folder; a path from a request is never opened.
- Every route that serves an item checks the access rules (libraries per user, age limits); a hidden item answers "not found", as if it did not exist.
- **FFmpeg is bundled in the desktop apps** (v1.4.0, [Per-platform optimization plan](plan-per-platform.md) step 4): FFmpeg 9.1 built from its official source (SHA-256 and the release key checked), LGPL 2.1 only, as shared libraries beside `ffmpeg` and `ffprobe` in the app's resources, with `LICENSE.txt` and `SOURCE.txt` (source address and configure flags). Every demuxer and decoder; only the encoders, muxers, filters and protocols the server uses (`gradle/bundled-ffmpeg.gradle.kts`). H.264: VideoToolbox on macOS; NVENC, AMF, then Media Foundation on Windows. `STREAMFLIX_FFMPEG_DIR` points at another build. Without FFmpeg the server still runs and direct play still works; a missing FFmpeg is a clear message, never a crash.
- An "Open in JaiPlayer" option sends an explicit `ACTION_VIEW` intent to `com.ks.jaiplayer`. It falls back to the built-in ExoPlayer when JaiPlayer isn't installed.

These rules are also in `CLAUDE.md`, where they are loaded into every AI-assisted session; change both together.
- **Releases follow the suite cycle** (KS-Suite [release cycle](https://github.com/kumasumit/ks-suite/blob/dev/specs/release-cycle.md)): work on `dev`, automated tests, a DEV build, the owner's manual test, then a production release only with the owner's explicit approval for that app and platform. A feature raises MINOR, a fix raises PATCH. Never commit or release while a test fails.
- **The desktop server runs until Quit** (macOS and Windows): closing the window leaves it in the menu bar or the notification area (Compose `Tray`); `--background` starts it there without the window, which is how a start at login runs it. Start at login is on by default in production (set once; Settings ▸ On this computer turns it off), off by default in a DEV build, and only in the installed app (`jpackage.app-path`). While a stream is answered, or one ended less than 3 minutes ago (`StreamActivity`), the computer does not sleep. Each OS uses its own means, in its platform folder, with no added library: a LaunchAgent and `caffeinate -i` on macOS (`MacSystem`), the `HKCU\…\Run` value and `SetThreadExecutionState` through PowerShell on Windows (`WindowsSystem`).
