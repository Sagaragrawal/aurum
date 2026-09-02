(async () => {
  "use strict";

  const navigation = performance.getEntriesByType("navigation")[0];
  if (!globalThis.__aurumMasterRunner && navigation?.type !== "reload") {
    console.info("Refreshing Amazon before extraction. Run this script again after the page reloads.");
    location.reload();
    return;
  }

  /******************************************************************
   * AMAZON GOLD MASTER V14.3 — ALL GOLD FINAL
   * ================================================================
   *
   * PRIMARY OUTPUT:
   *   ALL ACCEPTED GOLD PRODUCTS
   *
   * Includes:
   *   - Gold bullion / coins / bars
   *   - Gold jewellery
   *   - Other gold products
   *   - 24K / 23K / 22K / 21K / 20K / 19K / 18K / 17K /
   *     16K / 15K / 14K / 13K / 12K / 11K / 10K / 9K / 8K
   *
   * Excludes:
   *   - gold plated
   *   - gold coated
   *   - gold tone
   *   - gold colour/color only
   *   - vermeil
   *   - silver
   *   - brass / copper / steel / alloy / base metal
   *   - non-gold collectible coins
   *
   * IMPORTANT:
   *   We keep the proven Amazon Gold-facet classification behavior.
   *   We DO NOT require every PLP title to independently prove gold.
   *
   * Classification precedence:
   *   1. Hard non-gold exclusion
   *   2. Explicit bullion terminology
   *   3. Jewellery terminology
   *   4. Other gold
   *
   * MAIN EXPORT:
   *   bullion + jewellery + gold-other
   ******************************************************************/

  const CFG = {
    fallbackPageSize: 48,
    maxPages: 20,

    searchTimeoutMs: 12000,
    searchRetries: 1,
    searchPageDelayMs: 50,
    suspiciousSmallHtmlBytes: 25000,

    enablePDP: false,
    minimumPlausiblePricePerGram: 5000,
    pdpTimeoutMs: 10000,
    pdpDelayMs: 400
  };

  /******************************************************************
   * LOGGING
   ******************************************************************/

  const LOG = (...x) =>
    console.log("[AMZ-GOLD]", ...x);

  const WARN = (...x) =>
    console.warn("[AMZ-GOLD]", ...x);

  console.clear();

  const STARTED_AT = performance.now();

  const PRODUCTS = new Map();
  const PAGE_LOG = [];
  const PDP_LOG = [];

  /******************************************************************
   * BASIC HELPERS
   ******************************************************************/

  const clean = value =>
    String(value ?? "")
      .replace(/<br\s*\/?>/gi, " ")
      .replace(/<[^>]+>/g, " ")
      .replace(/&nbsp;/gi, " ")
      .replace(/&amp;/gi, "&")
      .replace(/&quot;/gi, '"')
      .replace(/&#39;/gi, "'")
      .replace(/\u00a0/g, " ")
      .replace(/\s+/g, " ")
      .trim();

  function num(value) {
    if (
      value === null ||
      value === undefined ||
      value === ""
    ) {
      return null;
    }

    if (typeof value === "number") {
      return Number.isFinite(value)
        ? value
        : null;
    }

    const m = String(value)
      .replace(/,/g, "")
      .match(/-?\d+(?:\.\d+)?/);

    if (!m)
      return null;

    const n = Number(m[0]);

    return Number.isFinite(n)
      ? n
      : null;
  }

  function validASIN(value) {
    return /^[A-Z0-9]{10}$/i.test(
      String(value || "")
    );
  }

  function canonicalURL(asin) {
    return asin
      ? `https://www.amazon.in/dp/${asin}`
      : null;
  }

  function sleep(ms) {
    return new Promise(resolve =>
      setTimeout(resolve, ms)
    );
  }

  function uniqueStrings(values) {
    const seen = new Set();
    const out = [];

    for (const value of values) {
      const s = clean(value);

      if (!s)
        continue;

      const key = s.toLowerCase();

      if (seen.has(key))
        continue;

      seen.add(key);
      out.push(s);
    }

    return out;
  }

  /******************************************************************
   * SEARCH CONTEXT
   ******************************************************************/

  const CURRENT_URL = new URL(location.href);

  const ACTIVE_RH = decodeURIComponent(
    CURRENT_URL.searchParams.get("rh") || ""
  );

  const ACTIVE_GOLD_FACET =
    /p_n_material_two_browse-bin:2160347031/i
      .test(ACTIVE_RH);

  /******************************************************************
   * REPORTED TOTAL
   ******************************************************************/

  function reportedTotal(doc = document) {
    const candidates = [
      clean(
        doc.querySelector(
          ".a-section.a-spacing-small.a-spacing-top-small"
        )?.textContent
      ),

      clean(
        doc.querySelector(
          "[data-component-type='s-result-info-bar']"
        )?.textContent
      ),

      clean(
        doc.body?.textContent?.slice(0, 30000)
      )
    ];

    for (const text of candidates) {
      if (!text)
        continue;

      let m = text.match(
        /\b\d+\s*[-–]\s*\d+\s+of\s+(?:over\s+)?([\d,]+)\s+results?/i
      );

      if (m)
        return num(m[1]);

      m = text.match(
        /\bof\s+(?:over\s+)?([\d,]+)\s+results?\b/i
      );

      if (m)
        return num(m[1]);
    }

    return null;
  }

  /******************************************************************
   * BRANDS
   ******************************************************************/

  const KNOWN_BRANDS = [
    "Bangalore Refinery",
    "MMTC-PAMP",
    "MMTC PAMP",

    "Joyalukkas",
    "Joy Alukkas",
    "Jos Alukkas",

    "Kalyan Jewellers",

    "P. N. Gadgil Jewellers",
    "P.N.Gadgil Jewellers",
    "P N Gadgil Jewellers",
    "PNG Jewellers",

    "P. C. Chandra Jewellers",
    "P.C. Chandra Jewellers",
    "PC Chandra Jewellers",

    "Sri Jagdamba Pearls Dealer",
    "Sri Jagdamba Pearls",

    "Malabar Gold & Diamonds",

    "Kundan & Zeya",
    "KUNDAN",

    "BHIMA",
    "Bhima",

    "Muthoot Pappachan",
    "Muthoot PAPPACHAN",

    "KALAMANDIR",
    "Augmont",
    "Tanishq",
    "Senco",
    "PMJ",
    "RSBL",
    "DISHIS",
    "ASPECT BULLION",
    "C.Krishniah Chetty Jewellers",
    "KaratCraft"
  ];

  function isBrandOnly(text) {
    const s = clean(text).toLowerCase();

    if (!s)
      return false;

    return KNOWN_BRANDS.some(
      brand =>
        brand.toLowerCase() === s
    );
  }

  function brandOf(text) {
    const s = clean(text);

    if (!s)
      return null;

    const lower = s.toLowerCase();

    const known = KNOWN_BRANDS.find(
      brand =>
        lower.startsWith(
          brand.toLowerCase()
        )
    );

    if (known)
      return known;

    const prefix = clean(
      s.split(
        /\b(?:24|23|22|21|20|19|18|17|16|15|14|13|12|11|10|9|8)\s*(?:k|kt)|\b999\.9|\b999|\b995|\b916|\bgold\b|\bcoin\b|\bbar\b/i
      )[0]
    )
      .replace(/[-–—,:|]+$/g, "")
      .trim();

    if (
      prefix.length >= 2 &&
      prefix.length <= 80
    ) {
      return prefix;
    }

    return null;
  }

  /******************************************************************
   * TITLE EXTRACTION
   ******************************************************************/

  const PRODUCT_EVIDENCE_RE =
    /\b(?:gold|coin|coins|bar|bars|bullion|biscuit|vedhani|pendant|chain|ring|rings|earring|earrings|necklace|bracelet|bangle|gm|gram|grams|mg|24k|24kt|22k|22kt|18k|18kt|14k|14kt|999|995|916)\b/i;

  function titleScore(text) {
    const s = clean(text);

    if (!s)
      return -Infinity;

    let score =
      Math.min(s.length, 180) / 10;

    if (PRODUCT_EVIDENCE_RE.test(s))
      score += 30;

    if (
      /\b(?:coin|coins|bar|bars|bullion|biscuit|vedhani)\b/i
        .test(s)
    ) {
      score += 35;
    }

    if (
      /\b(?:24|23|22|21|20|19|18|17|16|15|14|13|12|11|10|9|8)\s*(?:k|kt|karat|carat)\b/i
        .test(s)
    ) {
      score += 25;
    }

    if (
      /\b(?:999\.9|999|995|916|875|750|585|417|375)\b/
        .test(s)
    ) {
      score += 15;
    }

    if (
      /\b\d+(?:\.\d+)?\s*(?:mg|g|gm|grams?)\b/i
        .test(s)
    ) {
      score += 20;
    }

    if (isBrandOnly(s))
      score -= 100;

    if (s.length < 12)
      score -= 25;

    if (
      /^(?:sponsored|prime|limited time deal)$/i
        .test(s)
    ) {
      score -= 200;
    }

    return score;
  }

  function extractBestTitle(card) {
    const candidates = [];

    function push(value, source) {
      const text = clean(value);

      if (!text)
        return;

      candidates.push({
        text,
        source,
        score: titleScore(text)
      });
    }

    const selectors = [
      "h2 a span",
      "h2 span",
      "h2",

      "[data-cy='title-recipe'] a span",
      "[data-cy='title-recipe'] span",

      "a.a-link-normal.s-line-clamp-2 span",
      "a.a-link-normal.s-line-clamp-3 span",

      ".a-size-base-plus.a-color-base.a-text-normal",
      ".a-size-medium.a-color-base.a-text-normal"
    ];

    for (const selector of selectors) {
      for (
        const el
        of card.querySelectorAll(selector)
      ) {
        push(
          el.textContent,
          `selector:${selector}`
        );
      }
    }

    for (
      const a
      of card.querySelectorAll(
        'a[href*="/dp/"],a[href*="/gp/product/"]'
      )
    ) {
      push(
        a.textContent,
        "product-link-text"
      );

      push(
        a.getAttribute("aria-label"),
        "product-link-aria"
      );

      push(
        a.getAttribute("title"),
        "product-link-title"
      );
    }

    for (
      const img
      of card.querySelectorAll("img[alt]")
    ) {
      push(
        img.getAttribute("alt"),
        "image-alt"
      );
    }

    candidates.sort(
      (a, b) =>
        b.score - a.score ||
        b.text.length - a.text.length
    );

    return {
      title:
        candidates[0]?.text ||
        null,

      source:
        candidates[0]?.source ||
        null,

      score:
        candidates[0]?.score ??
        null
    };
  }

  /******************************************************************
   * CLASSIFICATION
   ******************************************************************/

  /*
   * HARD NON-GOLD EXCLUSIONS.
   *
   * These always win.
   */
  const GOLD_PLATED_RE =
    /\b(?:gold[\s-]*(?:plated|plate|coated|coating|tone|toned|finish|finished|polished|colour|colored|coloured|color)|gold\s+plating|gold\s+coating|vermeil)\b/i;

  const SILVER_RE =
    /\b(?:silver|sterling|925\s*silver|925\s*sterling)\b/i;

  const BASE_METAL_RE =
    /\b(?:brass|copper|bronze|stainless\s+steel|steel|zinc|alloy|aluminium|aluminum|iron|plastic|wood|wooden)\b/i;

  const OTHER_METAL_RE =
    /\b(?:platinum|palladium)\b/i;

  const GOLD_RE =
    /\b(?:gold|yellow\s+gold|rose\s+gold|white\s+gold)\b/i;

  const KARAT_RE =
    /\b(?:24|23|22|21|20|19|18|17|16|15|14|13|12|11|10|9|8)\s*(?:k|kt|karat|carat)\b/i;

  /*
   * Explicit bullion terminology.
   *
   * This gets precedence over jewellery.
   */
  const EXPLICIT_BULLION_RE =
    /\b(?:gold\s+)?(?:coin|coins|bar|bars|bullion|biscuit|vedhani)\b/i;

  const JEWELLERY_RE =
    /\b(?:pendants?|chains?|rings?|earrings?|necklaces?|bracelets?|bangles?|mangalsutras?|nose\s*pins?|nosepins?|diamonds?|solitaires?|jewellery|jewelry|jhumkas?|jhumkis?|studs?|lockets?)\b/i;

  const COLLECTIBLE_RE =
    /\b(?:collectors?|collectible|modern\s+issue|limited\s+edition|currency|banknote|foreign\s+coin|old\s+coin|antique\s+coin)\b/i;

  function explicitNonGoldReason(text) {
    const s = clean(text);

    if (!s)
      return null;

    if (GOLD_PLATED_RE.test(s))
      return "gold-plated/coated/tone";

    if (SILVER_RE.test(s))
      return "silver";

    if (BASE_METAL_RE.test(s))
      return "base-metal";

    if (OTHER_METAL_RE.test(s))
      return "other-metal";

    return null;
  }

  function productTypeOf(text) {
    const s = clean(text);

    /*
     * Bullion types first.
     */
    if (/\bcoins?\b/i.test(s))
      return "coin";

    if (/\bbars?\b/i.test(s))
      return "bar";

    if (/\bbullion\b/i.test(s))
      return "bullion";

    if (/\bbiscuit\b/i.test(s))
      return "biscuit";

    if (/\bvedhani\b/i.test(s))
      return "vedhani";

    if (/\bpendants?\b/i.test(s))
      return "pendant";

    if (/\bchains?\b/i.test(s))
      return "chain";

    if (/\brings?\b/i.test(s))
      return "ring";

    if (/\bearrings?\b/i.test(s))
      return "earring";

    if (/\bnecklace\b/i.test(s))
      return "necklace";

    if (/\bbracelet\b/i.test(s))
      return "bracelet";

    if (/\bbangle\b/i.test(s))
      return "bangle";

    if (/\bmangalsutra\b/i.test(s))
      return "mangalsutra";

    return null;
  }

  function classifyProductScoped(titleText, extraText = "") {
    const title = clean(titleText);
    const extra = clean(extraText);

    /*
     * Critical V14.2 rule:
     * classification is decided from the PRODUCT TITLE first.
     * Noisy PDP text may enrich a weak title, but incidental words such as
     * "silver" in bullets/metadata must not overturn an explicit title like
     * "24K (995) Pure Gold Bar".
     */
    const titleNonGold = explicitNonGoldReason(title);
    const titleHasGold = GOLD_RE.test(title) || KARAT_RE.test(title);
    const titleHasBullion = EXPLICIT_BULLION_RE.test(title);
    const titleHasJewellery = JEWELLERY_RE.test(title);
    const titleCollectible = COLLECTIBLE_RE.test(title);

    if (titleNonGold) {
      return {
        classification: "non-gold",
        reason: titleNonGold,
        metal: "non-gold",
        productType: productTypeOf(title),
        isGold: false
      };
    }

    if (titleCollectible && !titleHasGold) {
      return {
        classification: "non-bullion-collectible",
        reason: "collectible-without-gold-evidence",
        metal: null,
        productType: productTypeOf(title),
        isGold: false
      };
    }

    if (titleHasBullion && (titleHasGold || ACTIVE_GOLD_FACET)) {
      return {
        classification: "gold-bullion",
        reason: titleHasGold ? "title-gold+bullion" : "gold-facet+title-bullion",
        metal: "gold",
        productType: productTypeOf(title),
        isGold: true
      };
    }

    if (titleHasJewellery && (titleHasGold || ACTIVE_GOLD_FACET)) {
      return {
        classification: "gold-jewellery",
        reason: titleHasGold ? "title-gold+jewellery" : "gold-facet+title-jewellery",
        metal: "gold",
        productType: productTypeOf(title),
        isGold: true
      };
    }

    if (titleHasGold) {
      return {
        classification: "gold-other",
        reason: "explicit-title-gold",
        metal: "gold",
        productType: productTypeOf(title),
        isGold: true
      };
    }

    /* Only weak/ambiguous titles fall back to combined evidence. */
    return classifyProduct(clean([title, extra].filter(Boolean).join(" | ")));
  }

  function classifyProduct(text) {
    const s = clean(text);

    const productType =
      productTypeOf(s);

    /*
     * 1. HARD EXCLUSION
     */
    const nonGold =
      explicitNonGoldReason(s);

    if (nonGold) {
      return {
        classification:
          "non-gold",

        reason:
          nonGold,

        metal:
          "non-gold",

        productType,

        isGold:
          false
      };
    }

    const collectible =
      COLLECTIBLE_RE.test(s);

    const hasGoldWord =
      GOLD_RE.test(s);

    const hasKarat =
      KARAT_RE.test(s);

    const hasBullion =
      EXPLICIT_BULLION_RE.test(s);

    const hasJewellery =
      JEWELLERY_RE.test(s);

    /*
     * 2. COLLECTIBLE PROTECTION
     *
     * Collectible coin without explicit gold/karat evidence is not
     * promoted just because the search facet is Gold.
     */
    if (
      collectible &&
      !hasGoldWord &&
      !hasKarat
    ) {
      return {
        classification:
          "non-bullion-collectible",

        reason:
          "collectible-without-gold-evidence",

        metal:
          null,

        productType,

        isGold:
          false
      };
    }

    /*
     * 3. EXPLICIT BULLION HAS PRECEDENCE.
     *
     * Important:
     * "Gold Bar cum Coin" must remain bullion even if some other
     * evidence contains a jewellery-looking word.
     */
    if (
      hasBullion &&
      (
        hasGoldWord ||
        hasKarat ||
        ACTIVE_GOLD_FACET
      )
    ) {
      return {
        classification:
          "gold-bullion",

        reason:
          (
            hasGoldWord ||
            hasKarat
          )
            ? "gold+bullion"
            : "gold-facet+bullion",

        metal:
          "gold",

        productType,

        isGold:
          true
      };
    }

    /*
     * 4. JEWELLERY
     *
     * No 22K/24K restriction.
     *
     * 18K / 14K / etc. all qualify.
     */
    if (
      hasJewellery &&
      (
        hasGoldWord ||
        hasKarat ||
        ACTIVE_GOLD_FACET
      )
    ) {
      return {
        classification:
          "gold-jewellery",

        reason:
          (
            hasGoldWord ||
            hasKarat
          )
            ? "gold+jewellery"
            : "gold-facet+jewellery",

        metal:
          "gold",

        productType,

        isGold:
          true
      };
    }

    /*
     * 5. OTHER GOLD
     */
    if (
      hasGoldWord ||
      hasKarat
    ) {
      return {
        classification:
          "gold-other",

        reason:
          "explicit-gold",

        metal:
          "gold",

        productType,

        isGold:
          true
      };
    }

    /*
     * 6. Gold material facet fallback.
     *
     * This is intentionally retained from the successful V14
     * approach instead of V15's over-strict positive proof.
     */
    if (ACTIVE_GOLD_FACET) {
      return {
        classification:
          "gold-other",

        reason:
          "gold-material-facet",

        metal:
          "gold",

        productType,

        isGold:
          true
      };
    }

    return {
      classification:
        "unknown",

      reason:
        "insufficient-evidence",

      metal:
        null,

      productType,

      isGold:
        false
    };
  }

  /******************************************************************
   * PURITY
   ******************************************************************/

  function normalizeFineness(raw) {
    if (raw == null)
      return null;

    let s = String(raw)
      .replace(",", ".")
      .replace(/[^\d.]/g, "");

    if (!s)
      return null;

    if (s === "9999")
      return "999.9";

    if (s === "9167")
      return "916.7";

    const n = Number(s);

    if (!Number.isFinite(n))
      return null;

    if (
      n >= 1000 &&
      n <= 9999
    ) {
      return String(n / 10);
    }

    if (
      n >= 300 &&
      n <= 1000
    ) {
      return String(n);
    }

    return null;
  }

  function standardFineness(karat) {
    /*
     * 24K intentionally omitted.
     * We don't fabricate 999/999.9 when Amazon didn't state it.
     */
    return ({
      23: "958",
      22: "916",
      21: "875",
      20: "833",
      19: "792",
      18: "750",
      17: "708",
      16: "667",
      15: "625",
      14: "585",
      13: "542",
      12: "500",
      11: "458",
      10: "417",
      9: "375",
      8: "333"
    })[karat] ?? null;
  }

  function karatFromFineness(fineness) {
    const n = Number(fineness);

    if (!Number.isFinite(n))
      return null;

    if (n >= 990)
      return 24;

    if (n >= 957 && n <= 959)
      return 23;

    if (n >= 915 && n <= 918)
      return 22;

    if (n >= 874 && n <= 876)
      return 21;

    if (n >= 832 && n <= 834)
      return 20;

    if (n >= 791 && n <= 793)
      return 19;

    if (n >= 749 && n <= 751)
      return 18;

    if (n >= 707 && n <= 709)
      return 17;

    if (n >= 666 && n <= 668)
      return 16;

    if (n >= 624 && n <= 626)
      return 15;

    if (n >= 584 && n <= 586)
      return 14;

    if (n >= 541 && n <= 543)
      return 13;

    if (n >= 499 && n <= 501)
      return 12;

    if (n >= 457 && n <= 459)
      return 11;

    if (n >= 416 && n <= 418)
      return 10;

    if (n >= 374 && n <= 376)
      return 9;

    if (n >= 332 && n <= 334)
      return 8;

    return null;
  }

  function explicitFinenessEvidence(text) {
    const s = clean(text);

    let m = s.match(
      /\b(?:purity|fineness|fine)\s*[:\-]?\s*(999\.9\+?|999|995|990|958|916\.7|916|875|833|792|750|708|667|625|585|542|500|458|417|375|333)\b/i
    );

    if (m)
      return m[1];

    m = s.match(
      /\b(999\.9\+?|999|995|990|958|916\.7|916|875|833|792|750|708|667|625|585|542|500|458|417|375|333)\s*(?:purity|fine|fineness)\b/i
    );

    if (m)
      return m[1];

    return null;
  }

  function parsePurity(text) {
    const s = clean(text);

    if (
      !s ||
      explicitNonGoldReason(s)
    ) {
      return {
        karat: null,
        fineness: null,
        source: null
      };
    }

    let m;

    /*
     * 18K (750)
     * 22K (916)
     * 24K (999.9)
     */
    m = s.match(
      /\b(24|23|22|21|20|19|18|17|16|15|14|13|12|11|10|9|8)\s*(?:k|kt|karat|carat)\s*\(\s*(9999|999\.9\+?|999|995|990|958|916\.7|9167|916|875|833|792|750|708|667|625|585|542|500|458|417|375|333)\s*\)/i
    );

    if (m) {
      return {
        karat:
          Number(m[1]),

        fineness:
          normalizeFineness(m[2]),

        source:
          "explicit-both"
      };
    }

    /*
     * 18K 750
     * 22K 916
     */
    m = s.match(
      /\b(24|23|22|21|20|19|18|17|16|15|14|13|12|11|10|9|8)\s*(?:k|kt|karat|carat)\s*(?:purity|fine|fineness)?\s*[-:/]?\s*(9999|999\.9\+?|999|995|990|958|916\.7|9167|916|875|833|792|750|708|667|625|585|542|500|458|417|375|333)\b/i
    );

    if (m) {
      return {
        karat:
          Number(m[1]),

        fineness:
          normalizeFineness(m[2]),

        source:
          "explicit-both"
      };
    }

    const km = s.match(
      /\b(24|23|22|21|20|19|18|17|16|15|14|13|12|11|10|9|8)\s*(?:k|kt|karat|carat)\b/i
    );

    let karat =
      km
        ? Number(km[1])
        : null;

    let fineness = null;

    let source =
      karat != null
        ? "karat"
        : null;

    const explicit =
      explicitFinenessEvidence(s);

    if (explicit) {
      fineness =
        normalizeFineness(explicit);

      source =
        "explicit-fineness";
    }

    if (
      karat == null &&
      fineness
    ) {
      karat =
        karatFromFineness(fineness);
    }

    /*
     * Standard non-24K mapping.
     *
     * This means 18K -> 750, 14K -> 585, etc.
     */
    if (
      karat &&
      karat !== 24 &&
      !fineness
    ) {
      fineness =
        standardFineness(karat);

      if (fineness)
        source = "karat-standard";
    }

    return {
      karat,
      fineness,
      source
    };
  }

  /******************************************************************
   * WEIGHT
   ******************************************************************/

  function grams(
    value,
    unit = "g"
  ) {
    let n = Number(value);

    if (!Number.isFinite(n))
      return null;

    unit =
      String(unit).toLowerCase();

    if (unit === "mg")
      n /= 1000;

    if (unit === "kg")
      n *= 1000;

    return n;
  }

  function validWeight(value) {
    return (
      Number.isFinite(value) &&
      value > 0 &&
      value <= 1000
    );
  }

  function normalizeWeightText(text) {
    return clean(text)
      .toLowerCase()

      .replace(/[×✕✖]/g, "x")

      .replace(
        /(\d+(?:\.\d+)?)\s*milligrams?\b/gi,
        "$1 mg"
      )

      .replace(
        /(\d+(?:\.\d+)?)\s*mgs?\b/gi,
        "$1 mg"
      )

      .replace(
        /(\d+(?:\.\d+)?)\s*kilograms?\b/gi,
        "$1 kg"
      )

      .replace(
        /(\d+(?:\.\d+)?)\s*kgs?\b/gi,
        "$1 kg"
      )

      .replace(
        /(\d+(?:\.\d+)?)\s*grams?\b/gi,
        "$1 g"
      )

      .replace(
        /(\d+(?:\.\d+)?)\s*gms?\b/gi,
        "$1 g"
      )

      .replace(
        /(\d+(?:\.\d+)?)\s*gm\b/gi,
        "$1 g"
      )

      .replace(
        /\bpieces?\b/gi,
        "pcs"
      )

      .replace(/\s+/g, " ")
      .trim();
  }

  function weightResult(x = {}) {
    return {
      total: null,
      unit: null,
      quantity: null,
      components: null,
      calculatedTotal: null,
      conflict: false,
      source: null,
      confidence: 0,
      ...x
    };
  }

  function parseWeight(text) {
    const s =
      normalizeWeightText(text);

    if (!s)
      return weightResult();

    let m;

    /*
     * LABELLED PDP WEIGHT
     */
    m = s.match(
      /\b(?:item|product|gold|net|metal)\s+weight\s*[:\-]?\s*(\d+(?:\.\d+)?)\s*(mg|g|kg)\b/i
    );

    if (m) {
      return weightResult({
        total:
          grams(m[1], m[2]),

        source:
          "labelled-weight",

        confidence: 100
      });
    }

    m = s.match(
      /\b(\d+(?:\.\d+)?)\s*(mg|g|kg)\s+(?:item|product|gold|net)\s+weight\b/i
    );

    if (m) {
      return weightResult({
        total:
          grams(m[1], m[2]),

        source:
          "labelled-weight",

        confidence: 100
      });
    }

    /*
     * 25g (5g each x 5 pcs)
     */
    m = s.match(
      /(\d+(?:\.\d+)?)\s*(mg|g|kg)[^()]{0,100}\(\s*(\d+(?:\.\d+)?)\s*(mg|g|kg)\s*each\s*x\s*(\d+)\s*(?:pcs)?\s*\)/i
    );

    if (m) {
      const total =
        grams(m[1], m[2]);

      const unit =
        grams(m[3], m[4]);

      const quantity =
        Number(m[5]);

      const calculated =
        unit * quantity;

      return weightResult({
        total,
        unit,
        quantity,

        components:
          Array(quantity).fill(unit),

        calculatedTotal:
          calculated,

        conflict:
          Math.abs(
            total - calculated
          ) > 0.0001,

        source:
          "explicit-total+each",

        confidence: 100
      });
    }

    /*
     * 5g each x 5
     */
    m = s.match(
      /(\d+(?:\.\d+)?)\s*(mg|g|kg)\s*each\s*x\s*(\d+)\s*(?:pcs)?/i
    );

    if (m) {
      const unit =
        grams(m[1], m[2]);

      const quantity =
        Number(m[3]);

      return weightResult({
        total:
          unit * quantity,

        unit,
        quantity,

        components:
          Array(quantity).fill(unit),

        source:
          "each-x-quantity",

        confidence: 95
      });
    }

    /*
     * N pcs ... X each
     */
    m = s.match(
      /\b(\d+)\s*pcs\b[\s\S]*?(\d+(?:\.\d+)?)\s*(mg|g|kg)\s*each\b/i
    );

    if (m) {
      const quantity =
        Number(m[1]);

      const unit =
        grams(m[2], m[3]);

      return weightResult({
        total:
          quantity * unit,

        unit,
        quantity,

        components:
          Array(quantity).fill(unit),

        source:
          "pcs-x-each",

        confidence: 95
      });
    }

    /*
     * NAMED ADDITIVE COMBO
     *
     * Examples:
     *   2.50gm Ganesh + 2.50gm Lakshmi Combo -> 5g
     *   2gm Ganesh + 2gm Lakshmi Combo       -> 4g
     *
     * The old parser missed these because text appears between the first
     * weight and the plus sign.
     */
    m = s.match(
      /\b(\d+(?:\.\d+)?)\s*(mg|g|kg)\b[^+|;]{0,80}?\+\s*(\d+(?:\.\d+)?)\s*(mg|g|kg)\b/i
    );

    if (m) {
      const a = grams(m[1], m[2]);
      const b = grams(m[3], m[4]);

      if (validWeight(a) && validWeight(b)) {
        return weightResult({
          total: a + b,
          quantity: 2,
          components: [a, b],
          calculatedTotal: a + b,
          source: "named-components",
          confidence: 98
        });
      }
    }

    /*
     * 4g (2g + 2g)
     */
    m = s.match(
      /(\d+(?:\.\d+)?)\s*(mg|g|kg)\s*\(\s*([^)]*\+[^)]*)\)/i
    );

    if (m) {
      const total =
        grams(m[1], m[2]);

      const defaultUnit =
        m[2];

      const components =
        m[3]
          .split("+")
          .map(part => {
            const x = part.match(
              /(\d+(?:\.\d+)?)\s*(mg|g|kg)?/i
            );

            return x
              ? grams(
                  x[1],
                  x[2] || defaultUnit
                )
              : null;
          })
          .filter(validWeight);

      const calculated =
        components.reduce(
          (a, b) => a + b,
          0
        );

      return weightResult({
        total,

        quantity:
          components.length ||
          null,

        components,

        calculatedTotal:
          calculated,

        conflict:
          components.length > 0 &&
          Math.abs(
            total - calculated
          ) > 0.0001,

        source:
          "explicit-total+components",

        confidence: 100
      });
    }

    /*
     * 2g + 1g
     */
    const expression = s.match(
      /((?:\d+(?:\.\d+)?\s*(?:mg|g|kg)\s*\+\s*)+\d+(?:\.\d+)?\s*(?:mg|g|kg))/i
    );

    if (expression) {
      const components = [
        ...expression[1].matchAll(
          /(\d+(?:\.\d+)?)\s*(mg|g|kg)/gi
        )
      ]
        .map(x =>
          grams(x[1], x[2])
        )
        .filter(validWeight);

      if (
        components.length >= 2
      ) {
        return weightResult({
          total:
            components.reduce(
              (a, b) => a + b,
              0
            ),

          quantity:
            components.length,

          components,

          source:
            "components",

          confidence: 95
        });
      }
    }

    /*
     * Set / pack N ... X each
     */
    m = s.match(
      /\b(?:set|pack|combo)\s+of\s+(\d+)\b[\s\S]*?(\d+(?:\.\d+)?)\s*(mg|g|kg)\s*each\b/i
    );

    if (m) {
      const quantity =
        Number(m[1]);

      const unit =
        grams(m[2], m[3]);

      return weightResult({
        total:
          quantity * unit,

        unit,
        quantity,

        components:
          Array(quantity).fill(unit),

        source:
          "set-x-each",

        confidence: 95
      });
    }

    /*
     * Contextual product weight
     */
    m = s.match(
      /\b(?:gold\s+)?(?:coin|bar|bullion|biscuit|vedhani)[^.;|]{0,80}?(\d+(?:\.\d+)?)\s*(mg|g|kg)\b/i
    );

    if (m) {
      const value =
        grams(m[1], m[2]);

      return weightResult({
        total: value,
        unit: value,
        quantity: 1,

        source:
          "product-context",

        confidence: 95
      });
    }

    m = s.match(
      /\b(\d+(?:\.\d+)?)\s*(mg|g|kg)[^.;|]{0,50}?(?:gold\s+)?(?:coin|bar|bullion|biscuit|vedhani)\b/i
    );

    if (m) {
      const value =
        grams(m[1], m[2]);

      return weightResult({
        total: value,
        unit: value,
        quantity: 1,

        source:
          "product-context",

        confidence: 95
      });
    }

    /*
     * Single weight candidate
     */
    const candidates = [
      ...s.matchAll(
        /\b(\d+(?:\.\d+)?)\s*(mg|g|kg)\b/gi
      )
    ]
      .map(x => ({
        value:
          grams(x[1], x[2]),

        index:
          x.index
      }))
      .filter(x =>
        validWeight(x.value)
      );

    if (candidates.length) {
      let best = null;
      let bestScore = -Infinity;

      for (const candidate of candidates) {
        const context =
          s.slice(
            Math.max(
              0,
              candidate.index - 50
            ),

            Math.min(
              s.length,
              candidate.index + 90
            )
          );

        let score = 0;

        if (
          /\b(?:gold|coin|bar|bullion|biscuit|vedhani|pendant|chain|ring|necklace|bracelet|bangle)\b/i
            .test(context)
        ) {
          score += 30;
        }

        if (KARAT_RE.test(context))
          score += 20;

        if (
          /₹|mrp|price|\/count/i
            .test(context)
        ) {
          score -= 60;
        }

        if (score > bestScore) {
          bestScore = score;
          best = candidate;
        }
      }

      if (best) {
        return weightResult({
          total:
            best.value,

          unit:
            best.value,

          quantity: 1,

          source:
            "product-weight",

          confidence: 85
        });
      }
    }

    return weightResult();
  }

  /******************************************************************
   * PRICE / MRP
   ******************************************************************/

  function extractPrice(card) {
    const whole = clean(
      card.querySelector(
        ".a-price-whole"
      )?.textContent
    );

    const fraction = clean(
      card.querySelector(
        ".a-price-fraction"
      )?.textContent
    );

    if (whole) {
      const raw =
        whole.replace(/[^\d]/g, "") +
        (
          fraction
            ? "." +
              fraction.replace(
                /[^\d]/g,
                ""
              )
            : ""
        );

      const n = Number(raw);

      if (
        Number.isFinite(n) &&
        n > 0
      ) {
        return n;
      }
    }

    for (
      const el
      of card.querySelectorAll(
        ".a-price .a-offscreen"
      )
    ) {
      const n =
        num(el.textContent);

      if (
        n != null &&
        n > 0
      ) {
        return n;
      }
    }

    return null;
  }

  function extractMRP(card) {
    const selectors = [
      ".a-price.a-text-price .a-offscreen",
      ".a-text-price .a-offscreen",
      "[data-a-strike='true'] .a-offscreen"
    ];

    for (const selector of selectors) {
      for (
        const el
        of card.querySelectorAll(selector)
      ) {
        const n =
          num(el.textContent);

        if (
          n != null &&
          n > 0
        ) {
          return n;
        }
      }
    }

    return null;
  }

  /******************************************************************
   * RATINGS
   ******************************************************************/

  function extractRating(card) {
    for (
      const el
      of card.querySelectorAll(
        ".a-icon-alt,[aria-label*='out of 5 stars']"
      )
    ) {
      const text = clean(
        el.getAttribute("aria-label") ||
        el.textContent
      );

      const m = text.match(
        /(\d+(?:\.\d+)?)\s*out\s+of\s+5/i
      );

      if (m)
        return Number(m[1]);
    }

    return null;
  }

  function extractRatingCount(card) {
    for (
      const a
      of card.querySelectorAll(
        'a[href*="customerReviews"],a[href*="#customerReviews"]'
      )
    ) {
      const m = clean(
        a.textContent
      ).match(
        /\(?([\d,]+)\)?/
      );

      if (m)
        return num(m[1]);
    }

    return null;
  }

  /******************************************************************
   * SEARCH CARDS
   ******************************************************************/

  function cardsFromDocument(doc) {
    let cards = [
      ...doc.querySelectorAll(
        '[data-component-type="s-search-result"][data-asin]'
      )
    ];

    if (!cards.length) {
      cards = [
        ...doc.querySelectorAll(
          ".s-result-item[data-asin]"
        )
      ];
    }

    return cards.filter(
      card =>
        validASIN(
          card.getAttribute("data-asin")
        )
    );
  }

  /******************************************************************
   * EVIDENCE
   ******************************************************************/

  function collectEvidence(card) {
    const titleInfo =
      extractBestTitle(card);

    const imageAlts =
      uniqueStrings(
        [
          ...card.querySelectorAll(
            "img[alt]"
          )
        ].map(
          img =>
            img.getAttribute("alt")
        )
      );

    const linkTexts =
      uniqueStrings(
        [
          ...card.querySelectorAll(
            'a[href*="/dp/"],a[href*="/gp/product/"]'
          )
        ].map(
          a => a.textContent
        )
      );

    const evidence =
      uniqueStrings([
        titleInfo.title,
        ...imageAlts,
        ...linkTexts
      ]).join(" | ");

    return {
      titleInfo,
      evidence
    };
  }

  /******************************************************************
   * PRODUCT FROM CARD
   ******************************************************************/

  function productFromCard(
    card,
    page
  ) {
    const asin = String(
      card.getAttribute("data-asin") || ""
    ).toUpperCase();

    if (!validASIN(asin))
      return null;

    const e =
      collectEvidence(card);

    const name =
      e.titleInfo.title;

    const classification =
      classifyProductScoped(
        name,
        e.evidence
      );

    const purity =
      parsePurity(
        e.evidence
      );

    const weight =
      parseWeight(
        e.evidence
      );

    const price =
      extractPrice(card);

    const mrp =
      extractMRP(card);

    return {
      asin,

      brand:
        brandOf(name) ||
        brandOf(e.evidence),

      name,

      titleSource:
        e.titleInfo.source,

      titleScore:
        e.titleInfo.score,

      classification:
        classification.classification,

      classificationReason:
        classification.reason,

      metal:
        classification.metal,

      productType:
        classification.productType,

      isGold:
        classification.isGold,

      karat:
        classification.isGold
          ? purity.karat
          : null,

      fineness:
        classification.isGold
          ? purity.fineness
          : null,

      puritySource:
        classification.isGold
          ? purity.source
          : null,

      weight:
        weight.total != null
          ? `${+Number(
              weight.total
            ).toFixed(4)} g`
          : null,

      weightGrams:
        weight.total,

      unitWeightGrams:
        weight.unit,

      quantity:
        weight.quantity,

      componentWeightsGrams:
        weight.components,

      calculatedWeightGrams:
        weight.calculatedTotal,

      weightConflict:
        weight.conflict,

      weightSource:
        weight.source,

      weightConfidence:
        weight.confidence,

      price,

      mrp,

      discountAmount:
        (
          price != null &&
          mrp != null &&
          mrp >= price
        )
          ? mrp - price
          : null,

      discountPercent:
        (
          price != null &&
          mrp != null &&
          mrp > 0 &&
          price <= mrp
        )
          ? +(
              (
                (mrp - price) /
                mrp
              ) * 100
            ).toFixed(2)
          : null,

      rating:
        extractRating(card),

      ratingCount:
        extractRatingCount(card),

      image:
        card.querySelector(
          "img.s-image"
        )?.src ||
        card.querySelector("img")?.src ||
        null,

      link:
        canonicalURL(asin),

      pages:
        new Set([page]),

      evidenceText:
        e.evidence,

      pdpEvidenceText:
        null,

      pdpLoaded:
        false,

      pdpStatus:
        null
    };
  }

  /******************************************************************
   * MERGE
   ******************************************************************/

  function mergeProduct(
    old,
    incoming
  ) {
    if (!old)
      return incoming;

    for (
      const page
      of incoming.pages
    ) {
      old.pages.add(page);
    }

    if (
      incoming.name &&
      (
        !old.name ||
        (
          incoming.titleScore ??
          -Infinity
        ) >
        (
          old.titleScore ??
          -Infinity
        )
      )
    ) {
      old.name =
        incoming.name;

      old.titleSource =
        incoming.titleSource;

      old.titleScore =
        incoming.titleScore;
    }

    for (
      const field
      of [
        "brand",

        "karat",
        "fineness",
        "puritySource",

        "weight",
        "weightGrams",
        "unitWeightGrams",
        "quantity",
        "componentWeightsGrams",
        "calculatedWeightGrams",
        "weightSource",
        "weightConfidence",

        "price",
        "mrp",
        "discountAmount",
        "discountPercent",

        "rating",
        "ratingCount",

        "image",
        "link"
      ]
    ) {
      if (
        old[field] == null &&
        incoming[field] != null
      ) {
        old[field] =
          incoming[field];
      }
    }

    if (
      incoming.evidenceText &&
      (
        !old.evidenceText ||
        incoming.evidenceText.length >
          old.evidenceText.length
      )
    ) {
      old.evidenceText =
        incoming.evidenceText;
    }

    old.weightConflict =
      Boolean(
        old.weightConflict ||
        incoming.weightConflict
      );

    return old;
  }

  /******************************************************************
   * INGEST
   ******************************************************************/

  function ingestDocument(
    doc,
    page,
    source,
    meta = {}
  ) {
    const cards =
      cardsFromDocument(doc);

    const before =
      PRODUCTS.size;

    const local =
      new Set();

    for (const card of cards) {
      const product =
        productFromCard(
          card,
          page
        );

      if (!product)
        continue;

      local.add(product.asin);

      PRODUCTS.set(
        product.asin,
        mergeProduct(
          PRODUCTS.get(product.asin),
          product
        )
      );
    }

    const gained =
      PRODUCTS.size - before;

    const row = {
      page,
      source,

      http:
        meta.http ??
        200,

      cards:
        cards.length,

      uniqueOnPage:
        local.size,

      gained,

      cumulative:
        PRODUCTS.size,

      bytes:
        meta.bytes ??
        null,

      ms:
        meta.ms ??
        null
    };

    PAGE_LOG.push(row);

    LOG(
      `PAGE ${page}` +
      ` | ${source}` +
      ` | cards=${cards.length}` +
      ` | +${gained}` +
      ` | UNION=${PRODUCTS.size}`
    );

    return row;
  }

  /******************************************************************
   * SEARCH PAGE URL
   ******************************************************************/

  function makePageURL(page) {
    const u =
      new URL(location.href);

    u.searchParams.set(
      "page",
      String(page)
    );

    u.searchParams.set(
      "ref",
      `sr_pg_${Math.max(
        1,
        page - 1
      )}`
    );

    return u.href;
  }

  /******************************************************************
   * FETCH SEARCH PAGE
   ******************************************************************/

  async function fetchSearchPage(page) {
    const url =
      makePageURL(page);

    for (
      let attempt = 0;
      attempt <= CFG.searchRetries;
      attempt++
    ) {
      const controller =
        new AbortController();

      const timer =
        setTimeout(
          () =>
            controller.abort(),
          CFG.searchTimeoutMs
        );

      const started =
        performance.now();

      try {
        LOG(
          `Page ${page}: fetching${attempt ? " retry" : ""}...`
        );

        const response =
          await fetch(
            url,
            {
              credentials:
                "include",

              cache:
                "no-store",

              signal:
                controller.signal,

              headers: {
                accept:
                  "text/html,application/xhtml+xml"
              }
            }
          );

        const html =
          await response.text();

        clearTimeout(timer);

        const ms =
          Math.round(
            performance.now() -
            started
          );

        const doc =
          new DOMParser()
            .parseFromString(
              html,
              "text/html"
            );

        const cards =
          cardsFromDocument(doc);

        const challenge =
          /validatecaptcha|enter the characters you see below|sorry,\s*we just need to make sure you're not a robot|automated access/i
            .test(html);

        const softBlock =
          response.ok &&
          cards.length === 0 &&
          (
            challenge ||
            html.length <
              CFG.suspiciousSmallHtmlBytes
          );

        if (
          response.ok &&
          cards.length > 0
        ) {
          LOG(
            `Page ${page}: OK` +
            ` | HTTP=${response.status}` +
            ` | cards=${cards.length}` +
            ` | ${ms}ms`
          );

          return {
            ok: true,
            page,

            http:
              response.status,

            bytes:
              html.length,

            cards:
              cards.length,

            ms,

            doc
          };
        }

        if (
          challenge ||
          softBlock
        ) {
          return {
            ok: false,
            page,

            reason:
              "amazon-soft-block",

            http:
              response.status,

            bytes:
              html.length,

            cards:
              cards.length
          };
        }

        if (
          attempt <
          CFG.searchRetries
        ) {
          await sleep(500);
          continue;
        }

        return {
          ok: false,
          page,

          reason:
            "invalid-search-response"
        };

      } catch (error) {
        clearTimeout(timer);

        if (
          attempt <
          CFG.searchRetries
        ) {
          await sleep(500);
          continue;
        }

        return {
          ok: false,
          page,

          reason:
            "network-error",

          error:
            String(
              error?.message ||
              error
            )
        };
      }
    }
  }

  /******************************************************************
   * PDP
   ******************************************************************/

  function collectPDPText(doc) {
    const selectors = [
      "#productTitle",
      "#feature-bullets",
      "#productOverview_feature_div",
      "#detailBullets_feature_div",
      "#detailBulletsWrapper_feature_div",
      "#productDetails_detailBullets_sections1",
      "#productDetails_techSpec_section_1",
      "#productDetails_techSpec_section_2",
      "#productDescription",
      "#aplus"
    ];

    const pieces = [];

    for (const selector of selectors) {
      for (
        const el
        of doc.querySelectorAll(selector)
      ) {
        const text =
          clean(el.textContent);

        if (text)
          pieces.push(text);
      }
    }

    return uniqueStrings(
      pieces
    ).join(" | ");
  }

  function extractPDPPrice(doc) {
    const selectors = [
      "#corePrice_feature_div .priceToPay .a-offscreen",
      "#corePrice_feature_div .a-price .a-offscreen",
      "#corePriceDisplay_desktop_feature_div .a-price .a-offscreen",
      ".priceToPay .a-offscreen",
      "#priceblock_ourprice",
      "#priceblock_dealprice",
      "#price_inside_buybox",
      "#newBuyBoxPrice",
      "[data-a-color='price'] .a-offscreen"
    ];

    for (const selector of selectors) {
      for (
        const el
        of doc.querySelectorAll(selector)
      ) {
        const n =
          num(el.textContent);

        if (
          n != null &&
          n > 100
        ) {
          return n;
        }
      }
    }

    return null;
  }

  async function fetchPDP(product) {
    const controller =
      new AbortController();

    const timer =
      setTimeout(
        () =>
          controller.abort(),
        CFG.pdpTimeoutMs
      );

    const started =
      performance.now();

    try {
      const response =
        await fetch(
          canonicalURL(product.asin),
          {
            credentials:
              "include",

            cache:
              "no-store",

            signal:
              controller.signal,

            headers: {
              accept:
                "text/html,application/xhtml+xml"
            }
          }
        );

      const html =
        await response.text();

      clearTimeout(timer);

      const ms =
        Math.round(
          performance.now() -
          started
        );

      if (
        /validatecaptcha|enter the characters you see below|sorry,\s*we just need to make sure you're not a robot|automated access/i
          .test(html)
      ) {
        return {
          ok: false,
          asin:
            product.asin,
          reason:
            "challenge",
          ms
        };
      }

      const doc =
        new DOMParser()
          .parseFromString(
            html,
            "text/html"
          );

      const text =
        collectPDPText(doc);

      if (!text) {
        return {
          ok: false,
          asin:
            product.asin,
          reason:
            "empty-pdp",
          ms
        };
      }

      const purity =
        parsePurity(text);

      const weight =
        parseWeight(text);

      const price =
        extractPDPPrice(doc);

      return {
        ok: true,

        asin:
          product.asin,

        ms,

        title:
          clean(
            doc.querySelector(
              "#productTitle"
            )?.textContent
          ),

        text,

        karat:
          purity.karat,

        fineness:
          purity.fineness,

        puritySource:
          purity.source,

        weight:
          weight.total,

        weightSource:
          weight.source,

        weightConfidence:
          weight.confidence,

        price
      };

    } catch (error) {
      clearTimeout(timer);

      return {
        ok: false,

        asin:
          product.asin,

        reason:
          "network-error",

        error:
          String(
            error?.message ||
            error
          )
      };
    }
  }

  /******************************************************************
   * RECLASSIFY / REPARSE
   ******************************************************************/

  function reparseCatalogue(catalogue) {
    for (const p of catalogue) {
      const evidence =
        clean(
          [
            p.name,
            p.evidenceText,
            p.pdpEvidenceText
          ]
            .filter(Boolean)
            .join(" | ")
        );

      const classification =
        classifyProductScoped(
          p.name,
          clean([p.evidenceText, p.pdpEvidenceText].filter(Boolean).join(" | "))
        );

      p.classification =
        classification.classification;

      p.classificationReason =
        classification.reason;

      p.metal =
        classification.metal;

      p.productType =
        classification.productType;

      p.isGold =
        classification.isGold;

      p.brand =
        brandOf(p.name) ||
        brandOf(evidence) ||
        p.brand;

      if (p.isGold) {
        let purity =
          parsePurity(p.name);

        if (purity.karat == null && !purity.fineness) {
          purity = parsePurity(evidence);
        }

        if (
          purity.karat != null
        ) {
          p.karat =
            purity.karat;
        }

        if (purity.fineness) {
          p.fineness =
            purity.fineness;
        }

        if (purity.source) {
          p.puritySource =
            purity.source;
        }

      } else {
        p.karat = null;
        p.fineness = null;
        p.puritySource = null;
      }

      const weight =
        parseWeight(evidence);

      if (
        weight.total != null
      ) {
        p.weightGrams =
          weight.total;

        p.weight =
          `${+Number(
              weight.total
            ).toFixed(4)} g`;

        p.unitWeightGrams =
          weight.unit;

        p.quantity =
          weight.quantity;

        p.componentWeightsGrams =
          weight.components;

        p.calculatedWeightGrams =
          weight.calculatedTotal;

        p.weightConflict =
          weight.conflict;

        p.weightSource =
          weight.source;

        p.weightConfidence =
          weight.confidence;
      }

      if (
        p.pages instanceof Set
      ) {
        p.pages =
          [...p.pages]
            .sort(
              (a, b) =>
                a - b
            );
      }
    }
  }

  /******************************************************************
   * VALIDATE
   ******************************************************************/

  function requiresWeightVerification(product) {
    return Boolean(
      product.isGold &&
      product.price != null &&
      product.weightGrams != null &&
      product.price /
        product.weightGrams <
        CFG.minimumPlausiblePricePerGram
    );
  }

  function validate(catalogue) {
    for (const p of catalogue) {
      const issues = [];

      if (p.isGold) {
        if (p.karat == null) {
          issues.push(
            "missing-karat"
          );
        }

        if (
          p.weightGrams == null
        ) {
          issues.push(
            "missing-weight"
          );
        }

        if (p.price == null) {
          issues.push(
            "missing-price"
          );
        }

        if (
          p.karat === 24 &&
          !p.fineness
        ) {
          issues.push(
            "24k-fineness-not-supplied"
          );
        }

        if (p.weightConflict) {
          issues.push(
            "weight-conflict"
          );
        }
      }

      if (
        p.classification ===
        "unknown"
      ) {
        issues.push(
          "classification-unknown"
        );
      }

      p.issues = issues;

      p.incomplete =
        p.isGold &&
        issues.some(
          issue =>
            [
              "missing-karat",
              "missing-weight",
              "missing-price"
            ].includes(issue)
        );

      p.needsReview =
        issues.some(
          issue =>
            [
              "weight-conflict",
              "classification-unknown"
            ].includes(issue)
        );
    }
  }

  /******************************************************************
   * START
   ******************************************************************/

  LOG(
    "================================================"
  );

  LOG(
    "AMAZON GOLD MASTER V14.3 — ALL GOLD"
  );

  LOG(
    "Gold material facet:",
    ACTIVE_GOLD_FACET
  );

  const REPORTED =
    reportedTotal(document);

  const page1 =
    ingestDocument(
      document,
      1,
      "LIVE-DOM"
    );

  const PAGE_SIZE =
    page1.uniqueOnPage ||
    CFG.fallbackPageSize;

  const EXPECTED_PAGES =
    Math.min(
      CFG.maxPages,

      REPORTED != null
        ? Math.ceil(
            REPORTED /
            PAGE_SIZE
          )
        : 1
    );

  LOG(
    "Reported:",
    REPORTED
  );

  LOG(
    "Page size:",
    PAGE_SIZE
  );

  LOG(
    "Expected pages:",
    EXPECTED_PAGES
  );

  let acquisitionStopped = false;
  let acquisitionStopReason = null;
  let stoppedOnPage = null;

  /******************************************************************
   * ACQUIRE COMPLETE CATALOGUE
   ******************************************************************/

  for (
    let page = 2;
    page <= EXPECTED_PAGES;
    page++
  ) {
    if (
      REPORTED != null &&
      PRODUCTS.size >= REPORTED
    ) {
      break;
    }

    if (page > 2) {
      await sleep(
        CFG.searchPageDelayMs
      );
    }

    const result =
      await fetchSearchPage(page);

    if (!result.ok) {
      acquisitionStopped = true;

      acquisitionStopReason =
        result.reason;

      stoppedOnPage =
        page;

      WARN(
        `SAFE STOP PAGE ${page}`,
        result
      );

      break;
    }

    ingestDocument(
      result.doc,
      page,
      "FETCH",
      result
    );

    LOG(
      `PROGRESS: ${PRODUCTS.size}/${REPORTED ?? "?"}`
    );
  }

  /******************************************************************
   * INITIAL PARSE
   ******************************************************************/

  const CATALOGUE =
    [...PRODUCTS.values()];

  reparseCatalogue(
    CATALOGUE
  );

  validate(CATALOGUE);

  /******************************************************************
   * PDP TARGETS
   *
   * All incomplete accepted-gold products.
   *
   * No arbitrary 20-item cap.
   ******************************************************************/

  const PDP_TARGETS =
    CATALOGUE.filter(
      p =>
        p.isGold &&
        (
          requiresWeightVerification(p) ||
          (
            CFG.enablePDP &&
            (
              p.karat == null ||
              p.weightGrams == null ||
              p.price == null
            )
          )
        )
    );

  LOG(
    `TARGETED PDP: ${PDP_TARGETS.length}`
  );

  /******************************************************************
   * PDP ENRICHMENT
   ******************************************************************/

  for (
    let i = 0;
    i < PDP_TARGETS.length;
    i++
  ) {
    const product =
      PDP_TARGETS[i];

    LOG(
      `PDP ${i + 1}/${PDP_TARGETS.length}` +
      ` | ${product.asin}` +
      ` | ${product.brand || ""}`
    );

    const result =
      await fetchPDP(product);

    PDP_LOG.push({
      asin:
        product.asin,

      brand:
        product.brand,

      success:
        result.ok,

      karat:
        result.karat ??
        null,

      fineness:
        result.fineness ??
        null,

      weight:
        result.weight ??
        null,

      price:
        result.price ??
        null,

      reason:
        result.reason ??
        null,

      ms:
        result.ms ??
        null
    });

    if (result.ok) {
      product.pdpLoaded = true;
      product.pdpStatus = "success";

      product.pdpEvidenceText =
        result.text;

      /*
       * Better title if PDP is genuinely better.
       */
      if (
        result.title &&
        (
          !product.name ||
          titleScore(result.title) >
            titleScore(product.name)
        )
      ) {
        product.name =
          result.title;

        product.brand =
          brandOf(result.title) ||
          product.brand;
      }

      /*
       * Fill only missing values.
       */
      if (
        product.karat == null &&
        result.karat != null
      ) {
        product.karat =
          result.karat;

        product.puritySource =
          result.puritySource ||
          "PDP";
      }

      if (
        !product.fineness &&
        result.fineness
      ) {
        product.fineness =
          result.fineness;

        product.puritySource =
          result.puritySource ||
          "PDP";
      }

      if (
        (
          product.weightGrams == null ||
          requiresWeightVerification(product)
        ) &&
        result.weight != null
      ) {
        product.weightGrams =
          result.weight;

        product.weight =
          `${+Number(
              result.weight
            ).toFixed(4)} g`;

        product.unitWeightGrams =
          result.weight;

        product.quantity =
          product.quantity ??
          1;

        product.weightSource =
          result.weightSource ||
          "PDP";

        product.weightConfidence =
          result.weightConfidence ||
          95;
      }

      if (
        product.price == null &&
        result.price != null
      ) {
        product.price =
          result.price;
      }

    } else {
      product.pdpLoaded = false;

      product.pdpStatus =
        result.reason ||
        "failed";
    }

    if (
      i <
      PDP_TARGETS.length - 1
    ) {
      await sleep(
        CFG.pdpDelayMs
      );
    }
  }

  /******************************************************************
   * FINAL PARSE
   ******************************************************************/

  reparseCatalogue(
    CATALOGUE
  );

  for (const product of CATALOGUE) {
    product.weightVerificationRequired =
      requiresWeightVerification(product);
  }

  validate(CATALOGUE);

  /******************************************************************
   * FINAL GROUPS
   ******************************************************************/

  const GOLD_BULLION =
    CATALOGUE.filter(
      p =>
        p.classification ===
        "gold-bullion"
    );

  const GOLD_JEWELLERY =
    CATALOGUE.filter(
      p =>
        p.classification ===
        "gold-jewellery"
    );

  const GOLD_OTHER =
    CATALOGUE.filter(
      p =>
        p.classification ===
        "gold-other"
    );

  /*
   * PRIMARY OUTPUT:
   *
   * ALL accepted gold.
   *
   * No 22K / 24K filter.
   */
  const GOLD_PRODUCTS = [
    ...GOLD_BULLION,
    ...GOLD_JEWELLERY,
    ...GOLD_OTHER
  ];

  /*
   * Safety dedupe by ASIN.
   */
  const GOLD_UNIQUE = [
    ...new Map(
      GOLD_PRODUCTS.map(
        p => [
          p.asin,
          p
        ]
      )
    ).values()
  ];

  const PUBLISHABLE_GOLD =
    GOLD_UNIQUE.filter(
      product =>
        !product.weightVerificationRequired
    );

  const EXCLUDED =
    CATALOGUE.filter(
      p =>
        !GOLD_UNIQUE.some(
          g =>
            g.asin === p.asin
        )
    );

  const PLATED =
    EXCLUDED.filter(
      p =>
        /plated|coated|tone/i
          .test(
            p.classificationReason ||
            ""
          )
    );

  const COLLECTIBLES =
    EXCLUDED.filter(
      p =>
        p.classification ===
        "non-bullion-collectible"
    );

  const UNKNOWN =
    EXCLUDED.filter(
      p =>
        p.classification ===
        "unknown"
    );

  const INCOMPLETE =
    GOLD_UNIQUE.filter(
      p =>
        p.incomplete
    );

  const REVIEW =
    CATALOGUE.filter(
      p =>
        p.needsReview
    );

  /******************************************************************
   * SORT
   ******************************************************************/

  GOLD_UNIQUE.sort(
    (a, b) =>
      (
        b.karat ??
        -Infinity
      ) -
      (
        a.karat ??
        -Infinity
      ) ||
      (
        a.weightGrams ??
        Infinity
      ) -
      (
        b.weightGrams ??
        Infinity
      ) ||
      String(a.asin)
        .localeCompare(
          String(b.asin)
        )
  );

  PAGE_LOG.sort(
    (a, b) =>
      a.page - b.page
  );

  /******************************************************************
   * KARAT DISTRIBUTION
   ******************************************************************/

  const KARAT_COUNTS = {};

  for (const p of GOLD_UNIQUE) {
    const key =
      p.karat != null
        ? `${p.karat}K`
        : "unknown";

    KARAT_COUNTS[key] =
      (
        KARAT_COUNTS[key] ||
        0
      ) + 1;
  }

  /******************************************************************
   * ROWS
   ******************************************************************/

  function rows(array) {
    return array.map(
      p => ({
        asin:
          p.asin,

        brand:
          p.brand,

        name:
          p.name,

        classification:
          p.classification,

        classificationReason:
          p.classificationReason,

        metal:
          p.metal,

        productType:
          p.productType,

        karat:
          p.karat,

        fineness:
          p.fineness,

        puritySource:
          p.puritySource,

        weight:
          p.weight,

        weightGrams:
          p.weightGrams,

        unitWeightGrams:
          p.unitWeightGrams,

        quantity:
          p.quantity,

        components:
          Array.isArray(
            p.componentWeightsGrams
          )
            ? p.componentWeightsGrams
                .join(" + ")
            : null,

        weightSource:
          p.weightSource,

        weightConfidence:
          p.weightConfidence,

        weightConflict:
          p.weightConflict,

        price:
          p.price,

        mrp:
          p.mrp,

        discountAmount:
          p.discountAmount,

        discountPercent:
          p.discountPercent,

        rating:
          p.rating,

        ratingCount:
          p.ratingCount,

        pdpLoaded:
          p.pdpLoaded,

        pdpStatus:
          p.pdpStatus,

        pages:
          Array.isArray(p.pages)
            ? p.pages.join(" | ")
            : p.pages,

        issues:
          p.issues
            ?.join(" | ") ||
          "",

        image:
          p.image,

        link:
          p.link
      })
    );
  }

  /******************************************************************
   * STATS
   ******************************************************************/

  const elapsedMs =
    Math.round(
      performance.now() -
      STARTED_AT
    );

  const stats = {
    reportedTotal:
      REPORTED,

    expectedPages:
      EXPECTED_PAGES,

    catalogue:
      CATALOGUE.length,

    differenceVsReported:
      REPORTED != null
        ? CATALOGUE.length -
          REPORTED
        : null,

    catalogueComplete:
      REPORTED != null &&
      CATALOGUE.length ===
        REPORTED &&
      !acquisitionStopped,

    acquisitionStopped,

    acquisitionStopReason,

    stoppedOnPage,

    /*
     * MAIN NUMBER
     */
    allGold:
      GOLD_UNIQUE.length,

    goldBullion:
      GOLD_BULLION.length,

    goldJewellery:
      GOLD_JEWELLERY.length,

    goldOther:
      GOLD_OTHER.length,

    excluded:
      EXCLUDED.length,

    platedExcluded:
      PLATED.length,

    collectibleExcluded:
      COLLECTIBLES.length,

    unknown:
      UNKNOWN.length,

    karatCounts:
      KARAT_COUNTS,

    incomplete:
      INCOMPLETE.length,

    missingKarat:
      GOLD_UNIQUE.filter(
        p =>
          p.karat == null
      ).length,

    missingWeight:
      GOLD_UNIQUE.filter(
        p =>
          p.weightGrams == null
      ).length,

    missingPrice:
      GOLD_UNIQUE.filter(
        p =>
          p.price == null
      ).length,

    missingFineness:
      GOLD_UNIQUE.filter(
        p =>
          !p.fineness
      ).length,

    weightConflicts:
      GOLD_UNIQUE.filter(
        p =>
          p.weightConflict
      ).length,

    pdpTargets:
      PDP_TARGETS.length,

    pdpSuccess:
      PDP_LOG.filter(
        x => x.success
      ).length,

    pdpFailed:
      PDP_LOG.filter(
        x => !x.success
      ).length,

      withheldUnverifiedWeights:
        GOLD_UNIQUE.length -
        PUBLISHABLE_GOLD.length,

    elapsedMs,

    elapsedSeconds:
      +(elapsedMs / 1000)
        .toFixed(2)
  };

  /******************************************************************
   * GLOBALS
   ******************************************************************/

  window.amazonStats =
    stats;

  window.amazonCatalogue =
    CATALOGUE;

  /*
   * MAIN OUTPUT = ALL GOLD
   */
  window.amazonProducts =
    PUBLISHABLE_GOLD;

  window.amazonGold =
    PUBLISHABLE_GOLD;

  window.amazonGoldProducts =
    PUBLISHABLE_GOLD;

  /*
   * SUBGROUPS
   */
  window.amazonGoldBullion =
    GOLD_BULLION;

  window.amazonGoldJewellery =
    GOLD_JEWELLERY;

  window.amazonGoldOther =
    GOLD_OTHER;

  window.amazonExcluded =
    EXCLUDED;

  window.amazonPlated =
    PLATED;

  window.amazonCollectibles =
    COLLECTIBLES;

  window.amazonUnknown =
    UNKNOWN;

  window.amazonIncomplete =
    INCOMPLETE;

  window.amazonReview =
    REVIEW;

  window.amazonPageLog =
    PAGE_LOG;

  window.amazonPdpLog =
    PDP_LOG;

  const bridgeResult = await fetch("http://localhost:8788/api/browser-bridge/products", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      store: "amazon.in",
      records: PUBLISHABLE_GOLD.map(p => ({
        bridgeSnapshot: true,
        asin: p.asin,
        url: p.url || p.link || canonicalURL(p.asin),
        name: p.name,
        brand: p.brand,
        price: p.price,
        metal: "gold",
        grams: p.weightGrams,
        karat: p.karat,
        purity: p.fineness
      }))
    })
  }).then(r => r.json()).catch(error => ({ error: String(error) }));
  console.log("Aurum Amazon bridge:", bridgeResult);

  /******************************************************************
   * TABLES
   ******************************************************************/

  /*
   * MAIN TABLE = ALL GOLD
   */
  window.amazonTable =
    () => {
      console.table(
        rows(GOLD_UNIQUE)
      );

      return GOLD_UNIQUE;
    };

  window.amazonGoldTable =
    window.amazonTable;

  window.amazonBullionTable =
    () => {
      console.table(
        rows(GOLD_BULLION)
      );

      return GOLD_BULLION;
    };

  window.amazonJewelleryTable =
    () => {
      console.table(
        rows(GOLD_JEWELLERY)
      );

      return GOLD_JEWELLERY;
    };

  window.amazonOtherGoldTable =
    () => {
      console.table(
        rows(GOLD_OTHER)
      );

      return GOLD_OTHER;
    };

  window.amazonExcludedTable =
    () => {
      console.table(
        rows(EXCLUDED)
      );

      return EXCLUDED;
    };

  window.amazonPlatedTable =
    () => {
      console.table(
        rows(PLATED)
      );

      return PLATED;
    };

  window.amazonCollectibleTable =
    () => {
      console.table(
        rows(COLLECTIBLES)
      );

      return COLLECTIBLES;
    };

  window.amazonUnknownTable =
    () => {
      console.table(
        rows(UNKNOWN)
      );

      return UNKNOWN;
    };

  window.amazonIncompleteTable =
    () => {
      console.table(
        rows(INCOMPLETE)
      );

      return INCOMPLETE;
    };

  window.amazonReviewTable =
    () => {
      console.table(
        rows(REVIEW)
      );

      return REVIEW;
    };

  window.amazonCatalogueTable =
    () => {
      console.table(
        rows(CATALOGUE)
      );

      return CATALOGUE;
    };

  window.amazonPageTable =
    () => {
      console.table(PAGE_LOG);

      return PAGE_LOG;
    };

  window.amazonPdpTable =
    () => {
      console.table(PDP_LOG);

      return PDP_LOG;
    };

  window.amazonKaratTable =
    () => {
      const table =
        Object.entries(
          KARAT_COUNTS
        )
          .map(
            ([karat, count]) => ({
              karat,
              count
            })
          )
          .sort(
            (a, b) =>
              (
                parseFloat(b.karat) ||
                -1
              ) -
              (
                parseFloat(a.karat) ||
                -1
              )
          );

      console.table(table);

      return table;
    };

  /******************************************************************
   * FIND / PARSERS
   ******************************************************************/

  window.amazonFind =
    asin => {
      const p =
        CATALOGUE.find(
          x =>
            x.asin ===
            String(asin)
              .toUpperCase()
        );

      console.log(p);

      return p;
    };

  window.amazonParseWeight =
    parseWeight;

  window.amazonParsePurity =
    parsePurity;

  window.amazonClassify =
    text => {
      const result =
        classifyProductScoped(text);

      console.log(result);

      return result;
    };

  /******************************************************************
   * DIAGNOSTIC
   ******************************************************************/

  window.amazonDiagnostic =
    () => {
      LOG(
        "=== AMAZON V14.3 DIAGNOSTIC ==="
      );

      console.log(stats);

      LOG("KARATS");
      amazonKaratTable();

      LOG("INCOMPLETE");
      amazonIncompleteTable();

      LOG("PLATED EXCLUDED");
      amazonPlatedTable();

      LOG("UNKNOWN");
      amazonUnknownTable();

      return {
        stats,

        gold:
          GOLD_UNIQUE,

        bullion:
          GOLD_BULLION,

        jewellery:
          GOLD_JEWELLERY,

        otherGold:
          GOLD_OTHER,

        excluded:
          EXCLUDED,

        incomplete:
          INCOMPLETE
      };
    };

  /******************************************************************
   * CSV / JSON
   ******************************************************************/

  const CSV_FIELDS = [
    "asin",
    "brand",
    "name",

    "classification",
    "classificationReason",

    "metal",
    "productType",

    "karat",
    "fineness",
    "puritySource",

    "weight",
    "weightGrams",
    "unitWeightGrams",
    "quantity",
    "components",
    "weightSource",
    "weightConfidence",
    "weightConflict",

    "price",
    "mrp",
    "discountAmount",
    "discountPercent",

    "rating",
    "ratingCount",

    "pdpLoaded",
    "pdpStatus",

    "pages",
    "issues",

    "image",
    "link"
  ];

  function csvEscape(value) {
    return (
      '"' +
      String(value ?? "")
        .replace(
          /"/g,
          '""'
        ) +
      '"'
    );
  }

  function makeCSV(array) {
    const data =
      rows(array);

    return [
      CSV_FIELDS
        .map(csvEscape)
        .join(","),

      ...data.map(
        row =>
          CSV_FIELDS
            .map(
              field =>
                csvEscape(
                  row[field]
                )
            )
            .join(",")
      )
    ].join("\n");
  }

  function download(
    content,
    filename,
    type
  ) {
    const blob =
      new Blob(
        [content],
        { type }
      );

    const url =
      URL.createObjectURL(blob);

    const a =
      document.createElement("a");

    a.href = url;
    a.download = filename;

    document.body.appendChild(a);

    a.click();
    a.remove();

    setTimeout(
      () =>
        URL.revokeObjectURL(url),
      1000
    );
  }

  /*
   * MAIN EXPORT = ALL GOLD
   */
  window.amazonDownloadCSV =
    () =>
      download(
        "\uFEFF" +
        makeCSV(GOLD_UNIQUE),

        `amazon-all-gold-${GOLD_UNIQUE.length}.csv`,

        "text/csv;charset=utf-8"
      );

  window.amazonDownloadJSON =
    () =>
      download(
        JSON.stringify(
          rows(GOLD_UNIQUE),
          null,
          2
        ),

        `amazon-all-gold-${GOLD_UNIQUE.length}.json`,

        "application/json;charset=utf-8"
      );

  window.amazonDownloadCatalogueCSV =
    () =>
      download(
        "\uFEFF" +
        makeCSV(CATALOGUE),

        `amazon-catalogue-${CATALOGUE.length}.csv`,

        "text/csv;charset=utf-8"
      );

  window.amazonDownloadExcludedCSV =
    () =>
      download(
        "\uFEFF" +
        makeCSV(EXCLUDED),

        `amazon-excluded-${EXCLUDED.length}.csv`,

        "text/csv;charset=utf-8"
      );

  window.amazonCopyCSV =
    async () => {
      await navigator.clipboard
        .writeText(
          makeCSV(GOLD_UNIQUE)
        );

      LOG(
        "ALL-GOLD CSV copied."
      );
    };

  window.amazonCopyJSON =
    async () => {
      await navigator.clipboard
        .writeText(
          JSON.stringify(
            rows(GOLD_UNIQUE),
            null,
            2
          )
        );

      LOG(
        "ALL-GOLD JSON copied."
      );
    };

  /******************************************************************
   * SELF TESTS
   ******************************************************************/

  const CLASS_TESTS = [
    {
      text:
        "Bangalore Refinery 24K 999.9 Gold Bar cum Coin 2g",

      expected:
        "gold-bullion"
    },

    {
      text:
        "18K Yellow Gold Diamond Pendant",

      expected:
        "gold-jewellery"
    },

    {
      text:
        "14KT BIS Hallmarked Gold Ring",

      expected:
        "gold-jewellery"
    },

    {
      text:
        "18K Gold Plated Brass Necklace",

      expected:
        "non-gold"
    },

    {
      text:
        "Gold Coated Silver Coin",

      expected:
        "non-gold"
    },

    {
      text:
        "Gold Tone Stainless Steel Pendant",

      expected:
        "non-gold"
    },

    {
      text:
        "Collectors Special Finland Modern Issue Limited Edition Coin",

      expected:
        "non-bullion-collectible"
    },

    {
      text:
        "ASPECT BULLION Miligram Gold Bar Kalpavruksha Design, 24K (995) Pure Gold Foil",

      expected:
        "gold-bullion"
    },

    {
      text:
        "Bangalore Refinery Yellow Gold Pendants (without hook) 22K (916)",

      expected:
        "gold-jewellery"
    }
  ];

  const classificationTests =
    CLASS_TESTS.map(t => {
      const result =
        classifyProductScoped(t.text);

      return {
        text:
          t.text,

        result:
          result.classification,

        expected:
          t.expected,

        pass:
          result.classification ===
          t.expected
      };
    });

  const WEIGHT_TESTS = [
    ["1GM", "24K Gold Coin 1GM", 1],

    ["2Gram", "22K Gold Coin 2Gram", 2],

    ["500mg", "24K Gold Coin 500mg", 0.5],

    [
      "components",
      "24K Gold Bar 2Gram + 1Gram",
      3
    ],

    [
      "each",
      "25 Gm (5gm each x 5 Pcs)",
      25
    ],

    [
      "named combo 2.5+2.5",
      "2.50gm Ganesh + 2.50gm Lakshmi Combo",
      5
    ],

    [
      "named combo 2+2",
      "2gm Ganesh + 2gm Lakshmi Combo",
      4
    ]
  ];

  const weightTests =
    WEIGHT_TESTS.map(
      ([test, input, expected]) => {
        const result =
          parseWeight(input);

        return {
          test,

          result:
            result.total,

          expected,

          pass:
            result.total != null &&
            Math.abs(
              result.total -
              expected
            ) < 0.0001,

          source:
            result.source
        };
      }
    );

  /******************************************************************
   * FINAL OUTPUT
   ******************************************************************/

  LOG("");

  LOG(
    "================================================"
  );

  LOG(
    "AMAZON GOLD MASTER V14.3 COMPLETE"
  );

  LOG(
    "================================================"
  );

  LOG(
    "STATS:",
    stats
  );

  LOG("");

  console.table(
    PAGE_LOG.map(
      p => ({
        page:
          p.page,

        source:
          p.source,

        http:
          p.http,

        cards:
          p.cards,

        gained:
          p.gained,

        cumulative:
          p.cumulative,

        bytes:
          p.bytes,

        ms:
          p.ms
      })
    )
  );

  LOG("");

  if (
    classificationTests.every(
      x => x.pass
    )
  ) {
    LOG(
      "CLASSIFICATION TESTS PASSED"
    );
  } else {
    WARN(
      "CLASSIFICATION TEST FAILURE"
    );
  }

  console.table(
    classificationTests
  );

  if (
    weightTests.every(
      x => x.pass
    )
  ) {
    LOG(
      "WEIGHT TESTS PASSED"
    );
  } else {
    WARN(
      "WEIGHT TEST FAILURE"
    );
  }

  console.table(
    weightTests
  );

  LOG("");

  if (stats.catalogueComplete) {
    LOG(
      `CATALOGUE COMPLETE: ${stats.catalogue}/${stats.reportedTotal}`
    );
  } else {
    WARN(
      `CATALOGUE: ${stats.catalogue}/${stats.reportedTotal ?? "?"}`
    );
  }

  LOG(
    `ALL GOLD: ${stats.allGold}`
  );

  LOG(
    `  Bullion: ${stats.goldBullion}`
  );

  LOG(
    `  Jewellery: ${stats.goldJewellery}`
  );

  LOG(
    `  Other gold: ${stats.goldOther}`
  );

  LOG(
    `EXCLUDED: ${stats.excluded}`
  );

  LOG(
    `  Plated/coated/tone: ${stats.platedExcluded}`
  );

  LOG(
    `  Collectibles: ${stats.collectibleExcluded}`
  );

  LOG(
    `UNKNOWN: ${stats.unknown}`
  );

  LOG("");

  LOG(
    "KARAT DISTRIBUTION:"
  );

  console.table(
    Object.entries(
      KARAT_COUNTS
    ).map(
      ([karat, count]) => ({
        karat,
        count
      })
    )
  );

  LOG("");

  LOG(
    `INCOMPLETE GOLD: ${stats.incomplete}`
  );

  LOG(
    `Missing karat: ${stats.missingKarat}`
  );

  LOG(
    `Missing weight: ${stats.missingWeight}`
  );

  LOG(
    `Missing price: ${stats.missingPrice}`
  );

  LOG(
    `PDP: ${stats.pdpSuccess}/${stats.pdpTargets}`
  );

  LOG(
    `TIME: ${stats.elapsedSeconds}s`
  );

  LOG("");

  LOG(
    "MAIN OUTPUT = ALL GOLD"
  );

  LOG("");

  LOG("COMMANDS:");

  LOG(
    "window.amazonStats"
  );

  LOG(
    "window.amazonProducts"
  );

  LOG("");

  LOG(
    "amazonTable()"
  );

  LOG(
    "amazonKaratTable()"
  );

  LOG(
    "amazonBullionTable()"
  );

  LOG(
    "amazonJewelleryTable()"
  );

  LOG(
    "amazonOtherGoldTable()"
  );

  LOG(
    "amazonIncompleteTable()"
  );

  LOG(
    "amazonExcludedTable()"
  );

  LOG(
    "amazonPlatedTable()"
  );

  LOG(
    "amazonCollectibleTable()"
  );

  LOG(
    "amazonUnknownTable()"
  );

  LOG(
    "amazonPageTable()"
  );

  LOG(
    "amazonPdpTable()"
  );

  LOG(
    "amazonDiagnostic()"
  );

  LOG("");

  LOG(
    'amazonClassify("18K Yellow Gold Diamond Ring")'
  );

  LOG(
    'amazonClassify("18K Gold Plated Brass Ring")'
  );

  LOG("");

  LOG(
    "amazonDownloadCSV()  // ALL GOLD"
  );

  LOG(
    "amazonDownloadJSON()"
  );

  LOG(
    "amazonDownloadCatalogueCSV()"
  );

  LOG(
    "amazonDownloadExcludedCSV()"
  );

  LOG(
    "amazonCopyCSV()"
  );

  LOG(
    "amazonCopyJSON()"
  );

  LOG(
    "================================================"
  );

  return {
    stats,

    gold:
      GOLD_UNIQUE,

    bullion:
      GOLD_BULLION,

    jewellery:
      GOLD_JEWELLERY,

    otherGold:
      GOLD_OTHER,

    excluded:
      EXCLUDED,

    incomplete:
      INCOMPLETE
  };
})();