---
type: Specification
title: DEV builds side by side with production
description: How a DEV build of KS StreamFlix (-PksVariant=dev) installs and runs next to the production app on every platform without touching it.
tags: [release, dev, android, google-tv, macos, windows]
---

# DEV builds side by side with production

The KS-Suite [release cycle](https://github.com/kumasumit/ks-suite/blob/dev/specs/release-cycle.md#dev-and-prod-side-by-side) has the owner test DEV builds by hand before any production release. `-PksVariant=dev` selects the DEV variant; the release workflow's manual run passes it, with `-PksDevVersion=X.Y.Z-dev+<commit>`. Without it every build is production, whose identities never change.

| | Production | DEV |
|---|---|---|
| Android and Google TV ID | `com.ks.streamflix` | `com.ks.streamflix.dev` (its own data and update provider) |
| Name | KS StreamFlix | KS StreamFlix DEV (launcher, window, tray, install folder, shortcuts) |
| Icon and TV banner | dark zinc | orange, marked DEV (`shared/android-dev/res`, `packaging/icon-dev.*`) |
| macOS bundle ID and LaunchAgent label | `com.ks.streamflix` | `com.ks.streamflix.dev` |
| Windows MSI upgrade code | `b7e3c2d1-5a64-4f0e-9d8b-3c2a1f6e4b57` | `2f9c6a14-8d3b-4c71-a5e2-94b07d1c3e68` |
| Windows `Run` value | KS StreamFlix | KS StreamFlix DEV |
| Server data folder (database, plugin data, caches) | `…/KS StreamFlix` | `…/KS StreamFlix DEV` (`~/Library/Application Support`, `%APPDATA%`) |
| Desktop app settings (`java.util.prefs`) | `com/ks/streamflix/client` | `com/ks/streamflix-dev/client` |
| HTTP and discovery port | 8742 | 8752, so both servers run at once |
| Server name | the computer's name | the computer's name + " DEV" |
| Browser pages | KS StreamFlix in the title and header | KS StreamFlix DEV |
| Browser cookies | `sf_session`, `sf_lang` | the same with `_dev` (cookies are shared between ports) |
| Updates | GitHub releases | none: a DEV version is never offered a release |
| Android signing | suite key | `DEV_KEYSTORE_BASE64`, `DEV_KEYSTORE_PASSWORD`, `DEV_KEY_ALIAS`, `DEV_KEY_PASSWORD` |

macOS and Windows DEV installers are unsigned. File names and artifacts are the shared workflow's (`ks-streamflix-dev-<version>-<platform>`, kept 14 days).

## Apps and servers

A server keeps watch progress and users, so a DEV app must not use a production server:

- DEV apps look for servers on the DEV port only, and add it to an address typed without one.
- `GET /api/v1/info` says `dev: true` for a DEV server. A DEV app refuses to connect to any other (`client.error.devOnly`).
- A production app is not stopped from connecting to a DEV server whose address and port are typed in.
- `--data` and `--port` still override the defaults: never point a DEV server at the production data folder.

## Not marked

The Android app's own headings still read "KS StreamFlix".

## Where it is set

The root `build.gradle.kts` (Android ID, label, banner, resources, versions), `desktop/*/build.gradle.kts` (name, bundle ID, upgrade code, icon, `-Dstreamflix.variant`), `DesktopVariant` in `shared/client` (desktop names, settings, port) and the application ID on Android (`AndroidHost.isDev`).
