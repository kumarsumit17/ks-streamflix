---
type: Policy
title: Quality budgets
description: Stability, app size and speed as numbers in one file that the build and tests check automatically, so a change that makes an app bigger, slower or less stable fails.
tags: [quality, size, performance, stability, ci, policy]
---

# Quality budgets

The owner's rule (2026-11-23): stability, app size and speed are variables for automation. Each is a number in `quality-budgets.properties` in the repository root, and a check in the build fails when a budget is broken. A budget is changed only on purpose, in its own commit that says why.

## Budgets

| Key | Meaning | Checked by |
|---|---|---|
| `size.server.maxMb` | The server alone: every jar of the core and the server plugins, as `--headless` runs it, in MB | `./gradlew checkSize` |
| `size.desktop.maxMb` | The whole macOS app: every jar it runs (server, window, Compose for this OS), in MB; the Java runtime the installer bundles is measured in stage 6 | `checkSize` |
| `size.apk.maxMb` | The release APK, shrunk by R8, in MB; 15 MB from [Constraints](constraints.md) | `checkSize` |
| `speed.server.startMs` | From start to the first answer of `/api/v1/info` | server tests (in-process); the cold start of the installed app was 752 ms on the owner's Mac on 2026-11-23, checked by hand |
| `speed.scan.filesPerSecond` | Scanning a library of new files, at least | server tests |
| `speed.api.p95Ms` | The 95th percentile of item-list and first-byte stream answers on a local server | server tests |
| `speed.transcode.firstSegmentMs` | From the playback request to the first HLS segment, for a remux and for a transcode (a 720p test film) | transcode plugin tests, with FFmpeg installed |
| `speed.folderWatch.detectMs` | From a new file in a library folder to the item in the library | folder-watch plugin tests |
| `stability.concurrentStreams` | Simultaneous range requests that must all succeed | server tests |
| `stability.repeatRuns` | Start, scan, stream and stop cycles in a row without an error or a leaked file handle | server tests |

## Stage 1 measurements (owner's Mac, 2026-11-23)

Server runtime 22.3 MB (budget 24), cold start 752 ms, scan about 26,000 files/s, p95 2 ms, 16 parallel streams correct, open files unchanged over 5 restarts. Ktor's HTTP client and Kotlin reflection are left out of the apps; the SQLite driver's natives for every OS (12 MB) are trimmed to one OS when packaging (stage 6).

## Stage 2 measurements (owner's Mac, 2026-11-23)

Server 22.4 MB (budget 24); desktop app 60.5 MB (new budget 62: Compose's native graphics 17.5 MB and SQLite's natives for every OS 11.4 MB are the largest; Material 2, Ktor's client and Kotlin reflection are left out; trimming SQLite to one OS in stage 6 lowers it by about 10 MB, and the budget with it); release APK 1.7 MB (budget 15). The release APK was installed and used on the 55-inch TV emulator and the phone emulator.

## Stage 3 measurements (owner's Mac, 2026-11-23)

Server 22.7 MB, desktop app 60.8 MB, release APK 1.8 MB: the four new plugins and the FFmpeg helper add about 0.3 MB, with no new library (images, XML and HTTP come from the JDK; images in the apps from Compose).

## Stage 4 measurements (owner's Mac, 2026-11-23)

First segment 177 ms for a remux and 477 ms for a transcode with VideoToolbox (budget 6000 ms). Server 22.8 MB, desktop app 60.9 MB, release APK 1.8 MB (Media3's HLS module included).

## Stage 5 measurements (owner's Mac, 2026-11-23)

A new film appeared 694 ms after it was copied in (budget 15000 ms; the test waits 300 ms for quiet). Server 22.9 MB, desktop app 61.1 MB, release APK 1.8 MB.

## Rules

- `./gradlew check` runs every check that applies on the machine (the size check needs the built files, so it depends on them).
- The tests read the budgets from the file, never from numbers in the code. A test machine that is far slower can loosen a speed budget for one run with `-Pbudget.<key>=<value>`; CI never does.
- Every stage adds the budgets its features need (for example transcoding start time in stage 4, client start-up time and frame drops in stage 2) to this table and the file.
- A broken budget is fixed in the code. Raising the budget instead needs the owner's OK.
