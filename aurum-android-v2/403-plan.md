# Aurum V2 — AJIO 403 RCA Validation & Safe Refresh Implementation Plan

## Objective

Fix AJIO ingestion in Aurum V2 without changing the legacy implementation, without redesigning the existing UI, and without using browser automation or security-bypass techniques.

The immediate objective is:

```text
REAL AJIO RESPONSE
        ↓
REAL PRODUCT DISCOVERY
        ↓
REAL PARSING
        ↓
REAL VALIDATION
        ↓
REAL DATABASE UPSERT
        ↓
REAL UI UPDATE
```

The implementation must first establish empirical evidence for the current 403 behavior, then fix AJIO end-to-end before modifying Myntra, Flipkart, Shopsy, or Amazon.

---

# 1. CURRENT VERIFIED ISSUES

Based on inspection of the current V2 implementation, the following issues require investigation/fixing.

### 1.1 Destructive PDP fallback

`NativeParallelRefreshEngine.kt` currently contains a stale-product verification path that can iterate through approximately 1,376 existing products and request individual AJIO PDP endpoints:

```text
/api/p/{retailerId}
```

This must not be used as an uncontrolled fallback discovery mechanism.

More importantly, an HTTP failure must **never cause healthy existing catalogue data to be destroyed**.

The current behavior involving:

```text
database.dao().markOutOfStock(...)
```

must be audited and changed so that network failure, 403, timeout, parser failure, or incomplete refresh cannot incorrectly mark products out of stock.

---

### 1.2 Incorrect generic request profile

`CronetNetworkClient.kt` currently applies browser document-navigation headers to requests that may actually be REST/JSON API requests.

Examples include:

```text
Sec-Fetch-Dest: document
Sec-Fetch-Mode: navigate
Upgrade-Insecure-Requests: 1
Accept: text/html...
```

Do not assume these headers are the proven cause of the 403.

Instead:

1. Instrument the current behavior.
2. Compare it with an API-appropriate request.
3. Capture actual responses.
4. Determine whether the request profile materially affects the result.

Do not add headers merely to imitate or disguise another client.

---

### 1.3 Insufficient response diagnostics

`ProductFetchResponse` currently exposes only:

```kotlin
status
body
```

This is insufficient for diagnosing source behavior.

Expand it to expose diagnostic metadata such as:

```kotlin
data class ProductFetchResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val protocol: String = "",
    val durationMs: Long = 0L
)
```

Maintain compatibility with existing callers where practical.

---

### 1.4 Limited pagination

The current refresh engine uses a hardcoded/limited page strategy such as:

```text
maxPages.coerceAtLeast(8)
```

This is not acceptable as the actual catalogue discovery rule.

Pagination must be response-driven.

A safety ceiling may exist solely to protect against malformed pagination metadata or infinite loops.

---

# 2. STRICT SAFETY RULES

## No bot bypassing

Do not implement or introduce:

* CAPTCHA solving
* WAF bypass
* fingerprint spoofing
* stealth browser behavior
* rotating IPs/identities
* cookie harvesting
* browser-cookie extraction
* private authentication tokens
* browser automation
* Playwright
* Puppeteer
* Selenium
* WebView
* JavaScript execution
* localhost/loopback scraper bridges

The application must use legitimate native HTTP requests.

If AJIO returns HTTP 403, record it accurately and preserve last-known-good data.

---

# 3. STRICT EXECUTION ORDER

Execute phases sequentially.

## Phase 1

Instrumentation and empirical evidence.

## Phase 2

AJIO native refresh and database validation.

## Phase 3

Only after AJIO is proven end-to-end:

* Myntra
* Flipkart
* Shopsy
* Amazon

Do not modify all stores simultaneously.

---

# PHASE 1 — DIAGNOSTIC INSTRUMENTATION

## 4. Modify `ProductFetchResponse`

Expand:

```kotlin
data class ProductFetchResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val protocol: String = "",
    val durationMs: Long = 0L
)
```

The additional metadata is required for empirical diagnostics.

---

# 5. Modify `CronetNetworkClient.kt`

In `executeCronetWithHeaders`, capture:

```text
HTTP status
all response headers
negotiated protocol
request duration
response size
```

Use the actual Cronet response metadata.

Do not fabricate protocol information.

For example:

```text
h2
h3
http/1.1
unknown
```

must reflect the actual negotiated protocol.

---

## 5.1 API request method

Add a dedicated request path for REST/JSON endpoints, for example:

