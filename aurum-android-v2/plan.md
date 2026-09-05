# AURUM ANDROID V2

## STRICT GROUND-UP REBUILD

### EXISTING UI + EXISTING DATABASE + EXISTING PRODUCTS + ALL EXISTING FUNCTIONALITY

### ONLY THE UNDERLYING ACQUISITION/REFRESH ENGINE IS BEING REPLACED

---

# 0. THIS DOCUMENT IS AN IMPLEMENTATION CONTRACT

You are Gemini working inside Android Studio.

You are **not** being asked to design a new Aurum application.

You are **not** being asked to create a simplified demonstration app.

You are **not** being asked to redesign the UI.

You are **not** being asked to create a new simplified product database.

You are **not** being asked to implement only a few example stores.

You are **not** being asked to create a parser prototype.

You are being asked to create:

```text
aurum-android-v2/
```

as a **complete, independently buildable replacement implementation of Aurum**, while keeping the existing Aurum implementation completely untouched.

The existing Aurum implementation is the **source of truth for application behaviour, UI, database structure, products, fields, navigation, and features**.

The new implementation must reproduce that behaviour and then replace the underlying acquisition mechanism with a new native process.

---

# 1. THE MOST IMPORTANT RULE

## DO NOT INTERPRET "REBUILD" AS "REDESIGN"

The following are prohibited unless they are required for technical compatibility and do not alter behaviour:

* new UI design
* new screen layouts
* new navigation
* new product cards
* new colour scheme
* new typography
* new spacing
* new icons
* new filters
* removing existing buttons
* removing existing fields
* simplifying screens
* changing product presentation
* replacing existing database entities with simplified entities
* inventing new product models
* removing existing stores
* implementing only AJIO
* implementing only bullion
* creating placeholder products
* replacing the product database with an empty database
* replacing the existing product catalogue with test data
* removing existing functionality because it is unrelated to scraping

The goal is:

```text
OLD AURUM
    │
    │ inspect / understand / reproduce
    ▼
AURUM V2
    │
    ├── SAME UI
    ├── SAME NAVIGATION
    ├── SAME PRODUCT MODEL
    ├── SAME DATABASE SEMANTICS
    ├── SAME FEATURES
    ├── SAME PRODUCT INFORMATION
    │
    └── NEW NATIVE ACQUISITION ENGINE
```

---

# 2. ABSOLUTE PROJECT ISOLATION

Create:

```text
aurum-android-v2/
```

Do not modify the existing project.

The existing project must remain:

* buildable
* runnable
* untouched
* available for comparison

Do not:

* rename the old project
* move the old project
* delete old files
* modify old source files
* modify old Gradle files
* modify old resources
* modify old database files
* change old parsers
* change old scraping logic
* change old manifests

The only allowed operation on the old project is **READ-ONLY inspection**.

If necessary, make a baseline/tag/checkpoint before starting.

---

# 3. EXPLICIT OWNERSHIP BOUNDARY

Before writing application code, establish:

```text
OLD PROJECT
READ ONLY
│
├── inspect
├── inventory
├── compare
└── reference

NEW PROJECT
WRITE
│
└── aurum-android-v2/
```

Every new source file must belong to V2.

Every V2 package must belong to V2.

Do not import legacy implementation classes simply to make V2 work.

Do not create runtime dependencies from V2 → old application code.

Do not make V2 depend on an old local service.

Do not make V2 call the old scraper.

Do not make V2 call a legacy browser process.

The V2 application must ultimately operate independently.

---

# 4. FIRST PHASE: COMPLETE LEGACY AUDIT

## DO NOT START REBUILDING IMMEDIATELY.

Before implementing anything, inspect the entire existing Aurum project.

Create an explicit inventory.

Do not rely on memory.

Do not infer.

Do not assume.

Do not skip files because they appear unrelated.

---

# 5. CREATE A LEGACY FEATURE INVENTORY

Create a document in V2:

```text
docs/LEGACY_FEATURE_INVENTORY.md
```

Record every existing feature.

At minimum inspect:

* Main screen
* Product listing
* Product detail
* Filters
* Sorting
* Search
* Store/source selection
* Gold purity selection
* Weight filtering
* Price filtering
* Product availability
* Product images
* Product links
* Refresh
* Manual refresh
* Automatic refresh
* Background refresh
* Logs/debug section
* Settings
* Notifications
* Error messages
* Loading states
* Empty states
* Offline behaviour
* Database behaviour
* Bullion rates
* Retail products
* Product statistics
* Any portfolio-related functionality
* Any existing calculations
* Any other screen or feature discovered

Do not stop at these examples.

**Inventory whatever actually exists in the project.**

---

# 6. CREATE A SCREEN-BY-SCREEN UI PARITY INVENTORY

Create:

```text
docs/UI_PARITY_MATRIX.md
```

For every existing screen record:

* screen name
* source file
* layout/composable file
* navigation route
* toolbar
* title
* buttons
* icons
* text
* cards
* lists
* filters
* sorting controls
* dialogs
* bottom sheets
* loading indicators
* error states
* empty states
* colours
* dimensions
* typography
* product fields displayed
* click actions
* navigation actions
* refresh actions

The existing UI is the target.

---

# 7. UI PARITY RULE

The V2 UI should initially be treated as a **port/reproduction**, not a redesign.

The following should visually and behaviourally remain equivalent:

