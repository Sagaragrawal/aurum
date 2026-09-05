# Aurum Rebuild — Complete Codebase Source & Endpoint Inventory

**Phase 1 Deliverable — Frozen Legacy Architecture Audit**

---

## 1. Retail Store Sources

### 1. AJIO (`ajio.com`)
* **ID:** `ajio` / `ajio.com`
* **Source Type:** STORE
* **Listing / Category Endpoint URLs:**
  * `https://www.ajio.com/women/c/8303?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat%3Averticalmetalpurity%3A22+Karat&gridColumns=5`
  * `https://www.ajio.com/s/jewellery-176606?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat%3Averticalmetalpurity%3A22+Karat&gridColumns=5`
  * `https://www.ajio.com/s/girls-169379?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat%3Averticalmetalpurity%3A22+Karat&gridColumns=5`
  * `https://www.ajio.com/s/boys-169373?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat%3Averticalmetalpurity%3A22+Karat&gridColumns=5`
  * `https://www.ajio.com/women-rings/c/830306004?query=%3Arelevance%3Averticalmetaltype%3AYellow%20Gold`
* **Direct Product API Endpoint:** `https://www.ajio.com/api/p/{productId}`
* **Current Transport:** WebView / Headless WebView / Loopback Bridge (`http://localhost:8788/api/browser-bridge/products`)
* **Cronet Feasibility:** Confirmed HTTP 200 Native GET
* **Current Parser:** `AjioNativeParser.kt` & `ajio_gold_master.js`
* **Known Issues:** JS Bridge deadlocks, WebView memory leakage, Pincode script injection timing failures.

### 2. Amazon India (`amazon.in`)
* **ID:** `amazon` / `amazon.in`
* **Source Type:** STORE
* **Listing / Category Endpoint URLs:**
  * `https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A14800650031%7C14800651031&page=1`
* **Direct Product URL Pattern:** `https://www.amazon.in/dp/{ASIN}`
* **Current Transport:** Headless WebView / Loopback Bridge
* **Cronet Feasibility:** Confirmed HTTP 200 Native GET
* **Current Parser:** `AmazonNativeParser.kt` & `amazon_gold_master_v14_3_final.js`
* **Known Issues:** Anti-bot HTML captcha blocks when missing browser headers, ASIN canonicalization parameter noise.

### 3. Flipkart (`flipkart.com`)
* **ID:** `flipkart` / `flipkart.com`
* **Source Type:** STORE
* **Listing / Category Endpoint URLs:**
  * `https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p[]=facets.purity%255B%255D%3D24K&p[]=facets.purity%255B%255D%3D22K`
* **Direct Product URL Pattern:** `https://www.flipkart.com/item?pid={PID}`
* **Current Transport:** Headless WebView / Loopback Bridge
* **Cronet Feasibility:** Confirmed HTTP 200 Native GET
* **Current Parser:** `FlipkartNativeParser.kt` & `flipkart_gold_master_final.js`
* **Known Issues:** Dynamic JSON payload obfuscation, variant selection ambiguity.

### 4. Shopsy (`shopsy.in`)
* **ID:** `shopsy` / `shopsy.in`
* **Source Type:** STORE
* **Listing / Category Endpoint URLs:**
  * `https://www.shopsy.in/gold-silver-coins/pr?sid=mcr,73x&marketplace=FLIPKART&p[]=facets.purity%5B%5D%3D24K&p[]=facets.purity%5B%5D%3D22K`
* **Current Transport:** Headless WebView / Loopback Bridge
* **Cronet Feasibility:** Confirmed HTTP 200 Native GET
* **Current Parser:** Shared Flipkart native logic / JS bridge
* **Known Issues:** Shares Flipkart backend API structures.

### 5. Myntra (`myntra.com`)
* **ID:** `myntra` / `myntra.com`
* **Source Type:** STORE
* **Listing / Category Endpoint URLs:**
  * `https://www.myntra.com/gold-coin`
* **Direct Product API Endpoint:** `https://www.myntra.com/gateway/v2/product/{productId}`
* **Current Transport:** Headless WebView / Loopback Bridge
* **Cronet Feasibility:** Confirmed HTTP 200 Native GET
* **Current Parser:** `MyntraNativeParser.kt` & `myntra_gold_master_v7_final.js`
* **Known Issues:** Direct API response schema changes, coupon code application variations.

---

## 2. Bullion Provider Sources

