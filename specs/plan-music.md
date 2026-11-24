---
type: Plan
title: Music plan
description: Music libraries in KS StreamFlix as plugins: scanning tags, albums and artists, playing on every app, with gapless playback and no new engine.
tags: [plan, music, audio, plugins]
status: draft
---

# Music plan

> Draft for the owner's approval; nothing is built.

**What it does.** A library kind `music`: folders of audio files (`mp3`, `flac`, `m4a`/`aac`, `ogg`/`opus`, `wav`) shown by artist, album and track, with album art, playlists and "play album / shuffle". Listening history and "continue" reuse the `watch-state` plugin.

**How (plugins).**
- Server plugin `music`: reads tags (title, artist, album, album artist, track and disc number, year, genre, embedded cover) with `ffprobe` (already found by `shared/ffmpeg`; no new library), stores them in its own database, and adds `/api/v1/plugins/music/…` (artists, albums, tracks, playlists). Covers go through the core's image service (a metadata provider). Audio streams use the core's direct play; formats a client cannot play are transcoded to AAC by the `transcode` plugin (audio only).
- The core needs one change: a third `LibraryKind` (`music`), so the scanner accepts audio files for it and leaves names to the plugin.
- App plugin `music`: an artist/album/track browser and a mini player that keeps playing while browsing (Media3's background playback on Android, with a media notification; on TV a full-screen "now playing"). Desktop: hands albums to KS JaiPlayer as a playlist, or plays in the browser pages.

**Engines and licences.** ffprobe (owner's FFmpeg), Media3 ExoPlayer (Apache-2.0, already in the APK; gapless needs no extra module). No tag library.

**Budgets.** Scan speed for tags (files per second with ffprobe, run 4 at a time); the APK stays under 15 MB.

**Open questions for the owner.** Lyrics (`.lrc` files)? Last.fm-style scrobbling (off by default)? Android Auto (needs a MediaBrowserService)?
