# Aurum V2 — Current Implementation Audit

**Generated:** 2026-09-05  
**Target Project:** urum-android-v2  
**Reference Project:** urum-android (Legacy, Read-Only)

---

## 1. Executive Summary

Aurum V2 replaces the legacy WebView-based scraping engine with a 100% native HTTP/2 client (CronetNetworkClient) and direct API/HTML parsers (NativeParallelRefreshEngine).
However, prior to this audit, V2 was uncompilable due to merge errors in 4 files (AjioNativeParser.kt, MyntraNativeParser.kt, FlipkartNativeParser.kt, NativeParallelRefreshEngine.kt).
The legacy UI, Room database schema v5, and financial calculation models are completely preserved.

## 2. Source Inventory (43 Files)
All 43 Kotlin source files are organized into:
- Background (3): AurumNotificationManager, BackgroundRefreshScheduler, BackgroundRefreshWorker
- Data Layer (24): AurumDatabase, BridgePayload, BridgeRepository, CronetNetworkClient, NativeParallelRefreshEngine, etc.
- Parsers (5): AjioNativeParser, AmazonNativeParser, BullionNativeParser, FlipkartNativeParser, MyntraNativeParser
- UI (8): AurumApp, AurumViewModel, BullionTrend, ProductCalculations, ProductExperience, RefreshActivityPanel, RetailerSelection, SettingsScreen, Theme

## 3. Data Flow
UI -> AurumApp.refreshEverything() -> NativeParallelRefreshEngine.refreshAllParallel()
-> 5 Store Concurrent Coroutines + 1 Bullion Coroutine
-> CronetNetworkClient (HTTP/2 with Chrome Android Headers)
-> Store Native Parser -> BridgeRecord.toProductCandidate() -> DatabaseSanitizerEngine
-> NativeParallelRefreshEngine.saveCandidates() -> AurumDao.upsertProduct() & insertPriceHistory()
-> Flow updates observed by AurumViewModel -> UI displays live products and logs.
