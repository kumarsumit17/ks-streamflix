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

> Status: approved by the owner on 2026-11-23, with one APK for Android and Google TV. Stages 1 to 6 are built (see [Stage notes](#stage-notes)); the first release waits for the owner's OK.

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

## A minimal core, plugins for the rest

Every app keeps a bare-minimum core and adds every other feature as a plugin (owner's decision, 2026-11-23), so each app carries only the features it includes and stays small. It is the same model as [JaiPlayer's plugins](https://github.com/kumasumit/ks-jaiplayer/blob/main/specs/plugins.md).

- **Server core** (`shared/server`): libraries and their folders, scanning and name parsing, the database, the owner account and sessions, the API (`/api/v1`), direct play with byte ranges, signed stream links, the language files, and three extension points: metadata providers (the core merges their details and resizes and caches their images; it reads no metadata itself), access rules (every rule must allow; the owner sees everything; the core and every plugin check them) and owner settings pages. For plugins it also offers user management (add, remove, set a password) and removing a library. Nothing else.
- **Server plugins** (`shared/plugins/<id>`, one Gradle module each): a `ServerPlugin` has a stable `id` and `install(context)`. The context gives it the core's services (libraries, items, scan, sign-in), the language files and its own data folder, and lets it add HTTP routes and listen for scans. Its own settings and database tables (named `plugin_<id>_…`, with their own migrations) come with the first plugin that needs them. The core never knows which plugins exist: each entry point passes its list, and two plugins with one id are refused.
- **Client core and plugins** (stage 2): the Android and Google TV app's core is sign-in, browsing and playing; the rest (continue watching, JaiPlayer hand-off, subtitles menu, …) are client plugins with the same shape.
- Plugins are compiled in (first-party), never downloaded and loaded at run time: that would run code nobody reviewed, and Android forbids it in most cases. Leaving a plugin out of an app's list leaves its code and its dependencies out of that app.

| Plugin | id | Adds | Stage |
|---|---|---|---|
| Browser pages | `web` | Setup, sign-in, libraries, browsing and playing in a browser | 1 |
| Discovery | `discovery` | Clients find the server on the home network | 2 |
| Watch state | `watch-state` | Progress, watched, resume, continue watching, next up | 2 |
| Local metadata | `local-metadata` | Posters, backdrops and `.nfo` next to the files; resized images | 3 |
| Media details | `media-info` | ffprobe details (duration, codecs, tracks), read after every scan | 3 |
| Subtitles | `subtitles` | Side-car and embedded text subtitles as WebVTT | 3 |
| TMDB | `tmdb` | Online metadata with the owner's own key | 3 |
| Transcoding | `transcode` | Remux and FFmpeg HLS transcoding, hardware encoding | 4 |
| Users | `users` | More users, per-library access, parental ratings | 5 |
| Folder watching | `folder-watch` | Rescan when files change | 5 |

The "What the server does" table above says what each feature does; the stage column there and here agree.

## Platforms: server and clients

| Platform folder | Role | Notes |
|---|---|---|
| `desktop/mac/` | **Server + desktop app** | macOS entry point and `.dmg`: runs the server, with a Compose Desktop window (and menu-bar item) to start and stop it, show its address, manage libraries and browse; playback hands off to JaiPlayer or the browser. The owner's Mac is the first server. |
| `desktop/windows/` | **Server + desktop app** | The same for Windows (tray item), `.msi`, built and tested on CI ([Testing](https://github.com/kumasumit/KS-Suite/blob/main/specs/testing.md)). |
| `mobile/android/` | **Client** | Android phone and tablet app: touch layout, Media3 ExoPlayer, "Open in JaiPlayer". |
| `tv/google-tv/` | **Client** | Google TV app: Compose for TV, Media3 ExoPlayer, D-pad first, "Open in JaiPlayer". The skeleton in `app/` moves here when its build is created. |
| `shared/` | Code used by several apps | Written once: `shared/api` (Kotlin Multiplatform: the wire models, used by all four apps), `shared/server` (the server core, used by macOS and Windows), `shared/plugins/<id>` (server plugins), and from stage 2 `shared/client` (Kotlin Multiplatform + Compose Multiplatform: the API client, state and screens of all four apps). |
| browser | **Client** | Simple pages served by the server, with the browser's own video player. Written in Kotlin on the server (HTML text), no JavaScript framework. |
| `mobile/ios/` | not a target | Stays empty (`.gitkeep`). |

- **Linux and NAS:** the server is a JVM program, so the same `.jar` runs on Linux. The suite has no Linux platform folder, so Linux is not a release target; a plain archive (and perhaps a container image) is a later decision for the owner.
- **Suite changes this needs:** StreamFlix's targets in the suite's [Platform folders](https://github.com/kumasumit/KS-Suite/blob/main/specs/platform-folders.md) table become `desktop/mac`, `desktop/windows`, `mobile/android`, `tv/google-tv` with `shared/`. The [cross-repo](https://github.com/kumasumit/KS-Suite/blob/main/specs/cross-repo.md) edge StreamFlix → JaiPlayer stays as it is, with an `http(s)` stream URL as the data. Both are changed in the KS-Suite repository, not here.
- All four apps are released together, with one version tag; the API carries a version number (`/api/v1`) so an older client can tell when the server is too new for it.

## Engines and libraries

Only what each stage needs, each justified when it is added ([Dependencies](dependencies.md)).

| Piece | Used for | Licence | Stage |
|---|---|---|---|
| Kotlin standard library, coroutines, JDK 17 | Everything; PBKDF2 and `SecureRandom` from the JDK | Apache-2.0; JDK GPL-2.0 with Classpath Exception | 1 |
| [Ktor](https://ktor.io/) server, CIO engine | The HTTP server: routing, JSON, byte ranges, sign-in, error pages; each plugin adds its own routes | Apache-2.0 | 1 |
| [SQLDelight](https://github.com/sqldelight/sqldelight) on SQLite (`sqlite-jdbc`) | The library database: type-safe Kotlin from `.sq` files, migration files checked in the build | Apache-2.0 (SQLite: public domain) | 1 |
| [`kotlinx.serialization`](https://github.com/Kotlin/kotlinx.serialization) JSON | The API's request and response bodies, on server and client | Apache-2.0 | 1 |
| SLF4J simple logger (desktop apps only) | Ktor's log output | MIT | 1 |
| `ks-utility` | Language files (English and Hindi), app updates | MIT, first-party | 1 |
| FFmpeg and ffprobe, **installed by the owner** | Media details, remuxing, transcoding | LGPL-2.1+, or GPL if the build has GPL parts | 3, 4 |
| Compose Multiplatform, lifecycle ViewModel | The screens and state of all four apps (`shared/client`) | Apache-2.0 | 2 |
| Media3 ExoPlayer (+ HLS), Compose for TV | Playback and TV focus on Android and Google TV | Apache-2.0 | 2 |
| TMDB API (a web service, no library) | Online metadata, owner's own key, off by default | TMDB terms of use; the attribution notice is shown | 3 |

- **FFmpeg is never bundled.** It runs as a separate program that the owner installs (Homebrew on macOS, a download or `winget` on Windows), the way JaiPlayer needs VLC. The server looks for it on `PATH` and in the usual places, lets the owner set its path, and shows a clear message when it is missing: direct play keeps working without it. Running a separate program keeps StreamFlix MIT whatever licence the owner's FFmpeg build has.
- **Architecture** (owner: "latest modern architecture"): coroutines throughout; the server in layers (`domain` services, `data` on SQLDelight, `http` routes on Ktor); the apps in one Kotlin Multiplatform module with one-way data flow (ViewModel + `StateFlow`, Compose Multiplatform screens), platform folders holding only entry points and packaging.
- **Size, speed and stability** are budgets checked by the build ([Quality budgets](quality-budgets.md)).
- **No Jellyfin API compatibility.** Jellyfin's own clients will not connect to StreamFlix. Copying its API would tie StreamFlix to a large GPL project's design; StreamFlix's API stays small and its own.

## Roadmap

Each stage is built and tested on `dev` and ends in something that works on its own. A release (dev → main → tag) happens only with the owner's OK.

1. **Server core (first version).** Gradle build (`shared/api`, `shared/server`, `shared/plugins/web`, `desktop/mac`, `desktop/windows`). The core: libraries, scanning and name parsing, SQLite, the owner account, the API (`/api/v1`: server info, sign-in, libraries, items, rescan, stream with byte ranges) and the plugin API. The first plugin, `web`: browser pages for setup, sign-in, browsing and playing. Run with `./gradlew :desktop:mac:run`. Tests: name parser, scanner on a temporary folder, byte ranges, password hashing, the API end to end against a server on a free port. Done when: on the Mac, a folder of films is added, scanned, listed in the browser and plays with seeking; English and Hindi pages pass the language-file check.
2. **The four apps.** macOS and Windows: the desktop window around the server (start/stop, address, libraries, browse, play through JaiPlayer or the browser). Android and Google TV: find or type the server, sign in, rows per library and "continue watching", detail page, playback with ExoPlayer (direct play), watch progress back to the server, "Open in JaiPlayer" with the stream URL. Tested on the phone and TV emulators and Windows CI ([Testing](https://github.com/kumasumit/KS-Suite/blob/main/specs/testing.md)). The APK stays under 15 MB.
3. **Media details and metadata.** ffprobe details, local posters and `.nfo`, resized images, subtitles, optional TMDB.
4. **Transcoding.** Decide direct play, remux or transcode from what the client says it can play; FFmpeg to HLS with hardware encoding; clean up old segments; limit how many run at once.
5. **Users and admin.** More users, per-library access, parental ratings, the remaining admin pages, folder watching.
6. **Packaging and release.** `.dmg` and `.msi` for the desktop apps, signed APK (and AAB) for Android and Google TV, through ks-utility's shared release workflow (its desktop inputs), Windows CI, app updates ([App updates](app-updates.md)) for the TV app and the server.

## Stage notes

- **Stage 1** (2026-11-23): the server core, the `web` plugin, the desktop entry points.
- **Stage 2** (2026-11-23): server plugins `watch-state` (its own SQLite file in its data folder) and `discovery`; signed stream links in the core (`POST /api/v1/items/{id}/link`, 6 hours, one item) for players that cannot send the sign-in header; `shared/client` (one Kotlin Multiplatform + Compose Multiplatform module for the four apps) with client plugins `watch-state` (continue watching, resume, progress) and `jaiplayer` (Open in KS JaiPlayer); `shared/desktop` (the server and its window); the one APK in `mobile/android` + `tv/google-tv`.
  - TV focus uses Compose Multiplatform's own focus handling (focus rings, starting focus, focus restored on Back), not `androidx.tv:tv-material`, which is Android-only and could not be shared with the desktop apps. The player takes OK, left and right, and Back from the remote.
  - The macOS and Windows apps have no player of their own (decision 5): Play opens a stream link in KS JaiPlayer when it is installed, else in the browser.
- **Stage 3** (2026-11-23): `shared/ffmpeg` (a helper library, not a plugin: finds the owner's FFmpeg in `STREAMFLIX_FFMPEG_DIR`, `PATH` or the usual places, and runs it with at most 4 processes at a time); server plugins `local-metadata` (posters and backdrops next to the files, Kodi `.nfo` with external entities refused), `tmdb` (off by default; the owner's key on its own settings page; only titles and years are sent; answers and images cached; TMDB's notice shown), `media-info` and `subtitles` (side-car SRT/VTT need nothing, ASS and embedded text tracks need FFmpeg; image subtitles wait for stage 4). App plugins `metadata` (posters at the card's size, kept in a cache of 60 images; plot, genres, rating), `media-info` (one line of details) and `subtitles` (a choice on the item's page; a default or forced track is chosen first; the TV player draws the lines). The browser pages show posters, the plot and subtitles through the same APIs.
  - Choice buttons (subtitles, language) keep the D-pad focus when chosen: one button whose colours change, not two kinds of button.
  - FFmpeg on the owner's Mac comes from Homebrew (a GPL build); StreamFlix only runs it as a separate program.
- **Stage 4** (2026-11-23): server plugin `transcode` and app plugin `transcode`.
  - **Decision:** each app sends what it plays (containers, codecs, largest height, bitrate; Android reads its decoders and display). Direct play when everything fits; **remux** (streams copied into HLS) when only the container does not fit; **transcode** to H.264/AAC when the video codec, height or bitrate does not, or to burn in a picture subtitle. Audio the client cannot play becomes AAC in both cases.
  - **HLS, as a normal video:** the server writes the whole playlist at once (VOD, segments of about 4 s, fMP4), so every player sees the length and can seek anywhere. FFmpeg makes the segments in order; a request far ahead of it, or behind where it started, restarts FFmpeg at that segment with the item's own timestamps. A transcode cuts every 4 s (a keyframe forced there); a remux cuts at the file's keyframes, read from its packets beforehand. Subtitles keep the item's timeline.
  - **Encoders:** the first hardware H.264 encoder that works (VideoToolbox on macOS; NVENC, Quick Sync or AMF on Windows), tested on a few frames, else `libx264`. On the owner's Mac: VideoToolbox.
  - **Limits:** two sessions at once by default (`transcode.error.busy` beyond; a session quiet for a minute gives way), a session idle for 10 minutes is stopped, the app stops its session when playback stops, the browser page when it is closed, and every session's files are deleted when it stops and when the server starts.
  - **Session files** are reached by the session id alone (128 random bits), as a stream link is.
  - **Browser pages:** the watch page asks the same question with the browser's `canPlayType` answers and plays the HLS stream natively (Chrome 156 and Safari do; Firefox does not, and says so).
  - The desktop apps keep handing playback to KS JaiPlayer or the browser (decision 5).
  - Name parsing: a title may start with a word that is also a release tag ("DTS Test"); a rescan now also updates items whose parsed name changed.
- **Stage 5** (2026-11-23): server plugins `users` and `folder-watch`.
  - **Users:** the owner adds and removes users and chooses, per user, the libraries they see and an age limit; videos above it are hidden, and videos without a certification can be hidden too. Certifications come from `.nfo` (`<mpaa>`, read by `local-metadata`) and are turned into ages for many countries (US, UK, India, Germany). The rules are an access rule, so the core's API, every plugin (watch progress, details, subtitles, transcoding) and the browser pages hide the same items; a hidden item answers "not found". Every user can change their own password, which ends their other sessions. The owner's page is in the browser (Settings → Users).
  - **Folder watching:** every library folder and the folders inside are watched; a library is rescanned when its files have been quiet for 10 s (a copy changes a file many times), new libraries are picked up within a minute, and everything is rescanned every 6 hours for changes the system does not report (network drives).
  - **Browser pages:** the owner can remove a library (after a question; the files stay).
  - The apps needed no change: a user simply sees what they may see.
- **Stage 6** (2026-11-24): installers, app updates and CI.
  - **Installers:** macOS `.dmg` (Apple silicon) and Windows `.msi` from Compose Desktop, with an app icon, only the Java modules the app uses, and SQLite's native library for that OS only (an artifact transform; the macOS app went from 61 to 51 MB). The `.dmg` is 72.8 MB with its own Java runtime. The packaged app was started on the owner's Mac: it scans, finds FFmpeg and uses VideoToolbox.
  - **Android and Google TV:** the signed APK (R8, 1.9 MB) and AAB from `:mobile:android`, signed from the repository's secrets (never committed).
  - **Release workflow:** ks-utility's shared `kotlin-app-release.yml` with `mac-module`, `windows-module`, `test-tasks: check`; file names follow the app-update rules (`ks-streamflix-v<version>.apk`, `-macos-arm64.dmg`, `-windows-x64.msi`).
  - **CI** (`.github/workflows/ci.yml`): Linux runs every check with FFmpeg; Windows runs the tests, builds the `.msi`, installs it silently, starts the installed server, checks it answers, and uninstalls it; macOS builds the `.dmg`, checks its size and starts the packaged server. Each keeps its unsigned file for 14 days.
  - **App updates** ([App updates](app-updates.md)): plugin `app-update` in all four apps.
- **After v1.0.0** (2026-11-24, for v1.0.1):
  - ks-utility moved from v0.3.0 to v0.6.0: the server's text look-up is ks-utility's `Translator`, the apps' text comes from `lang.mjs compose` (Compose-safe), the release workflow is `kotlin-app-release.yml@v0.6.0`.
  - **Phone and Google TV become separate apps** (KS-Suite [platform folders](https://github.com/kumasumit/KS-Suite/blob/main/specs/platform-folders.md)'s rule, as KS JaiPlayer did). The suite's rule is that an app first releases its new update checker in a one-APK release, so v1.0.1 stays one APK and, on a TV, already asks for the `-google-tv` file; the split (`mobile/android` and `tv/google-tv` as two apps with the same package name and keystore, the TV versionCode plus 1,000,000,000, the activity and manifest in `shared/client`, the release workflow's `tv-module`) follows in the release after it (v1.1.0, below).
  - Picture subtitles: burning in is now tested with a real PGS track (the test writes one), and the frame of the HLS output shows it.
  - Browsers without HLS (Firefox): each session also serves `stream.mp4`, the init segment and the segments in order as one fragmented MP4 (`?at=<seconds>` starts later); the watch page uses it and reloads it for a seek past what it has. Checked by tests and in Chrome; Firefox itself is not installed on the owner's Mac.
  - TMDB: an opt-in test against the real TMDB runs with `STREAMFLIX_TMDB_KEY` set; without a key it does nothing.
  - Plans for the larger features, for the owner's approval: [music](plan-music.md), [photos](plan-photos.md), [live TV and recording](plan-live-tv.md), [casting](plan-casting.md), [remote access](plan-remote-access.md).
  - **Open in KS JaiPlayer on Android** needs JaiPlayer's `ACTION_VIEW` filter to accept `http`/`https` stream URLs (today it lists `content` and `file`). Until it does, the button stays hidden, because the app checks that JaiPlayer can open the link. This is a change in ks-jaiplayer.
- **v1.1.0** (2026-11-25): phone and Google TV are separate apps.
  - `mobile/android` (phone launcher entry) and `tv/google-tv` (Leanback entry, banner, `com.ks.streamflix.FORM_FACTOR` meta-data) are two `android.application` modules configured once in the root `build.gradle.kts`: package name `com.ks.streamflix`, the same version and keystore, the TV versionCode plus 1,000,000,000.
  - The activity, the manifest with its permissions, `queries` and update `FileProvider`, the icon, the language list and the R8 rules moved to `shared/client` (androidMain); `TvSupport.kt` (UiModeManager) is gone: the layout follows the app (`isTvBuild`), not the device.
  - Each app updates to its own file (`UpdatePlatform.GOOGLE_TV` → `-google-tv.apk`, `ANDROID` → the plain APK); copies of v1.0.1 on a TV already ask for the TV file.
  - The release publishes `ks-streamflix-v<version>.apk`/`.aab` and `-google-tv.apk`/`.aab` (`tv-module`); `checkSize` measures both APKs; CI builds both debug APKs.

## Decisions (approved)

1. **Four apps:** macOS and Windows are the server with a desktop window; Android and Google TV are clients. Kotlin/JVM server (code in `shared/`); the jar also runs on Linux unofficially.
2. **Android and Google TV: separate apps** (changed 2026-11-25 by the suite rule): a phone app and a Google TV app with the same package name, from v1.1.0. Until then one APK switched its layout on a TV.
3. **Modern architecture** (changed by the owner the same day): Ktor with coroutines for the server, SQLDelight on SQLite for the database, `kotlinx.serialization` for JSON, and one Kotlin Multiplatform + Compose Multiplatform client module for the four apps.
4. **Transcoding engine:** FFmpeg installed by the owner and run as a separate program, never bundled; from stage 4.
5. **Desktop playback:** the macOS and Windows apps hand playback to JaiPlayer (or the browser) instead of having their own player, which keeps them small.
6. **A minimal core with plugins:** every feature outside the core is a compiled-in plugin, so each app includes only what it lists.
7. **Budgets:** stability, app size and speed are numbers in one file that the build checks ([Quality budgets](quality-budgets.md)).
