# AJIO PLP-First Flow Plan

Status: production implementation active; validation and hardening ongoing
Updated: 2026-08-29

This file tracks the proposed redesign before implementation. Current production behavior remains documented in `STORE_EXECUTION_CONTEXT.md` and remains authoritative until this plan is approved and implemented.

## User Goal

Replace normal per-product AJIO page/API refresh with one AJIO search-results (PLP) browser session:

1. Open `https://www.ajio.com/search/?text=gold%20coin` once.
2. Incrementally scroll/load the result list.
3. Parse product cards already rendered on the listing page.
4. Match extracted products to Aurum records and update in bulk.
5. Avoid opening every PDP and reduce request volume, browser churn, timeouts, and rate-limit exposure.

Qualifying discovery scope decided by the user:

- Import newly discovered products, not only refresh existing tracked records.
- Gold products at **22K or higher**.
- Weight **0.5g or higher**.
- Include coins, jewelry, pendants, bars, and other qualifying gold products.
- Track enough price, weight, purity, and offer data to rank the best price-per-gram deals correctly.

The pasted browser-console extractor is a prototype/input, not approved production code. It must be adapted to Aurum's numeric schema, parser rules, worker IPC, failure semantics, and browser lifecycle.

## Observed Live PLP Evidence

Read-only inspection on 2026-08-29; no full extractor or PDP fallback was run.

- AJIO page reports **2,890 Items Found**, not the prototype's hardcoded 880.
- At approximately `scrollY=30,900` with document height about 35,862px, the DOM contained 180 matching anchors and **178 unique product IDs**.
- Product anchor selector currently works: `a[href*="/p/"]` filtered by `/p/<digits>_multi`.
- Card class observed: `.rilrtl-products-list__link.desktop`.
- `.brand` and `.nameCls` were present in sampled cards.
- Card text includes current price, MRP, discount, and usually `Offer Price` or `Best Price`.
- Read-only prototype dry run over 178 unique cards produced:
  - missing name: 0
  - missing current price: 0
  - missing weight: 4
  - missing karat: 7
  - missing explicit purity: 48
  - Offer/Best price found: 172
- Existing authoritative AJIO state contains 658 unique tracked products: 40 stale, 618 unavailable, 0 live at inspection time.

Enhanced structured canary results using timed listing requests in visible AJIO tabs:

- Filtered search URL supplied by the user: API `GET /api/search`, zero-based pages 0-29, 30 requests, 0 retries/failures, 1,340 records and **1,336 unique product codes**. Four records were duplicate codes across pages. All unique records had names/current prices; 1,308 exposed Offer/Best prices. Preliminary title-based qualification found 663 products, but this count remains exploratory until Node-side parser fixtures pass.
- Category `830306012` (Women - Bars & Coins): API `GET /api/category/830306012`, query parameters from `window.__PRELOADED_STATE__.request.query`, 18 zero-based pages, 18 requests, 0 retries/failures, 774 records and **771 unique codes**. Preliminary qualifiers: 610; under 0.5g: 144; missing karat: 15; missing weight: 14; obvious non-gold: 2.
- Category `830306009` (Women - Idols & Coins): one API page, 7 unique records, 3 preliminary qualifiers; four records lacked title-visible weight/karat.
- Combined category union: 778 unique codes with no overlap between these two category IDs.
- Category union versus filtered search: 690 shared, 88 category-only, 646 search-only; total union **1,424 unique codes**. Therefore both the filtered search and category URLs add coverage and should be globally deduplicated.
- The rough DOM loop stopped at 1,296 because it omitted zero-based page 0. AJIO's API pages are 0 through `totalPages - 1`; requesting page `totalPages` returns an empty sentinel.
- Standalone read-only `scratch/ajio-listing-extractor.js` now runs both category IDs from either AJIO category page. Final verified run: 780 observations, 773 globally unique products, 621 strict qualifiers, 0 incomplete, 152 rejected, and 749 with offer prices. Requests: 19 listing + 25 purity-facet + 22 bounded detail JSON, zero retries/failures and zero PDP document navigations.
- Purity-facet membership resolved every tested missing karat classification: 15/15 Bars & Coins gaps and 7/7 Idols & Coins records. A facet label is applied only when the product code appears in that filtered facet result.
- `/api/p/<code>` JSON exposes exact `metalWeight`/`netWeight`/`grossWeight` and `uom` under `variantOptions`, while `metalPurity` often lives in the exact full-code `baseOptions` selection. Join those sibling structures; recursive label/value searches lose the association.
- Detail JSON recovered 15 exact weights through variant qualifiers, corrected two observed `10GG` title typos to 10g, and supplied metal evidence that rejected silver/platinum conflicts without loading product documents.

