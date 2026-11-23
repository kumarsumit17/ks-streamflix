---
type: Plan
title: Media server plan
description: The plan to turn KS StreamFlix into a free, open-source home media server with macOS, Windows, Android and Google TV apps: features, platforms, engines and libraries with their licences, and a staged roadmap.
tags: [plan, server, streaming, transcoding, roadmap]
status: approved
---

# Media server plan

KS StreamFlix becomes a home media server in the spirit of [Jellyfin](https://jellyfin.org/): it runs on a computer at home, scans the owner's video folders, and streams them to its own apps. There are four apps: **macOS** and **Windows** (the server, with a desktop window to manage it and watch on that computer), **Android** (phone and tablet) and **Google TV** (clients), plus simple pages in a browser. It is free and open source (MIT, [LICENSE](../LICENSE)).

Jellyfin's [documentation](https://jellyfin.org/docs/) and download repository ([repo.jellyfin.org](https://repo.jellyfin.org/)) are a **feature reference only**. Jellyfin is GPL-2.0: no Jellyfin code, assets, text, API definitions or FFmpeg builds are copied or bundled. StreamFlix has its own design, its own API and its own words.

> Status: approved by the owner on 2026-11-23, with one APK for Android and Google TV.

## What the server does

| Area | What it does | Stage |
|---|---|---|
| **Libraries** | The owner adds libraries (a name, a kind: films or series, one or more folders). | 1 |
| **Scanning** | Walks the folders for video files; reads the title and year from the file or folder name (`Film Name (2019)`), and series, season and episode from the path (`Show/Season 01/Show S01E02`). A rescan adds new files, updates changed ones (size and modified time) and removes missing ones. Runs at start-up and on request; watching folders for changes comes later. | 1 |
| **Users** | One owner account, created on first run. Passwords are stored as PBKDF2-HMAC-SHA256 hashes; clients sign in for a random session token. More users, per-library access and parental ratings come later. | 1 (owner), 5 (users) |
| **Direct play** | Streams the original file over HTTP with byte ranges (seek works), when the client can play it. | 1 |
| **Media details** | Reads duration, video and audio codecs, audio and subtitle tracks with `ffprobe`. These details decide when transcoding is needed. | 3 |
| **Metadata** | Local first: posters and backdrops next to the file (`poster.jpg`, `folder.jpg`, `fanart.jpg`), and `.nfo` files. Online: TMDB, with the owner's own free API key, switched off by default. Images are resized on the server to the size the client asks for. | 3 |
| **Subtitles** | Side-car `.srt`/`.vtt` files and embedded text subtitle tracks, served as WebVTT. | 3 |
| **Watch state** | Watch progress, watched/unwatched, resume, "continue watching" and "next up", kept on the server per user so every client agrees. | 2 |
| **Transcoding** | When a client cannot play a file (codec, container, bitrate), FFmpeg converts it on the fly to HLS (H.264/AAC), with hardware encoding where the computer has it (VideoToolbox on macOS; NVENC, Quick Sync or AMF on Windows). Remuxing (copying streams into a new container) is tried before re-encoding. | 4 |
| **Discovery** | Clients on the home network find the server without typing an address (a UDP broadcast answer on the local network). | 2 |
| **Admin pages** | Setup, libraries, users, scan status and server settings, as pages served by the server, in English and Hindi. | 1 (setup, libraries), 5 (the rest) |
| **Later, not planned yet** | Music, photos, live TV and recording, DLNA, Chromecast, synced group watching, plugins, remote access outside the home. Each needs its own plan. | — |

## Platforms: server and clients

| Platform folder | Role | Notes |
|---|---|---|
| `desktop/mac/` | **Server + desktop app** | macOS entry point and `.dmg`: runs the server, with a Compose Desktop window (and menu-bar item) to start and stop it, show its address, manage libraries and browse; playback hands off to JaiPlayer or the browser. The owner's Mac is the first server. |
| `desktop/windows/` | **Server + desktop app** | The same for Windows (tray item), `.msi`, built and tested on CI ([Testing](https://github.com/kumarsumit17/KS-Suite/blob/main/specs/testing.md)). |
| `mobile/android/` | **Client** | Android phone and tablet app: touch layout, Media3 ExoPlayer, "Open in JaiPlayer". |
| `tv/google-tv/` | **Client** | Google TV app: Compose for TV, Media3 ExoPlayer, D-pad first, "Open in JaiPlayer". The skeleton in `app/` moves here when its build is created. |
| `shared/` | Code used by several apps | Written once: `shared/api` (the wire models and an API client, used by all four apps), `shared/server` (scanner, database, HTTP, transcoding; used by macOS and Windows), and the client screens and state that the Android and Google TV apps share. |
| browser | **Client** | Simple pages served by the server, with the browser's own video player. Written in Kotlin on the server (HTML text), no JavaScript framework. |
| `mobile/ios/` | not a target | Stays empty (`.gitkeep`). |

- **Linux and NAS:** the server is a JVM program, so the same `.jar` runs on Linux. The suite has no Linux platform folder, so Linux is not a release target; a plain archive (and perhaps a container image) is a later decision for the owner.
- **Suite changes this needs:** StreamFlix's targets in the suite's [Platform folders](https://github.com/kumarsumit17/KS-Suite/blob/main/specs/platform-folders.md) table become `desktop/mac`, `desktop/windows`, `mobile/android`, `tv/google-tv` with `shared/`. The [cross-repo](https://github.com/kumarsumit17/KS-Suite/blob/main/specs/cross-repo.md) edge StreamFlix → JaiPlayer stays as it is, with an `http(s)` stream URL as the data. Both are changed in the KS-Suite repository, not here.
- All four apps are released together, with one version tag; the API carries a version number (`/api/v1`) so an older client can tell when the server is too new for it.

## Engines and libraries

Only what each stage needs, each justified when it is added ([Dependencies](dependencies.md)).

| Piece | Used for | Licence | Stage |
|---|---|---|---|
| Kotlin standard library, JDK 17 | Everything; the JDK's own HTTP server (`jdk.httpserver`), PBKDF2 and `SecureRandom` | Apache-2.0; JDK GPL-2.0 with Classpath Exception | 1 |
| [`sqlite-jdbc`](https://github.com/xerial/sqlite-jdbc) (SQLite inside) | The library database on the server | Apache-2.0 (SQLite: public domain) | 1 |
| [`kotlinx.serialization`](https://github.com/Kotlin/kotlinx.serialization) JSON | The API's request and response bodies, on server and client | Apache-2.0 | 1 |
| `ks-utility` | Language files (English and Hindi), app updates | MIT, first-party | 1 |
| FFmpeg and ffprobe, **installed by the owner** | Media details, remuxing, transcoding | LGPL-2.1+, or GPL if the build has GPL parts | 3, 4 |
| Compose Multiplatform (desktop) | The macOS and Windows window | Apache-2.0 | 2 |
| Compose (Material 3 on phone, Compose for TV on TV), Media3 ExoPlayer (+ HLS) | The Android and Google TV apps | Apache-2.0 | 2 |
| TMDB API (a web service, no library) | Online metadata, owner's own key, off by default | TMDB terms of use; the attribution notice is shown | 3 |

- **FFmpeg is never bundled.** It runs as a separate program that the owner installs (Homebrew on macOS, a download or `winget` on Windows), the way JaiPlayer needs VLC. The server looks for it on `PATH` and in the usual places, lets the owner set its path, and shows a clear message when it is missing: direct play keeps working without it. Running a separate program keeps StreamFlix MIT whatever licence the owner's FFmpeg build has.
- **No web framework** (Ktor, Spring) at first: the JDK's HTTP server is enough for a home network and adds nothing. If it falls short (HTTP/2, many clients), Ktor (Apache-2.0) is the fallback, by a new decision.
- **No Jellyfin API compatibility.** Jellyfin's own clients will not connect to StreamFlix. Copying its API would tie StreamFlix to a large GPL project's design; StreamFlix's API stays small and its own.

## Roadmap

Each stage is built and tested on `dev` and ends in something that works on its own. A release (dev → main → tag) happens only with the owner's OK.

1. **Server core (first version).** Gradle build (`shared/api`, `shared/server`, `desktop/mac`, `desktop/windows`). Libraries, scanning and name parsing, SQLite, the owner account, the API (`/api/v1`: server info, sign-in, libraries, items, rescan, stream with byte ranges), and browser pages for setup, sign-in, browsing and playing. Run with `./gradlew :desktop:mac:run`. Tests: name parser, scanner on a temporary folder, byte ranges, password hashing, the API end to end against a server on a free port. Done when: on the Mac, a folder of films is added, scanned, listed in the browser and plays with seeking; English and Hindi pages pass the language-file check.
2. **The four apps.** macOS and Windows: the desktop window around the server (start/stop, address, libraries, browse, play through JaiPlayer or the browser). Android and Google TV: find or type the server, sign in, rows per library and "continue watching", detail page, playback with ExoPlayer (direct play), watch progress back to the server, "Open in JaiPlayer" with the stream URL. Tested on the phone and TV emulators and Windows CI ([Testing](https://github.com/kumarsumit17/KS-Suite/blob/main/specs/testing.md)). The APK stays under 15 MB.
3. **Media details and metadata.** ffprobe details, local posters and `.nfo`, resized images, subtitles, optional TMDB.
4. **Transcoding.** Decide direct play, remux or transcode from what the client says it can play; FFmpeg to HLS with hardware encoding; clean up old segments; limit how many run at once.
5. **Users and admin.** More users, per-library access, parental ratings, the remaining admin pages, folder watching.
6. **Packaging and release.** `.dmg` and `.msi` for the desktop apps, signed APK (and AAB) for Android and Google TV, through ks-utility's shared release workflow (its desktop inputs), Windows CI, app updates ([App updates](app-updates.md)) for the TV app and the server.

## Decisions (approved)

1. **Four apps:** macOS and Windows are the server with a desktop window; Android and Google TV are clients. Kotlin/JVM server (code in `shared/`); the jar also runs on Linux unofficially.
2. **Android and Google TV: one APK** that switches its layout on a TV (as JaiPlayer does). Two APKs would keep the TV app leaner, at the cost of a second build and release.
3. **HTTP and storage:** the JDK's built-in HTTP server, SQLite through `sqlite-jdbc`, JSON with `kotlinx.serialization` (two new dependencies).
4. **Transcoding engine:** FFmpeg installed by the owner and run as a separate program, never bundled; from stage 4.
5. **Desktop playback:** the macOS and Windows apps hand playback to JaiPlayer (or the browser) instead of having their own player, which keeps them small.
