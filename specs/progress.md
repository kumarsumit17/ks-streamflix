---
type: Plan
title: Progress
description: The checkpoint of KS StreamFlix - production version, what is unreleased, what is left to adopt and the next action.
tags: [plan, progress, tracker]
---

# Progress

Updated 2026-11-28. How it is kept: KS-Suite [working method](https://github.com/kumasumit/ks-suite/blob/dev/specs/working-method.md#checkpoints).

## Phase

In the suite's active group. The first DEV build (1.4.1) is made and checked; waiting for the owner's manual test and approval.

## Releases

- **Production:** `v1.3.1` (all four builds, 2026-11-27); `main` is at its commit `e3293cc`.
- **`v1.4.0` did not publish:** the tag is on `dev` commit `582e11b`, which is not on `main`, so the release run [46038589029](https://github.com/kumasumit/ks-streamflix/actions/runs/46038589029) stopped at its version check. The tag stays; the changes since `v1.3.1` (bundled FFmpeg among them) are unreleased.
- **ks-utility:** `v0.19.0` on `dev`.
- **DEV build and release pull request:** see below; the pull request from `dev` to `main` is open, not merged.

## DEV build 1.4.1 (2026-11-28)

[Run 46134989162](https://github.com/kumasumit/ks-streamflix/actions/runs/46134989162), commit `35b9ef5` (all four builds succeeded). Later commits on `dev` change only tests, build scripts, CI and docs.

**Passed** (macOS arm64 on the owner's Mac; phone APK on the `ks-phone-36` emulator):
- Identity: APKs `com.ks.streamflix.dev`, "KS StreamFlix DEV", signed `CN=KS-Suite DEV` (production: another key); macOS `com.ks.streamflix.dev`, "KS StreamFlix DEV", `-Dstreamflix.variant=dev`.
- Side by side: macOS DEV and v1.3.1 apps installed and both servers ran (8752 and 8742); the DEV default data folder is `KS StreamFlix DEV`; DEV pages say DEV and use `sf_session_dev`. Android DEV beside production 1.4.0 (separate package and UID, own icon; production stayed on its own server's sign-in).
- DEV phone app: finds only the DEV server, refuses the production server ("This DEV app connects only to a DEV server."), signs in, browses, plays a 720p H.264 video; English and Hindi.

**Known issue:** Settings ▸ App updates in a DEV build says the copy was built from source and has no version number, beside the DEV version (the shared catalogue's text).

**Not checked (the owner):** the Pixel 11 (it left adb before the test), the macOS DEV window and tray, Google TV, the Windows `.msi`.

## Adopted from ks-utility v0.19.0 (2026-11-28)

- The [DEV variant](dev-variant.md) on all four builds; `release.yml` passes the four `DEV_*` secrets.
- `ks-utility-bump.yml` calls the shared workflow; it runs on its own only once it is on `main` (next release).
- **Tests:** `./gradlew check` passes (78 tests on this Mac, where the Windows-only ones return early; budgets, since removed, within limits); `ks-suite-check` passes. The DEV and production Android manifests were checked.
- **Not yet checked:** DEV installers and APKs (first DEV build), side-by-side install on the emulators and the Mac, and on a real phone, TV and Windows PC (the owner).

## Pending

- [KS JaiPlayer as the default player](plan-jaiplayer-default.md): approved, not started.
- [Per-platform optimization plan](plan-per-platform.md): steps 1–4 built (step 4 unreleased), step 5 onwards open.

## Decisions and blockers

- Open (owner): the scope of the approved plan, and how StreamFlix uses JaiPlayer's playback (KS-Suite [progress](https://github.com/kumasumit/ks-suite/blob/dev/specs/progress.md#open-decisions-for-the-owner)).
- The four `DEV_*` secrets are set (2026-11-28).

## Next action

The owner tests the DEV build by hand and approves (or not) the release pull request, naming the platforms. After approval: fast-forward `main`, tag those platforms, publish and verify. Never merge or tag without it.