These numbers are observations, not stable selectors or completion targets. They must become fixtures/telemetry, not hardcoded assumptions.

## Proposed Production Execution

### Phase A: One PLP Session

- Reuse the persistent AJIO worker/browser lifecycle rather than introducing an independent browser process.
- Open the search URL once per AJIO batch.
- Use one page owner while scrolling/capturing; do not run concurrent operations against the same PLP page.
- Capture cards after initial load and after each controlled scroll/load cycle.
- Deduplicate by canonical AJIO product code from `/p/<code>`; preserve the canonical `_multi` code when present.
- Emit progress events with scroll count, unique cards, newly captured cards, stagnant cycles, page-reported count, and elapsed time.
- Prefer AJIO's structured listing endpoints once the page establishes the same-origin session:
  - filtered search: `/api/search` with query/refinement parameters derived from the page URL;
  - category: `/api/category/<categoryId>` with request parameters read from `window.__PRELOADED_STATE__.request.query`.
- Pagination is zero-based. Fetch pages `0 .. totalPages - 1`; do not infer pages from DOM batch count or request `totalPages` as a page index.
- Every listing request requires an abort timeout, bounded retries, pacing, and recoverable global/worker state. The validated visible canary used a 12-second abort, up to 3 attempts, and 250-430ms pacing.

### Phase B: Completion Detection

Do not use fixed `expectedTotal` as the primary stop condition.

Stop when one of these explicit conditions is met:

- A verified end-of-results condition is visible and two final captures add nothing.
- Unique product count remains unchanged for a configurable stagnant limit after attempts to trigger loading.
- Configurable maximum scroll count or wall-clock timeout is reached.
- AJIO blocks/challenges the session or the page/browser disconnects.

Record whether the run is `complete`, `partial`, `blocked`, or `failed`. A partial run must never imply that absent products are unavailable.

### Phase C: PLP Parsing

Normalize each card to an internal candidate, not directly to persisted state:

- `ajioCode`
- canonical URL
- `name`
- `brand`
- `price` (current listed selling price)
- `wasPrice` (MRP; informational unless schema is expanded)
- `couponPrice` (Offer/Best price only when positive and lower than current price)
- `grams` as a number, not a string such as `"2 g"`
- `purity`
- derived display `karat`
- `metal`
- field provenance (`plp`, preserved stored value, or fallback)
- capture timestamp

Reuse shared production rules where possible:

- `extractGrams`
- `normalizeGoldWeight`
- `isNonGoldProductText`
- AJIO `inferPurity`/purity-to-karat behavior
- canonical URL handling

Do not maintain a second incompatible parser inside `page.evaluate`. Page code should collect raw structured card fields; Node-side code should normalize and validate them with tested helpers.

### Phase D: Match, Qualify, Discover, And Merge

The user approved importing newly discovered qualifying products in addition to refreshing existing records.

- Match by canonical AJIO product code, not product name.
- For matched cards, update valid PLP fields and mark `refreshMethod: "ajio-plp"`.
- For new cards, add only after metal, karat/purity, weight, and price pass the agreed criteria: gold, at least 22K, at least 0.5g, and a valid positive selling price.
- The `gold coin` search alone cannot cover the approved coins/jewelry/everything scope. Discovery must use a reviewed list of AJIO category/query URLs, deduplicate globally by canonical product code, and record source query/category per observation.
- New records that lack enough evidence to qualify go to a review/incomplete report; do not add them with guessed purity or weight.
- Require at minimum a valid current price and product identity before accepting a PLP observation.
- Preserve known stored `grams`/`purity` when PLP omits them; record provenance rather than replacing with null.
- Apply price-aware weight normalization before merge.
- Preserve manual edits according to an explicitly agreed policy.
- A tracked product absent from a partial or complete search result must remain stale/unavailable as previously stored; absence alone is not proof of delisting or out-of-stock.
- Persist through existing server/worker result flow so JSON remains authoritative and SQLite/history/SSE stay synchronized.

