# Aurum Android

Native Android 16 implementation of Aurum. This project is an active migration target; the Node.js application remains the production reference while features are ported.

## Toolchain

- Gradle wrapper 9.7.1
- Android Gradle Plugin 9.1.0
- Android API 36 / Android 16
- Java 17 bytecode toolchain
- Kotlin built into AGP plus Kotlin Compose plugin 2.4.10
- Jetpack Compose with BOM 2026.06.01, the newest BOM compatible with compile SDK 36

Android Studio is not required. Install and use the Android SDK command-line tools, platform tools, JDK, and Gradle wrapper from a terminal or VS Code.

## SDK Setup

Set `ANDROID_HOME` or create an ignored local `local.properties` file:

```properties
sdk.dir=/path/to/your/Android/sdk
```

Install Android 16 packages with the current Android CLI:

```sh
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
```

## Build

```sh
./gradlew :app:assembleDebug --warning-mode all
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Implemented Application

- Edge-to-edge native Compose shell with Market and Watchlist screens
- Android 16 manifest and localhost-only cleartext network policy
- Room-backed products, price history, bullion sources/history, raw bridge archives, and schema migrations
- App-private `127.0.0.1:8788` bridge with CORS, payload bounds, raw archival, and transactional merge
- In-app AJIO, Amazon, Flipkart, and Myntra WebView refresh runner with exact production master URLs, store-scoped retries, timeout containment, and no external-browser intents
- Runner-owned AJIO bridge adapter after `window.ajioDone`; the AJIO master source remains unchanged
- Build-generated script assets copied byte-for-byte from the repository `manual_js/` sources
- Watchlist add, edit, retry, and two-step delete flows with manual metadata protection
- Versioned ZIP import/export through the Android document picker
- Persistent theme and background-refresh settings
- Persistent Show bullion browser and Show product browser toggles; Android system back closes Settings and browser refresh screens before exiting the app
- WorkManager scheduling that prompts the user to open Aurum for browser collection instead of attempting unsupported background WebView execution
- Native Malabar, MMTC-PAMP, and Kalyan bullion HTTP collection, source-isolated stale fallback, price history, and median-cleaned 24K/22K benchmark
- In-app Tanishq rendered-page collection that dismisses overlays, selects 24 Karat, and reads the dynamic `data-goldrate24kt` value
- First-run Room seed from all four authoritative desktop product files; 2,322 source rows are ranked/deduplicated into 2,305 unique products without overwriting later device state
- Idempotent startup recovery fills any missing bundled products in partially refreshed installations without replacing existing user/live rows
- Desktop Chromium WebView identity and retailer-specific readiness gates: Amazon result cards, Flipkart PID links, Myntra `__myx.searchData.results`, and settled AJIO page state
- Myntra fallback pincode bootstrap, store-specific four/eight-minute ceilings, one low-coverage retry for Amazon/Flipkart/Myntra, and aggregate AJIO coverage diagnostics
- True-black Compose palette matching the desktop mobile tokens, compact 62dp bottom navigation, Watchlist count badge, and 48dp icon-only card actions
- Watchlist purity/search/weight/store/status filters with counts, all seven desktop sort choices, ascending/descending direction, and live-first ordering
- Coupon-aware effective price/gram and benchmark delta on product cards; armed deletion uses a dedicated full-width confirmation row
- Persistent Deal Radar percent/Rs-per-gram threshold with closest-first results and below-bullion highlighting
- Room-backed dual 24K/22K blended history line chart with low/high/change statistics and independent series toggles
- Location/pincode, product/bullion browser visibility, background schedule, and product/bullion startup refresh settings
- Desktop bullion history seeded idempotently from the packaged SQLite/WAL snapshot so the graph is populated on first install
- Watchlist Refresh Activity retains up to 300 detailed store/script/network/SSL/renderer log lines with Expand and Clear controls
- Typed All, current Selection, Stale only, and one-store card retry requests; selecting Myntra and choosing Selection runs only Myntra
- Store masters run with at most two concurrent WebViews for mobile memory safety; AJIO's four URLs remain serial inside its store session
- Hidden product and Tanishq WebViews are constrained to 1dp behind native progress/log UI, so browser-gray surfaces are not shown when visibility toggles are off

The debug-signed installable APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Current Limitations

- Live retailer and bullion requests have not been executed on an Android 16 device. The WebView/loopback behavior is compiled and contract-tested but still needs device validation.
- Tanishq requires rendered DOM interaction and is now handled by the bounded in-app WebView collector; live Android validation is still required.
- Background work is best-effort and posts a notification to open the in-app browser refresh, because Android does not support reliable background WebView automation.
- ADB is installed on this laptop, but corporate endpoint policy terminates the `adb` process, so installation was not validated here.
- The current native UI implements core Market, Watchlist, Settings, CRUD, refresh, benchmark, and import/export workflows. Advanced desktop/PWA presentation such as full trend charts and Refresh Activity log visualization remains follow-up parity work.
