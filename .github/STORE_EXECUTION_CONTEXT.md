# Product Store Execution Context

Verified against source: 2026-08-30

This document describes current production behavior, including awkward or inconsistent behavior. Do not "correct" a path to match README release notes without tests and an explicit behavior decision.

## How To Use This File

Before changing a store, read:

1. The store section below.
2. `src/product/stores/<store>/worker.js`, `collector.js`, and the called part of `store.js`.
3. The shared section if the store uses `http-fast-path.js` or `page-pool.js`.
4. The parent orchestration section of `src/app/server.js` when changing IPC, timeout, queue, failure, or concurrency behavior.

Legend:

- **Used**: reached by the current server-driven product refresh path.
- **Fallback**: reached only after the preceding path fails or a setting enables it.
- **Dormant**: present in source but not reached by the current production refresh chain.
- **Defensive**: normally unreachable but retained for failure containment.

## Parent Product Orchestration

### Used

- `productWorkerPathByStore` maps normalized `ajio.com`, `amazon.in`, `flipkart.com`, and `myntra.com` domains to fixed worker files.
- Unfiltered `POST /api/products/refresh` runs exactly four direct catalogue masters concurrently (`runAjioMaster`, `runMyntraMaster`, `runFlipkartMaster`, `runAmazonMaster`) with `Promise.allSettled` and merges returned catalogue records directly. The AJIO master owns four configured filtered pages in one persistent Firefox window: Boys curated, Girls curated, Jewellery curated with 22K included, and Women category with 24K/999 facets, `nontransacted` cohort, and pincode `560048`. Every page executes only `manual_js/ajio_gold_master.js`, the unified script also supplied as `new.js`; original split scripts are archive-only under `manual_js/backups/2026-08-30-unified-ajio/`. It creates all but the first through target-blank `window.open`, with Firefox preferences set to open them as tabs rather than windows. It broadcasts start, link, merge, failure, and fallback-skipped events to Refresh Activity. Filtered/selected batches run only masters represented by their targets. A failed all-store or filtered batch master preserves its existing records and opens no PDPs; only an explicitly ID-selected multi-product request may run targeted fallback for products from its failed store. Incomplete rows from a successful master do not open targeted product pages.
- Direct masters own one process-lifetime Firefox persistent context per retailer and reuse their URL-bound listing pages. At first direct-master use, the parent creates one `/tmp/aurum-run-*` root with isolated `{ajio,amazon,flipkart,myntra}` profile subdirectories. These four profiles are reused for the Aurum process lifetime, no profiles are shared between retailers, and the parent removes the full root after closing every context on restart/shutdown. AJIO receives its assigned `ajio` directory through its proxy-sanitized child environment, retains one page per configured category, and never navigates its `830306009` page to `830306012`. Each page snapshots its own `plpRequestMobile` request before the next same-origin page can overwrite shared local storage. Automatic execution then retries that page-specific page-zero request for up to 60 seconds with backoff; a successful JSON page is assigned to `window.__AURUM_AJIO_PAGE0__`, avoiding a duplicate initial request. The unified AJIO script enriches incomplete genuine-gold records from serialized PDP `Net Weight`/`Metal Weight`, `UOM`, and `METAL PURITY` option values while preserving category-derived prices. Readiness failures report an explicit AJIO browser/API initialization error. `PRODUCT_AJIO_MANUAL_DIAGNOSTIC=1` opens all configured AJIO pages without probe or master execution. The All products direct-master path opens no Chrome browsers. Direct masters always use direct network and ignore all environment/saved proxy settings and proxy credentials; browser-native authentication prompts are left to the user.
- Server startup removes only stale `/tmp/aurum-run-*` directories older than 24 hours, never a current run. Normal restart plus `SIGINT`/`SIGTERM` await direct-master disposal before server close, then remove the active run root.
- Every Aurum browser launch uses direct network. No direct master, product worker, or bullion collector reads a proxy address from environment variables or injects saved credentials. AJIO direct-master Firefox is additionally forked from an environment that explicitly removes every proxy environment variable, matching the verified standalone Firefox command. The Settings credential UI/API remains present for a future explicit user-prompt flow but does not configure a current runtime.
- Every current Aurum browser path uses Playwright Firefox only. Direct masters, AJIO/Myntra catalogue workers, Amazon/Flipkart workers, and bullion collection have no Chromium or Google Chrome selection.
- `productDebugVisibleBrowser` controls direct-master visibility as well as legacy product workers. When it changes, each affected direct master replaces its retained context on the next refresh to honor the new mode. `debugVisibleBrowser` forces a visible browser rendering pass for every bullion source, even when a fast HTTP result already exists.
- Every multi-product refresh enters the direct catalogue-master route for exactly its represented stores, including Refresh selection and filtered store sets. Only a single-product Retry goes directly to a product page. If an applicable direct master fails, only that store's selected products enter PDP fallback.
- `runProductsRefreshJob` groups products by store and runs up to `PRODUCT_STORE_PARALLELISM` store batches at once. Current default: **4**.
- `ensurePersistentProductWorker` keeps one forked Node process per store.
- `runPersistentProductWorkerTask` serializes batches for the same store through `productWorkerQueues`; different stores may overlap.
- Every worker has its own `running` guard and rejects overlapping requests as `"<store> product worker busy"`.
- Progress IPC can incrementally merge and persist completed products before the full batch finishes.
- Each store job catches and reports its own failure. One blocked retailer must not reject the all-store orchestration while other store workers are still running.
- A mixed result where AJIO is blocked but other stores complete is global `partial`, not global `blocked`; final counts include live/stale/unverified/failed/unavailable.
- Parent failure handling preserves a valid old price as `stale`; without a valid old price it becomes `failed`.
- Explicit collector classifications such as sold out/not found may mark `unavailable` and clear `price`/`couponPrice` before parent merge.
- Per-product collector progress treats `unavailable` as a valid product state, not a technical scraper failure count increment.

