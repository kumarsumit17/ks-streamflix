# ks-streamflix

Lean, D-pad optimized streaming media manager for Android TV.

## Specs
- All specs for this project live in `specs/` in the repository root, as an Open Knowledge Format (OKF) v0.2 bundle. Never create spec files anywhere else. Start at `specs/index.md`.
- One topic per file. Every file except `index.md` and `log.md` starts with YAML frontmatter: `type` (required: `Specification`, `Policy`, `Plan`, `Playbook` or `Reference`), `title`, `description` (one sentence), `tags`.
- When a spec is added: list it in `specs/index.md` with its description. When a spec is added or changed: add a line under today's date (`# YYYY-MM-DD`, newest first) in `specs/log.md`.
- Link between specs with relative markdown links (`[Dependencies](dependencies.md)`), so they also work on GitHub.

## Stack
- Kotlin, Jetpack Compose for TV (`androidx.tv:tv-material`), Media3/ExoPlayer, Room, Hilt
- Package: `com.ks.streamflix`. Features: `ui/`, `player/`, `library/`
- minSdk 26 · targetSdk latest stable

## Platform constraints
- **D-pad first.** Every screen must be usable with the remote's arrow, select, and back keys. Restore focus to the last item when returning to a screen. Back from the player returns to the exact row and item.
- **Lean means lean.** Keep the release APK under 15 MB. Ask before adding a dependency. Prefer what's already in Media3, Compose, and Room.
- **Low-RAM TVs.** Load images at their display size. Never keep a full-resolution bitmap in a row. Cap the decoded-image cache.
- **10-foot UI.** Overscan-safe margins are 48dp horizontal, 27dp vertical.
- Declare `android.software.leanback` as required and `android.hardware.touchscreen` as not required. Provide a banner (320×180) for the TV home row.
- The library (watch progress, favorites) lives in Room. Database schema changes need a Room `Migration` plus an exported schema in `app/schemas/`.
- An "Open in JaiPlayer" option sends an explicit `ACTION_VIEW` intent to `com.ks.jaiplayer`. It falls back to the built-in ExoPlayer when JaiPlayer isn't installed.

## Commands
```bash
./gradlew assembleDebug
./gradlew installDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lintDebug
./gradlew :app:assembleRelease && ls -lh app/build/outputs/apk/release/   # check APK size
```
Test on the Android TV emulator (1080p, API 34).
