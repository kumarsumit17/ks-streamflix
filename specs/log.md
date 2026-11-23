# 2026-11-23

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
* **Update**: Release: `.github/workflows/release.yml` now calls ks-utility's shared release workflow (`kotlin-app-release.yml@v0.4.0`, [spec](https://github.com/kumarsumit17/ks-utility/blob/main/specs/release-workflow.md)): one workflow for every Kotlin app, with a rehearsal input, pinned actions, signature checks, build-provenance attestations and `gh` publishing.
* **Creation**: [App updates](app-updates.md) added: the suite's shared app-update behaviour (daily GitHub check with a switch, Check for updates, download, SHA-256 and signer checks, PackageInstaller) with ks-utility `v0.3.0`; not built yet.
* **Update**: `ks-utility` moved to `v0.3.0`.

# 2026-11-21

* **Update**: [Dependencies](dependencies.md): `ks-utility` added as a git submodule for shared generic utilities.
* **Initialization**: `specs/` created as an Open Knowledge Format (OKF) v0.2 bundle with this log and `index.md`.
* **Creation**: [Overview](overview.md), [Constraints](constraints.md) added as drafts, from `README.md` and `CLAUDE.md`.
* **Creation**: [Dependencies](dependencies.md) added: third-party dependencies are kept to the minimum.