```text
Screen
↓
Layout
↓
Navigation
↓
Controls
↓
Product cards
↓
Data displayed
↓
Interactions
↓
Loading/error states
```

Do not "improve" the UI.

Do not modernize the UI.

Do not make your own interpretation of the UI.

If the old UI looks unusual, preserve it.

If the old UI has a particular field placement, preserve it.

If the old UI has an existing control, preserve it.

If the old UI has an existing screen that appears unnecessary, preserve it.

---

# 8. SPECIAL RULE FOR THE EXISTING BROWSER SECTION

The existing browser section must remain as a **section of the UI**.

However:

## THERE MUST BE NO BROWSER IN V2.

Do not use:

* WebView
* WebViewClient
* WebChromeClient
* browser tabs
* embedded browser
* Playwright
* Puppeteer
* Selenium
* JavaScript browser execution
* browser automation
* hidden browser
* browser cookies
* browser cookie extraction

Instead, preserve the section's location/navigation purpose but replace its contents with:

# Native Refresh Logs

It should display actual native engine activity.

For example:

```text
AJIO
DISCOVERING
HTTP 200
847 ms
207 KB

PARSING
Products discovered: 42
Accepted: 38
Rejected: 4

DATABASE
Inserted: 35
Updated: 3

SUCCESS
```

This is a **logging replacement**, not a UI redesign.

---

# 9. DATABASE IS NOT OPTIONAL

This is one of the most important requirements.

The original Aurum database must be inspected completely.

Create:

```text
docs/DATABASE_PARITY.md
```

Inventory:

* database name
* database version
* entities
* tables
* columns
* primary keys
* foreign keys
* indexes
* relations
* unique constraints
* nullable fields
* defaults
* DAOs
* queries
* sorting
* filtering
* migrations
* converters
* embedded objects
* source/store identifiers
* product identifiers
* variant identifiers
* timestamps
* refresh metadata
* bullion data
* price data
* product data
* image data
* availability data
* any other persisted data

---

# 10. "PROPER ORIGINAL DATABASE" MEANS THIS

Do NOT create something like:

```text
Product
-------
id
name
price
weight
url
```

and declare the database complete.

That is specifically prohibited.

The V2 database must reproduce the **logical data contract of the existing application**.

If the old application contains:

```text
Product
ProductVariant
Store
Source
Price
BullionRate
Refresh
Image
Offer
Availability
```

or equivalent structures, those must be understood and recreated as appropriate.

Do not remove fields merely because the new scraper does not currently populate them.

---

# 11. DATABASE PARITY TEST

After recreating the database, produce:

```text
docs/DATABASE_PARITY.md
```

with a table:

| Legacy   | V2       | Status |
| -------- | -------- | ------ |
| Entity   | Entity   | MATCH  |
| Field    | Field    | MATCH  |
| Type     | Type     | MATCH  |
| Relation | Relation | MATCH  |
| Query    | Query    | MATCH  |
| Index    | Index    | MATCH  |

Every meaningful legacy entity must have a disposition:

```text
MATCHED
OR
INTENTIONALLY REPLACED WITH DOCUMENTED REASON
```

Nothing can simply disappear.

---

# 12. EXISTING PRODUCT DATA MUST NOT DISAPPEAR

This is another hard requirement.

V2 must not launch with:

```text
0 products
```

and then claim the implementation is complete.

The application must have a proper mechanism for:

* existing product data
* product persistence
* refresh
* insertion
* update
* deduplication
* reconciliation
* last-known-good data

If the legacy project contains a database/data asset that must be reproduced, inspect and migrate/recreate it appropriately.

Do not replace it with dummy data.

Do not replace it with three test products.

Do not replace it with an empty Room database.

---

# 13. PRODUCT MODEL MUST BE RECONSTRUCTED FROM THE OLD APPLICATION

Inspect every place where product data is:

* created
* parsed
* transformed
* stored
* queried
* sorted
* filtered
* displayed
* calculated
* refreshed

Create:

```text
docs/PRODUCT_DATA_CONTRACT.md
```

Document every field.

Potential fields include:

* product ID
* variant ID
* source
* store
* brand
* product name
* title
* description
* URL
* image URL
* weight
* weight unit
* purity
* fineness
* karat
* metal
* price
* MRP
* sale price
* discount
* coupon
* offer
* stock
* availability
* seller
* rating
* review count
* pincode context
* timestamps
* source metadata
* refresh metadata

These are examples.

The actual legacy model determines the final contract.

---

# 14. DO NOT INVENT A NEW PRODUCT MODEL

The new scraper should produce a source-specific intermediate model if necessary.

But it must ultimately become:

```text
Source response
    ↓
Source parser
    ↓
Normalized product
    ↓
Canonical Aurum product
    ↓
Existing V2 database contract
    ↓
Existing V2 UI
```

Do not let the scraper define the application's data model.

The application's canonical model defines what the scraper must provide.

---

# 15. PRODUCT DISCOVERY IS A RELEASE REQUIREMENT

A source parser that compiles but discovers zero products is **not complete**.

For every source:

```text
REQUEST
↓
HTTP RESPONSE
↓
RESPONSE VALIDATION
↓
PARSER
↓
PRODUCT DISCOVERY
↓
NORMALIZATION
↓
VALIDATION
↓
DATABASE
↓
UI
```

must be demonstrated.

For every source, record:

```text
URL
HTTP status
response size
products discovered
products accepted
products rejected
duplicates
database inserts
database updates
database total
```

