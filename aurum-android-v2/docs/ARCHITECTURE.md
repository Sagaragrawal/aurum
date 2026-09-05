# AURUM V2 NATIVE ARCHITECTURE

This document describes the clean-room native Android architecture of Aurum V2 (`aurum-android-v2`).

---

## 1. High-Level Architecture Overview

```text
                                 AURUM V2 APP
                                      │
           ┌──────────────────────────┼──────────────────────────┐
           │                          │                          │
       COMPOSE UI               VIEWMODEL LAYER              ROOM DATABASE
    (Catalog / Bullion         (MainViewModel)            (RebuildDatabase)
     Diagnostics / Settings)          │                          │
           │                          │                          │
           └──────────────────────────┼──────────────────────────┘
                                      │
                           REFRESH COORDINATOR
                        (RefreshCoordinator.kt)
                                      │
                     ┌────────────────┴────────────────┐
                     │                                 │
           RETAIL SOURCE ADAPTERS            BULLION SOURCE ADAPTERS
       (Ajio, Amazon, Flipkart,               (Tanishq, Malabar,
        Shopsy, Myntra Parsers)                Kalyan, MMTC Parsers)
                     │                                 │
                     └────────────────┬────────────────┘
                                      │
                           NATIVE HTTP TRANSPORT
                      (CronetHttpClient / OkHttp)
                                      │
                             LIVE SOURCE APIS
```

---

## 2. Core Package Structure

```text
com.aurum.rebuild/
├── AurumApplication.kt         # Application class initializing Room DB & RefreshCoordinator
│
├── db/                         # Persistence Layer (Room)
│   ├── RebuildEntities.kt      # RebuildProductEntity, RebuildBullionQuoteEntity, RebuildRefreshLogEntity
│   ├── RebuildDaos.kt          # RebuildProductDao, RebuildBullionDao, RebuildRefreshLogDao
│   └── RebuildDatabase.kt      # RoomDatabase definition
│
├── engine/                     # Orchestration & Pipeline Execution
│   ├── RefreshCoordinator.kt   # Central parallel refresh orchestrator
│   ├── StoreRegistry.kt        # Central retail source registry & URLs
│   ├── StoreSource.kt          # Store source interface definition
│   ├── AdaptiveConcurrencyController.kt # Dynamic concurrency limiter
│   └── RetryEngine.kt          # Exponential backoff with jitter
│
├── bullion/                    # Bullion Source Adapters
│   ├── BullionProvider.kt      # Bullion provider interface
│   ├── BullionRegistry.kt      # Bullion registry & cross-source median anomaly validator
│   ├── TanishqParser.kt        # Tanishq rate extractor
│   ├── MalabarParser.kt        # Malabar GraphQL rate extractor
│   ├── KalyanParser.kt         # Kalyan rate extractor
│   └── MmtcPampParser.kt       # MMTC-PAMP API rate extractor
│
├── network/                    # Native HTTP Network Layer
│   ├── NativeHttpClient.kt     # Client interface
│   ├── CronetHttpClient.kt     # Google Play Services Cronet HTTP client
│   ├── OkHttpHttpClient.kt     # OkHttp fallback transport client
│   └── RequestProfile.kt       # Per-source headers, timeouts, and profiles
│
├── parser/                     # Data Extraction & Normalization Engines
│   ├── WeightParser.kt         # Standalone exact weight extractor (mg, g, kg, multi-packs)
│   ├── PurityParser.kt         # Gold Karat & Fineness extractor
│   ├── MetalClassifier.kt      # Metal classification (Gold vs Plated vs Silver)
│   ├── PriceParser.kt          # Selling price & MRP extractor
│   ├── AjioParser.kt           # AJIO JSON & HTML fallback parser
│   ├── AmazonParser.kt         # Amazon HTML search parser
│   ├── FlipkartParser.kt       # Flipkart PID listing parser
│   ├── ShopsyParser.kt         # Shopsy listing parser
│   └── MyntraParser.kt         # Myntra search parser
│
├── validation/                 # Quality & Persistence Gates
│   ├── ProductValidator.kt     # Metal, weight, price, and price/gram bounds validator
│   ├── ProductNormalizer.kt    # RawProduct -> CanonicalProduct mapper
│   └── ProductDeduplicator.kt  # Canonical URL cleaner & unique product deduplicator
│
├── repository/                 # Data Repositories
│   ├── RebuildStoreRepository.kt # Room DB product repository
│   ├── RebuildBullionRepository.kt # Room DB bullion repository
│   └── RebuildLogRepository.kt # Room DB refresh log activity repository
│
├── background/                 # Background Job Scheduling
│   └── RebuildBackgroundWorker.kt # WorkManager worker delegating to RefreshCoordinator
│
└── ui/                         # Jetpack Compose UI
    ├── MainActivity.kt         # Main Activity & scaffold navigation
    ├── viewmodel/
    │   └── MainViewModel.kt    # Main ViewModel
    ├── screens/
    │   ├── CatalogScreen.kt    # Search, store chips, sort options, product cards
    │   ├── BullionScreen.kt    # Market benchmark summary & live bullion rates
    │   ├── DiagnosticsScreen.kt # Rule matrix, state cards, and activity log feed
    │   └── SettingsScreen.kt   # Pincode location setting
    └── theme/
        ├── Color.kt
        ├── Theme.kt
        └── Type.kt
```

---

## 3. Forbidden Technology Verification Audit

To guarantee 100% compliance with the clean-room specification, the entire `aurum-android-v2` codebase was audited for forbidden legacy browser components:

* **WebView**: **0** (Eliminated)
* **WebViewClient / WebChromeClient**: **0** (Eliminated)
* **evaluateJavascript / loadUrl**: **0** (Eliminated)
* **Loopback HTTP Server (8788)**: **0** (Eliminated)
* **Playwright / Puppeteer / Selenium**: **0** (Eliminated)
* **Thread.sleep()**: **0** (Eliminated)
* **Hardcoded Delays**: **0** (Eliminated)

All network traffic runs natively over Cronet/OkHttp with coroutines, structured concurrency, and atomic Room database persistence.