### 1. MMTC-PAMP (`mmtcpamp.com`)
* **ID:** `mmtc`
* **Source Type:** BULLION
* **API Endpoint:** `https://www.mmtcpamp.com/api/getQuote`
* **Web Page:** `https://www.mmtcpamp.com/gold-silver-rate-today`
* **Rate Types:** 24K Gold Rate (Buy/Sell)
* **Current Transport:** OkHttp / Cronet Native API
* **Current Parser:** `BullionNativeParser.kt`

### 2. Malabar Gold & Diamonds (`malabargoldanddiamonds.com`)
* **ID:** `malabar`
* **Source Type:** BULLION
* **GraphQL Endpoint:** `https://www.malabargoldanddiamonds.com/graphql-magento?query=`
* **Web Page:** `https://www.malabargoldanddiamonds.com/in/pan-india/en/live-gold-rate.html`
* **Rate Types:** 24K and 22K Gold Rates
* **Current Transport:** OkHttp / Cronet Native GraphQL
* **Current Parser:** `BullionNativeParser.kt`

### 3. Kalyan Jewellers (`kalyanjewellers.net`)
* **ID:** `kalyan`
* **Source Type:** BULLION
* **Web Page:** `https://store.kalyanjewellers.net/gold-rate/india/en`
* **Rate Types:** 24K and 22K Gold Rates
* **Current Transport:** Native HTTP GET + HTML RegEx
* **Current Parser:** `BullionNativeParser.kt`

### 4. Tanishq (`tanishq.co.in`)
* **ID:** `tan`
* **Source Type:** BULLION
* **Web Page:** `https://www.tanishq.co.in/gold-rate.html`
* **Rate Types:** 24K and 22K Gold Rates
* **Current Transport:** WebView DOM JS Extractor (`TanishqBrowserScreen.kt`)
* **Current Parser:** `BullionNativeParser.kt` / DOM script
* **Known Issues:** Unnecessary WebView dependency for rate extraction; must be converted to native HTTP GET + HTML/JSON parser.

---

## 3. Frozen Legacy Components To Be Replaced In V2

The following components belong exclusively to the old scraping/WebView architecture and must NOT be imported or depended upon in `aurum-android-v2`:

1. `com.aurum.intelligence.background.HeadlessStoreScraper`
2. `com.aurum.intelligence.background.ProductDetailScraper`
3. `com.aurum.intelligence.browser.RetailerWebView`
4. `com.aurum.intelligence.browser.MasterScriptAssetLoader`
5. `com.aurum.intelligence.browser.NavigationDecision`
6. `com.aurum.intelligence.browser.NavigationResult`
7. `com.aurum.intelligence.browser.BrowserViewport`
8. `com.aurum.intelligence.bridge.LoopbackBridgeServer`
9. `com.aurum.intelligence.data.BridgeRepository`
10. `com.aurum.intelligence.data.BridgePayload`
11. `com.aurum.intelligence.ui.TanishqBrowserScreen`
12. Assets in `manual_js/` (`ajio_gold_master.js`, `amazon_gold_master_v14_3_final.js`, `flipkart_gold_master_final.js`, `myntra_gold_master_v7_final.js`)
13. Scraper bridge server on `http://localhost:8788/api/browser-bridge/products`

---

## 4. Known Bugs & Parser Defect Catalog

1. **Multi-Pack Weight Calculation Defect**:
   - Issue: "2 x 1g" or "3 pcs x 10g" extracted as 1g or 2g inappropriately or multiplied incorrectly when total weight was already provided.
   - Solution: Explicit Multi-Pack mathematics (`unitWeightGrams`, `quantity`, `totalWeightGrams`).
2. **Milligram Conversion Defect**:
   - Issue: "50mg" or "500mg" treated as 50g or 500g instead of 0.05g or 0.5g.
   - Solution: Rigorous mg -> g conversion (`BigDecimal` scale division by 1000).
3. **SKU Number / Purity Confusion**:
   - Issue: "SKU 999" or "Item 916" treated as 999g or 916g weight.
   - Solution: Strict purity vs weight regex isolation and context matching.
4. **Non-Gold Product Contamination**:
   - Issue: Silver coins, silver bars, platinum items passing filters into gold results.
   - Solution: Multilayer classification (structured fields -> category -> title/description negative keyword filter).
5. **Fixed Delay Scraping Dependency**:
   - Issue: `delay(1000)` / `delay(2000)` causing slow/brittle execution and hangs.
   - Solution: Event-driven native network call completions + coroutine channels.
6. **WebView Render Process Crash / Port Binding Failures**:
   - Issue: Android WebView render process gone / localhost port 8788 binding conflict.
   - Solution: Total elimination of WebView and local HTTP bridge server.