---

# 16. SOURCE INVENTORY

Inspect the old project and identify **every existing source**.

Do not assume the known list is complete.

At minimum investigate:

## RETAIL

* AJIO
* Flipkart
* Shopsy
* Amazon
* Myntra

## BULLION

* Malabar Gold & Diamonds
* MMTC-PAMP
* Kalyan Jewellers
* Tanishq

But:

# THE OLD PROJECT MAY CONTAIN ADDITIONAL SOURCES.

Any additional source found must be added to the V2 source inventory.

Create:

```text
docs/SOURCE_INVENTORY.md
```

---

# 17. EVERY URL MUST BE INVENTORIED

For every source document:

* base URL
* category URL
* product URL pattern
* API endpoint
* GraphQL endpoint
* pagination endpoint
* rate endpoint
* other relevant endpoints
* request method
* required parameters
* response type
* pagination mechanism
* source-specific headers if legitimately required

Do not silently omit URLs.

Do not replace all old URLs with one generic search URL.

Do not hard-code URLs without first understanding their purpose.

---

# 18. IMPORTANT: OBSERVED ENDPOINTS ARE STARTING EVIDENCE ONLY

Previously observed endpoints may be used as investigation starting points.

Examples include:

```text
AJIO:
https://www.ajio.com/api/category/8303

Flipkart:
https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART...

Shopsy:
https://www.shopsy.in/gold-silver-coins/pr?sid=mcr,73x...

Amazon:
https://www.amazon.in/s?i=jewelry...

Myntra:
https://www.myntra.com/gold-coin?p={page}&rows=50

Malabar:
https://www.malabargoldanddiamonds.com/graphql-magento

MMTC-PAMP:
https://www.mmtcpamp.com/gold-silver-rate-today

Kalyan:
https://store.kalyanjewellers.net/gold-rate/india/en

Tanishq:
https://www.tanishq.co.in/gold-rate.html
```

These are **not permanent guarantees**.

Before implementing a parser:

1. request the actual endpoint
2. inspect the actual response
3. record response schema
4. create a fixture
5. write parser tests
6. verify actual products are extracted

Never invent a response schema.

---

# 19. NO BROWSER FALLBACK

If native HTTP cannot access a source:

Do NOT reintroduce a browser.

Do NOT use:

```text
WebView
Playwright
Puppeteer
Selenium
browser cookies
JS execution
hidden browser
```

Instead classify the source:

```text
AVAILABLE
PARTIALLY AVAILABLE
BLOCKED
RATE LIMITED
AUTH REQUIRED
UNSUPPORTED
SCHEMA CHANGED
```

and preserve last-known-good data.

---

# 20. NATIVE NETWORK ARCHITECTURE

Create a native network abstraction.

Example:

```text
NativeHttpClient
├── CronetHttpClient
└── OkHttpHttpClient
```

Use one shared app-level networking engine where appropriate.

Requirements:

* connection reuse
* HTTP/2 where supported
* cancellation
* timeouts
* bounded response size
* async operation
* proper error classification
* request IDs
* request duration
* protocol logging
* retry policy

---

# 21. NO HARD-CODED DELAYS

Absolutely prohibited:

```kotlin
delay(2000)
Thread.sleep(2000)
delay(5000)
```

Do not use arbitrary waits to compensate for slow networks.

Network completion must be determined by actual state:

```text
request started
↓
response received
↓
response body complete
↓
parse complete
```

A slow network must wait for the response.

A fast network must proceed immediately.

---

# 22. RETRY POLICY

Retry only genuinely retryable failures.

Examples:

* connection reset
* timeout
* 408
* 429
* selected 5xx

Do not blindly retry:

* 400
* 401
* 403
* 404
* malformed response
* parser error

Use:

```text
exponential backoff
+
jitter
+
maximum attempts
```

No infinite retry loops.

---

# 23. SOURCE ADAPTER ARCHITECTURE

Use independent source adapters.

Example:

```text
SourceAdapter
│
├── RetailSourceAdapter
│   ├── AjioSourceAdapter
│   ├── FlipkartSourceAdapter
│   ├── ShopsySourceAdapter
│   ├── AmazonSourceAdapter
│   └── MyntraSourceAdapter
│
└── BullionSourceAdapter
    ├── MalabarBullionAdapter
    ├── MmtcPampBullionAdapter
    ├── KalyanBullionAdapter
    └── TanishqBullionAdapter
```

Do not create:

```text
HugeScraper.kt
```

with hundreds of:

```kotlin
if (source == ...)
```

branches.

Each source owns:

* URL construction
* request profile
* response parsing
* pagination
* source-specific normalization
* source-specific diagnostics

---

# 24. REFRESH COORDINATOR

Create a central coordinator.

Example:

```text
RefreshCoordinator
```

Responsibilities:

1. determine sources
2. check connectivity
3. schedule source work
4. execute requests
5. parse responses
6. normalize products
7. validate products
8. deduplicate
9. persist atomically
10. emit logs
11. report final result

The same coordinator must be used by:

```text
Manual refresh
Background refresh
WorkManager
```

Do not create separate scraping implementations.

---

# 25. SOURCE FAILURE ISOLATION

If AJIO fails:

```text
AJIO = FAILED
```

that must not cancel:

```text
Flipkart
Shopsy
Amazon
Myntra
Malabar
MMTC-PAMP
Kalyan
Tanishq
```