```text
executeCronetApiRequest(...)
```

It should use headers appropriate for a JSON API.

At minimum, investigate:

```text
Accept: application/json
```

or an endpoint-appropriate equivalent.

Do not blindly add:

```text
x-requested-with: XMLHttpRequest
```

unless the actual endpoint behavior demonstrates that it is required.

Do not use headers for the purpose of evading source protection.

---

# 6. Diagnostic Logging

Record sanitized information including:

```text
source
request ID
endpoint
HTTP status
protocol
duration
response size
content-type
relevant non-sensitive Akamai headers
retry-after where present
```

Never log:

```text
cookies
authorization tokens
credentials
private authentication values
PII
```

---

# 7. AJIO Benchmark

Update `AjioApiBenchmark.kt` to compare:

### Test A

Category 8303 using the current request profile.

### Test B

Category 8303 using the API/JSON request profile.

### Test C

Category 176606.

### Test D

Category 169379.

### Test E

Category 169373.

### Test F

Search endpoint, if still part of the current source configuration.

For every test capture:

```text
URL/path
HTTP status
protocol
duration
response size
content-type
pagination metadata
product count
relevant response headers
```

---

# 8. Evidence Classification

The final diagnostic report must classify conclusions as:

```text
PROVEN
LIKELY
UNPROVEN
```

For example:

```text
PROVEN:
The current client sends document-navigation headers to the JSON endpoint.

PROVEN:
The PDP fallback can issue a large number of individual requests.

UNPROVEN:
Those requests alone caused the subsequent 403 responses.

UNPROVEN:
A specific query parameter is mandatory for avoiding 403.
```

Do not turn correlation into causation.

---

# 9. Pixel 9 Verification

Build and install the application on the connected device.

Use the actual project/toolchain configuration.

Example:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"

.\gradlew :app:installDebug
```

Then capture diagnostics using the connected device.

Example:

```powershell
& "C:\Users\Agraw\AppData\Local\Android\Sdk\platform-tools\adb.exe" `
    -s 192.168.88.3:37997 `
    logcat -d -s AllStoresBenchmark CronetClient
```

Do not claim success unless the command actually succeeds.

---

# PHASE 2 — SAFE AJIO REFRESH

# 10. Remove the Uncontrolled PDP Sweep

Remove the current approximately 1,376-item PDP fallback sweep from the normal catalogue refresh path.

Specifically investigate the code resembling:

```text
staleItems.map { ... /api/p/{retailerId} ... }
```

Do not leave a hidden equivalent implementation elsewhere.

Search the entire V2 source tree for:

```text
/api/p/
markOutOfStock
staleItems
retailerId
```

before declaring the storm removed.

---

# 11. Do Not Delete Legitimate Enrichment

The PDP mechanism may have existed for legitimate purposes such as:

* missing weight
* missing purity
* missing availability
* missing price
* product enrichment

Do not simply delete that functionality without understanding it.

Instead:

```text
catalog discovery
        ↓
identify products missing required information
        ↓
optional bounded enrichment
```

Any enrichment must be:

* bounded
* cancellable
* observable
* retry-limited
* source-aware
* non-destructive

It must never become a 1,000+ request storm.

---

# 12. AJIO Dynamic Pagination

Start from the configured first page.

For each response:

1. Validate HTTP response.
2. Parse response.
3. Extract products.
4. Extract pagination metadata.
5. Persist valid products.
6. Determine whether another page exists.
7. Continue only when the source indicates another page.

Conceptually:

```text
page = 0

while source indicates next page:

    request page

    validate response

    parse products

    normalize

    validate

    deduplicate

    persist

    determine next page

    page++
