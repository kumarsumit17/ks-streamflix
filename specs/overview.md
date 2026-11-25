---
type: Specification
title: Overview
description: What KS StreamFlix is for, the platforms it runs on and its tech stack.
tags: [overview, platform, stack]
status: draft
---

# Overview

A free, open-source home media server: it scans the owner's video folders and streams them to its own apps on the home network. The full plan and roadmap: [Media server plan](media-server-plan.md).

| | |
|---|---|
| **Server** | macOS and Windows (`desktop/mac`, `desktop/windows`); core in `shared/server` |
| **Clients** | Android phone and Google TV (separate apps since v1.1.0; one APK before), and browser pages served by the server |
| **Server stack** | Kotlin/JVM 17, the JDK's HTTP server, SQLite (`sqlite-jdbc`), `kotlinx.serialization`; FFmpeg from stage 4, installed by the owner |
| **Client stack** | Kotlin, Compose (Compose for TV on a TV), Media3/ExoPlayer |

- Package: `com.ks.streamflix`. Android minSdk 26 · targetSdk latest stable.
- Stages 1 to 6 are built (stage 6: installers, app updates, CI); the first release waits for the owner's OK. Stages (stage 3: posters, details, media details and subtitles; stage 4: transcoding; stage 5: users, age limits and folder watching): the server with browser pages, and the four apps (macOS and Windows: server with a window; Android and Google TV: one APK).