Each source has an independent result.

Overall refresh can be:

```text
SUCCESS
PARTIAL_SUCCESS
FAILED
```

---

# 26. REFRESH STATE MACHINE

Implement explicit states such as:

```text
IDLE

DISCOVERING

FETCHING

PARSING

VALIDATING

PERSISTING

SUCCESS

PARTIAL_SUCCESS

RETRYING

RATE_LIMITED

BLOCKED

OFFLINE

INVALID_RESPONSE

PARSER_ERROR

FAILED

CANCELLED
```

The logs UI must reflect these real states.

---

# 27. PAGINATION MUST BE DYNAMIC

Never write:

```text
for page in 1..10
```

unless 10 is genuinely determined by the source and validated.

Discover pagination using:

* next URL
* cursor
* total pages
* hasNext
* product count
* source-specific termination

Stop when:

```text
source says finished
OR
no new products are returned
```

A safety cap may exist as a protection against infinite pagination, but it must not be treated as the expected number of pages.

---

# 28. DEDUPLICATION

The same product may appear:

* across pages
* across sort orders
* through multiple endpoints
* through multiple variants

Use stable identity.

Preferred:

```text
source + product ID + variant ID
```

Do not use product title as the primary identity.

---

# 29. WEIGHT ENGINE — CRITICAL

Create a standalone tested weight library.

Preferred internal representation:

```text
Long milligrams
```

not floating-point grams.

Support:

```text
50 mg       → 50 mg
500 mg      → 500 mg
0.5 g       → 500 mg
1 g         → 1000 mg
2 x 1 g     → 2000 mg
3 x 10 g    → 30000 mg
0.5g + 1g + 2g → 3500 mg
```

Support legitimate variations:

```text
mg
g
gm
gram
grams
kg
```

Handle:

* spaces
* Unicode
* capitalization
* decimal values
* multipacks
* expressions
* source-specific weight fields

---

# 30. NEVER "FIX" WEIGHT BASED ON PRICE

This is explicitly prohibited.

For example:

```text
Vendor says 500g
Price looks like a 500mg product
```

Do NOT silently convert:

```text
500g → 500mg
```

Instead:

```text
WEIGHT_CONFLICT
```

or:

```text
AMBIGUOUS_WEIGHT
```

and quarantine/reject according to validation policy.

Price is not evidence sufficient to overwrite vendor-declared weight.

---

# 31. INSPECT ALL LEGACY WEIGHT LOGIC

Before implementing the V2 weight engine, locate all existing references to concepts such as:

```text
WeightExtractor
DatabaseSanitizerEngine
ProductDetailScraper
StoreAdapter
weight
grams
mg
price-per-gram
sorting
filtering
```

Document where each was used.

Then reproduce the required application behaviour with the new tested engine.

---

# 32. PURITY

Preserve explicit source purity.

Examples:

```text
999.9
999
995
→ 24K classification where appropriate

916
→ 22K

750
→ 18K

585
→ 14K
```

Do not throw away valid non-22K/24K gold.

Store the original fineness where available.

Detect contradictions.

---

# 33. METAL CLASSIFICATION

At minimum distinguish:

```text
SOLID_GOLD
GOLD_PLATED
GOLD_TONE
SILVER
PLATINUM
OTHER
UNKNOWN
```

Gold-plated and gold-tone products must not silently enter the solid-gold dataset.

Use positive and negative evidence.

---

# 34. PRICE ENGINE

Use exact paise where practical:

```text
Long
```

Separate:

* MRP
* listed price
* sale price
* coupon
* effective price
* discount
* offer text
* GST semantics
* making charges
* other charges

Do not invent an effective price when coupon conditions are unknown.

Do not confuse:

```text
gold metal rate
```

with:

```text
final jewellery price
```

---

# 35. AVAILABILITY

Represent availability explicitly.

For example:

```text
IN_STOCK
OUT_OF_STOCK
LOW_STOCK
UNKNOWN
```

Also retain:

```text
isBuyable
```

when available.

Do not claim pincode-specific availability unless it was actually tested for that pincode.

---

# 36. PRODUCT VALIDATION

A product must pass validation before persistence.

Possible rejection reasons:

```text
MISSING_PRODUCT_ID
INVALID_URL
EMPTY_TITLE
NON_GOLD
GOLD_PLATED
INVALID_WEIGHT
AMBIGUOUS_WEIGHT
WEIGHT_CONFLICT
INVALID_PURITY
PURITY_CONFLICT
INVALID_PRICE
MISSING_REQUIRED_FIELD
MALFORMED_RESPONSE
UNSUPPORTED_VARIANT
DUPLICATE_PRODUCT
```

Do not silently discard rejected products.

Record the reason.

---

# 37. PARSER CONTRACT

Every parser must follow:

```text
Raw Response
      ↓
Parser
      ↓
ParseResult
```

`ParseResult` should contain:

```text
accepted products
rejected products
warnings
diagnostics
parser version
source metadata
```

Every parser must be testable without the network.

---

# 38. FIXTURES ARE REQUIRED

For every source:

```text
src/test/resources/fixtures/
```

Store representative real responses.

Create:

```text
response
↓
parser
↓
expected products
```

tests.

Include:

* normal response
* empty response
* malformed response
* changed schema
* missing field
* invalid product
* pagination
* duplicate
* weight conflict
* purity conflict
* price anomaly

---

