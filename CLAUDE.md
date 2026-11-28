# ks-streamflix

A free, open-source home media server with its own apps: the server on macOS and Windows, clients on Android and Google TV, and simple pages in a browser. Plan: `specs/media-server-plan.md`. Jellyfin (GPL-2.0) is a feature reference only: never copy its code, assets, text or API.

## Specs
- All specs for this project live in `specs/` in the repository root, as an Open Knowledge Format (OKF) v0.2 bundle. Never create spec files anywhere else. Start at `specs/index.md`.
- One topic per file. Every file except `index.md` and `log.md` starts with YAML frontmatter: `type` (required: `Specification`, `Policy`, `Plan`, `Playbook` or `Reference`), `title`, `description` (one sentence), `tags`.
- When a spec is added: list it in `specs/index.md` with its description. When a spec is added or changed: add a line under today's date (`# YYYY-MM-DD`, newest first) in `specs/log.md`.
- Link between specs with relative markdown links (`[Dependencies](dependencies.md)`), so they also work on GitHub.

## Stack
- Server: Kotlin/JVM 17 with coroutines, Ktor (CIO engine), SQLDelight on SQLite, JSON with `kotlinx.serialization`; layers `domain` / `data` / `http`. FFmpeg (stage 4) is installed by the owner and run as a separate program, never bundled.
- Apps (stage 2): one Kotlin Multiplatform + Compose Multiplatform module `shared/client` (API client, ViewModel + StateFlow, screens) for all four apps; separate phone and Google TV apps (two APKs and AABs) with Media3/ExoPlayer.
- **Budgets:** stability, app size and speed are numbers in `quality-budgets.properties`, checked by `./gradlew check` and `checkSize` (`specs/quality-budgets.md`). Never raise one without the owner's OK.
- Package: `com.ks.streamflix`. minSdk 26 · targetSdk latest stable
- Interface text with an apostrophe uses ’ (U+2019): the language tool escapes `'` for Android, and Compose resources show the backslash.

## Module layout
| Gradle project | Folder | Purpose |
|---|---|---|
| `:shared:api` | `shared/api` | API wire models (`/api/v1`), Kotlin Multiplatform (JVM, Android), used by every app; `shared/api/lang` holds the API's error texts for the server and the apps |
| `:shared:server` | `shared/server` | Server core only: libraries, scanner, name parser, database, owner account, HTTP API, direct play, plugin API |
| `:shared:plugins:<id>` | `shared/plugins/<id>` | One server plugin each: `web` (browser pages), `watch-state` (progress, own database), `discovery` (UDP), `local-metadata` (posters, `.nfo`), `tmdb` (online details, off by default), `media-info` (ffprobe), `subtitles` (WebVTT), `transcode` (direct play / remux / transcode to HLS), `users` (users, libraries per user, age limits), `folder-watch` (rescan on changes) |
| `:shared:ffmpeg` | `shared/ffmpeg` | Not a plugin: finds and runs FFmpeg (the bundled copy first) for the plugins that need it |
| `:shared:client` | `shared/client` | The apps: API client, `AppViewModel` (StateFlow), client plugins (`watch-state`, `jaiplayer`, `metadata`, `media-info`, `subtitles`, `transcode`), Compose Multiplatform screens, language files; `jvmSharedMain` (HTTP and discovery on java.net), `androidMain` (Media3 player, and everything both Android apps run: the activity, the manifest with permissions and the update provider, R8 rules), `desktopMain` |
| `:shared:desktop` | `shared/desktop` | The macOS and Windows app: the server plus its window |
| `:desktop:mac` | `desktop/mac` | macOS entry point: picks the plugins |
| `:desktop:windows` | `desktop/windows` | Windows entry point: picks the plugins |
| `:mobile:android` | `mobile/android` | The phone app (APK + AAB): phone launcher entry |
| `:tv:google-tv` | `tv/google-tv` | The Google TV app (`-google-tv` APK + AAB): Leanback entry, banner, `FORM_FACTOR` meta-data (TV layout) |

