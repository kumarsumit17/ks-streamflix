---
type: Specification
title: App updates
description: How KS StreamFlix finds a newer version of itself on GitHub, shows it in Settings and installs it, using the suite's shared app-update helpers.
tags: [update, github, release, settings, android]
status: built
---

# App updates

KS StreamFlix follows the suite's [app-update behaviour](https://github.com/kumarsumit17/ks-utility/blob/main/specs/app-update.md) and uses ks-utility's Kotlin helpers (`com.ks.utility.appupdate`, from `v0.3.0`). This file records only what is particular to KS StreamFlix.

- **Repository:** `kumarsumit17/ks-streamflix`. The check is `GET https://api.github.com/repos/kumarsumit17/ks-streamflix/releases/latest` with only the headers from `GitHubRepo.requestHeaders`, made with the app's own HTTP client; the body goes to `AppUpdate.evaluate(json, repo, BuildConfig.VERSION_NAME, UpdatePlatform.ANDROID)`.
- **When:** the switch **Check for updates automatically** (on by default, saved with the app's settings) and `AppUpdate.isCheckDue`: 10 s after start-up, again when the app comes back to the foreground, at most once a day; never for a `0.0.0` build. The last-check time is saved with the settings. **Check for updates** always asks.
- **Release file:** the release attaches a signed `.apk`; only a file from this repository's releases with GitHub's `sha256:` digest is used.
- **Settings, App updates section:** "This is version X", the switch with the privacy note ("Once a day, the app asks GitHub whether a newer version is out. Nothing else is sent."), **Check for updates** with its status line, and **Download and install (size)** when a newer version is found. Every row is reachable with the D-pad. Texts: the shared `common.appUpdate.*` catalogue.
- **Install:** download the APK to `cacheDir/updates/`, check its size and SHA-256 (`Sha256.hex`, `Sha256.matches`), then its package name and signing certificate against the installed app's, then hand it to `PackageInstaller` (Android confirms every install). Needs `REQUEST_INSTALL_PACKAGES`; when `canRequestPackageInstalls()` is false, show `common.appUpdate.allowInstall` with **Open system settings** (`ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this package) and continue when the user comes back. Any failed check deletes the file and installs nothing.
- **Tests:** unit tests for the decisions the app adds (state of the section, the permission step); the shared rules are tested in ks-utility. Reference implementation on Android: `ks-tv-launcher` (`data/updates/`).

## As built (stage 6, 2026-11-24)

- Plugin `app-update` (`shared/client`): the section on the Settings screen of all four apps; `GitHubUpdater` (`jvmSharedMain`) follows the rules above with ks-utility's `AppUpdate`, `GitHubRepo` and `Sha256`. Downloads follow redirects over HTTPS only and stop past the announced size; size and SHA-256 must match, or the file is deleted.
- **Android and Google TV:** the phone app asks for `UpdatePlatform.ANDROID` (the plain APK), the Google TV app for `GOOGLE_TV` (`-google-tv.apk`), so each updates to its own build. `AndroidUpdateInstaller` checks the APK's package name and signing certificates against the installed app before `ACTION_VIEW` through a `FileProvider` (`cacheDir/updates/`); `REQUEST_INSTALL_PACKAGES`, with **Open system settings** when it is not allowed; checked again when the app comes to the foreground.
- **macOS and Windows:** the same check for `-macos-arm64.dmg` / `-windows-x64.msi`; the verified file is opened (the disk image, or the installer, after which the app and its server close so they can be replaced). The server updates with its app.