# 39. REAL PRODUCT DISCOVERY TEST

For each source, perform a real request.

Record:

```text
Source
Endpoint
HTTP status
Response size
Products discovered
Products accepted
Products rejected
Duplicates
Pagination pages
```

A source cannot be marked complete simply because:

```text
HTTP 200
```

was received.

The following is insufficient:

```text
AJIO HTTP 200
```

The acceptance requirement is:

```text
AJIO HTTP 200
+
products actually discovered
+
products parsed
+
products validated
+
products persisted
+
products visible in UI
```

---

# 40. BULLION SOURCES

Implement and test each separately:

```text
Malabar
MMTC-PAMP
Kalyan
Tanishq
```

For each:

```text
request
↓
rate extraction
↓
purity/metal validation
↓
normalization
↓
database
↓
UI
```

Do not create one generic bullion parser if source schemas differ.

---

# 41. DATABASE WRITE SAFETY

Never perform destructive writes merely because one request failed.

If a source returns:

```text
HTTP 500
```

retain last-known-good products.

If parser returns:

```text
0 products
```

do not automatically delete the entire source.

Determine whether:

```text
legitimate empty result
```

or:

```text
parser/schema/filter failure
```

has occurred.

---

# 42. ATOMIC SOURCE REFRESH

A source refresh should behave transactionally.

Prefer:

```text
fetch
↓
parse
↓
validate
↓
deduplicate
↓
complete result
↓
transaction
↓
commit
```

rather than deleting all old products first.

If the transaction fails:

```text
rollback
retain previous data
```

---

# 43. LAST-KNOWN-GOOD DATA

Every source should preserve valid existing data when a refresh fails.

Example:

```text
Existing AJIO data
        ↓
AJIO refresh fails
        ↓
KEEP existing AJIO data
        +
show failure in logs
```

Do not replace valid data with:

```text
empty list
```

because of a temporary network/parser failure.

---

# 44. LOGGING

Create structured native logs.

Every refresh event should include as appropriate:

```text
timestamp
source
request ID
sanitized URL
HTTP status
transport
protocol
request duration
response size
parse duration
validation duration
database duration
discovered count
accepted count
rejected count
duplicate count
retry count
failure reason
parser version
```

Never log:

* cookies
* authentication tokens
* secrets
* sensitive headers
* unnecessary personal data

---

# 45. LOGGING UI

The old browser section becomes a real-time native log viewer.

Example:

```text
REFRESH STARTED

AJIO
FETCHING
HTTP 200
847 ms
207 KB

PARSING
42 discovered
38 accepted
4 rejected

DATABASE
35 inserted
3 updated

FLIPKART
FETCHING
HTTP 200
...

FINAL RESULT

Sources: 9
Successful: 8
Failed: 1

Products discovered: 1,284
Accepted: 1,126
Rejected: 158
Updated: 742
Inserted: 384
```

These numbers must come from actual execution.

Never hard-code demo numbers.

---

# 46. NO FAKE SUCCESS

Absolutely prohibited:

```text
productsFound = 100
```

or:

```text
refreshSuccess = true
```

just to make the UI look complete.

Every status must originate from the real pipeline.

---

# 47. NO PLACEHOLDER IMPLEMENTATIONS

Do not leave production code containing:

```text
TODO
FIXME
NotImplementedError
return emptyList()
return null
fake data
mock products
dummy repository
placeholder parser
```

unless the code is explicitly test-only.

---

# 48. NO "IMPLEMENT LATER"

Do not mark the project complete while saying:

```text
AJIO implemented, other stores can be added later.
```

or:

```text
database simplified for now.
```

or:

```text
UI recreated approximately.
```

or:

```text
products can be populated later.
```

Those are incomplete states.

---

# 49. EXECUTION ORDER

Follow this exact sequence.

## PHASE 1 — LEGACY AUDIT

Do not write the final implementation yet.

Produce:

```text
LEGACY_FEATURE_INVENTORY.md
UI_PARITY_MATRIX.md
DATABASE_PARITY.md
PRODUCT_DATA_CONTRACT.md
SOURCE_INVENTORY.md
```

Also inventory:

```text
all existing URLs
all stores
all parsers
all product fields
all DB fields
all screens
all navigation
all refresh paths
all background jobs
```

### Gate

Do not continue until the legacy application has been completely inventoried.

---

# 50. PHASE 2 — CREATE V2 PROJECT

Create:

```text
aurum-android-v2/
```

with its own:

```text
settings.gradle
build.gradle
gradle.properties
app/
```

and all necessary configuration.

Do not blindly copy legacy Gradle configuration.

Determine compatible:

* Android Gradle Plugin
* Gradle
* Kotlin
* JVM target
* compile SDK
* target SDK
* dependencies

### Gate

Run:

```bash
./gradlew clean
./gradlew assembleDebug
```

Fix all errors before continuing.

---

# 51. PHASE 3 — RECREATE DATABASE

Implement the complete V2 database contract.

Do not start with the scraper.

First make sure:

```text
database
↓
repository
↓
UI
```

works.

Seed/reproduce legitimate existing data where applicable.

### Gate

Verify:

* entities
* fields
* queries
* relationships
* product retrieval
* filtering
* sorting
* persistence

---

# 52. PHASE 4 — RECREATE EXISTING UI

Port/recreate the existing UI exactly.

Do not redesign.

Do not improve.

Do not simplify.

Do not change navigation.

