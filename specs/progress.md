---
type: Plan
title: Progress
description: The checkpoint of KS StreamFlix - production version, what is unreleased, what is left to adopt and the next action.
tags: [plan, progress, tracker]
---

# Progress

Updated 2026-11-28. How it is kept: KS-Suite [working method](https://github.com/kumasumit/ks-suite/blob/dev/specs/working-method.md#checkpoints).

## Phase

In the suite's active group. First DEV build (1.4.1) running: [run 46134989162](https://github.com/kumasumit/ks-streamflix/actions/runs/46134989162), commit `35b9ef5`.

## Releases

- **Production:** `v1.3.1` (all four builds, 2026-11-27); `main` is at its commit `e3293cc`.
- **`v1.4.0` did not publish:** the tag is on `dev` commit `582e11b`, which is not on `main`, so the release run [46038589029](https://github.com/kumasumit/ks-streamflix/actions/runs/46038589029) stopped at its version check. The tag stays; the changes since `v1.3.1` (bundled FFmpeg among them) are unreleased.
- **ks-utility:** `v0.19.0` on `dev`.
- **DEV builds and release pull request:** DEV build 1.4.1 in progress; no pull request yet.
- A DEV server's browser pages say DEV in the title and header (`35b9ef5`; `./gradlew check` and `ks-suite-check` pass).

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

Check the DEV build (phone and macOS artifacts, side by side, DEV server and phone), record the results and open the release pull request. Do not merge: production waits for the owner's approval.