### Timeouts

- Persistent worker timers are **total task lifetime**, not inactivity timers. Progress does not re-arm them.
- Non-AJIO multi-product batch: `PRODUCT_BULK_WORKER_STALL_MS`, default **300000 ms**.
- Non-AJIO single product: `PRODUCT_WORKER_TIMEOUT_MS`, default **90000 ms**.
- AJIO single product: `PRODUCT_WORKER_TIMEOUT_AJIO_MS`, default **45000 ms**.
- AJIO batch: `products.length * 35000 + 60000`, clamped to **180000..3600000 ms**, unless overridden by `PRODUCT_AJIO_BULK_WORKER_TIMEOUT_MS`; visible debug may use `AJIO_DEBUG_WORKER_TIMEOUT_MS`.

### Dormant / Not A Fallback

- `runWorkerTask` implements an inactivity timeout that re-arms on progress, but it has no current product call site. Do not describe or modify it as the persistent product-worker fallback.
- `PRODUCT_STORE_DOMAIN` is passed into each child environment, but workers use their own hard-coded domains.
- `productPersistentBrowser: true` is sent in settings, but collectors do not read that flag; persistence is already unconditional within each worker.

### High-Risk Invariants

- Keep both parent per-store queueing and worker `running` guards unless shared browser state is redesigned.
- Preserve progress IPC payload shape and incremental persistence.
- Never log runtime proxy credentials or page-pool proxy identity keys.
- Non-gold filtering exists in store parsers and parent merge. Removing one layer requires coverage for every entry path.

## Shared HTTP Fast Path

Used only by **Amazon, Flipkart, and Myntra**. AJIO does not import it.

### Used

- `tryHttpFastPath(product, store.parse)` fetches HTML and passes it to the same store parser used by browser HTML.
- Enabled by default. Set `PRODUCT_HTTP_FAST_PATH=0` to disable.
- Timeout: `PRODUCT_HTTP_FAST_TIMEOUT_MS`, default **8000 ms**, minimum 1000.
- Per-process semaphore: `PRODUCT_HTTP_FAST_CONCURRENCY`, default **24**, minimum 1. Each store is a separate process, so this is not one application-wide semaphore.
- Amazon URLs normalize to `/dp/<ASIN>`; Flipkart removes the `marketplace` query parameter.
- A successful parse is accepted immediately as `live` with `refreshMethod: "http"`. There is no browser verification after success.

### Fallback

- HTTP timeout, non-2xx response, fetch error, or unusable parsed HTML falls through to store browser rendering.
- If browser rendering also fails, the HTTP failure reason is appended to the browser error.

