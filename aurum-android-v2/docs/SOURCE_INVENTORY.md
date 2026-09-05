# AURUM V2 SOURCE INVENTORY

This document inventories all retail and bullion sources, request endpoints, parameters, transport preferences, and parser configurations in Aurum V2.

---

## 1. Retail Sources

### 1. AJIO (`ajio`)
* **Display Name**: AJIO
* **Transport**: `CronetHttpClient` (HTTP/2)
* **Endpoints**:
  * Primary Category API: `https://www.ajio.com/api/category/8303`
  * Gold Coins URL: `https://www.ajio.com/women/c/8303?query=%3Arelevance%3Averticalmetalpurity%3A24+Karat...`
  * Jewellery URL: `https://www.ajio.com/s/jewellery-176606?query=...`
  * Girls URL: `https://www.ajio.com/s/girls-169379?query=...`
  * Boys URL: `https://www.ajio.com/s/boys-169373?query=...`
  * Rings URL: `https://www.ajio.com/women-rings/c/830306004?query=...`
* **Parser (`AjioParser`)**:
  * JSON parser extracting `products` array, `code`, `name`, `fnlColorVariantData.brandName`, `price.value`, `price.wasPriceData.value`, `url`.
  * Regex fallback extracting embedded product nodes for HTML category pages.

### 2. Amazon India (`amazon`)
* **Display Name**: Amazon India
* **Transport**: `CronetHttpClient` (HTTP/2)
* **Endpoint**: `https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A2160347031&s=popularity-rank&dc&fs=true`
* **Parser (`AmazonParser`)**:
  * HTML regex parser extracting `data-asin`, title span `a-size-medium`, price `a-price-whole`.
  * Product detail page URL construction: `https://www.amazon.in/dp/{ASIN}`.

### 3. Flipkart (`flipkart`)
* **Display Name**: Flipkart
* **Transport**: `CronetHttpClient` (HTTP/1.1)
* **Endpoint**: `https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold`
* **Parser (`FlipkartParser`)**:
  * Extracts 16-character `pid=([A-Za-z0-9]{16})`.
  * Product link construction: `https://www.flipkart.com/item?pid={PID}`.

### 4. Shopsy (`shopsy`)
* **Display Name**: Shopsy
* **Transport**: `CronetHttpClient` (HTTP/2)
* **Endpoint**: `https://www.shopsy.in/gold-silver-coins/pr?sid=mcr,73x&marketplace=FLIPKART&p[]=facets.material[]=Gold`
* **Parser (`ShopsyParser`)**:
  * Extracts 16-character `pid=([A-Za-z0-9]{16})`.
  * Product link construction: `https://www.shopsy.in/item?pid={PID}`.

### 5. Myntra (`myntra`)
* **Display Name**: Myntra
* **Transport**: `CronetHttpClient` (HTTP/2)
* **Endpoint**: `https://www.myntra.com/gold-coin`
* **Parser (`MyntraParser`)**:
  * JSON parser extracting `data.products` array (`productId`, `product`, `brand`, `price`, `mrp`, `landingPageUrl`).
  * Regex fallback extracting `"productId": ... "product": ...`.

---

## 2. Bullion Sources

### 1. Tanishq (`tan`)
* **Display Name**: Tanishq
* **Endpoint**: `https://www.tanishq.co.in/gold-rate.html`
* **Parser (`TanishqParser`)**: HTML regex extractor for 24K and 22K per-gram rates.

### 2. Malabar Gold & Diamonds (`malabar`)
* **Display Name**: Malabar Gold & Diamonds
* **Endpoint**: `https://www.malabargoldanddiamonds.com/graphql-magento?query={getGoldRate{gold_rate_24k,gold_rate_22k}}`
* **Parser (`MalabarParser`)**: GraphQL JSON parser for `gold_rate_24k` and `gold_rate_22k`.

### 3. Kalyan Jewellers (`kalyan`)
* **Display Name**: Kalyan Jewellers
* **Endpoint**: `https://store.kalyanjewellers.net/gold-rate/india/en`
* **Parser (`KalyanParser`)**: HTML regex extractor for 24K and 22K per-gram rates.

### 4. MMTC-PAMP (`mmtc`)
* **Display Name**: MMTC-PAMP
* **Endpoint**: `https://www.mmtcpamp.com/api/getQuote`
* **Parser (`MmtcPampParser`)**: JSON API parser extracting `data.buy_rate` for 24K pure gold.
