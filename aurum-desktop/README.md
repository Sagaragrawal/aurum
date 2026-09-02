# Aurum v4

24K bullion and ecommerce gold intelligence tracker.

## Run

On Windows, open a terminal in this folder and run one command to install everything and start Aurum:

```powershell
scripts\run-windows.bat
```

Stop it from another terminal with:

```powershell
scripts\stop-windows.bat
```

The start command installs Node.js through `winget` or Chocolatey when needed, installs npm dependencies and Playwright Firefox, then starts the server. On macOS/Linux, use `scripts/run-macos.sh` or `scripts/run-linux.sh`.

Open `http://localhost:8787/` for desktop or `http://localhost:8787/mobile` for the mobile/PWA layout.

## v4 compatibility

All existing REST API routes and JSON data files are retained. v4 adds `/api/events` (Server-Sent Events) and `/api/health`; clients fall back to REST polling if SSE is unavailable. Existing product and bullion data require no migration.

The server serves only `public/`; that is the single frontend source of truth.

Settings includes opt-in startup refresh switches for bullion and products. Both are saved with the JSON state and default off; when enabled, server startup refreshes bullion first and then the full product watchlist. `AUTO_REFRESH_ON_START=1` remains supported as a bullion-only startup override.

The Live benchmark card follows the source-card rhythm with 24K and 22K price rows, plus compact median-cleaned notes, range bars, and low/high labels specific to each karat. The bullion trend graph plots blended benchmark history with date/time on the x-axis and price on the y-axis. It shows 24K and 22K blended benchmark lines together; the 24K/22K controls toggle each visible line, and the 22K line uses a warm secondary gold tone. The visible low gets 5% lower padding and the visible high gets 5% upper padding. Product Refresh All is store-parallel, capped by `PRODUCT_STORE_PARALLELISM` (default 4), while each store keeps its own internal worker concurrency.

## v4.4 notes
- Myntra purity parsing now accepts 22K/18K/14K products (for example `Metal Purity: 22 KT`) instead of rejecting them as non-24K.
- Products below 24K are shown in a separate "Below 24K" watchlist section and are excluded from 24K bullion deal comparisons.
- Scheduled auto-refresh refreshes below-24K products less often. Default multiplier: 6x the configured product interval. Override with `SUB24K_REFRESH_MULTIPLIER` (minimum 2).
- Manual product refresh still refreshes the explicitly requested products immediately.
- Mobile sorting uses a larger native selector and 48px direction control.
- Mobile Watchlist gets a floating back-to-top button after scrolling down.

## v4.5.0 MacBook/UI reliability notes

- Product live-state rendering is coalesced during active refreshes to reduce browser main-thread pressure with large watchlists.
- Product state persistence/broadcasts are coalesced more aggressively while refresh jobs are active.
- Store selection is normalized by domain: All always resets to every store; choosing a store from All focuses that store; additional store clicks create a multi-selection.
- `Show product browser` is honored by bulk-add refreshes instead of being overridden to headless mode.
- Store filters sent by the frontend are normalized on the server before matching products.
- No product parser, stored-data schema, or API route was removed.

## v4.6 performance engine

This build keeps the existing frontend/API/JSON format intact while adding an additive SQLite WAL mirror at `data/aurum.sqlite` for indexed current state, price history, bullion history and refresh-run performance metrics. JSON remains authoritative for compatibility.

Product workers keep Firefox alive and now reuse store browser contexts and rendered pages between products. Images, fonts and media are blocked in pooled headless pages; document/script/XHR remain enabled. Store pools run in parallel and concurrency can be tuned independently with `PRODUCT_AJIO_CONCURRENCY`, `PRODUCT_AMAZON_CONCURRENCY`, `PRODUCT_FLIPKART_CONCURRENCY`, and `PRODUCT_MYNTRA_CONCURRENCY` (defaults 12/10/12/12).

A 1000-product / 30-second sweep is a throughput target, not a guarantee: retailer response time and throttling still set the upper bound. Increase per-store concurrency gradually and use the `refresh_runs` SQLite table to measure actual products/sec and success rate before raising it further.