The UI must be able to display the canonical product model from the V2 database.

### Gate

Perform screen-by-screen comparison against the old app.

---

# 53. PHASE 5 — NATIVE NETWORK ENGINE

Implement:

```text
NativeHttpClient
Cronet
OkHttp fallback where appropriate
request profiles
timeouts
cancellation
retry policy
network classification
```

### Gate

Perform real requests against each known source endpoint.

---

# 54. PHASE 6 — CORE DATA ENGINES

Implement and test:

```text
WeightEngine
PurityEngine
MetalClassificationEngine
PriceEngine
AvailabilityEngine
ProductIdentityEngine
ValidationEngine
NormalizationEngine
```

### Gate

All unit tests pass.

---

# 55. PHASE 7 — SOURCE ADAPTERS

Implement every discovered source.

Minimum expected:

```text
AJIO
Flipkart
Shopsy
Amazon
Myntra
Malabar
MMTC-PAMP
Kalyan
Tanishq
```

Plus any additional source found in the legacy project.

### Gate

Every source must demonstrate real product/rate discovery.

---

# 56. PHASE 8 — REFRESH COORDINATOR

Connect:

```text
UI
↓
Repository
↓
RefreshCoordinator
↓
Source adapters
↓
Parsers
↓
Validation
↓
Database
↓
UI
```

### Gate

One complete refresh must work end-to-end.

---

# 57. PHASE 9 — LOG SECTION

Replace the browser content with native refresh logs.

Do not alter the surrounding application structure unnecessarily.

### Gate

Logs must show actual refresh events.

---

# 58. PHASE 10 — BACKGROUND REFRESH

Use WorkManager.

WorkManager must call the same:

```text
RefreshCoordinator
```

used by manual refresh.

Do not implement a second scraper.

### Gate

Test:

* foreground refresh
* background refresh
* cancellation
* app restart
* network unavailable
* network restored

---

# 59. PHASE 11 — FAILURE TESTING

Explicitly test:

```text
offline
DNS failure
timeout
connection reset
408
429
500
502
503
403
404
malformed HTML
malformed JSON
schema change
empty response
parser failure
DB failure
cancellation
```

Verify that healthy existing data remains available.

---

# 60. PHASE 12 — PRODUCT PARITY TEST

Take representative products from the legacy implementation and compare V2.

For each product compare:

```text
source
ID
variant
title
brand
URL
image
weight
purity
fineness
karat
metal
price
MRP
sale price
availability
offers
timestamps
```

Any difference must be explained.

Do not simply say:

```text
looks similar
```

---

# 61. PHASE 13 — SOURCE ACCEPTANCE MATRIX

Create:

```text
docs/SOURCE_ACCEPTANCE_MATRIX.md
```

Example:

| Source    | Request | Response | Pagination | Parser | Products | Validation | DB   | UI   | E2E  |
| --------- | ------- | -------- | ---------- | ------ | -------- | ---------- | ---- | ---- | ---- |
| AJIO      | PASS    | PASS     | PASS       | PASS   | PASS     | PASS       | PASS | PASS | PASS |
| Flipkart  |         |          |            |        |          |            |      |      |      |
| Shopsy    |         |          |            |        |          |            |      |      |      |
| Amazon    |         |          |            |        |          |            |      |      |      |
| Myntra    |         |          |            |        |          |            |      |      |      |
| Malabar   |         |          |            |        |          |            |      |      |      |
| MMTC-PAMP |         |          |            |        |          |            |      |      |      |
| Kalyan    |         |          |            |        |          |            |      |      |      |
| Tanishq   |         |          |            |        |          |            |      |      |      |

No source is complete until every applicable column is PASS.

---

# 62. PHASE 14 — FULL TEST SUITE

Run:

```bash
./gradlew clean
./gradlew test
./gradlew lint
./gradlew assembleDebug
```

Also run applicable:

```bash
./gradlew connectedAndroidTest
```

Install the generated APK on a real device/emulator.

Test actual application behaviour.

---

# 63. PERFORMANCE TESTING

Measure:

```text
DNS
connect
TLS
TTFB
download
parse
normalize
validation
database
total source time
total refresh time
```

Do not promise:

```text
every refresh < 2 seconds
```

because pagination, network conditions and source behaviour vary.

The objective is:

```text
as fast as reasonably possible
without sacrificing correctness
```

---

# 64. CONCURRENCY

Use bounded concurrency.

Do not start unlimited requests.

Respect source/host limits.

Do not overload one provider.

Use cancellation propagation.

A slow source should not unnecessarily block unrelated sources.

---

# 65. RESPONSE MEMORY SAFETY

Large HTML responses can consume substantial memory.

Avoid unnecessary copies.

Use bounded response sizes.

Do not repeatedly convert large payloads between:

```text
ByteArray
String
JSON tree
String
```

without reason.

Optimize only after correctness is established.

---

# 66. SCHEMA DRIFT

If a source changes:

```text
old response
≠
new response
```

the parser should fail visibly.

Do not silently produce:

```text
0 products
```

and delete the database.

Record:

```text
SCHEMA_CHANGED
PARSER_ERROR
```

as appropriate.

---

# 67. SECURITY / ACCESS RESTRICTIONS

If a source returns:

```text
403
429
CAPTCHA
challenge
login wall
```

do not attempt to bypass security controls.

Do not:

