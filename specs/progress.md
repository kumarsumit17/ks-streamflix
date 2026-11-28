---
type: Plan
title: Progress
description: The checkpoint of KS StreamFlix - production version, what is unreleased, what is left to adopt and the next action.
tags: [plan, progress, tracker]
---

# Progress

Updated 2026-11-28. How it is kept: KS-Suite [working method](https://github.com/kumasumit/ks-suite/blob/dev/specs/working-method.md#checkpoints).

## Phase

In the suite's active group. No development or release in progress.

## Releases

- **Production:** `v1.3.1` (all four builds, 2026-11-27); `main` is at its commit `e3293cc`.
- **`v1.4.0` did not publish:** the tag is on `dev` commit `582e11b`, which is not on `main`, so the release run [46038589029](https://github.com/kumasumit/ks-streamflix/actions/runs/46038589029) stopped at its version check. CI on the tag passed. The tag stays; the changes since `v1.3.1` (bundled FFmpeg among them) are unreleased.
- **ks-utility:** pinned to `v0.18.4` on `dev`; the newest tag is `v0.19.0`.
- **DEV builds and release pull request:** none open.

## To adopt from ks-utility v0.19.0

- [ ] Move the pin to `v0.19.0` (a release is refused on an older pin). The automatic move has not run: GitHub does not find `ks-utility-bump.yml` on the default branch, so it has to reach `main`.
- [ ] **DEV variant:** read `ksVariant` and `ksDevVersion`: Android and Google TV application ID `.dev`, macOS bundle ID `.dev`, a separate Windows MSI UpgradeCode, " DEV" names and icons, a separate data folder, no production updates (`isDevBuild`).
- [ ] **DEV signing secrets** (owner): `DEV_KEYSTORE_BASE64`, `DEV_KEYSTORE_PASSWORD`, `DEV_KEY_ALIAS`, `DEV_KEY_PASSWORD`. Only the four production secrets are set.
- [ ] **Release caller:** `release.yml` calls the shared workflow `@main` with `secrets: inherit`; check its inputs and permissions against v0.19.0 and prove them with a DEV build.

## Pending

- [KS JaiPlayer as the default player](plan-jaiplayer-default.md): approved, not started.
- [Per-platform optimization plan](plan-per-platform.md): steps 1–4 built (step 4 unreleased), step 5 onwards open. Its "released with the next tag" and "never block a release" wording predates the suite release cycle, which applies.

## Decisions and blockers

- Open (owner): the scope of the approved plan, and how StreamFlix uses JaiPlayer's playback (KS-Suite [progress](https://github.com/kumasumit/ks-suite/blob/dev/specs/progress.md#open-decisions-for-the-owner)).
- Blocker for DEV builds: the DEV signing secrets.

## Next action

Adopt v0.19.0 on `dev` (pin, DEV variant, release caller); the owner adds the `DEV_*` secrets; then make a DEV build for the owner's manual test. Production waits for explicit approval, under a new version (not `v1.4.0`).