### Not Used / Not Implemented

- No adaptive failure counter, five-minute bypass, or cooldown exists.
- HTTP is not merely opt-in despite older README wording.
- Browser rendering is a fallback, not the source of truth after a successful HTTP parse.

## Shared Page Pool

Used by **Amazon, Flipkart, and Myntra**. AJIO owns a separate runtime.

### Used

- `acquirePooledPage` keeps one context per browser plus proxy-auth identity and reuses pages.
- New pages block images, media, and fonts; documents, scripts, and XHR remain enabled.
- `releasePooledPage` sends healthy pages to `about:blank` by default and retains up to `PRODUCT_POOL_IDLE_PAGES`, default **1**.
- Set `PRODUCT_POOL_BLANK_IDLE=0` to retain the current DOM when idling.
- Unhealthy/closed pages are removed and closed.
- Context defaults: locale `en-IN`, timezone `Asia/Kolkata`, viewport 1366x900, Firefox default user agent, optional `httpCredentials`.

### Defensive / Currently Unreachable

- Store branches that create a direct context/page when the pooled handle is falsy are defensive. `acquirePooledPage` currently returns a handle or throws.

### Not Used

- Product stores do not use `src/common/browser.js` or product settings from `src/common/environment.js`; they launch Firefox and create contexts in store code.
- The shared browser proxy launch configuration is therefore not automatically applied to product-store Firefox launches. Proxy auth is applied at context level.

## AJIO (`ajio.com`)

### Accepted-Browser Bridge

- The extension runs in accepted AJIO category tabs, reloads before pulling, then uses the supplied master extractor's same-origin `/api/search` live pagination for `gold coin`. It fetches remaining pages at bounded concurrency 8, deduplicates codes, and selectively calls `/api/p/<code>` for listing records missing weight or karat before posting to the local `/api/browser-bridge/products` endpoint.
- This is separate from the worker session. It is the preferred path when the standalone worker receives 403 but an open user browser tab receives API 200. It does not navigate product PDPs.

### Active Chain

Automatic/batch: `server persistent worker` -> `ajio/worker.js` -> `refreshProductBatch` -> `refreshProductBatchFromListings` -> `/api/category/<id>` pages -> purity facets -> bounded `/api/p/<code>` JSON qualifiers -> qualified merge/discovery.

Manual single Retry: `runSingleProductRefreshJob` sets `ajioTargetedRefresh` -> legacy `refreshProduct` -> `store.refreshProductPage` -> `fetchProductViaInBrowserApi`.

### Used

