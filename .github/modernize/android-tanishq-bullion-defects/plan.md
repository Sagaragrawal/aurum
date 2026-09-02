# Android Defect-Fix Plan — Tanishq Top Spacing & Bullion Refresh Count

Created: 2026-09-01
Project: Aurum (`aurum-android/`) — native Android 16 Kotlin Compose app
Language/runtime: JVM / Kotlin / Gradle (`compileSdk 36`)
Starting release: `4.9.17` / `40917`

## Purpose

Two narrowly scoped, independently shippable P1 Android defect fixes, executed as
**sequential focused cycles** (Cycle 1 fully diagnosed, fixed, and device-validated before
Cycle 2 begins). No combined or speculative changes: each cycle changes only the layer proven
responsible for its own defect and retests the exact same scenario on a physical device.

## Mandatory Pre-Read (before any code change)

Per `.github/copilot-instructions.md`, before touching browser lifecycle or store/bullion code:

1. `.github/PROJECT_CONTEXT.md` — routing index (Android hosting section is authoritative for
   `BrowserRefreshScreen`/`TanishqBrowserScreen`).
2. Newest relevant entries in `.github/AGENT_CHANGES.md` — especially the `4.9.17` browser-host
   correction and the `BrowserRefreshScreen` overlay-bounding fix.
3. `.github/STORE_EXECUTION_CONTEXT.md` — relevant Tanishq / bullion section only.

## Global Constraints & Invariants

- **Preserve intentional Browser behavior**: the runner stays mounted in its existing composition
  slot; do not move the composition slot (that restarts the WebView/session lifecycle). Do not add
  a second WebView, re-run a factory, or reintroduce a keyed host.
- **Physical device testing is required** using the fixed ADB at
  `/opt/homebrew/share/android-commandlinetools/platform-tools/adb`. Reproduce the *same* reported
  scenario before and after each fix.
- **Device-test versioning**: each cycle bumps `versionName`/`versionCode` in
  `aurum-android/app/build.gradle.kts` so the installed APK under test is unambiguous
  (Cycle 1 → `4.9.18` / `40918`; Cycle 2 → `4.9.19` / `40919`).
- **Data safety**: preserve user/runtime Room state; installs must retain data. No `.git` metadata
  in this workspace — destructive edits require extra care.
- **Audit artifacts**: each cycle writes a timestamped `device-audit/<timestamp>/` folder with
  before/after screenshots, logcat/Room extracts, findings, and an artifact manifest, and appends a
  newest-first entry to `.github/AGENT_CHANGES.md` (and `.github/PROJECT_CONTEXT.md` only if an
  invariant/behavior/version changes).

## Validation Gates (per cycle)

Run in order; a cycle does not advance past a failing gate:

1. **Unit tests**: from `aurum-android/`, `./gradlew :app:testDebugUnitTest --warning-mode all`
   (use the local Android SDK 36 install; `gradlew-laptop` may be used on this machine).
2. **Lint**: `./gradlew :app:lintDebug --warning-mode all` — zero lint errors.
3. **Build**: `./gradlew :app:assembleDebug --warning-mode all` — clean debug APK.
4. **Install**: install the bumped debug APK with data retained via the fixed ADB path.
5. **Physical retest**: reproduce the original scenario on-device via ADB; capture before/after
   evidence proving the specific defect is resolved and no regression to Browser behavior.

## Cycle 1 (P1) — Tanishq Top Black-Bar Spacing

**Symptom**: Tanishq browser refresh opens better than prior builds, but excessive black-bar
spacing remains at the top of the rendered page.

**Diagnosis scope** (root-cause first, do not pre-edit):
- Outer `tanishqRefreshing` overlay `Box` in
  [AurumApp.kt](aurum-android/app/src/main/java/com/aurum/intelligence/ui/AurumApp.kt#L303-L316):
  `padding(top = 64.dp, bottom = 62.dp)` + `WindowInsets.navigationBars`.
- Internal chrome + host in
  [TanishqBrowserScreen.kt](aurum-android/app/src/main/java/com/aurum/intelligence/ui/TanishqBrowserScreen.kt):
  the visible header `Row` (title/status) + `LinearProgressIndicator` stacked above the WebView
  `Box`, plus `BrowserViewport.derive(...)` sizing and any `offset` applied to the child.
- Compare against the already-corrected `BrowserRefreshScreen` inset/overlay contract to identify
  the divergence producing the residual top gap (double top-inset, status-bar inset, or viewport
  offset).
- Reproduce on-device first and capture the top-gap measurement (UI hierarchy / window dump).

**Fix boundary**: change only the responsible inset/host/viewport layer for the Tanishq path so the
rendered page sits directly below the fixed top chrome with no extra black band. Do not alter
extraction, polling, parsing, navigation-token, or single-WebView lifecycle behavior; do not move
the composition slot.

**Gates**: unit tests → lint → build → install `4.9.18`/`40918` → physical ADB retest of the same
Tanishq refresh, with before/after screenshots showing the eliminated top gap and unchanged Browser
behavior.

## Cycle 2 (P1) — Bullion Refresh Progress Count (`3` vs expected `4`)

**Symptom**: During bullion refresh the UI reports `3` instead of the expected `4`.

**Expected-count definition & failure boundary**:
- There are four bullion sources: Tanishq (`browser_required`), Malabar, MMTC, Kalyan (direct HTTP)
  — see `defaultSources` in
  [BullionRepository.kt](aurum-android/app/src/main/java/com/aurum/intelligence/data/BullionRepository.kt#L213-L217).
- `refresh(sourceId = null)` filters out `TRANSPORT_BROWSER_REQUIRED`, so `selected.size = 3` and
  `BullionRefreshProgress.total` is set to `3`
  ([BullionRepository.kt](aurum-android/app/src/main/java/com/aurum/intelligence/data/BullionRepository.kt#L54-L62)).
  Tanishq is refreshed in parallel through the rendered-page path
  (`recordBrowserRates("tan", …)` via `recordTanishqRate`), which is not reflected in the direct
  refresh progress total.
- Root-cause boundary is therefore the **progress/count layer**: the expected user-facing count is
  the total number of bullion sources being refreshed in the combined action (4), not just the
  direct-HTTP subset (3).

**Fix boundary**: correct only the progress/count layer so the displayed total reflects all four
sources being refreshed in the combined bullion action, while Tanishq continues to be collected via
its required browser path and the direct-HTTP loop continues to iterate its own sources. Do not
change fetch transport, rate parsing, or persistence semantics.

**Gates**: unit tests (add/adjust regression asserting the combined bullion refresh reports total
`4`) → lint → build → install `4.9.19`/`40919` → physical ADB retest of a bullion refresh showing
the corrected `4` count.

## Sequencing

```
Cycle 1 (Tanishq top spacing) ──▶ validated + versioned + audit logged
                                        │
                                        ▼
Cycle 2 (bullion count 3→4) ──▶ validated + versioned + audit logged
```

Cycle 2 starts only after Cycle 1 passes all gates and its audit/ledger entries are written.

## Post-Execution Bookkeeping (each cycle)

- Write `device-audit/<timestamp>/` evidence + `artifact-manifest.txt`.
- Append newest-first `.github/AGENT_CHANGES.md` entry (files, behavior changed, validation run,
  device evidence, new version).
- Update `.github/PROJECT_CONTEXT.md` only if a documented invariant, hosting rule, or release
  version changes.
