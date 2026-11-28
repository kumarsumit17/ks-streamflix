---
type: Plan
title: KS JaiPlayer as the default player
description: Approved plan for StreamFlix to play through KS JaiPlayer by default on every platform, with its fallbacks, the DEV variant, and what StreamFlix will later need from JaiPlayer.
tags: [plan, playback, jaiplayer, cross-repo]
---

# KS JaiPlayer as the default player

The owner decided on 2026-11-28 that StreamFlix plays through KS JaiPlayer by default. **Approved by the owner on 2026-11-28. It is built when the owner makes StreamFlix the active app** (KS-Suite [working method](https://github.com/kumasumit/ks-suite/blob/dev/specs/working-method.md)). The work is a feature, so it raises MINOR.

## Today

**The contract** (KS-Suite [cross-repo](https://github.com/kumasumit/ks-suite/blob/dev/specs/cross-repo.md), `ks-streamflix` → `ks-jaiplayer`; built on JaiPlayer's side):
- An `ACTION_VIEW` intent with MIME type `video/*` or `audio/*`.
- An http(s) URL, explicit package `com.ks.jaiplayer`, and `EXTRA_TITLE`.
- JaiPlayer plays signed links with no extra headers, and plain http for the home server.

**In StreamFlix:**
| Platform | Play | JaiPlayer |
|---|---|---|
| Phone, Google TV | Built-in Media3 player (`AndroidHost.Player`), with the server's direct/remux/HLS decision, resume, subtitles and progress saved | Item action "Open in KS JaiPlayer" (`JaiPlayerPlugin`), shown only when the intent resolves (`AndroidJaiPlayer.canOpen`); `openExternally` sends an empty title |
| macOS, Windows | No built-in player (`hasPlayer = false`): Play opens a signed link in JaiPlayer when `/Applications/KS JaiPlayer.app` or `KS JaiPlayer.exe` exists, otherwise in the browser | `open -a` or the exe with the URL; no title |

## The change

1. **Play uses JaiPlayer first.** Play sends a signed stream link (`POST /api/v1/items/{id}/link`, valid for 6 hours) and the item's title to JaiPlayer when it is installed. Otherwise Play uses the fallback below. This is one setting, **Player**:
   - phone and Google TV: KS JaiPlayer (default) or Built-in;
   - desktop: KS JaiPlayer (default) or Browser.

   The player that is not chosen stays available as an item action ("Play here" or "Open in browser").
2. **When JaiPlayer is not installed:**
   - phone and Google TV fall back to the built-in player, so playback never breaks;
   - desktop falls back to the browser page, as it does today.

   Settings show "KS JaiPlayer is not installed" with a link to JaiPlayer's GitHub releases page. On the phone and desktop the link opens the page; on Google TV the address is shown as text, since a TV often has no browser. Nothing is downloaded or installed by StreamFlix.
3. **Keep the built-in player where JaiPlayer can't play the item yet.** On Android, JaiPlayer has the same decoders as StreamFlix (both use Media3), and today it gets only the direct file. When the server decides an item needs a remux, a transcode or a burned-in subtitle, Play stays in the built-in player. This lasts until JaiPlayer can take an HLS link (future asks). On desktop, libVLC plays almost every file directly, so the direct link is enough.
4. **The DEV variant** (KS-Suite release cycle, "DEV and PROD side by side"):
   - StreamFlix PROD hands off only to `com.ks.jaiplayer`;
   - StreamFlix DEV tries `com.ks.jaiplayer.dev` first, then `com.ks.jaiplayer`, so a DEV build can be tested with either player.

   Both package names go in the manifest's `<queries>`. On desktop, DEV looks for the DEV app first, then the PROD app. The DEV app's name and install path (for example `KS JaiPlayer DEV.app`) are to be confirmed with JaiPlayer's DEV packaging.
5. **Small fixes along the way:**
   - send the title on every platform;
   - send `audio/*` for audio items;
   - on desktop, also look in `~/Applications` and the per-user Windows folder.
6. **Tests:**
   - the choice of player, with a fake host for each case: JaiPlayer installed, not installed, or a DEV build;
   - the phone and TV emulators with JaiPlayer installed and without it;
   - the Mac with and without JaiPlayer.

None of this changes the contract, so `cross-repo.md` stays as it is. The DEV package name is the only new detail, and it follows the suite's DEV rule.

## What the hand-off loses today

With JaiPlayer playing, the server learns nothing back:
- no watch progress or "continue watching", and no resume;
- no server subtitles (WebVTT, picture subtitles burned in);
- no remux or transcode for codecs the device can't play.

Audio tracks inside the file are fine: JaiPlayer picks them itself. The setting in step 1 lets a user keep the built-in player on Android when they want these features.

## Future asks of JaiPlayer (not built now)

These go to the owner for ks-jaiplayer. Each one changes the contract and updates `cross-repo.md` on both sides.
1. **A start position:** an extra (milliseconds) on Android, and an argument on desktop, so JaiPlayer resumes where StreamFlix says.
2. **Position back:** when playback ends, JaiPlayer returns the position and the length, so StreamFlix can save watch progress. On Android this can be an activity result. A way that works on every platform: StreamFlix passes a signed progress URL that JaiPlayer calls now and then and on exit.
3. **Subtitles:** a list of WebVTT URLs with names and languages (on Android, extras; on desktop, arguments).
4. **Audio track and subtitle preferences:** the user's preferred languages, so JaiPlayer picks the same tracks as StreamFlix would.
5. **HLS and headers:** accept StreamFlix's HLS links (remux, transcode, burned subtitles). An optional header extra is needed only if a link can't be signed. Signed links stay the rule.
6. **Desktop hand-off arguments:** a documented command line (`--title`, `--start`, `--sub`), so the desktop matches Android.

## Later: a shared playback module

Later, StreamFlix will embed JaiPlayer's playback through a shared module instead of handing off to a separate app. That would give back progress, subtitles and HLS in one place. The KS JaiPlayer session is writing that design note; this plan doesn't design it. When it lands, this plan's steps 1–3 are revisited.