- Files: `ajio/worker.js`, `ajio/collector.js`, `ajio/listing.js`, `ajio/browser-runtime.js`, and targeted helpers in `ajio/store.js`.
- `ajio/browser-runtime.js` is installed automatically into the owned AJIO tab. It performs timed same-origin JSON requests and exposes live diagnostics at `window.__aurumAjioListing.state` (`phase`, category/page, request count, last URL/status/error).
- `scratch/ajio-listing-extractor.js` is a manual canary and is not injected by production; do not confuse it with the automatically executed browser runtime.
- PLP is enabled unless `PRODUCT_AJIO_PLP_FLOW=0`. Default categories are `830306012,830306009`, overridable with `PRODUCT_AJIO_PLP_CATEGORIES`.
- Each category bootstrap navigates, waits only for navigation commit, and reloads before reading preloaded state or issuing listing requests. The bounded bootstrap readiness check then waits for preloaded data or an explicit block signal; this ensures a slow DOM load cannot bypass the required reload. Listing pages are then fetched sequentially and zero-based in one persistent Firefox context to retain the 403 pacing/cooldown safeguards. Targeted single-product Retry remains Firefox. Show Product Browser controls headless/visible mode.
- Listing JSON supplies identity, name, brand, current price, MRP and offer price. Purity facets fill missing classifications only for codes returned by each filtered facet.
- Bounded detail JSON joins exact weight/UOM from shortened-code `variantOptions` with purity from full-code `baseOptions`; no PDP document navigation is used.
- Strict discovery criteria: gold, at least 22K, at least 0.5g, positive price. Parent `mergeAjioDiscoveries` appends canonical URLs once.
- Tracked metadata is reused before enrichment to reduce repeat facet/detail requests. Manual grams/purity remain preferred during merge.
- Catalogue misses and incomplete records enter the existing targeted product-route fallback. Explicit sold-out/removal/non-gold evidence becomes `unavailable` and clears price; a final transport/parser failure is `failed` while retaining the last known price for inspection. Catalogue absence alone never clears a price.
- Controls: `PRODUCT_AJIO_PLP_REQUEST_TIMEOUT_MS=12000`, `PRODUCT_AJIO_PLP_REQUEST_ATTEMPTS=3`, `PRODUCT_AJIO_PLP_REQUEST_DELAY_MS=300`, `PRODUCT_AJIO_PLP_DETAIL_LIMIT=50`.
- Listing HTTP 403 preserves stale values, reports blocked and starts `PRODUCT_AJIO_403_COOLDOWN_MS` (default 120000ms). Do not rotate sessions to evade it.
- The PLP page is retained per persistent context after success or block; it is not closed at batch completion. Worker shutdown still closes the browser/context.
- Category page 0 is consumed from `window.__PRELOADED_STATE__.grid.entities`; API pagination starts at page 1 to avoid immediately requesting page 0 twice.
- If direct pagination is blocked, the installed runtime scrolls ReactVirtualized grids and the window and streams every DOM batch through worker progress IPC.
- DOM fallback must reach `PRODUCT_AJIO_PLP_MINIMUM_COVERAGE` (default 0.8 of category-reported records) before merge. Lower coverage reports blocked and preserves every last-known product.
- Server refresh dispatch does not set AJIO records to `checking`; this preserves each prior live/stale/unavailable status when a blocked or low-coverage run returns. Block attempts update `lastAttemptAt` and error details, not `checkedAt`/`lastLiveAt`.
- Server refresh dispatch also preserves Myntra records supplied by the accepted-browser bridge. A blocked worker run updates `lastAttemptAt`/`lastAttemptError` without downgrading or attaching a visible failure error to a current `live` price.
- Worker `SIGTERM`, `SIGINT`, and disconnect handlers await browser cleanup.

### Current Fallback Behavior

- Batch order: listing text -> purity facets -> bounded detail JSON. PDP documents remain inactive.
- Manual Retry retains candidate-code `/api/p` lookup and existing unavailable/stale classification.
- Ambiguous AJIO 404/not-found is not proof of product unavailability: preserve a known price as stale or use failed/unknown without a price. Only explicit out-of-stock/sold-out/no-longer-available and non-gold evidence use `unavailable`.
- Any AJIO targeted fallback worker, network, or parser failure with a valid last-known price stays `stale`; do not escalate it to `failed`. A `failed` status requires no usable retained price or an actual processing classification unrelated to transient transport.
- Neutral status is `unverified`: use it for AJIO products with no current price when listing/API access cannot establish availability. It is retryable and distinct from an actual processing `failed` state.
- Myntra timeout/worker-exit ambiguity without a price is also `unverified`; it is not evidence of product unavailability.
- `PRODUCT_AJIO_PLP_FLOW=0` rolls batch jobs back to the old per-product path.

### Dormant / Ineffective / Misleading

- Dormant production path: `bootstrapContext`, `open`, `waitForData`, `ensureAjioProductRoute`, `recoverToProductUrl`, `waitForDomReady`, `navigateAndCaptureProduct`, visible-DOM extraction, and transient-route HTML capture.
- `parse(html, url)` remains useful to standalone tests but is not called by AJIO collector and is not a production fallback.
- AJIO does not use shared `tryHttpFastPath` or shared `page-pool.js`.
- Legacy targeted timeout/concurrency/restart settings do not control PLP batch pagination.

### Do Not Change Casually