## v4.7 MacBook Firefox resource profile

The rendered-browser pipeline keeps Firefox sessions alive, but now releases expensive idle page DOMs to `about:blank` and retains only one idle page per store context by default. Peak page concurrency is reduced to MacBook-balanced defaults (Ajio 6, Amazon 4, Flipkart 6, Myntra 6), and at most two retailer batches run simultaneously unless `PRODUCT_STORE_PARALLELISM` is increased. This preserves warm cookies/contexts while substantially reducing idle and peak memory pressure.

Tuning: `PRODUCT_POOL_IDLE_PAGES=1`, `PRODUCT_POOL_BLANK_IDLE=1`, and `PRODUCT_STORE_PARALLELISM=2`. For a low-memory machine use store parallelism 1 and concurrency 3-4. For a fast/high-memory machine, raise store concurrency gradually while watching refresh-run throughput rather than maximizing page count.

## Current Product Refresh

- Aurum uses Playwright Firefox only, with direct network browser launches. Proxy environment values and saved proxy credentials are not injected into browser sessions.
- **Show bullion browser** opens a visible Firefox rendering pass for every bullion source. **Show product browser** opens visible direct-master and targeted product sessions. Both switches apply on the next refresh.
- **Refresh bullion at server start** checks every bullion source during startup. **Refresh products at server start** runs the same full direct-master product refresh as Refresh All after startup bullion collection completes.
- Multi-product Refresh All, selection, and filtered store refreshes use store catalogue masters. Only a failed store master sends that store's selected products to product-page fallback; a single-product Retry remains direct PDP.
- Direct masters use one isolated temporary profile tree under `/tmp/aurum-run-*` per Aurum process. Each store receives its own profile subdirectory, all contexts are reused during that process, and the full tree is removed on shutdown.
- AJIO keeps its two category pages separate, retries page-zero API readiness with a 30-second ceiling, then supplies that successful response to the unchanged master to avoid a duplicate initial request.
- The HTTP fast path is enabled by default for Amazon, Flipkart, and Myntra before their browser fallback. It has no adaptive cooldown.

## v4.9.2 visible-browser lifecycle fix
- `Show product browser` now reuses the persistent browser/context/page pool instead of creating a fresh context for each product on Amazon, Flipkart, or Myntra.
- AJIO no longer forces visible mode when the setting is off.
- Bulk refresh no longer launches surprise visible fallback browsers after a headless failure.
- In visible mode each store worker keeps one Firefox process alive for the batch/runtime and uses one persistent context with pooled tabs. Concurrent workers may use multiple tabs, but not one new browser per product.


## v4.9.3 hotfix
- Fixes MMTC 22K purity token `916` being displayed as ₹916/g; implausible 22K parses now fall back to the existing derived 22K rate.
- Show product browser now intentionally uses one reusable visible page per store, preventing a browser/window storm.
- AJIO warms the exact pooled page on the AJIO homepage before product navigation, while retaining the same persistent context/session.


### v4.9.6 AJIO transient-route capture
AJIO now reads price/weight/purity while the product route is visible, before a client-side redirect can return the single persistent tab to the AJIO homepage. It does not open extra tabs and no longer retries by warming the homepage after a product-route bounce.


### Current AJIO PLP refresh

- Direct catalogue refresh opens retained Firefox pages for `830306009` and `830306012`, waits until each exact page-zero API request succeeds, and then evaluates the supplied no-scroll master sequentially.
- The successful page-zero JSON is passed to the master so it does not immediately repeat the critical request. If API readiness is still unavailable after 30 seconds, AJIO is reported as a failed store and only then may selected products use targeted fallback.
- Set `PRODUCT_AJIO_MANUAL_DIAGNOSTIC=1` to open the two pages without probing or executing the master. `PRODUCT_AJIO_API_READY_TIMEOUT_MS` overrides the 30-second readiness ceiling.
