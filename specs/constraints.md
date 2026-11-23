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
- **Lean means lean.** Keep the release APK under 15 MB. Ask before adding a dependency. Prefer what's already in Media3, Compose, and Room.
- **Low-RAM TVs.** Load images at their display size. Never keep a full-resolution bitmap in a row. Cap the decoded-image cache.
- **10-foot UI.** Overscan-safe margins are 48dp horizontal, 27dp vertical.
- **One APK for phone and TV.** Declare `android.software.leanback` and `android.hardware.touchscreen` with `required="false"`; the TV layout switches at runtime. Provide a banner (320×180) for the TV home row.
- Watch progress and favourites live on the server, per user. Any local database in the apps needs a `Migration` plus an exported schema for every schema change.

## Server

- The database schema has a version number and a migration step for every change; a library is never lost on upgrade.
- Passwords are stored only as PBKDF2 hashes; session tokens come from `SecureRandom`. No telemetry: the server contacts nothing the owner has not switched on.
- Files are served by item id only, and only files inside a library folder; a path from a request is never opened.
- Without FFmpeg the server still runs and direct play still works; a missing FFmpeg is a clear message, never a crash.
- An "Open in JaiPlayer" option sends an explicit `ACTION_VIEW` intent to `com.ks.jaiplayer`. It falls back to the built-in ExoPlayer when JaiPlayer isn't installed.

These rules are also in `CLAUDE.md`, where they are loaded into every AI-assisted session; change both together.
