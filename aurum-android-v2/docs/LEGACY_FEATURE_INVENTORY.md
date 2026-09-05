# AURUM V1 LEGACY FEATURE INVENTORY

This document inventories all functionality, screens, database tables, sources, and user flows from the original Aurum V1 implementation (`aurum-android`), serving as the specification baseline for Aurum V2 (`aurum-android-v2`).

---

## 1. Primary Application Sections & Navigation

The application uses a 3-tab bottom navigation bar (`CompactBottomNavigation`):

1. **Market**:
   * Official Live Bullion Rates (Tanishq, Kalyan Jewellers, Malabar Gold & Diamonds, MMTC-PAMP).
   * Blended Market Benchmarks (24K & 22K per-gram average rates).
   * Bullion Rate History & Trend visualization.
   * Refresh trigger for live bullion rates.

2. **Watchlist / Products**:
   * Comprehensive product catalogue and watchlist view.
   * Live Search bar (filtering by product name, brand, store, or weight).
   * Retailer store chips (All Stores, AJIO, Amazon, Flipkart, Shopsy, Myntra).
   * Gold Purity chips (24K, 22K, Other).
   * Quick filters: All, Below Bullion, Live, Stale, Unverified, Failed, Unavailable, Not Live.
   * Sorting controls: Price per Gram (₹/g), Weight, Name, Price, Coupon per Gram, Vs Bullion, Store.
   * Product Cards displaying:
     * Retailer source badge & status tags (Live, Stale, Flagged/Suspicious).
     * Product Title, Brand, Quantity, Unit Weight, and Karat/Fineness.
     * Listed Price, MRP, Effective/Coupon Price, and Savings.
     * Calculated ₹/gram rate and percentage delta vs live bullion benchmark.
     * Action button: Direct product URL launcher ("View / Buy on Store").
   * Add Product Dialog: Allows adding custom product URLs + titles.
   * Edit Product Dialog: Allows updating target weight, karat, and title.
   * Delete Product Action: Confirmation and item removal from database.

3. **Browser / Native Refresh Logs** (Replaced in V2):
   * Replaced headless WebView execution with a **Native Refresh Activity Panel**.
   * Real-time structured log feed: Timestamp, severity level (`INFO`, `WARN`, `ERROR`), source identifier, HTTP response codes, latency in ms, item counts, and DB transaction results.
   * Log filter chips by severity and store.
   * Actions: **Copy Logs** (exports formatted text to clipboard) and **Clear Logs**.
   * Architecture Rule Matrix: Displays zero WebView usage, zero JS bridge, zero loopback server, and active Cronet / OkHttp transports.

---

## 2. Database & Data Model Inventory

### Primary Entities (`AurumDatabase`):

1. **`products` (`ProductEntity`)**:
   * `id`: Primary key (`store:retailerId`).
   * `store`: Retailer source identifier (`ajio`, `amazon`, `flipkart`, `shopsy`, `myntra`).
   * `retailerId`: Source product ID.
   * `canonicalUrl`: Standardized product URL.
   * `name`: Product title.
   * `brand`: Brand name.
   * `grams`: Total weight in grams.
   * `karat`: Gold karat (24, 22, 18, 14).
   * `purity`: Textual purity representation.
   * `price`: Selling price in INR.
   * `couponPrice`: Offer / effective price in INR.
   * `status`: Product status (`live`, `stale`, `unavailable`).
   * `refreshMethod`: Method used (`native_http`, `direct_api`).
   * `checkedAt`: Last fetch attempt timestamp.
   * `lastLiveAt`: Last successful live price timestamp.
   * `unitWeightGrams`, `quantity`, `totalWeightGrams`, `weightConfidence`.
   * `pincode`: Pincode context used for request.
   * `deliverable`, `isMicroCoin`, `isBlinkDeal`.

2. **`product_price_history` (`ProductPriceHistoryEntity`)**:
   * Historical price snapshots linked via foreign key `productId`.

3. **`bullion_sources` (`BullionSourceEntity`)**:
   * Official bullion source records (`tanishq`, `malabar`, `kalyan`, `mmtc`).
   * `price24`, `price22`, `price22Derived`, `transport`, `status`, `fetchedAt`, `lastLiveAt`.

4. **`bullion_history` (`BullionHistoryEntity`)**:
   * Time-series historical bullion rates for trend calculations.

5. **`refresh_activity_logs` (`RefreshActivityLogEntity`)**:
   * Persistent execution log records (`timestamp`, `severity`, `store`, `message`).

---

## 3. Retail & Bullion Sources Inventory

| Source Identifier | Display Name | Source Category | Transport | Endpoints & Scope |
| :--- | :--- | :--- | :--- | :--- |
| `ajio` | AJIO | Retailer | Cronet / HTTP/2 | Category API `8303` & listing queries |
| `amazon` | Amazon India | Retailer | Cronet / HTTP/2 | Search listing `s?i=jewelry` |
| `flipkart` | Flipkart | Retailer | Cronet / HTTP/1.1 | Listing `/gold-silver-coins/pr` |
| `shopsy` | Shopsy | Retailer | Cronet / HTTP/2 | Category `/gold-silver-coins/pr` |
| `myntra` | Myntra | Retailer | Cronet / HTTP/2 | Category `/gold-coin` |
| `malabar` | Malabar Gold | Bullion | Cronet / GraphQL | GraphQL `getGoldRate` |
| `mmtc` | MMTC-PAMP | Bullion | Cronet / HTTP/2 | Rates API `getQuote` |
| `kalyan` | Kalyan Jewellers | Bullion | Cronet / HTTP/1.1 | Rate page `/gold-rate/india/en` |
| `tan` | Tanishq | Bullion | Cronet / HTTP/2 | Rate page `/gold-rate.html` |

---

## 4. Background Refresh & Scheduling

* **WorkManager (`RebuildBackgroundWorker`)**:
  * Periodically executes background refresh using the shared `RefreshCoordinator`.
  * Respects battery, network, and pincode settings.