## Core and plugins
- **Keep the core bare minimum** (owner's rule): every feature outside the core is a plugin in its own module, compiled in and listed by each app's entry point, so an app carries only what it lists. See `specs/media-server-plan.md`. Never add a feature to the core when it can be a plugin.

## Platform constraints (Android and Google TV apps)
- **D-pad first.** Every screen must be usable with the remote's arrow, select, and back keys. Restore focus to the last item when returning to a screen. Back from the player returns to the exact row and item.
- **The desktop server runs until Quit:** closing the window leaves it in the menu bar / notification area; start at login (on by default, installed app only) and staying awake while someone watches are each OS's own (`desktop/mac/MacSystem.kt`, `desktop/windows/WindowsSystem.kt`, behind `DesktopSystem`). See `specs/constraints.md`.
- **Lean means lean.** Keep the release APK under 15 MB. Ask before adding a dependency. Prefer what's already in Media3, Compose, and Room.
- **Low-RAM TVs.** Load images at their display size. Never keep a full-resolution bitmap in a row. Cap the decoded-image cache.
- **10-foot UI.** Overscan-safe margins are 48dp horizontal, 27dp vertical.
- **Separate phone and Google TV apps.** `mobile/android` and `tv/google-tv` are two application modules with the same package name (`com.ks.streamflix`), version and keystore, configured once in the root `build.gradle.kts`; the TV app's versionCode adds 1,000,000,000 (Google Play needs a different code per bundle). Both only add a launcher entry to what `shared/client/src/androidMain` declares. The TV app declares `android.software.leanback` required and `android.hardware.touchscreen` not required, has the banner (320×180), and marks itself with the `com.ks.streamflix.FORM_FACTOR` meta-data (`isTvBuild`): the layout follows the app, not the device. Each app updates to its own build (`UpdatePlatform.GOOGLE_TV` / `ANDROID`). Both APKs stay within `size.apk.maxMb`.
- Watch progress and favourites live on the server, per user. Any local database in the apps needs a `Migration` plus an exported schema for every schema change.

## Server constraints
- The server's database schema has a version number and a migration step for every change; never lose a library on upgrade.
- Passwords only as PBKDF2 hashes; session tokens from `SecureRandom`. No telemetry: the server never contacts anything the owner has not switched on.
- Never follow a file path from a request: files are served by item id only, and only files inside a library folder.
- Every route that serves an item checks the access rules (`call.visibleItem(core, id)` or `core.access`); a hidden item answers "not found".
- **The desktop apps bundle FFmpeg** (LGPL only, built from the pinned official source by `gradle/bundled-ffmpeg.gradle.kts`: VideoToolbox on macOS; Media Foundation, NVENC and AMF on Windows; no libx264). `Ffmpeg.locate` takes `STREAMFLIX_FFMPEG_DIR` first, then the bundled copy, then `PATH`. Without FFmpeg the server still runs; never crash. The `BundledFfmpegTest` in each desktop module checks the bundled build.
- An "Open in JaiPlayer" option sends an explicit `ACTION_VIEW` intent to `com.ks.jaiplayer`. It falls back to the built-in ExoPlayer when JaiPlayer isn't installed.

## Commands
```bash
./gradlew check                                   # tests, lint, language files, all budgets incl. sizes (needs Node 23.6+)
./gradlew :desktop:mac:run --args="--library Films=movies:$HOME/Movies"   # the macOS app: server and window
./gradlew :desktop:mac:run --args="--headless"    # the server alone
./gradlew :mobile:android:installDebug            # the phone app; an emulator reaches the Mac at 10.0.2.2:8742
./gradlew :tv:google-tv:installDebug              # the Google TV app
./gradlew -Pcompose.desktop.packaging.checkJdkVendor=false :desktop:mac:packageReleaseDmg checkInstallerSize   # .dmg on the owner's Mac (Homebrew JDK)
```
The server listens on port 8742 (`--port` to change) and keeps its data in `~/Library/Application Support/KS StreamFlix` (macOS) or `%APPDATA%\KS StreamFlix` (Windows); `--data <dir>` to change. Open `http://localhost:8742/` for the browser pages.

**Releases follow the suite cycle** (KS-Suite [release cycle](https://github.com/kumasumit/ks-suite/blob/dev/specs/release-cycle.md)): work on `dev`, automated tests, a DEV build, the owner's manual test, then a production release only with the owner's explicit approval for that app and platform. A feature raises MINOR, a fix raises PATCH. Never commit or release while a test fails.
Releases: a plain `vX.Y.Z` tag releases all four builds; `android-vX.Y.Z`, `google-tv-vX.Y.Z`, `mac-vX.Y.Z` or `windows-vX.Y.Z` releases only that build (from after v1.2.0, the bridge release), and a rehearsal can pick one with `-f platform=…`. A build's version is the higher of its own latest tag and the latest plain tag (KS-Suite versioning rule).
Compose's packaging refuses Homebrew's JDK unless `-Pcompose.desktop.packaging.checkJdkVendor=false` is passed (only on the command line; CI uses Temurin). The `.msi` builds only on Windows: CI (`.github/workflows/ci.yml`) builds, installs, starts and uninstalls it. FFmpeg (Homebrew's on the owner's Mac) is found in `STREAMFLIX_FFMPEG_DIR`, `PATH` or the usual places; tests that need it skip without it.

Emulators (from the KS-Suite workspace; suite spec `specs/testing.md`): `../scripts/emulators.sh create` once, then `../scripts/emulators.sh start tv` boots the Android TV AVD (`ks-tv-36`, API 36). Then:
```bash
export ANDROID_SERIAL=$(../scripts/emulators.sh serial tv)
./gradlew :tv:google-tv:installDebug   # the phone emulator: :mobile:android:installDebug
adb shell monkey -p com.ks.streamflix -c android.intent.category.LEANBACK_LAUNCHER 1   # open it (use the debug package if it has a suffix)
```

## Platform folders
- Every KS-Suite app repo has the same top-level platform folders: `tv/google-tv/`, `desktop/mac/`, `desktop/windows/`, `mobile/android/` and `mobile/ios/`. All five exist in every app repo, whether or not the app supports that platform yet.
- This app targets `desktop/mac`, `desktop/windows` (the server), `mobile/android` and `tv/google-tv` (separate phone and TV apps). `mobile/ios` is empty on purpose (a `.gitkeep` only). Do not add code for a platform that is not a target without the owner asking.
- Three layers, narrowest first: a platform folder holds code for one platform of this app; `shared/` in the repository root holds code used by two or more platforms of this app, written once and never copied between platform folders; `ks-utility` holds generic code used by several apps. Here `shared/` holds the API models and the server core.
- Never rename, merge or remove these folders, and never add another top-level platform folder; the layout is a suite-wide rule.

## Shared code (`ks-utility`)
- Generic utility code shared across the KS-Suite apps lives in the `ks-utility` repository. It is a dependency of this app: a git submodule at `ks-utility/`, pinned to a commit. After cloning, run `git submodule update --init`.
- Before writing a generic helper (one that knows nothing about this app), look in `ks-utility/kotlin` and use it from there. If it is missing and another app would need it too, add it in the `ks-utility` repository, not here. Application-specific logic stays in this repo.
- Never edit files under `ks-utility/` from this repo. Change them in the `ks-utility` repository, then move the pin: `git -C ks-utility fetch && git -C ks-utility checkout <commit>`, and commit the changed `ks-utility` entry.
- Gradle wiring: `includeBuild("ks-utility/kotlin")` in `settings.gradle.kts`, `ks-utility = { module = "com.ks:utility" }` in `gradle/libs.versions.toml`, and `implementation(libs.ks.utility)` in the module that uses it. It is a plain Kotlin/JVM library with no Android SDK.
- CI checks out with `submodules: true` (`.github/workflows/release.yml`).

## Working efficiently (keep token use low)
- Use the knowledge graph first: follow the rules in the `graphify` section at the end of this file. [graphify](https://github.com/Graphify-Labs/graphify) is a development tool only; it is never a dependency of the code and is never bundled or shipped.
- One-time setup on a new machine (owner): `uv tool install graphifyy` (the package name has two y's), then `/graphify .` in Claude Code. The setup in `.claude/` is committed; `graphify-out/` is generated and git-ignored.
- Read only the specs the task needs, and do not re-read a file that has not changed in this session.
- Never read generated files: `build/`, `.gradle/`, `graphify-out/graph.json`.
- Keep command output short (show only failures), and keep summaries brief.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- When graphify-out/graph.json exists, run `graphify query "<question>"` before your first grep, search or source-file read for a codebase question, and put this rule in every subagent prompt that explores code. Read raw files only for lines the graph or an earlier read already located. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. When the answer is under a Markdown heading, `graphify section "<heading>"` prints that section's text, so the .md need not be opened. These return a scoped subgraph, usually much smaller than GRAPH_REPORT
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- `graphify-out/` is generated and git-ignored: never commit it.