- Do not activate dormant DOM navigation without explicit request budgets and 403 handling.
- Do not change API error classification without tests proving transient errors preserve stale values and true 404/out-of-stock clears them.
- Do not remove canonical URL deduplication or parent discovery merge; worker-created UUIDs are not stable identity.
- Back-to-back canary/production scans caused listing HTTP 403. Five-minute operation must retain pacing, metadata reuse, non-overlap and cooldown.
- AJIO page console warnings about unused `assets-jiocdn`/Lora preloads and `[copilot] Max attempts reached` come from AJIO's frontend and are not collector failures. The actionable block signal is the explicit `/api/category` HTTP 403 captured by the collector.
- Verified under active Akamai block: the browser script streamed 18 DOM batches, combined 52/780 cards, failed the 80% coverage gate, preserved all records/prices, and kept Firefox plus the worker alive. This is an upstream denial, not a stopped script.
- AJIO targets are not pre-marked `checking`; blocked/low-coverage attempts retain prior status and successful timestamps while recording `lastAttemptAt`.
- `scratch/restore-ajio-plp-status.js` is a one-off recovery tool for the earlier status-regression incident. It restores only prior successful `ajio-plp` records that still pass price, >=22K, >=0.5g and non-gold checks.

## Amazon (`amazon.in`)

### Active Chain

`worker` -> `refreshProductBatch` -> internal `refreshProduct` -> `tryHttpFastPath(store.parse)` -> on failure `store.refreshProductPage` -> pooled page -> `open` -> `waitForData` -> stable HTML read -> `parse`

### Used

- Files: `amazon/worker.js`, `amazon/collector.js`, `amazon/store.js`.
- Concurrency: `PRODUCT_AMAZON_CONCURRENCY`, then `PRODUCT_CONCURRENCY`, then **4**; visible/debug mode forces one.
- Item/readiness/launch defaults: **75000 / 45000 / 30000 ms**.
- HTTP and browser both parse product title, Amazon core/legacy price selectors or `priceToPay`, shared weight, and 999/24K purity text.
- `waitForData` timeout is swallowed; final HTML parsing determines success.
- Stable HTML read retries three times with **750 ms** delay.
- Access-block and sold-out text are checked after rendering.
- Disconnected browser is replaced on next acquisition; no success/failure restart threshold or cooldown.
- Worker explicitly cleans up on IPC disconnect; it has no AJIO-style SIGTERM/SIGINT handlers.

### Fallback

- Failed shared HTTP probe -> pooled Firefox page rendering.
- Optional headless failure -> visible retry only when `productFallbackVisibleOnFailure` is true; normally false.
- Sold out/not found clears values. A transient failure preserves a valid previous value as stale; without one, collector status becomes unavailable.

### Not Used

- No store-specific API path.
- No adaptive HTTP bypass, browser restart threshold, or cooldown.

## Flipkart (`flipkart.com`)

### Active Chain

Automatic/batch has two separate flows: regular `marketplace=FLIPKART` products use a Firefox listing page -> bounded default/price-ascending/price-descending catalogue HTML -> qualified tracked/discovered merge; Flipkart Minutes `marketplace=HYPERLOCAL` products retain the existing per-product browser/location flow. The regular listing flow never falls back to individual PDP navigations.

Manual single Retry: `server` sets `flipkartTargetedRefresh` -> `worker` -> `tryHttpFastPath(store.parse)` -> on failure `store.refreshProductPage` -> pooled page -> `open`/location handling -> stable HTML read -> `parse`. A single retry never runs catalogue discovery.

### Used

- Files: `flipkart/worker.js`, `flipkart/collector.js`, `flipkart/store.js`.
- `flipkart/listing.js` owns listing normalization, identity, qualification, and canonical discovery records. Batch listing is enabled unless `PRODUCT_FLIPKART_PLP_FLOW=0`; it scans the supplied gold/yellow-gold category across default, `price_asc`, and `price_desc`, with 30 pages and 150ms pacing by default (`PRODUCT_FLIPKART_PLP_MAX_PAGES`, `PRODUCT_FLIPKART_PLP_REQUEST_DELAY_MS`).
- The regular Flipkart listing page is navigated then reloaded before every batch scan begins. This occurs before any card extraction or same-origin HTML page requests.
- Listing records require gold evidence, price, observed weight, and karat before they update or discover products. Catalogue misses/incomplete records fall back to their targeted product page; explicit stock/removal evidence becomes unavailable, while final non-stock failures are failed with the last known price retained. This does not affect Flipkart Minutes, which is already product-page based.
- Flipkart Minutes skips the HTTP fast path because its normalizer removes `marketplace`; the browser route retains hyperlocal location selection. Do not use regular listing prices or identity absence to update Minutes records.
- Raw Flipkart bridge rows can have no observed grams. Shared URL parsing treats the listing slug `0-05-g` as `0.05g`; it must not be normalized to `0.5g`. Bridge normalization strips volatile `iid`, `ssid`, `ov_redirect`, and `store` values from links while retaining stable `pid` plus `marketplace=FLIPKART`, so persisted product links do not depend on expired listing-session parameters.
- Concurrency: `PRODUCT_FLIPKART_CONCURRENCY`, then `PRODUCT_CONCURRENCY`, then **6**; visible/debug mode forces one.
- Item/readiness/launch defaults: **75000 / 45000 / 30000 ms**.
- Parser reads product heading/title, visible rupee/Rs price, JSON `sellingPrice`, shared weight, and karat/fineness text.
- Browser hyperlocal mode detects `marketplace=HYPERLOCAL`, uses `preciseAddress`/`pincode`, confirms location, and may fall back to non-hyperlocal URL when selection is unavailable.
- Stable HTML read, pool lifecycle, stale handling, and disconnect cleanup follow Amazon pattern.

