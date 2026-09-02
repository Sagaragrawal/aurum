# Aurum Agent Instructions

## Start Every Task

1. Read `.github/PROJECT_CONTEXT.md` as the repository routing index.
2. Read only the newest relevant entries in `.github/AGENT_CHANGES.md`.
3. Use those files to select the owning module, then read only that module and its nearest caller or regression script. Do not rescan the repository unless the task crosses documented boundaries or the user explicitly requests a fresh full audit.
4. For any product-store, worker, scraper, timeout, concurrency, browser lifecycle, parser, or fallback change, read the relevant store section and shared dependencies in `.github/STORE_EXECUTION_CONTEXT.md` before reading code.
5. For AJIO PLP-first redesign work, also read `.github/AJIO_PLP_FLOW_PLAN.md`; treat it as planning/decisions only until the user explicitly approves implementation.
6. Treat runtime-loaded workers, browser assets, extension files, and standalone `scratch/` tools as entry points; absence of static imports alone does not prove dead code.

## Keep Context Current

After every source, configuration, script, or documentation change:

- Add one concise, newest-first entry to `.github/AGENT_CHANGES.md` with date, files, behavior changed, and validation performed.
- Update `.github/PROJECT_CONTEXT.md` in the same task when architecture, routes, persistence, entry points, commands, environment controls, or invariants change.
- Keep both files compact. Consolidate superseded ledger entries rather than allowing repetitive history.
- Record only changes actually applied and validations actually run. State failures or unavailable checks explicitly.

## Project Rules

- This workspace contains two sibling applications: the production-reference Node.js app under `aurum-desktop/` and the native Android app under `aurum-android/`. Keep their runtimes and state stores separate; their explicit shared contract is the portable archive plus immutable desktop `manual_js` sources packaged by Android at build time.
- Treat current source and `.github/STORE_EXECUTION_CONTEXT.md` as authoritative for store behavior. README version notes are historical and may describe superseded defaults or dormant paths.
- JSON files under `aurum-desktop/data/` are authoritative desktop application state. Desktop SQLite is a derived WAL-backed mirror and history store; Android Room is separately authoritative on-device.
- `aurum-desktop/public/` is the only desktop/web frontend source of truth.
- Product refresh work runs in forked per-store workers. Preserve worker IPC, timeouts, progress events, and stale-price fallback behavior.
- Preserve store-specific parsers and browser lifecycle rules; retailer behavior is not interchangeable.
- Do not delete `scratch/` files as unused without checking whether they are standalone regression, inspection, or migration tools.
- Preserve user/runtime data and unrelated local changes. This workspace has no Git metadata, so destructive edits require extra care.

## Validation

Choose the narrowest relevant check first, then run broader checks when shared code changes:

- JavaScript syntax: from `aurum-desktop/`, `for file in src/**/*.js public/*.js extension/*.js scratch/*.js; do node --check "$file" || exit 1; done`.
- Shell syntax: from `aurum-desktop/`, `for file in scripts/*.sh; do bash -n "$file" || exit 1; done`.
- Parser regression: from `aurum-desktop/`, `node scratch/test-all-cases.js`. `scratch/test-parsing.js` retains two known pack/set expectation mismatches and is diagnostic, not a required CI gate.
- Runtime: from `aurum-desktop/`, `PORT=8788 SKIP_BROWSER_INSTALL=1 ./scripts/run.sh`, then `curl --fail http://127.0.0.1:8788/api/state`.
- Android configuration/build: from `aurum-android/`, use a local Android SDK 36 installation and run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --warning-mode all`.

Do not run live retailer scripts unless the task requires network/browser behavior; they may be slow, trigger throttling, or mutate state.
