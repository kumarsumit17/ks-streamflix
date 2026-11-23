# 2026-11-23

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
