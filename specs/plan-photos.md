---
type: Plan
title: Photos plan
description: Photo libraries in KS StreamFlix as plugins: folders and dates, thumbnails from the core's image service, slideshows on the TV, with EXIF read by the JDK.
tags: [plan, photos, plugins]
status: approved
---

# Photos plan

> Approved by the owner on 2026-11-27; nothing is built yet.

**What it does.** A library kind `photos`: folders of pictures (`jpg`, `png`, `heic` where FFmpeg can read it, `webp`) and short videos, shown by folder and by month, full screen with swipe or D-pad, and a slideshow on the TV with a chosen interval.

**How (plugins).**
- Server plugin `photos`: reads the date and orientation from EXIF (a small reader of our own over the JDK's `ImageIO` metadata; no library), sizes and rotates pictures through the core's image service (new widths are snapped and cached as for posters), and lists by folder and month at `/api/v1/plugins/photos/…`. HEIC is turned into JPEG with the owner's FFmpeg when it can read it.
- The core needs a `LibraryKind` `photos`, so the scanner accepts picture files for it.
- App plugin `photos`: a grid that loads thumbnails at their display size (the image cache already caps memory for low-RAM TVs), a viewer, and the slideshow; the TV keeps the screen on during a slideshow.

**Engines and licences.** JDK `ImageIO` (and FFmpeg for HEIC). No new library.

**Measurements.** Thumbnail time for a 24-megapixel JPEG; images per second while scrolling a month on the TV emulator.

**Open questions for the owner.** Faces or places (needs a model or a map service: probably not)? Sharing an album link with someone outside the home (depends on [Remote access](plan-remote-access.md))?