### Fallback

- Failed shared HTTP probe -> pooled Firefox rendering.
- Location selection failure may continue and let final parsing validate the page.
- Optional visible retry exists only when explicitly enabled.

### Risk / Not Equivalent

- HTTP normalization removes `marketplace`; a successful HTTP parse can bypass hyperlocal browser handling and return non-hyperlocal pricing.
- Do not change URL normalization or location handling without explicit locality/price tests.
- No adaptive HTTP bypass, restart threshold, or cooldown.

## Myntra (`myntra.com`)

### Accepted-Browser Bridge

- The extension runs in an accepted `/gold-coin` tab, reloads before pulling, and posts live `/gateway/v4/search/gold-coin` records to the local bridge endpoint. It follows the supplied master extractor's bounded multi-sort boundary probes, deduplicates product IDs, and selectively fetches `/gateway/v2/product/{id}` for missing weight or karat.
- Browser bridge records are merged only when a retailer API returns a positive price; they persist as `refreshMethod: "myntra.com-browser-bridge"` or `"ajio.com-browser-bridge"`.

### Active Chain

Automatic/batch: `worker` -> Firefox `/gateway/v4/search/gold-coin` pagination -> bounded `/gateway/v2/product/{id}` enrichment -> qualifying tracked/discovered merge. It never falls back to individual PDP navigations.

Manual single Retry or catalogue transport fallback: `worker` -> `tryHttpFastPath(store.parse)` -> on failure `store.refreshProductPage` -> pooled page -> `/gateway/v2/product/{id}` attempt -> on unusable/error response retrying `open` -> stable HTML read -> `parse`.

### Used

