# Android Migration Status

The Android application is functional but the full migration plan is not complete. This file separates implemented behavior from work that still requires implementation or Android-device evidence.

## Implemented And Locally Validated

- Portable Gradle 9.7.1 / AGP 9.1.0 / Android API 36 project under `aurum-android/`.
- Native Compose Market, Watchlist, and Settings screens.
- Room product, price-history, bullion-history, and raw bridge persistence with migrations.
- App-private localhost bridge, retailer payload parsing, store-native identity merge, and raw payload archival.
- In-app WebView runner for unchanged AJIO, Amazon, Flipkart, and Myntra master assets.
- Add, edit, store-scoped retry, and two-step delete product workflows.
- ZIP import/export and persistent theme/background refresh settings.
- WorkManager scheduling with visible in-app pending state and notification prompt.
- Direct Android HTTP bullion refresh for Malabar, MMTC-PAMP, and Kalyan.
- Tanishq in-app rendered-page bullion collection, controlled by the bullion-browser visibility toggle.
- First-run seed of 2,305 unique products from the four desktop product JSON files, with duplicate ranking and no overwrite after device data exists.
- Product and bullion browser visibility toggles plus system-back handling for Settings and browser screens.
- Retailer-specific WebView readiness and coverage policy. Runs below AJIO 1,032, Amazon 156, Flipkart 386, or Myntra 284 are reported partial; single-page stores retry once and no low run deletes missing catalogue rows.
- Desktop-mobile Watchlist parity controls: purity/search/weight/store/status filters, counts, seven sorts, direction, coupon/effective per-gram values, and benchmark comparison.
- Functional persistent Deal Radar and bounded Room-backed 24K/22K blended line chart.
- True-black compact mobile layout with 62dp navigation and non-wrapping icon actions/delete confirmation.
- Seeded desktop bullion history, retained Refresh Activity, typed All/Selection/Stale-only/one-store refresh requests, two-store bounded parallel execution, and 1dp hidden WebViews.
- Failure containment for startup, bridge bind, WebView renderer/network/HTTP/SSL/script errors, invalid archives, identity conflicts, and accumulated raw payloads.
- Session-correlated product refresh ingestion: each in-app refresh registers a UUID and allowed stores before its WebViews start; every packaged master bridge POST carries that ID, and cancelled, completed, unknown, or wrong-store sessions are rejected before raw retention, product mutation, or history writes.
- Strict bridge/archive product validation: URLs must be HTTPS and owned by the claimed retailer; malformed present coupon, weight, and karat values are rejected rather than treated as absent. Bridge coverage uses distinct `(store, retailerId)` observations, including a set union across AJIO's four URLs, and Refresh Activity copy logs include session, unique coverage, and named rejection counts.
- Conservative Deal Radar eligibility: only recent 22K/24K live observations with current critical metadata and a plausible product price per gram may appear as deals.
- Product price history has a Room foreign key with `ON DELETE CASCADE`; migration 3-to-4 removes historical orphans while rebuilding the table, so a product deletion cannot leave database-level orphan price rows.
- Refreshes with no eligible visible stores close their session immediately instead of leaving the Refresh Activity running. Bridge HTTP responses are emitted as bounded `[Aurum Bridge]` diagnostics with session, status, acceptance, and response body; network failures are likewise recorded.
- Product and bullion refreshes remain on the current Market or Watchlist screen and run in a 1dp host by default. They never automatically navigate to the Browser destination or place a persistent browser overlay over the application; Browser is an explicit active-refresh tab. Each retailer WebView is bound to its URL index before loading, preventing Compose recomposition from issuing a new URL through the previous WebView.
- Browser is a permanent bottom-navigation destination and a non-blocking refresh dashboard. It presents product/bullion actions plus the persistent Refresh Activity log while exactly one hidden renderer remains mounted for each active job, so switching tabs neither stops nor restarts collection. Product titles open their validated canonical retailer URLs, while `unavailable`, `unverified`, and failed statuses display as `NOT DELIVERABLE`, `NEEDS VERIFICATION`, and `REFRESH FAILED`.
- Normal Android Back and tab navigation do not cancel hidden product or Tanishq renderers. Each copied product refresh log explicitly records background/optional-Browser mode, store URL queue with concurrency, and a final per-store unique-count outcome summary.
- Version `4.9.9` (`40909`) enforces the background model at the renderer and Android-view layers: product WebViews are mounted in an unconditional 1dp host outside the optional former refresh UI, while hidden Tanishq rendering creates only its 1dp WebView. Every retailer WebView uses a transparent software layer with near-zero alpha, preventing its hardware surface from briefly drawing a black overlay above Compose. URL navigation is idempotent per `(URL index, attempt)`, preventing duplicate `Opening URL` and duplicate page-load work. A correct installed APK logs `running in background, Browser tab is optional` immediately after session registration; absence of that exact line identifies an earlier APK.
- The top refresh icon and Browser dashboard primary action now start a guarded full refresh of bullion plus products. Browser presents that combined command, separate product/bullion job-state tiles, recent per-store outcomes, and the full copyable diagnostic log.
- Version `4.9.10` (`40910`) deduplicates retailer-console diagnostics per page navigation. Known retailer-page bootstrap errors (`pushData`/`$` missing on AJIO and `config`/`val`/`processingQueue` errors on Myntra) are retained once as warnings rather than repeated errors; true bridge, navigation, readiness, HTTP, SSL, and renderer failures remain errors.
- Version `4.9.11` (`40911`) removes the temporary near-transparent software-layer workaround after a device run stalled both first WebViews immediately after navigation. Product/Tanishq navigation now records `NAV_REQUEST`, `LOAD_URL_CALLED`, `onPageStarted`, progress, `onPageCommitVisible`, `onPageFinished`, HTTP/SSL/error, and renderer events with elapsed milliseconds. Browser shows the actual active retailer WebView from the existing refresh session while it is selected; otherwise that same session stays hidden. The top app-bar Refresh beside Settings is contextual again (Market refreshes bullion; Watchlist refreshes products; Browser runs the guarded combined action). Bullion trend now has adaptive non-zero ₹/g y-axis labels and three date/time x-axis ticks.
- Version `4.9.12` (`40912`) makes product availability take precedence over price-per-gram ranking. Fresh bridge/seed titles containing `Not Deliverable`, `Unavailable`, or `Out of Stock` are normalized to a clean display name and structured `unavailable` status. Legacy titles receive the same derived protection at render/ranking time. Live excludes them, All lists them under an `UNAVAILABLE / NOT DELIVERABLE` tail section after purchasable records, and Deal Radar/lowest-price/Below-bullion promotion cannot include them.
- Android automatic backup is disabled because the Room database contains diagnostic payloads. The controlled in-app archive remains the data-transfer mechanism. APK metadata is `versionCode 40907` / `versionName 4.9.7`.
- Separate path-scoped GitHub Actions for desktop and Android.