* steal cookies
* extract browser sessions
* solve CAPTCHA
* execute site JavaScript
* imitate authenticated users without authorization

Classify the source state and preserve last-known-good data.

---

# 68. FINAL FORBIDDEN-TECHNOLOGY AUDIT

Before completion, search the entire V2 source tree for:

```text
WebView
WebViewClient
WebChromeClient
evaluateJavascript
loadUrl
Playwright
Puppeteer
Selenium
browser
chromium
Firefox
localhost
127.0.0.1
8788
addJavascriptInterface
Thread.sleep
delay(2000)
delay(3000)
delay(5000)
NotImplementedError
TODO
FIXME
dummy
mock product
fake product
placeholder
```

Investigate every match.

Do not blindly delete legitimate comments/tests; determine whether production functionality violates the requirements.

---

# 69. LEGACY PROJECT MUST REMAIN UNCHANGED

At the end, verify the legacy project has not been modified.

Compare:

```text
legacy baseline
vs
legacy current
```

Expected:

```text
NO UNINTENDED CHANGES
```

V2 must be independently buildable.

---

# 70. FINAL END-TO-END REQUIREMENT

The final application must demonstrate:

```text
OPEN APP
    ↓
EXISTING UI
    ↓
EXISTING NAVIGATION
    ↓
EXISTING PRODUCT EXPERIENCE
    ↓
USER STARTS REFRESH
    ↓
NATIVE REFRESH COORDINATOR
    ↓
ALL DISCOVERED SOURCES
    ↓
NATIVE HTTP
    ↓
SOURCE-SPECIFIC PARSERS
    ↓
WEIGHT / PURITY / METAL / PRICE / AVAILABILITY
    ↓
VALIDATION
    ↓
DEDUPLICATION
    ↓
DATABASE TRANSACTION
    ↓
PRODUCTS PERSISTED
    ↓
EXISTING PRODUCT UI UPDATED
    ↓
REAL NATIVE LOGS DISPLAYED
```

---

# 71. THE MOST IMPORTANT COMPLETION TEST

Do not tell me:

> "The project builds."

That is not sufficient.

Do not tell me:

> "The UI works."

That is not sufficient.

Do not tell me:

> "The API returns HTTP 200."

That is not sufficient.

Do not tell me:

> "The parser is implemented."

That is not sufficient.

Completion requires proving:

```text
OLD APPLICATION
       ↓
FEATURE PARITY
       ↓
UI PARITY
       ↓
DATABASE PARITY
       ↓
PRODUCT MODEL PARITY
       ↓
NATIVE SOURCE DISCOVERY
       ↓
REAL PRODUCTS DISCOVERED
       ↓
PRODUCTS VALIDATED
       ↓
PRODUCTS WRITTEN TO DATABASE
       ↓
PRODUCTS DISPLAYED IN UI
```

---

# 72. NO EMPTY-DATABASE SUCCESS

If V2 launches and displays:

```text
No products
```

the task is **NOT COMPLETE** unless the actual source data was legitimately unavailable and the reason is documented.

You must investigate:

```text
Was the request successful?
Was the response non-empty?
Did the parser discover products?
Did validation reject them?
Did DB insertion fail?
Did the UI query the wrong table?
Did the source filter remove everything?
Did deduplication remove everything?
```

Find the actual failure.

Do not hide it.

---

# 73. NO RANDOM CHANGES

Do not make unrelated changes simply because you think they are better.

For every deviation from the old application, document:

```text
OLD BEHAVIOUR
NEW BEHAVIOUR
REASON
IMPACT
```

If the change is not necessary for the native architecture:

**DO NOT MAKE IT.**

---

# 74. NO ASSUMPTIONS

If you encounter uncertainty:

```text
DO NOT GUESS.
```

Inspect the old implementation.

Inspect the actual response.

Inspect the actual database.

Inspect the actual UI.

Create a fixture.

Write a test.

Then implement.

---

# 75. WORK IN SMALL VERIFIED PHASES

After each major phase:

```text
BUILD
↓
TEST
↓
FIX
↓
VERIFY
↓
ONLY THEN CONTINUE
```

Do not implement the entire project and discover 200 errors at the end.

---

# 76. DO NOT STOP AFTER SCAFFOLDING

The following is explicitly considered incomplete:

```text
project created
+
folders created
+
interfaces created
+
TODOs left
```

The deliverable must be an actual functioning Android application.

---

# 77. DO NOT STOP AFTER UI

The following is explicitly incomplete:

```text
UI recreated
+
dummy products
```

The actual database and native acquisition engine must work.

---

# 78. DO NOT STOP AFTER SCRAPERS

The following is explicitly incomplete:

```text
HTTP works
+
parser works
```

The result must reach:

```text
database
↓
repository
↓
UI
```

---

# 79. DO NOT STOP AFTER DATABASE

The following is explicitly incomplete:

```text
database works
```

Products must actually be discovered and populated.

---

# 80. FINAL DELIVERABLE

The final directory must contain a complete Android project:

```text
aurum-android-v2/
│
├── app/
│   ├── src/
│   │   ├── main/
│   │   ├── test/
│   │   └── androidTest/
│
├── docs/
│   ├── LEGACY_FEATURE_INVENTORY.md
│   ├── UI_PARITY_MATRIX.md
│   ├── DATABASE_PARITY.md
│   ├── PRODUCT_DATA_CONTRACT.md
│   ├── SOURCE_INVENTORY.md
│   ├── SOURCE_ACCEPTANCE_MATRIX.md
│   └── ARCHITECTURE.md
│
├── settings.gradle
├── build.gradle
├── gradle.properties
└── ...
```

