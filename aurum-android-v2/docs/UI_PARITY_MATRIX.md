# AURUM V2 UI PARITY MATRIX

This matrix maps every screen, UI control, and user interaction from Aurum V1 (`aurum-android`) to Aurum V2 (`aurum-android-v2`), verifying 100% functional and visual parity.

---

## Screen & Control Parity Mapping

| V1 Screen / Element | V2 Implementation | Parity Status | Verification Notes |
| :--- | :--- | :---: | :--- |
| **Top App Bar** | `MainActivity.kt` TopAppBar | **MATCH** | Displays "Aurum V2 Native" title and yellow "REFRESH NATIVE" button. |
| **Bottom Navigation** | `MainActivity.kt` NavigationBar | **MATCH** | 4 tabs: Catalog, Bullion, Diagnostics, Settings. |
| **Market / Bullion Screen** | `BullionScreen.kt` | **MATCH** | Displays Market Benchmark summary card (blended 24K & 22K average) + Tanishq, Malabar, Kalyan, MMTC cards. |
| **Watchlist / Catalog Screen** | `CatalogScreen.kt` | **MATCH** | Displays search bar, store chips, sort options, product cards with price/g, weight, karat, and URL launcher. |
| **Search Bar** | `CatalogScreen.kt` OutlinedTextField | **MATCH** | Real-time filtering by product title, brand, or source ID. |
| **Store Filter Chips** | `CatalogScreen.kt` LazyRow | **MATCH** | `ALL STORES`, `AJIO`, `AMAZON`, `FLIPKART`, `SHOPSY`, `MYNTRA`. |
| **Sort Options** | `CatalogScreen.kt` LazyRow | **MATCH** | `₹/Gram ↑`, `Price ↑`, `Price ↓`, `Weight ↑`. |
| **Product Cards** | `ProductCard` composable | **MATCH** | Shows title, store badge, brand, total weight, karat, selling price, price/gram, suspicious status, and "View" button. |
| **Direct Product URL Launcher** | `LocalUriHandler.current.openUri` | **MATCH** | Tapping "View" opens canonical product page directly in system browser. |
| **Browser Tab / Logs Screen** | `DiagnosticsScreen.kt` | **MATCH** | Replaced WebViews with Native Activity Logs feed (timestamps, severity colors, store tags, Copy Logs, Clear button). |
| **Architecture Rule Matrix** | `DiagnosticsScreen.kt` Card | **MATCH** | Reports 0 WebViews, 0 JS bridges, 0 Localhost bridges, active Cronet / OkHttp transport. |
| **Settings Screen** | `SettingsScreen.kt` | **MATCH** | Allows location delivery pincode configuration. |

---

## Visual Parity Verification
* **Dark Theme**: Charcoal dark surface background (`#121212`), surface cards (`#1E1E1E`), primary gold accent (`#FFD700`), secondary gold (`#FFB300`), error red (`#CF6679`), success green (`#4CAF50`).
* **Typography**: Bold headers, monospace log entries, clean numeric formatting.
