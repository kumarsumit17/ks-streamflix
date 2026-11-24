---
type: Plan
title: Remote access plan
description: Reaching the home server from outside the home safely: off by default, HTTPS with a certificate, sign-in hardening, and the ways in (port forwarding, a VPN, or a relay), with their trade-offs.
tags: [plan, remote-access, security, https, plugins]
status: draft
---

# Remote access plan

> Draft for the owner's approval; nothing is built. This one is mainly a security decision.

**What it does.** Watching the home library from outside: on a phone on mobile data, or at a friend's home, with transcoding to fit a slow connection.

**The ways in, and their trade-offs.**
1. **A VPN the owner already runs (recommended first).** Tailscale, WireGuard or the router's VPN: StreamFlix stays on the home network, and nothing new is exposed. The apps only need to accept a server address that is not on the local network (they already do). Needs no code beyond a guide in the browser pages.
2. **Port forwarding with HTTPS.** StreamFlix listens on HTTPS with a certificate (Let's Encrypt via ACME DNS or HTTP challenge, renewed automatically), the owner forwards one port on the router. Exposes the server to the internet: needs everything in "Hardening".
3. **A relay** (a server outside the home that both sides connect to). Needs a service someone runs and pays for; against the suite's "no services" stance unless the owner hosts it.

**How (plugins), for way 2.**
- Server plugin `remote-access`: off by default; turns on HTTPS (Ktor's TLS with a certificate the plugin obtains and renews; keys kept in the data folder with owner-only permissions), shows the outside address, and checks reachability.
- **Hardening** (in the core and the plugin): sign-in limits per address already exist; add longer lockouts, optional two-step sign-in (TOTP) for the owner, session lifetimes shorter outside the home, the browser pages' cookies `Secure`, and a list of active sessions the owner can end. Bitrate limits for remote clients in the `transcode` plugin.

**Libraries and licences.** An ACME client (for example `acme4j`, Apache-2.0) would be the only new library, and only in this plugin.

**Open questions for the owner.** Is a VPN acceptable (way 1, no code)? If not, which router and domain are available for way 2?