```

Do not permanently define catalogue size as:

```text
8 pages
10 pages
20 pages
25 pages
```

A configurable safety ceiling may exist to prevent infinite loops, but it must not replace actual pagination metadata.

---

# 13. Pagination Metadata Must Be Defensive

Do not assume every response contains:

```text
currentPage
totalPages
```

The parser must inspect the actual response schema.

Support whatever valid mechanism the source currently provides, such as:

```text
currentPage
totalPages
totalCount
hasNext
next URL
cursor
page-size metadata
```

If pagination metadata is missing or contradictory, generate a parser diagnostic.

Do not blindly continue until the safety ceiling.

---

# 14. Request Pacing

Do not introduce a fixed 350–500 ms delay simply as an anti-blocking mechanism.

First use:

* bounded concurrency
* sequential pagination where appropriate
* connection reuse
* retry limits
* exponential backoff
* jitter
* source-specific request limits

If empirical evidence demonstrates that pacing is required for reliability, implement a documented source-specific pacing policy.

Do not use arbitrary delays to bypass or evade rate limits.

---

# 15. AJIO Failure Handling

If an individual request returns:

```text
403
```

classify it as:

```text
HTTP_403 / BLOCKED
```

and stop inappropriate retries.

However, **do not automatically classify the entire source refresh as a total failure solely because one page fails**.

Distinguish:

```text
SUCCESS
PARTIAL_SUCCESS
BLOCKED
HTTP_403
RATE_LIMITED
PARSER_ERROR
INVALID_RESPONSE
OFFLINE
FAILED
```

Example:

```text
Page 0 = 200
Page 1 = 200
Page 2 = 403

Result:
PARTIAL_SUCCESS / BLOCKED_PAGE
```

The exact state model should reflect the existing Aurum refresh architecture.

---

# 16. Zero Destructive Operations on Failure

This is mandatory.

The following must never automatically cause products to be marked out of stock or deleted:

```text
403
401
404 during incomplete discovery
timeout
connection failure
DNS failure
parser failure
schema drift
partial refresh
unexpected empty response
```

Existing last-known-good data must remain intact.

---

# 17. Empty Response Protection

This is particularly important.

If:

```text
HTTP 200
products = 0
```

do not immediately interpret this as:

```text
catalogue is empty
```

Determine whether the response may instead indicate:

* wrong filter
* incorrect endpoint
* parser failure
* schema change
* incomplete response
* unexpected source behavior

Only a **validated complete refresh** may trigger source reconciliation.

---

# 18. Database Reconciliation

Separate:

### Discovery

Products actually returned by the source.

from:

### Reconciliation

Products that should be considered unavailable.

Only permit destructive reconciliation after a valid, complete source refresh.

For example:

```text
403
→ preserve database

parser error
→ preserve database

partial refresh
→ preserve database

schema drift
→ preserve database

valid complete refresh
→ reconciliation permitted
```

---

# 19. Database Transaction Safety

AJIO persistence should be transactional at the appropriate source/page/batch level.

A failed page must not partially corrupt the existing catalogue.

Where practical:

```text
parse
→ validate
→ normalize
→ deduplicate
→ transaction
→ upsert
```

Record:

```text
inserted
updated
unchanged
duplicates
rejected
```

---

# 20. Phase 2 Device Verification

Before refresh:

```text
store
COUNT(*)
```

must be captured.

Example:

```powershell
& "C:\Users\Agraw\AppData\Local\Android\Sdk\platform-tools\adb.exe" `
    -s 192.168.88.3:37997 `
    shell "run-as com.aurum.intelligence sqlite3 /data/user/0/com.aurum.intelligence/databases/aurum.db 'SELECT store, COUNT(*) FROM products GROUP BY store;'"
```

Then trigger the actual Aurum V2 refresh.

---

# 21. Required AJIO Runtime Logs

The device log must demonstrate actual work.

Example:

```text
[AJIO] DISCOVERING page=0
[AJIO] HTTP status=200
[AJIO] discovered=45

[AJIO] DISCOVERING page=1
[AJIO] HTTP status=200
[AJIO] discovered=45

[AJIO] PARSING
[AJIO] accepted=...
[AJIO] rejected=...
[AJIO] duplicates=...

[AJIO] PERSISTING
[AJIO] inserted=...
[AJIO] updated=...
[AJIO] unchanged=...