---

# 81. FINAL REPORT REQUIRED

At completion, produce a final report containing:

## Project

```text
V2 project path
```

## Build

```text
assembleDebug: PASS/FAIL
test: PASS/FAIL
lint: PASS/FAIL
instrumentation: PASS/FAIL
```

## UI

```text
Screens audited:
Screens reproduced:
Parity status:
```

## Database

```text
Entities:
Fields:
Queries:
Relations:
Parity status:
```

## Sources

For every source:

```text
HTTP:
Products discovered:
Products accepted:
Products rejected:
Pages:
DB inserted:
DB updated:
Final status:
```

## Native Engine

```text
HTTP client:
Concurrency:
Retry:
Cancellation:
Background refresh:
Status:
```

## Forbidden Technology Audit

```text
WebView:
Browser:
Playwright:
Puppeteer:
Selenium:
JavaScript execution:
Loopback:
Hard-coded delays:
```

All must be:

```text
ABSENT
```

unless a finding is explicitly documented as test-only/non-production.

---

# 82. FINAL DEFINITION OF DONE

Aurum Android V2 is complete only when ALL of these are true:

* [ ] Legacy project untouched
* [ ] V2 exists independently in `aurum-android-v2/`
* [ ] Full legacy feature inventory completed
* [ ] Full UI inventory completed
* [ ] Existing UI reproduced
* [ ] Existing navigation reproduced
* [ ] Existing database fully inspected
* [ ] Existing database contract reproduced
* [ ] Existing product model reproduced
* [ ] Existing product fields preserved
* [ ] Existing product functionality preserved
* [ ] Existing stores preserved
* [ ] All discovered sources implemented
* [ ] All discovered URLs inventoried
* [ ] Native HTTP implemented
* [ ] No browser implementation
* [ ] No WebView
* [ ] No Playwright
* [ ] No Puppeteer
* [ ] No Selenium
* [ ] No JavaScript execution
* [ ] No loopback scraper
* [ ] No hard-coded delays
* [ ] Dynamic pagination implemented
* [ ] Source-specific parsers implemented
* [ ] Weight engine tested
* [ ] Purity engine tested
* [ ] Metal classification tested
* [ ] Price parsing tested
* [ ] Availability tested
* [ ] Product identity tested
* [ ] Validation implemented
* [ ] Deduplication implemented
* [ ] Atomic database writes implemented
* [ ] Last-known-good data preserved
* [ ] Native logging implemented
* [ ] Existing browser section converted to native logs
* [ ] Manual refresh works
* [ ] Background refresh works
* [ ] Cancellation works
* [ ] Failure isolation works
* [ ] Real source requests tested
* [ ] Real products discovered
* [ ] Real products persisted
* [ ] Real products visible in UI
* [ ] Parser fixtures exist for every source
* [ ] Negative tests exist
* [ ] Database tests pass
* [ ] UI/device tests pass
* [ ] End-to-end tests pass
* [ ] Build passes
* [ ] Tests pass
* [ ] Lint passes
* [ ] Final forbidden-technology audit passes
* [ ] Final parity audit passes

---

# 83. INSTRUCTION TO GEMINI

**Do not optimize for producing code quickly.**

Optimize for:

```text
CORRECTNESS
+
PARITY
+
DATA INTEGRITY
+
RELIABILITY
+
COMPLETE IMPLEMENTATION
```

The existing Aurum application is the specification.

Do not replace it with your interpretation of what Aurum should be.

Do not redesign it.

Do not simplify it.

Do not omit functionality.

Do not create a new UI.

Do not create a simplified database.

Do not create fake products.

Do not stop at scaffolding.

Do not stop at HTTP 200.

Do not stop at parser creation.

Do not stop at compilation.

Follow the complete pipeline:

```text
AUDIT EXISTING AURUM
        ↓
DOCUMENT EVERYTHING
        ↓
RECREATE UI
        ↓
RECREATE DATABASE
        ↓
RECREATE PRODUCT CONTRACT
        ↓
IMPLEMENT NATIVE NETWORK ENGINE
        ↓
IMPLEMENT EVERY SOURCE
        ↓
IMPLEMENT EVERY PARSER
        ↓
VALIDATE PRODUCTS
        ↓
WRITE REAL PRODUCTS
        ↓
DISPLAY REAL PRODUCTS
        ↓
IMPLEMENT LOGGING
        ↓
IMPLEMENT BACKGROUND REFRESH
        ↓
TEST FAILURE CASES
        ↓
COMPARE AGAINST LEGACY
        ↓
BUILD
        ↓
TEST
        ↓
FINAL AUDIT
```

**Do not declare completion until every item in the Definition of Done has been verified.**

If something is unclear, inspect the existing implementation or actual source response rather than guessing.

If something fails, fix it before moving on.

If a source is unavailable, report the exact reason rather than pretending it was implemented.

If products are not being discovered, the implementation is not complete.

If the original database is missing, the implementation is not complete.

If the UI has been redesigned, the implementation is not complete.

If existing functionality has been removed, the implementation is not complete.

The objective is not to make **a new app inspired by Aurum**.

The objective is to make **Aurum V2 with the existing Aurum application experience and data model, powered by a completely new native acquisition process.**
