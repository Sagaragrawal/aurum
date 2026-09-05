# AURUM V2 PRODUCT DATA CONTRACT

This document defines the canonical domain model, weight calculation engine, purity mapping, metal classification, price processing, and validation pipeline in Aurum V2.

---

## 1. Canonical Domain Models

### `CanonicalProduct`:
* `id`: Composite identifier (`sourceId:sourceProductId:variantId`).
* `sourceId`: Store ID (`ajio`, `amazon`, `flipkart`, `shopsy`, `myntra`).
* `sourceProductId`: Retailer product identifier.
* `variantId`: Optional variant SKU.
* `brand`: Brand name.
* `title`: Full product name.
* `description`: Product details.
* `category`: Source category path.
* `productType`: Enum (`GOLD_COIN`, `GOLD_BAR`, `GOLD_JEWELLERY`, `OTHER_GOLD`).
* `url`: Canonical product link.
* `canonicalUrl`: Clean link stripped of tracking query parameters (`utm_*`, `ref`, `tag`).
* `imageUrl`: Primary image URL.
* `galleryImages`: List of high-resolution images.
* `metal`: Enum (`GOLD`, `GOLD_PLATED`, `GOLD_TONE`, `SILVER`, `PLATINUM`, `OTHER`, `UNKNOWN`).
* `purity`: `ParsedValue<PurityInfo>` containing Karat (24, 22, 18, 14) and Fineness (999.9, 999, 995, 916, 750, 585).
* `weightInfo`: `WeightInfo` containing `unitWeightGrams` (BigDecimal), `quantity` (Int), and `totalWeightGrams` (BigDecimal).
* `pricing`: `PricingInfo` containing `sellingPrice` (BigDecimal), `mrp` (BigDecimal?), and `effectivePrice` (BigDecimal).
* `availability`: Enum (`IN_STOCK`, `OUT_OF_STOCK`, `LOW_STOCK`, `UNKNOWN`).
* `isSuspicious`: Boolean flag for pricing/weight anomalies.
* `anomalyFlags`: List of descriptive anomaly strings.

---

## 2. Weight Parsing Engine (`WeightParser`)

* **Internal Representation**: Exact `BigDecimal` representation rounded to 3 decimal places for grams (or 4 decimal places for milligrams).
* **Single Weights**:
  * Grams: `1g`, `1.5g`, `5 Grams`, `10 GM`, `20 G` → Converted to exact grams.
  * Milligrams: `50 mg` → `0.0500 g`, `500 mg` → `0.5000 g`.
  * Kilograms: `1 kg` → `1000.000 g`.
* **Multi-Packs**:
  * Pattern: `(\d+)\s*(?:pcs?|pack|pieces?|x|×)(?:\s*(?:x|×))?\s*(\d+(?:\.\d+)?)\s*(mg|g|gm|kg)`
  * Examples: `2 x 1g` → Qty: 2, Unit: 1.0g, Total: 2.0g.
  * Examples: `3 pcs x 10g` → Qty: 3, Unit: 10.0g, Total: 30.0g.
* **Additive Weight Combinations**:
  * Pattern: `(\d+(?:\.\d+)?)\s*(mg|g|kg)\s*\+\s*(\d+(?:\.\d+)?)\s*(mg|g|kg)`
  * Example: `0.5g + 1g` → Total: 1.5g.
* **Context Protection**:
  * Ignores prices (`₹9,999`), karats (`24K`, `22K`), fineness (`999`, `916`), and model/SKU numbers.
  * **Rule against silent correction**: If source declares `500g` at price ₹3,500, the parser preserves `500g` and marks the product as `isSuspicious = true` (flagged) rather than silently rewriting `500g → 500mg`.

---

## 3. Purity Engine (`PurityParser`)

* **Fineness Mapping**:
  * `999.9`, `999`, `995` → 24K Gold (99.9% / 99.5% purity)
  * `916` → 22K Gold (91.6% purity)
  * `750` → 18K Gold (75.0% purity)
  * `585` → 14K Gold (58.5% purity)
* **Karat Mapping**:
  * `24K` / `24Kt` → 24 Karat Gold (999 fineness)
  * `22K` / `22Kt` → 22 Karat Gold (916 fineness)
  * `18K` / `18Kt` → 18 Karat Gold (750 fineness)
  * `14K` / `14Kt` → 14 Karat Gold (585 fineness)

---

## 4. Metal Classification Engine (`MetalClassifier`)

* **Solid Gold Indicators**: Title or category contains `gold`, `24k`, `22k`, `18k`, `999`, `916`.
* **Plated Indicators**: Title or description contains `gold plated`, `gold-plated`, `gold tone`, `vermeil`, `rolled gold`. → Classified as `GOLD_PLATED` and rejected from solid gold dataset during validation.
* **Rejected Metals**: Title contains `silver`, `chandi`, `sterling silver`, `platinum`, `pt950`, `brass`, `copper`, `imitation`, `artificial`. → Classified as `SILVER`, `PLATINUM`, or `OTHER` and rejected during validation.

---

## 5. Product Validation Pipeline (`ProductValidator`)

Every canonical product is checked before database persistence:
1. `metal == MetalType.GOLD`: Rejects `GOLD_PLATED`, `SILVER`, `PLATINUM`, `OTHER`.
2. `weightGrams > 0`: Rejects zero or negative weight.
3. `sellingPrice > 0`: Rejects zero or negative price.
4. `pricePerGram`: Calculates `sellingPrice / weightGrams`. Flags product as suspicious if rate is outside reasonable bounds (₹1,000/g – ₹25,000/g).
5. `weightGrams <= 1000`: Flags unusually heavy products (>1kg).
6. `purity.isValid`: Flags missing or ambiguous purity info.