[AJIO] SUCCESS
[AJIO] totalDiscovered=...
[AJIO] totalAccepted=...
```

If blocked:

```text
[AJIO] HTTP status=403
[AJIO] state=BLOCKED
[AJIO] destructiveReconciliation=false
```

The exact logging format can follow the existing `activityRepository` architecture, but the information must actually be present.

---

# 22. Verify PDP Storm Is Gone

After refresh, inspect logs/network diagnostics for:

```text
/api/p/{retailerId}
```

There must be no uncontrolled thousands-request fallback.

If enrichment is still legitimately implemented, provide:

```text
enrichment requests
concurrency
accepted
failed
403
429
```

and demonstrate that it is bounded.

---

# 23. Verify Database Integrity

Capture database counts before and after.

The result should demonstrate:

```text
before count
discovered
accepted
inserted
updated
unchanged
after count
```

If AJIO receives a 403 during refresh:

```text
before count
=
healthy data retained
```

There must be no mass `markOutOfStock()` operation caused solely by the failed refresh.

---

# 24. Verify UI

After successful ingestion, verify that the existing Aurum UI displays:

* real AJIO products
* correct product count
* correct store
* real product URLs
* real weight
* real purity
* real price
* correct availability where provided

Do not create fake test products to demonstrate UI functionality.

---

# PHASE 3 — OTHER SOURCES

Only begin Phase 3 after AJIO demonstrates:

```text
HTTP
→ parser
→ validation
→ DB
→ UI
```

end-to-end.

Then proceed one source at a time.

---

# 25. Myntra

Investigate the Gateway API response and determine its actual current schema.

Starting observed endpoint:

```text
https://www.myntra.com/gateway/v4/search/gold-coin
```

Investigate:

```text
rows
offset
page number
totalCount
next-page behavior
product structure
variant structure
price
weight
purity
availability
```

Create real response fixtures.

Do not hardcode an assumed schema.

---

# 26. Flipkart

Investigate actual PLP pagination metadata and product state.

Do not permanently cap the catalogue at eight pages.

Extract structured embedded state where legitimately present.

Do not execute embedded JavaScript.

---

# 27. Shopsy

Use the same evidence-driven approach.

Investigate:

* pagination
* embedded product state
* product IDs
* variants
* price
* weight
* purity
* availability

Create a dedicated parser.

---

# 28. Amazon

Inspect actual response pagination.

Do not assume eight or ten pages is the complete catalogue.

Extract:

```text
ASIN
title
URL
weight
purity
price
availability
variant
```

using native HTTP parsing.

If Amazon blocks the request, classify and preserve last-known-good data.

Do not attempt to bypass the block.

---

# 29. Architecture Requirement

Do not create separate ad-hoc refresh implementations for each source.

Use the common architecture:

```text
Existing UI
    ↓
Repository
    ↓
RefreshCoordinator
    ↓
SourceAdapter
    ↓
NativeHttpClient
    ↓
Source Parser
    ↓
Normalizer
    ↓
Validator
    ↓
Canonical Aurum Product
    ↓
Database
    ↓
Existing UI
```

Each source gets its own adapter/parser.

Avoid giant:

```text
if (source == ...)
```

blocks.

---

# 30. Final Acceptance Criteria

The implementation is **not complete** until actual device evidence demonstrates:

```text
HTTP requests > 0
responses > 0
products discovered > 0
products accepted > 0
database inserts/updates > 0
UI count changes
real products visible
```

For AJIO specifically, provide:

```text
pages requested
pages succeeded
pages failed
total discovered
accepted
rejected
duplicates
inserted
updated
unchanged
403 count
429 count
duration
```

---

# 31. Build/Test Requirements

After each phase, actually execute:

```powershell
.\gradlew clean
.\gradlew assembleDebug
.\gradlew test
.\gradlew lint
```

and applicable instrumentation/device tests.

Install and test the APK on the Pixel 9.

Do not claim a command passed unless it was actually executed successfully.

Do not proceed to the next phase while the current phase has unresolved build or runtime failures.

---

# 32. Final RCA Report

At completion, provide a factual report with:

## Root Cause

Separate:

```text
PROVEN
LIKELY
UNPROVEN
```

## AJIO

```text
working endpoint
failed endpoint
request profile
HTTP status
protocol
response size
pagination
products discovered
products accepted
DB changes
403 behavior
```

## Database

```text
before
inserted
updated
unchanged
after
destructive operations
```

## Performance

Provide actual measured:

```text
request duration
parse duration
validation duration
DB duration
total refresh duration
```

## Tests

Provide actual command results.

---

# FINAL NON-NEGOTIABLE RULE

Do not optimize for logs that merely say:

```text
SUCCESS
```

Optimize for verified data flow:

```text
REAL SOURCE RESPONSE
        ↓
REAL PRODUCTS DISCOVERED
        ↓
REAL PARSING
        ↓
REAL VALIDATION
        ↓
REAL DATABASE INSERT/UPDATE
        ↓
REAL PRODUCTS DISPLAYED IN EXISTING UI
```

If any stage produces zero, suspicious, or contradictory results:

**STOP. DIAGNOSE THAT STAGE. FIX IT. THEN CONTINUE.**

No fake products.

No placeholder data.

No mocked success.

No fabricated database updates.

No WAF bypass.

No browser automation.

No destructive database changes caused by failed network requests.
