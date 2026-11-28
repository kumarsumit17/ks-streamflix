---
okf_version: "0.2"
---

# Product specifications

* [Overview](overview.md) - What KS StreamFlix is for, the platforms it runs on and its tech stack.
* [DEV builds side by side with production](dev-variant.md) - How a DEV build of KS StreamFlix (-PksVariant=dev) installs and runs next to the production app on every platform without touching it.
* [App updates](app-updates.md) - How KS StreamFlix finds a newer version of itself on GitHub, shows it in Settings and installs it, using the suite's shared app-update helpers.

# Plans

* [Media server plan](media-server-plan.md) - The plan to turn KS StreamFlix into a free, open-source home media server with macOS, Windows, Android and Google TV apps: features, platforms, engines and libraries with their licences, and a staged roadmap.

* [Music plan](plan-music.md) - Music libraries in KS StreamFlix as plugins: scanning tags, albums and artists, playing on every app, with gapless playback and no new engine.
* [Photos plan](plan-photos.md) - Photo libraries in KS StreamFlix as plugins: folders and dates, thumbnails from the core's image service, slideshows on the TV, with EXIF read by the JDK.
* [Live TV and recording plan](plan-live-tv.md) - Live TV in KS StreamFlix as plugins: tuners and IPTV playlists, a programme guide from XMLTV, and recordings into a library, with FFmpeg for the streams.
* [Casting plan (DLNA and Chromecast)](plan-casting.md) - Playing StreamFlix items on other screens: as a DLNA/UPnP media server for smart TVs, and by handing items to KS Beam for DLNA renderers and Google Cast receivers.
* [Remote access plan](plan-remote-access.md) - Reaching the home server from outside the home safely: off by default, HTTPS with a certificate, sign-in hardening, and the ways in (port forwarding, a VPN, or a relay), with their trade-offs.
* [Per-platform optimization plan](plan-per-platform.md) - Approved plan for making each KS StreamFlix build (macOS, Windows, Android phone, Google TV) its own app tuned to its OS, with an audit of each build today and what changes per OS.
* [KS JaiPlayer as the default player](plan-jaiplayer-default.md) - Approved plan for StreamFlix to play through KS JaiPlayer by default on every platform, with its fallbacks, the DEV variant, and what StreamFlix will later need from JaiPlayer.
* [Progress](progress.md) - The checkpoint: production version, what is unreleased, what is left to adopt and the next action.

# Policies

* [Constraints](constraints.md) - The platform constraints every change to KS StreamFlix must keep.
* [Recorded measurements](quality-budgets.md) - The sizes and speeds the build measures and reports without failing, the stability tests, and the history of the former quality budgets.
* [Dependencies](dependencies.md) - Third-party dependencies are kept to the minimum; the order of preference and what must be reported for each new one.
