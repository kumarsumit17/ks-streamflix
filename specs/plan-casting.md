---
type: Plan
title: Casting plan (DLNA and Chromecast)
description: Playing StreamFlix items on other screens: as a DLNA/UPnP media server for smart TVs, and by handing items to KS Beam for DLNA renderers and Google Cast receivers.
tags: [plan, dlna, upnp, chromecast, cast, plugins]
status: approved
---

# Casting plan (DLNA and Chromecast)

> Approved by the owner on 2026-11-27; nothing is built yet. Its open questions are asked when its build starts.

**What it does.** Smart TVs and players that browse DLNA servers see StreamFlix's libraries (respecting each library's access rules for a "guest" profile chosen by the owner). From the apps, "Play on…" sends an item to a TV or speaker on the network.

**How (plugins).**
- Server plugin `dlna`: a UPnP MediaServer (SSDP announcements on the home network; ContentDirectory browse/search with DIDL-Lite; serving files through the core's stream links so no sign-in header is needed). Written with the JDK (UDP multicast, XML); no UPnP library.
- **Sending to a screen is not duplicated here.** KS Beam (the suite's casting app) already sends to DLNA renderers and plans Google Cast; StreamFlix hands an item to it with a signed stream link (a new cross-repo edge, recorded in KS-Suite's `cross-repo.md` on both sides). On desktop the same link opens in Beam.
- App plugin `cast`: "Play on another screen" on an item's page when KS Beam is installed.

**Engines and licences.** JDK only. Google Cast needs Google's Cast SDK (proprietary terms) on the sender: that stays in KS Beam's plan, not here.

**Open questions for the owner.** Should StreamFlix also be a DLNA renderer (receive), or leave that to KS Beam's TV app? Which guest profile do DLNA clients get?
