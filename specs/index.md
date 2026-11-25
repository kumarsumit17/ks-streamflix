---
okf_version: "0.2"
---

# Product specifications

* [Overview](overview.md) - What KS StreamFlix is for, the platforms it runs on and its tech stack.
* [App updates](app-updates.md) - How KS StreamFlix finds a newer version of itself on GitHub, shows it in Settings and installs it, using the suite's shared app-update helpers.

# Plans

* [Media server plan](media-server-plan.md) - The plan to turn KS StreamFlix into a free, open-source home media server with macOS, Windows, Android and Google TV apps: features, platforms, engines and libraries with their licences, and a staged roadmap.

* [Music plan](plan-music.md) - Music libraries in KS StreamFlix as plugins: scanning tags, albums and artists, playing on every app, with gapless playback and no new engine.
* [Photos plan](plan-photos.md) - Photo libraries in KS StreamFlix as plugins: folders and dates, thumbnails from the core's image service, slideshows on the TV, with EXIF read by the JDK.
* [Live TV and recording plan](plan-live-tv.md) - Live TV in KS StreamFlix as plugins: tuners and IPTV playlists, a programme guide from XMLTV, and recordings into a library, with FFmpeg for the streams.
* [Casting plan (DLNA and Chromecast)](plan-casting.md) - Playing StreamFlix items on other screens: as a DLNA/UPnP media server for smart TVs, and by handing items to KS Beam for DLNA renderers and Google Cast receivers.
* [Remote access plan](plan-remote-access.md) - Reaching the home server from outside the home safely: off by default, HTTPS with a certificate, sign-in hardening, and the ways in (port forwarding, a VPN, or a relay), with their trade-offs.
* [Per-platform optimization plan](plan-per-platform.md) - Draft plan for making each KS StreamFlix build (macOS, Windows, Android phone, Google TV) its own app tuned to its OS, with an audit of each build today and what changes per OS, awaiting the owner's approval.

# Policies

* [Constraints](constraints.md) - The platform constraints every change to KS StreamFlix must keep.
* [Quality budgets](quality-budgets.md) - Stability, app size and speed as numbers in one file that the build and tests check automatically, so a change that makes an app bigger, slower or less stable fails.
* [Dependencies](dependencies.md) - Third-party dependencies are kept to the minimum; the order of preference and what must be reported for each new one.