### Phase E: Fallback Hierarchy

The user wants PLP collection made robust, with actual PDP navigation only as the last resort.

Proposed hierarchy to validate before implementation:

1. Parse the rendered PLP card.
2. Inspect structured listing-page state/network data already loaded for that card, without navigating away or issuing one request per product where possible.
3. Use an in-page/console-equivalent targeted detail extraction for unresolved fields, with deduplication, bounded concurrency, delay, and explicit request accounting.
4. Use current in-browser product API lookup only for remaining unresolved candidates and manual/specific refreshes.
5. Navigate to an actual PDP only as the final fallback, one controlled page owner at a time.

- Automatic fallback must be bounded and observable even if PLP is expected to succeed. Track attempts, request count, field gained, failures, and reason for advancing each candidate.
- Existing unmatched records preserve last-known data; absence is not a deletion or out-of-stock signal.
- Do not use the pasted prototype's unlimited `enablePdpFallback: true` pattern. In the observed sample, 48 of 178 cards lacked explicit purity, which could create a large request burst.
- The validated standalone implementation of steps 1-4 is `scratch/ajio-listing-extractor.js`. Defaults: 12-second abort, three attempts, 250-430ms listing/facet pacing, 300ms detail pacing, and at most 50 detail API requests. It is read-only with respect to Aurum.

## Prototype Parts Worth Keeping

- Map-based deduplication by AJIO product code.
- Incremental capture during scrolling, not only one final DOM scan.
- Brand/name selectors with image-alt and slug fallback.
- Explicit field-source/provenance tracking.
- Separate completion statistics and incomplete list.
- Offer/Best price recognition.
- Conservative enrichment concept, but only after policy and limits are agreed.

## Prototype Parts To Replace Or Fix

- `expectedTotal: 880`: invalid; live page reports 2,890 and counts can change.
- `TARGET` and DOM no-growth are not reliable completion criteria. The filtered DOM loop missed page 0 and stopped at 1,296, while the structured API returned 1,340 records / 1,336 unique codes.
- Starting from the page's current scroll position: production must own navigation and initialize its scroll/capture state.
- `parseWeight` returns formatted strings and handles only limited combinations. Aurum needs numeric grams and shared normalization, including mg/title typo handling and multipacks.
- `parseKaratPurity` accepts only 22/24K and has separate semantics from production purity handling.
- `getMetal` checks silver before gold and can misclassify mixed descriptive text; use production non-gold filtering.
- `parsePrices` assumes rupee order always means selling price then MRP. Validate selectors/labels and ensure rating/review text cannot affect extraction.
- Treating Offer Price and Best Price identically may be acceptable for `couponPrice`, but eligibility/conditions are not known from PLP.
- `extractPreloadedState` slices assignment text naively and may parse unrelated trailing script content.
- PDP enrichment still performs one request per incomplete product and conflicts with the request-reduction goal.
- Browser globals, console tables, and CSV/JSON download helpers are useful for manual inspection but not worker/server integration.
- No stock/out-of-stock proof, blocked-page classification, partial-run semantics, IPC integration, stale preservation, or state persistence exists in the prototype.

## Failure Semantics

| PLP outcome | Product outcome |
|---|---|
| Valid matched card | Update valid fields; `live`; method `ajio-plp` |
| Matched card missing grams/purity but stored values valid | Update price; preserve known metadata; provenance records preservation |
| Matched card invalid price | Do not overwrite; preserve stale value and record parse reason |
| Tracked product absent from PLP | Preserve prior state; never clear price based only on absence |
| Scroll stops early / timeout | Mark run partial; merge captured matches only |
| 403/challenge/block page | Stop run; preserve all uncaptured records; report blocked/cooldown |
| Browser crash/disconnect | Preserve completed incremental results; report partial/failed |
| Explicit manual retry | May use bounded current in-browser API path if approved |

## Browser And Rate-Limit Safeguards

- One PLP page and one active scrolling owner.
- No concurrent PDP navigation during PLP collection.
- Configurable scroll delay with jitter and maximum wall-clock time.
- Detect access-denied/challenge text before and during scroll.
- Do not rotate sessions to evade a block; preserve current cooldown principle.
- Keep heavy-asset policy deliberate: PLP may require images/intersection observers to trigger loading, so do not copy the PDP page-pool blocking policy without testing.
- Abort cleanly on parent timeout/signal and close/release browser resources.
- Emit enough progress to diagnose stalls, but remember parent persistent timeout is currently total-duration, not inactivity-based.