- Files: `myntra/worker.js`, `myntra/collector.js`, `myntra/store.js`.
- `myntra/listing.js` owns PLP record normalization, product-detail enrichment, evidence, qualification, and canonical discovery records.
- PLP is enabled unless `PRODUCT_MYNTRA_PLP_FLOW=0`. It uses the supplied master extractor's complete multi-sort, high-yield, and boundary probe set at bounded concurrency 12 (default max 95 requests), then deduplicates every overlapping response. It has at most 500 product-detail enrichments and 150ms request pacing by default; each is configurable through the documented `PRODUCT_MYNTRA_PLP_*` controls. Navigation/reload waits for commit so a slow page DOM cannot prevent API collection.
- Every Myntra listing request includes the automatically assigned pincode from either `mynt-ulc` or the current `mynt-ulc-api` cookie; use the persisted Aurum pincode setting only as fallback. No login or manual pincode entry is required. The user's accepted-session extractor demonstrated 335 unique records from this API-only loop plus 89 selective product JSON enrichments.
- The retained Myntra listing page is reloaded before every batch scan before its cookie/pincode or listing API reads are used.
- Detail enrichment is bounded to records missing weight or karat, not merely exact fineness, and runs at `PRODUCT_MYNTRA_PLP_DETAIL_CONCURRENCY` (default 8). Listing bootstrap is one attempt capped at `PRODUCT_MYNTRA_PLP_BOOTSTRAP_TIMEOUT_MS` (default 8000ms) so unavailable transport ends quickly.
- The search collector includes organic and PLA records, deduplicates by product ID, and discovers records with positive price, gold evidence, an observed weight, and any observed karat. Existing manual grams/purity remain preferred. Product JSON enrichment covers missing weight/karat and 24K records without exact fineness; it preserves `purityLabel`, descriptive purity evidence, and a structured-versus-description conflict flag.
- Concurrency: `PRODUCT_MYNTRA_CONCURRENCY`, then `PRODUCT_CONCURRENCY`, then **6**; visible/debug mode forces one.
- Item/readiness/launch defaults: **75000 / 45000 / 30000 ms**.
- Navigation retries up to three times only for HTTP/2, connection reset/network change, or proxy-refused errors, with 1-second then 2-second delays.
- Primary parser source: `window.__myx.pdpData` product/brand, descriptors, attributes, seller prices, coupon, stock, weight, purity, and karat.
- The browser route first navigates to `/gateway/v2/product/{id}` and parses `style` fields, including seller price, `articleAttributes`, descriptors, product details, and content groups. This skips full PDP rendering when it returns usable gold data.
- Successful gateway parses persist `refreshMethod: "api"`; PDP parses retain `refreshMethod: "browser"` so refresh progress and stored data reveal actual route usage.
- HTML fallback reads title, visible text, embedded price JSON, specification rows, URL slug weight, and purity rows.
- Shared weight helpers are combined with Myntra-specific slug/combo parsing.
- Known retailer typo: product `41493417` says `500 g` in its title but is 500 mg. Price-aware `normalizeGoldWeight` resolves `500` to `0.5`; preserve this in Myntra parsing and server startup reconciliation.
- Pool lifecycle, stable HTML read, stale behavior, and disconnect cleanup follow Amazon pattern.

### Fallback

- Failed shared HTTP probe -> pooled Firefox rendering.
- A failed Firefox listing bootstrap/API request reports one blocked/partial note and preserves current product values without per-product refresh work. It does not add discoveries, clear prices, or mark unobserved products unavailable.
- Aurum browser launches always use direct network. They ignore `HTTPS_PROXY`, `HTTP_PROXY`, and `NO_PROXY` and do not inject saved proxy credentials.
- Catalogue misses/incomplete records and a failed catalogue bootstrap fall through to the existing targeted product API/PDP route. Explicit stock/removal evidence becomes unavailable; final non-stock failures are failed with their last known price retained. A transport failure disables further gateway attempts for that persistent Myntra worker, avoiding repeated failed requests.
- Missing `__myx` data -> HTML/specification parser.
- Optional visible retry exists only when explicitly enabled.

### Dormant

- `gramsFromWeightRange` and `gramsFromFreeText` are defined but have no caller.
- No adaptive HTTP bypass, restart threshold, or cooldown.

## Failure State Matrix

| Condition | Typical result | Existing prices |
|---|---|---|
| Successful parse | `live` | Replaced with parsed values |
| Transient failure with valid old price/grams | `stale` | Preserved |
| Explicit sold out/not found/unavailable | `unavailable` | Cleared |
| Parent worker failure without valid old data | `failed` | No usable value |
| Amazon/Flipkart/Myntra collector failure without old value | `unavailable` | No usable value |
| AJIO synthetic 404 after API miss | `unavailable` | Cleared, even if underlying miss was transient |

Always check both collector `setFailure` and parent `finalizeProductFailure`/merge behavior before changing this matrix.

## Documentation Drift To Ignore Until Corrected

Historical README sections describe releases, not guaranteed current behavior. Current source differs in these places:

- Old per-store defaults 12/10/12/12; current AJIO/Amazon/Flipkart/Myntra defaults are **6/4/6/6**.
- Old store parallelism 2; current default is **4**.
- HTTP fast path is described as opt-in/adaptive; it is enabled by default and has no adaptive cooldown.
- Successful HTTP parse is accepted without browser verification.
- Shared page pooling does not apply to AJIO.
- AJIO transient-route/DOM capture exists but is dormant in production.
- AJIO is not sequential in headless mode; current default concurrency is 6.
- AJIO success restart is 5000, active failure restart is 20, item timeout is 25 seconds, API-branch page-ready timeout is ineffective, and 403 cooldown is 2 minutes.

When source and this guide disagree, re-read the owning source and update this guide plus `AGENT_CHANGES.md` in the same change.
