---
type: Plan
title: Live TV and recording plan
description: Live TV in KS StreamFlix as plugins: tuners and IPTV playlists, a programme guide from XMLTV, and recordings into a library, with FFmpeg for the streams.
tags: [plan, live-tv, dvr, iptv, plugins]
status: draft
---

# Live TV and recording plan

> Draft for the owner's approval; nothing is built.

**What it does.** Channels from network tuners (HDHomeRun-style devices on the home network) and from M3U IPTV playlists the owner adds, a programme guide (EPG), watching live with pause, and scheduled recordings that land in a library as normal items.

**How (plugins).**
- Server plugin `live-tv`: channel sources (tuner discovery over HTTP, M3U parsing), guide data from XMLTV files or URLs the owner gives (parsed with the JDK's XML parser, external entities refused as for `.nfo`), and live streams remuxed or transcoded to HLS by the `transcode` plugin's sessions (a live session has no end and keeps a time-shift window on disk).
- Server plugin `dvr`: schedules (one programme, series, time slot), records with the owner's FFmpeg into a folder of a library, and lets `folder-watch` add the recordings; disk-space limits and padding before and after.
- App plugin `live-tv`: a channel row on the home screen, a guide grid that works with the D-pad, and the player for live HLS (Media3 already plays it).

**Engines and licences.** FFmpeg (owner's), Media3 HLS (in the APK). Guide data is the owner's; no guide service is bundled or contacted by default.

**Budgets.** Channel switch time (request to first frame); recording start accuracy.

**Open questions for the owner.** Which tuner hardware or IPTV source is in the home? Is a guide source available (for India, XMLTV sources are sparse)? Commercial-skip is out of scope.