## Required Tests Before Switching Production

- Card fixture tests for current price, MRP, Offer Price, Best Price, missing metadata, 22K/24K, mg/g, combination weights, silver/non-gold, and malformed cards.
- Canonical AJIO code deduplication tests, including duplicate anchors for one product.
- Merge tests proving absent/partial products keep stale values and valid manual data is not erased.
- Block/403, timeout, stagnant scrolling, browser disconnect, and partial persistence tests.
- Comparison run: PLP results versus known-good stored/API results for a small fixed sample without changing production state.
- Controlled live canary for tracked-only mode, followed by JSON/SQLite/history/SSE verification.
- Rollback path to current API-only worker until canary quality thresholds pass.

## Proposed Rollout

1. Build a standalone read-only PLP collector regression tool under `scratch/`; no persistence.
2. Save sanitized card fixtures and write Node-side parser tests.
3. Run all approved discovery queries without persistence and produce a report: unique qualifying products, duplicates across queries, existing matches, new candidates, rejected/incomplete candidates, changed prices, missing metadata, suspicious values, and request counts.
4. Review report and decide merge/fallback policy.
5. Add worker action behind an environment flag; default remains current flow.
6. Run a canary with persistence disabled, then enable incremental persistence for a small reviewed subset of existing and new qualifying records.
7. Switch default only after accuracy, coverage, duration, and block-rate thresholds are agreed.
8. Retain explicit manual retry fallback and a quick rollback flag.

Rollout steps 1-6 are complete. PLP is the default AJIO batch path with `PRODUCT_AJIO_PLP_FLOW=0` as rollback; targeted single Retry remains on the old API path.

## Decisions Recorded

1. **Scope:** refresh existing records and import all newly discovered products that meet the gold/karat/weight/price criteria.
2. **Product criteria:** gold, at least 22K, at least 0.5g; include coins, jewelry, pendants, bars, and other qualifying forms.
3. **Progress/persistence:** merge incrementally as cards are validated, while tracking coverage, additions, updates, skips, failures, and fallback use.
4. **Browser mode:** headless by default, controlled by the existing Show Product Browser setting for visible execution.
5. **Fallback intent:** make PLP robust; inspect listing-page/console-accessible details next; actual product-page navigation is the last resort.

## Decisions Still Needed Before Implementation

1. **Discovery URLs:** exact AJIO searches/categories required to cover coins, jewelry, pendants, bars, and other gold without an unbounded general search.
2. **Specific refresh:** should one-product Retry use the current direct API first, search the PLP for that code first, or use PDP immediately in visible mode?
3. **Purity policy:** when PLP says 24K/22K but omits 999/916, infer standard purity or require structured evidence?
4. **Offer policy:** store both `Offer Price` and `Best Price` as `couponPrice`, or distinguish conditional offers?
5. **Manual edits:** always preserve manually edited grams/purity, or replace them when stronger structured PLP/API evidence exists?
6. **Incremental commit boundary:** persist each card, each scroll capture, or a small validated batch; each option changes write/SSE/history volume.
7. **Fallback budgets:** maximum automatic detail/API/PDP requests per run and acceptable delay/concurrency.
8. **Unmatched status:** keep each existing status unchanged, or convert prior live records not observed in a complete run to stale?
9. **Rollout threshold:** required qualification accuracy, duplicate rate, coverage, duration, and block rate before enabling automatic imports.

## Change Log For This Plan

- 2026-08-29: Created from the user's PLP-first proposal, pasted extractor, current Aurum AJIO contract, and read-only live-page inspection. No AJIO source or runtime behavior changed. No full scroll or PDP enrichment executed.
- 2026-08-29: Recorded user decisions to import all qualifying >=22K, >=0.5g gold products across product forms; merge incrementally with tracking; use headless by default with visible control; and reserve PDP navigation as the last fallback. Implementation remains unapproved.
- 2026-08-29: Implemented the production PLP path. First persistent run observed 779, imported 125, and produced 626 live across 783 AJIO records in about 45 seconds. A back-to-back repeat received HTTP 403; all 634 prices were preserved stale and collector cooldown/metadata reuse were added.
