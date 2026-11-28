---
type: Policy
title: Recorded measurements
description: The sizes and speeds the build measures and reports without failing, the stability tests, and the history of the former quality budgets.
tags: [quality, size, performance, stability, measurements, policy]
---

# Recorded measurements

The owner's decision of 2026-11-28 replaces the quality-budget rule of 2026-11-23: there are **no automated size or speed budgets**. A build never fails on a size or a speed; performance is judged by the owner's test of the DEV build. The values are still measured, so each DEV build report can show them and a change can be compared with earlier ones.

## What is measured

| Name | Meaning | Measured by |
|---|---|---|
| `size.server` | Every jar of the core and the server plugins, as `--headless` runs it | `sizeReport` (part of `./gradlew check`) |
| `size.desktop` | Every jar the macOS app runs (server, window, Compose for this OS) | `sizeReport` |
| `size.apk.android`, `size.apk.googleTv` | The release APKs, shrunk by R8, before signing | `sizeReport` |
| `size.installer.dmg`, `size.installer.msi` | The installers, with their Java runtime and the bundled FFmpeg | `installerSizeReport` after packaging (CI's macOS and Windows jobs) |
| `server.start` | From start to the first answer of `/api/v1/info`, in-process | server tests |
| `scan.rate` | Scanning a library of 2,000 new files | server tests |
| `api.p95` | The 95th percentile of item-list and first-byte stream answers, the fastest of five rounds of 50 pairs | server tests |
| `transcode.firstSegment.remux`, `.transcode` | From the playback request to the first HLS segment (a 720p test film) | transcode plugin tests, with FFmpeg installed |
| `folderWatch.detect` | From a new file in a library folder to the item in the library (the test waits 300 ms for quiet) | folder-watch plugin tests |

The `measurements` task prints the values at the end of the tests and size reports, and adds them to the run's summary on GitHub Actions. Each value is the latest one measured in `build/measurements/`; a clean CI machine has only that run's.

## Stability tests

These are correctness tests and fail the build like any other, with their numbers in the test code (`StabilityTest`): 16 parallel range requests must all return the right bytes, and 5 start, scan, stream and stop cycles must leave no file handle open.

## Rules

- A DEV build report includes the measured values of that build.
- Adding a limit that fails a build needs the owner's decision.

## History: the budgets of 2026-11-23 to 2026-11-28

Until 2026-11-28 each value was a limit in `quality-budgets.properties`. The last limits: server 14 MB, desktop app 53 MB, APK 15 MB, `.dmg` 85 MB; server start 3000 ms, scan at least 500 files/s, API p95 100 ms (Windows 150 ms), first segment 6000 ms, folder watch 15000 ms. The measurements below were taken against the limits of their time.

### Stage 1 measurements (owner's Mac, 2026-11-23)

Server runtime 22.3 MB (budget 24), cold start 752 ms, scan about 26,000 files/s, p95 2 ms, 16 parallel streams correct, open files unchanged over 5 restarts. Ktor's HTTP client and Kotlin reflection are left out of the apps; the SQLite driver's natives for every OS (12 MB) are trimmed to one OS when packaging (stage 6).

### Stage 2 measurements (owner's Mac, 2026-11-23)

Server 22.4 MB (budget 24); desktop app 60.5 MB (new budget 62: Compose's native graphics 17.5 MB and SQLite's natives for every OS 11.4 MB are the largest; Material 2, Ktor's client and Kotlin reflection are left out; trimming SQLite to one OS in stage 6 lowers it by about 10 MB, and the budget with it); release APK 1.7 MB (budget 15). The release APK was installed and used on the 55-inch TV emulator and the phone emulator.

### Stage 3 measurements (owner's Mac, 2026-11-23)

Server 22.7 MB, desktop app 60.8 MB, release APK 1.8 MB: the four new plugins and the FFmpeg helper add about 0.3 MB, with no new library (images, XML and HTTP come from the JDK; images in the apps from Compose).

### Stage 4 measurements (owner's Mac, 2026-11-23)

First segment 177 ms for a remux and 477 ms for a transcode with VideoToolbox (budget 6000 ms). Server 22.8 MB, desktop app 60.9 MB, release APK 1.8 MB (Media3's HLS module included).

### Stage 5 measurements (owner's Mac, 2026-11-23)

A new film appeared 694 ms after it was copied in (budget 15000 ms; the test waits 300 ms for quiet). Server 22.9 MB, desktop app 61.1 MB, release APK 1.8 MB.

### Stage 6 measurements (owner's Mac, 2026-11-24)

With SQLite's natives for one OS only: server 12.9 MB (budget lowered from 24 to 14), macOS app 51.2 MB (budget lowered from 62 to 53), `.dmg` 72.8 MB (new budget 80), release APK 1.9 MB.
