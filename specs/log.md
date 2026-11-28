# 2026-11-28

* **Update**: [DEV builds side by side](dev-variant.md), [Constraints](constraints.md) and `CLAUDE.md`: a DEV build does not turn start at login on by itself.
* **Update**: [App updates](app-updates.md): a DEV build's Settings shows its DEV version.
* **Update**: [Recorded measurements](quality-budgets.md) (was Quality budgets): by the owner's decision sizes and speeds are reported and no longer fail the build; stability tests stay. [Constraints](constraints.md), the [Media server plan](media-server-plan.md), the other plans and `CLAUDE.md` follow.
* **Update**: [DEV builds side by side](dev-variant.md): a DEV server's browser pages say DEV in the title and header.
* **Added**: [DEV builds side by side with production](dev-variant.md): the DEV variant, built into the apps.
* **Update**: `ks-utility` moved to `v0.19.0`; `release.yml` names the production and `DEV_*` signing secrets.
* **Update**: [Per-platform optimization plan](plan-per-platform.md): releases follow the suite release cycle.
* **Added**: [Progress](progress.md): the checkpoint the suite working method requires.
* **Update**: [Constraints](constraints.md), [Media server plan](media-server-plan.md) and `CLAUDE.md`: releases follow the suite release cycle (DEV build, the owner's manual test, explicit approval), replacing "build, test, release, no waiting".
* **Added**: [KS JaiPlayer as the default player](plan-jaiplayer-default.md): plan for playing through KS JaiPlayer by default (fallbacks, the DEV variant, future asks of JaiPlayer); waits for the owner's approval, no code changed.
* **Update**: The owner approved [KS JaiPlayer as the default player](plan-jaiplayer-default.md); it is built when StreamFlix becomes the active app.

# 2026-11-27

* **Update**: `ks-utility` moved to `v0.18.4`.
* **Update**: `ks-utility` moved to `v0.18.3`.
* **Update**: `ks-utility` moved to `v0.18.1`.
* **Update**: `ks-utility` moved to `v0.18.0`; `release.yml` and `ks-utility-bump.yml` call ks-utility's shared workflows `@main`, which always equals its newest release, so only the code pin moves; no `KS_WORKFLOWS_TOKEN` (owner's decision).
* **Update**: `ks-utility` moved from `v0.16.0` to `v0.17.0`; `release.yml` and `ks-utility-bump.yml` call it `@v0.17.0`.
* **Update**: `ks-utility` moved from `v0.15.0` to `v0.16.0` (ks-suite-check also checks the shared-workflow refs); `release.yml` and `ks-utility-bump.yml` call ks-utility `@v0.16.0`, and the bump passes `KS_WORKFLOWS_TOKEN` so automatic moves also update those calls.
* **Update**: The release workflow is ks-utility's `@v0.15.0` (with ks-suite-check), `.github/workflows/ks-utility-bump.yml` moves ks-utility to its newest tag by itself, and the desktop updater installs through `DesktopInstaller` ([App updates](app-updates.md)).
* **Update**: `ks-utility` moved from `v0.14.0` to `v0.15.0` (DesktopInstaller, ks-suite-check, the bump workflow).
* **Update**: App icons (suite rule): the phone and Google TV apps have an adaptive launcher icon with a monochrome layer and a round icon (`mipmap-anydpi-v26`); the desktop window shows the app icon instead of the framework's default. The TV banner, the menu-bar/tray icon and the dmg/msi icons were already the app's own.
* **Update**: FFmpeg is bundled in the desktop apps (v1.4.0; [Constraints](constraints.md), [Per-platform optimization plan](plan-per-platform.md) step 4): FFmpeg 9.1 from its official source, LGPL only; the dmg grows 75.6 → 81.0 MB and its budget is 85 MB ([Quality budgets](quality-budgets.md)).
* **Update**: [Quality budgets](quality-budgets.md): the API p95 counts the fastest of five rounds (shared Windows runners measured 45–223 ms in one run); the budgets are unchanged.
* **Update**: The owner approved the [music](plan-music.md), [photos](plan-photos.md), [live TV](plan-live-tv.md), [casting](plan-casting.md) and [remote access](plan-remote-access.md) plans; the open questions in live TV, casting and remote access are asked when each build starts.
* **Update**: The opt-in test against the real TMDB (it needed the owner's API key) is removed, at the owner's request; the TMDB plugin is still tested against a stand-in server ([Media server plan](media-server-plan.md)). Users still enter their own TMDB key for the plugin.
* **Update**: TV focus fixes (v1.3.1): the home screen's first focus goes to the first card on screen once every row has loaded, never moves by itself after that, and comes back to the card in the row it was opened from; Settings focuses the chosen language, also after choosing one. `HomeSection` gained `items` and a focus target in `Content`.

# 2026-11-26

* **Update**: `ks-utility` moved from `v0.13.2` to `v0.14.0` (newest tag before each release). Links to the suite repository now say `kumasumit/ks-suite` (renamed).
* **Update**: [Quality budgets](quality-budgets.md): `speed.api.p95Ms.windows=150` (owner's decision); other systems keep 100 ms.
* **Update**: The desktop server runs until Quit (v1.3.0, [Per-platform optimization plan](plan-per-platform.md) step 3; [Constraints](constraints.md)): menu-bar / notification-area item, start at login on by default, the computer stays awake while someone watches.
* **Update**: `ks-utility` moved from `v0.10.0` to `v0.13.2` (owner-approved Hindi terms); the TMDB page's Hindi text says शीर्षक instead of टाइटल.
* **Update**: [Constraints](constraints.md): build, test, release, no waiting (suite rule): tested changes ship with the next tag, no approval before tagging; the plans now say the same.
* **Update**: Versioning per platform (v1.2.0, the bridge release): `ks-utility` moved from `v0.9.0` to `v0.10.0`; `release.yml` takes platform tags (`android-`, `google-tv-`, `mac-`, `windows-vX.Y.Z`) and a rehearsal `platform`; each build's version is the higher of its own and the plain tag; the updaters read the release list ([App updates](app-updates.md), [Per-platform optimization plan](plan-per-platform.md)).
* **Update**: The repository moved to the GitHub account `kumasumit`: links, the release workflow's ks-utility reference, the submodule URL and the updater's repository changed; [App updates](app-updates.md) says copies up to v1.1.0 update once by hand.

# 2026-11-25

* **Update**: [Per-platform optimization plan](plan-per-platform.md): versioning per platform is back (owner, suite rule "Platform tags"); v1.2.0 is the bridge release. [Quality budgets](quality-budgets.md): the API p95 is measured in five rounds and the median round counts.
* **Update**: [Per-platform optimization plan](plan-per-platform.md): the per-platform versioning step removed; one version for every platform (KS-Suite rule).
* **Update**: [Per-platform optimization plan](plan-per-platform.md) approved by the owner: one version for all builds, FFmpeg bundled, start at login on by default, Continue watching on the Google TV home screen, `media3-session`; step 1 (the phone/TV split) built.
* **Update**: Phone and Google TV are separate apps (v1.1.0): [Media server plan](media-server-plan.md) ("v1.1.0", decision 2), [Constraints](constraints.md), [Overview](overview.md), [App updates](app-updates.md).
* **Creation**: [Per-platform optimization plan](plan-per-platform.md), a draft for the owner's approval: an audit of each build today and what changes per OS (KS-Suite rule "Each platform build is optimized for its OS").
* **Update**: `ks-utility` moved from `v0.6.0` to `v0.9.0` (more languages, the translation tools; [ks-utility translation tools](https://github.com/kumasumit/ks-utility/blob/main/specs/translating.md)).

# 2026-11-24

* **Update**: [Media server plan](media-server-plan.md), "After v1.0.0": ks-utility v0.6.0 (Translator, `lang.mjs compose`, release workflow v0.6.0); v1.0.1 stays one APK with an update checker that asks for the Google TV file on a TV, the phone/TV split follows in the next release; real PGS burn-in test; `stream.mp4` for browsers without HLS; opt-in real-TMDB test. Gradle 9.6 delegate warnings removed from the build files.
* **Creation**: plans for the owner's approval: [Music](plan-music.md), [Photos](plan-photos.md), [Live TV and recording](plan-live-tv.md), [Casting](plan-casting.md), [Remote access](plan-remote-access.md).
* **Update**: Stage 6 built ([Media server plan](media-server-plan.md), "Stage notes"): `.dmg` and `.msi` with icons and SQLite trimmed to one OS; release workflow with the desktop modules; CI on Linux, Windows (install, start, uninstall) and macOS; [App updates](app-updates.md) built in all four apps (`app-update` plugin; Android checks package and signer); [Quality budgets](quality-budgets.md): budgets lowered, new `size.installer.macMb`. No new library.

# 2026-11-23

* **Update**: Stage 5 built ([Media server plan](media-server-plan.md), "Stage notes"): core access rules (checked by the API, every plugin and the browser pages), user management and library removal for plugins; server plugins `users` (users, libraries per user, age limits from `.nfo` certifications, own password change, owner page) and `folder-watch`; [Constraints](constraints.md) gained the access rule; new budget `speed.folderWatch.detectMs` in [Quality budgets](quality-budgets.md). No new library.
* **Update**: Stage 4 built ([Media server plan](media-server-plan.md), "Stage notes"): server and app plugins `transcode` (direct play, remux or transcode to HLS from a device profile; whole VOD playlist with FFmpeg restarted on a seek; hardware H.264 encoder; limits and clean-up; burn-in of picture subtitles), the browser watch page uses it; new budget `speed.transcode.firstSegmentMs` in [Quality budgets](quality-budgets.md). New dependency: Media3's HLS module (`media3-exoplayer-hls`, Apache-2.0) in the Android app.
* **Update**: Stage 3 built ([Media server plan](media-server-plan.md), "Stage notes"): core extension points for metadata providers (merged details, resized and cached images) and owner settings pages; `shared/ffmpeg`; server plugins `local-metadata`, `tmdb`, `media-info`, `subtitles`; app plugins `metadata`, `media-info`, `subtitles`; posters and subtitles in the browser pages; [Quality budgets](quality-budgets.md) stage 3 measurements. No new library. FFmpeg installed on the owner's Mac with Homebrew (owner's OK) for testing.
* **Update**: Stage 2 built ([Media server plan](media-server-plan.md), "Stage notes"): server plugins `watch-state` and `discovery`, signed stream links, `shared/client`, `shared/desktop`, the one APK in `mobile/android` + `tv/google-tv`; the empty `app/` skeleton removed; the release workflow builds `:mobile:android`.
* **Update**: [Quality budgets](quality-budgets.md): new `size.desktop.maxMb`; the size check now measures the server, the desktop app and the release APK; stage 2 measurements.
* **Update**: [Dependencies](dependencies.md) gained, for the apps: Compose Multiplatform (runtime, foundation, ui, material3, resources), the multiplatform lifecycle ViewModel, Media3 ExoPlayer and its Compose UI, activity-compose, kotlinx-coroutines-swing/android. Compose Desktop's Material 2 is excluded.
* **Update**: Stage 1 built: the server core (`shared/api`, `shared/server`), the `web` plugin and the macOS and Windows entry points; [Quality budgets](quality-budgets.md) has the first measurements; [Overview](overview.md) says stage 1 is built.
* **Update**: [Dependencies](dependencies.md) gained, with the owner's approval of the plan: Ktor (server core, CIO, content negotiation, partial content, auth, status pages), SQLDelight with its SQLite driver, kotlinx.serialization, kotlinx.coroutines, SLF4J simple (desktop apps only). Ktor's HTTP client and Kotlin reflection are excluded.
* **Creation**: [Quality budgets](quality-budgets.md) added: stability, app size and speed as numbers the build checks (owner's rule).
* **Update**: [Media server plan](media-server-plan.md): modern architecture by the owner's choice: Ktor with coroutines, SQLDelight, one Kotlin Multiplatform + Compose Multiplatform client module; replaces the JDK HTTP server.
* **Update**: [Media server plan](media-server-plan.md): a bare-minimum core in every app, every other feature a compiled-in plugin (owner's decision), with the plugin list and stages.
* **Update**: [Overview](overview.md) and [Constraints](constraints.md) follow the approved plan: server on macOS and Windows, one APK for Android and Google TV, server constraints added.
* **Creation**: [Media server plan](media-server-plan.md) added and approved by the owner (one APK for Android and Google TV): StreamFlix as a home media server with four apps (macOS and Windows: server with a desktop window; Android and Google TV: clients) and browser pages; Jellyfin's docs and repo.jellyfin.org as a feature reference only.
* **Update**: Release: `.github/workflows/release.yml` now calls ks-utility's shared release workflow (`kotlin-app-release.yml@v0.4.0`, [spec](https://github.com/kumasumit/ks-utility/blob/main/specs/release-workflow.md)): one workflow for every Kotlin app, with a rehearsal input, pinned actions, signature checks, build-provenance attestations and `gh` publishing.
* **Creation**: [App updates](app-updates.md) added: the suite's shared app-update behaviour (daily GitHub check with a switch, Check for updates, download, SHA-256 and signer checks, PackageInstaller) with ks-utility `v0.3.0`; not built yet.
* **Update**: `ks-utility` moved to `v0.3.0`.

# 2026-11-21

* **Update**: [Dependencies](dependencies.md): `ks-utility` added as a git submodule for shared generic utilities.
* **Initialization**: `specs/` created as an Open Knowledge Format (OKF) v0.2 bundle with this log and `index.md`.
* **Creation**: [Overview](overview.md), [Constraints](constraints.md) added as drafts, from `README.md` and `CLAUDE.md`.
* **Creation**: [Dependencies](dependencies.md) added: third-party dependencies are kept to the minimum.