## Implemented But Requires Android Runtime Validation

- Retailer WebView navigation, script execution, and localhost CORS POST on Android 16.
- Room migration 1-to-2 against a real pre-migration database.
- WorkManager timing, notification behavior, process death, reboot, and Doze behavior.
- Activity rotation/process recreation during an active retailer refresh.
- Direct bullion HTTP requests under real device networking.
- Compact, expanded, foldable, and split-screen Compose layout behavior.

## Pending Plan Functionality

- Foreground `dataSync` service owning long-running browser refresh with notification and cancel action.
- Full desktop-compatible archive importer/exporter and tested desktop-to-Android-to-desktop round trip.
- Paging 3 for very large product datasets; current Room list is fully materialized while Compose card creation is lazy.
- Product price-history chart UI; the underlying Room history is retained and included in archives.
- Live Tanishq WebView extraction on an Android 16 device.
- Screenshot tests, baseline profile, and macrobenchmark; physical visual comparison is still required for final font-scale/inset tuning.
- Physical Android 16 installation and live retailer smoke tests.

## Latest Device Evidence

The supplied Android runs confirmed the refresh-session and unique-count logs work. Amazon consistently completed with 215 unique accepted records; two invalid-price rows were rejected. AJIO accepted 8 records on Boys and 322 on Girls, then received retailer `/api/search` page-zero HTTP 403 on Jewellery and Women, ending safely as partial coverage 329/1032. Flipkart both completed once with 478 unique records and separately failed with `ERR_TOO_MANY_REDIRECTS`/retailer API timeouts. Myntra reached its listing page but failed its readiness gate after retailer-owned JavaScript errors. Existing prices were preserved for all failed/partial stores. These are live retailer/WebView conditions requiring further device diagnosis, not evidence of database corruption.

## Copyable Device Evidence

The persisted Refresh Activity Copy command is the primary artifact for a device run. It records the short refresh session ID, background-mode confirmation, target stores, complete URL queue and concurrency, URL index, `NAV_REQUEST`/`LOAD_URL_CALLED`/start/progress/commit/finish timing, readiness, script submission, bridge HTTP status/accepted/body-or-network-failure diagnostics, bridge received/unique/updated/discovered/skipped counts, named rejection reasons, coverage/retry decisions, main-frame/HTTP/SSL/renderer/console failures, and final per-store outcome or cancellation. A cancelled session is explicitly recorded as rejecting late bridge payloads. Include the copied log, Android version/WebView version, and the exact refresh scope when reporting a device result.

## Current Safety Behavior

- Database or startup errors render a local recovery screen instead of requiring logcat or Wi-Fi debugging.
- A loopback port conflict leaves the dashboard usable, shows the error, and provides Retry.
- Retailer page, HTTP, TLS, renderer, and Aurum JavaScript errors are shown in the refresh screen; the failed store retains prior values and the queue continues.
- Invalid or oversized imports show an in-app failure and do not partially commit.
- Bridge payloads without the active refresh-session header, or with a session/store mismatch, return an error without touching the database; their browser-side failure is captured by WebView console diagnostics.
- Hidden browser rendering uses a 1dp in-app WebView host; visible rendering opens the temporary Browser destination. Long-running background execution after app termination is not implemented.