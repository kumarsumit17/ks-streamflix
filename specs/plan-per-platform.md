---
type: Plan
title: Per-platform optimization plan
description: Approved plan for making each KS StreamFlix build (macOS, Windows, Android phone, Google TV) its own app tuned to its OS, with an audit of each build today and what changes per OS.
tags: [plan, platforms, macos, windows, android, google-tv, performance, accessibility]
---

# Per-platform optimization plan

**Status: approved by the owner on 2026-11-25** (decisions in section 3). Step 1, the phone/TV split, is built (9c1fdda, for v1.1.0). Built steps are marked in section 4; steps 2 (versioning per platform, v1.2.0) 3 (the desktop always-on server, v1.3.0) and 4 (bundled FFmpeg, v1.4.0) are built.

Owner's rule for every KS-Suite app (2026-11-25, KS-Suite [platform folders](https://github.com/kumasumit/ks-suite/blob/dev/specs/platform-folders.md), "Each platform build is optimized for its OS"): each build is its own app, made the best it can be on its OS, with as few dependencies as possible and nothing the user has to install separately. Modelled on [ks-jaiplayer's plan](https://github.com/kumasumit/ks-jaiplayer/blob/dev/specs/plan-per-platform.md).

StreamFlix differs from JaiPlayer in one way that shapes everything below: **the desktop builds are the server** (library, metadata, streaming, transcoding) plus a window that browses it and hands videos to KS JaiPlayer, while **the Android builds are clients** with their own Media3 player. So on desktop the work is about being a good always-on background service; on Android it is about being a good player.

## 1. Audit: what each build does today (v1.0.1, 2026-11-25)

| | macOS (`desktop/mac`) | Windows (`desktop/windows`) | Android phone (`mobile/android`) | Google TV (`tv/google-tv`) |
|---|---|---|---|---|
| Entry point | `Main.kt`: data in `~/Library/Application Support`, plugin lists; everything else in `shared/desktop` (`runDesktopApp`) | Same, data in `%APPDATA%`; identical plugin lists | `MainActivity` (single task); TV detection at run time | **Not its own app in v1.0.1**: a library merged into the phone APK. Since 9c1fdda (v1.1.0) its own application module (Leanback required, touchscreen not required, banner, `FORM_FACTOR=tv`, versionCode + 1,000,000,000) |
| Server lifetime | **Closing the window stops the server**; no menu-bar item, no start at login, so phones and TVs lose the library whenever the window is closed | Same: no tray icon, no start at login, no Windows service | n/a (client) | n/a (client) |
| Sleep while streaming | **Not prevented**: the Mac may sleep mid-film on a TV | Not prevented | n/a | n/a |
| Look | Compose window, system appearance (`apple.awt.application.appearance=system`), no menu bar of its own | Compose in a Swing frame, light title bar | Material 3 Compose, `Theme.Material.NoActionBar` | Same UI with TV focus rings, larger type |
| Playback | None built in: KS JaiPlayer if installed, else the browser | Same | Media3 ExoPlayer 1.11.1 + HLS, `SurfaceView`, device profile from `MediaCodecList` decides direct/remux/transcode | Same as phone |
| Audio focus, noisy | n/a | n/a | **Not handled** (no `AudioAttributes`, no becoming-noisy pause) | Same |
| Keep screen on while playing | n/a | n/a | **Not set** in `Media3Player` (no `keepScreenOn` found); needs a check on a phone with a short timeout | Same (TVs usually show the screensaver) |
| Media session | None | None | **None**: no lock-screen or notification controls, no PiP | **None**: Assistant "pause" and the remote's media keys outside the app do nothing |
| TV-specific playback | | | | No frame-rate matching, no passthrough (the profile lists decoders only, so AC-3/DTS are transcoded instead of sent to the receiver), HDR untested |
| Transcoding (server) | FFmpeg found on the machine (Homebrew); `h264_videotoolbox` first, `libx264` fallback | FFmpeg found on the machine; `h264_nvenc`, `h264_qsv`, `h264_amf`, then `libx264` | n/a | n/a |
| **Needs installing separately** | **FFmpeg** (and KS JaiPlayer to play) | **FFmpeg** (and KS JaiPlayer) | Nothing | Nothing |
| Packaging | dmg (arm64), jlink with 9 modules, ProGuard off, SQLite natives trimmed to the OS; 79.4 MB | msi, same modules, ProGuard off; 69 MB; no per-user install, no start-menu folder choice | APK 1.96 MB, R8 and resource shrinking on | In the phone APK (v1.0.1); its own APK and AAB from v1.1.0 |
| Version | One tag `vX.Y.Z` for all builds | Same | Same | Same |
| Accessibility | Few `contentDescription`s; Tab focus works via `focusRing` | Same, and `jdk.accessibility` is not in the runtime, so Narrator and NVDA cannot see the window | TalkBack reads Compose semantics; label gaps on cards | D-pad focus on every screen; label gaps |

## 2. The plan, per platform

Dependencies: no new library on desktop (JNA is not used today; where a native call is needed, the JDK or a small JNA addition is weighed in its step and reported as [constraints](constraints.md) require). On Android only `androidx.media3:media3-session` (same family as the player already used) is proposed.

### 2.1 macOS (`desktop/mac/`)

- **Always-on server, the Mac way**
  - A menu-bar item (`java.awt.SystemTray` shows as a menu-bar extra on macOS) with the server's state, its address, Open window, Pause scanning and Quit. Closing the window leaves the server running; Quit stops it.
  - "Start at login" in Settings, through a LaunchAgent plist in `~/Library/LaunchAgents` (no helper app, no new dependency); the app starts hidden to the menu bar.
  - Whether the Dock icon can go away while only the menu-bar item is open is checked in the step (AWT fixes `apple.awt.UIElement` at start-up); if not, a start at login launches with it set.
  - **Stay awake while streaming**: `caffeinate -i -w <pid>` started while at least one stream or HLS session is active and stopped after it; the display may sleep, the Mac does not.
- **Transcoding with nothing to install**: bundle an **LGPL FFmpeg build** (no GPL parts, so no `libx264`) inside the app, arm64 and Intel, with VideoToolbox for H.264/HEVC encoding and decoding. Homebrew FFmpeg stays usable through `STREAMFLIX_FFMPEG_DIR`. Size budget raised by the owner only if needed (see section 3).
- **Look**: a real menu bar (File ▸ Add library, Window, Help), ⌘, opens Settings, ⌘W closes the window (server keeps running), transparent title bar.
- **Performance and startup**: measure cold start of server + window; AppCDS archive tried, kept only if it saves more than 20%; ProGuard on the release build tried, kept only if all tests pass.
- **Accessibility**: VoiceOver labels on every card and button.

### 2.2 Windows (`desktop/windows/`)

- **Always-on server, the Windows way**
  - A notification-area (tray) icon with the same menu as macOS; closing the window minimises to it.
  - "Start when I sign in" through the `HKCU\...\Run` registry key, written by the app (`reg.exe`, no new dependency). A Windows service is left out: it needs admin rights and a separate account to reach the user's folders.
  - **Stay awake while streaming**: `SetThreadExecutionState(ES_SYSTEM_REQUIRED | ES_CONTINUOUS)` while a stream is active (one JNA call, or a PowerShell-free `powercfg /requestsoverride` if JNA is not wanted).
  - Firewall: the msi adds an inbound rule for the app on private networks, so phones and TVs can reach port 8742 without the user finding the Defender dialog.
- **Transcoding with nothing to install**: bundle an LGPL FFmpeg build for x64 with `h264_mf` (Media Foundation, on every Windows), `h264_nvenc`, `h264_qsv` and `h264_amf`; the encoder probe already picks the first that works. `libx264` is not in an LGPL build, so `h264_mf` is the software-free fallback.
- **Look**: system look and feel for the Swing parts, dark title bar (`DWMWA_USE_IMMERSIVE_DARK_MODE`) when the app is dark, the native folder picker for "Add library".
- **Packaging**: per-user install by default (no admin prompt), start-menu shortcut, `jdk.accessibility` added to the runtime so Narrator and NVDA see the app.
- **Checks**: CI already installs, starts and uninstalls the msi; add a check that the tray icon starts and the server survives closing the window. Hand checks by the owner never block a release.

### 2.3 Android phone (`mobile/android/`, shared Android code in `shared/client/src/androidMain`)

- **Media stack**: `AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MOVIE)` with audio focus handled, pause when headphones are unplugged, decoder fallback on, `keepScreenOn` while playing.
- **Media session** (`media3-session`): lock-screen and notification controls, Bluetooth headset buttons.
- **Picture-in-picture**: auto-enter on Android 12+ (`setAutoEnterEnabled`), `onUserLeaveHint` on 8–11, play/pause in the PiP window, source-rect hint.
- **Touch**: double tap left/right seeks 10 s, horizontal drag seeks with a time preview, landscape while playing, predictive back.
- **Size and start**: R8 already on; add a baseline profile for start-up and the home screen; target cold start under 800 ms on a mid-range phone; APK budget stays 15 MB.
- **Accessibility**: labels on every card, the system caption style (`CaptioningManager`) for subtitles, font scale 200% without clipping, 48 dp targets.

### 2.4 Google TV (`tv/google-tv/`)

- **Its own app first** (built, 9c1fdda, ships in v1.1.0): a separate application module with the same package, the TV versionCode offset, leanback required, touchscreen not required, `UpdatePlatform.GOOGLE_TV`; shared Android code in `shared/client/src/androidMain`.
- **Media stack**:
  - **Frame-rate matching**: "Match the video's frame rate" (off / seamless only / always), default seamless only, through `setVideoChangeFrameRateStrategy`.
  - **Audio passthrough**: the device profile adds AC-3, E-AC-3, DTS and TrueHD when `AudioCapabilities` says the HDMI sink takes them, so the server sends them untouched instead of transcoding to AAC.
  - **HDR**: the profile reports HDR10/HLG/Dolby Vision support from `Display.HdrCapabilities`, so HDR files are not tone-mapped or transcoded needlessly; `SurfaceView` stays.
- **Media session** (same `media3-session`): remote media keys and Google Assistant ("pause", "skip forward 30 seconds") reach the player.
- **D-pad**: hold left/right to seek faster (10 s, 30 s, 1 min); up opens subtitles and audio tracks in a side panel that keeps focus; every screen checked on the 32–100 inch emulators.
- **Continue watching on the home screen** (Watch Next channel, `androidx.tvprovider`), for items with a saved position (decision 4).
- **Size and start**: R8 and a baseline profile, as the phone; cold start under 1.5 s on a Chromecast with Google TV class device.

### 2.5 Shared

- The always-on server logic (menu-bar/tray menu, keep-awake hooks, start at login) goes behind one `DesktopService` interface in `shared/desktop`; the macOS and Windows implementations live in their platform folders.
- The server exposes "a stream is active" to that interface (from the transcode sessions and stream requests), so keep-awake follows real use.
- Device-profile extensions (passthrough, HDR) go in `shared/api` (`DeviceProfile`) with defaults that keep older clients working; the server's decision code in `shared/plugins/transcode` uses them.
- New interface text in English and Hindi (`lang/en.json`, `hi.json`) with the Hindi style guide; the language-file check runs in `check`.

## 3. Owner's decisions (2026-11-25)

1. **Own version per platform** (changed by the owner later on 2026-11-25, with the suite rule [Platform tags](https://github.com/kumasumit/ks-suite/blob/dev/specs/versioning-signing.md)): tags `android-vX.Y.Z`, `google-tv-vX.Y.Z`, `mac-vX.Y.Z`, `windows-vX.Y.Z`; a plain `vX.Y.Z` still releases all four. v1.1.0 (the split) ships plain on ks-utility v0.9.0; v1.2.0 is the plain **bridge release** whose builds already read the release list (ks-utility v0.10.0); the first platform tags come after it. The API version keeps clients and servers of different versions working together.
2. **FFmpeg: bundled** (LGPL build, VideoToolbox / Media Foundation and hardware encoders, no `libx264`). It adds about 25–35 MB to the dmg and msi; the installer budget (`size.installer.macMb=80`) is raised in that step and the new number reported to the owner. `STREAMFLIX_FFMPEG_DIR` still overrides it.
3. **Start at login: on by default**, hidden to the menu bar or tray; can be turned off in Settings.
4. **Continue watching on the Google TV home screen: yes** (`androidx.tvprovider`, a new dependency).
5. **`androidx.media3:media3-session`: approved** with the plan, for the media session on phone and TV.

## 4. Build order and testing

Each step is one or more commits on `dev`, tested on its platform before the next; each step is released with the next tag once its tests pass (no approval asked first), and every release is tried on the emulators first (owner does real-device checks).

| Step | What | Where | Tested on |
|---|---|---|---|
| 1 | **Built** (9c1fdda, released in v1.1.0): phone/TV split | `tv/google-tv`, `mobile/android`, release workflow `tv-module` | Phone and TV emulators, release rehearsal |
| 2 | **Built** (for v1.2.0): versioning per platform: ks-utility v0.10.0 (release workflow `platform` input, `AppUpdate.evaluateReleases` in the phone, TV and desktop updaters), then v1.2.0 as the plain bridge release | `ks-utility` pin, `.github/workflows/release.yml`, `shared/client` updaters | Unit tests of the update choice; a rehearsal; the emulators' update check finds v1.2.0 |
| 3 | **Built** (v1.3.0): desktop always-on server: window close, menu bar / tray, start at login, keep awake | `shared/desktop`, `desktop/mac`, `desktop/windows` | This Mac; Windows CI (install, start, close window, server still answers) |
| 4 | **Built** (v1.4.0): bundled FFmpeg | packaging in `desktop/mac`, `desktop/windows`; `shared/ffmpeg` locate order | Transcode tests against the bundled binary on CI (all three OSes); dmg/msi size check |
| 5 | Android media stack and session, PiP, keep screen on, touch | `shared/client/src/androidMain`, `mobile/android` | Phone emulator |
| 6 | Google TV: frame rate, passthrough and HDR in the profile, hold-to-seek, side panel, Watch Next | `tv/google-tv`, `shared/api`, `shared/plugins/transcode` | Every TV emulator 32–100 inch; the owner's TV for passthrough and frame rate |
| 7 | Look per desktop OS, accessibility pass on all four | each platform, `shared/client` | VoiceOver (Mac), TalkBack (phone, TV), Narrator by the owner |
