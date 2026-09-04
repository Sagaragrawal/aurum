(async () => {
  "use strict";

  const navigation = performance.getEntriesByType("navigation")[0];
  if (!globalThis.__aurumMasterRunner && navigation?.type !== "reload") {
    console.info("Refreshing Flipkart before extraction. Run this script again after the page reloads.");
    location.reload();
    return;
  }

  console.clear();

  /********************************************************************
   * FLIPKART COINS & BARS — MASTER EXTRACTOR
   * ================================================================
   *
   * DISCOVERY
   * ----------
   * Uses:
   *   1. default/relevance
   *   2. price_asc
   *   3. price_desc
   *
   * Previous testing:
   *   default       ~237 unique
   *   + price_asc   ~431 union
   *   + price_desc   476 union
   *
   * IMPORTANT
   * ---------
   * The catalogue count is taken ONLY from the page you actually
   * loaded in the browser.
   *
   * Synthetic page/sort responses sometimes display bogus counts
   * such as 520, 560, etc. Those DO NOT overwrite the canonical count.
   *
   * OUTPUT
   * ------
   * pid
   * brand
   * name
   * metal
   * weight
   * weightGrams
   * unitWeightGrams
   * quantity
   * totalWeightGrams
   * componentWeightsGrams
   * karat
   * fineness
   * puritySource
   * price
   * mrp
   * discountPercent
   * rating
   * streams
   * image
   * link
   *
   * PDP HTML is fetched ONLY for rows that remain missing/suspicious.
   ********************************************************************/

  const CFG = {
    streams: [
      {
        name: "default",
        sort: null
      },
      {
        name: "price_low",
        sort: "price_asc"
      },
      {
        name: "price_high",
        sort: "price_desc"
      },
      {
        name: "popularity",
        sort: "popularity",
        fallback: true
      },
      {
        name: "newest",
        sort: "recency_desc",
        fallback: true
      }
    ],

    maxPagesPerStream: 30,

    /*
     * We intentionally crawl sequentially inside each stream.
     * The catalogue is small enough and this is gentler on Flipkart.
     */
    listingDelayMs: 0,

    listingRetries: 2,
    retryBaseMs: 700,

    emptyPagesToStop: 2,

    /*
     * Once the union equals the canonical count from the original
     * page, catalogue discovery is finished.
     */
    stopAtCanonicalCount: true,

    /*
     * PDP enrichment is tiny because listing data is usually complete.
     */
    enablePdpFallback: false,
    pdpConcurrency: 3,
    pdpDelayMs: 150,

    /*
     * Fetch PDP for suspicious values as well as nulls.
     */
    enrichSuspicious: true
  };

  /********************************************************************
   * GENERIC HELPERS
   ********************************************************************/

  const sleep = ms =>
    new Promise(resolve => setTimeout(resolve, ms));

  const clean = value =>
    String(value ?? "")
      .replace(/\u00a0/g, " ")
      .replace(/\s+/g, " ")
      .trim();

  function numberFrom(value) {
    if (
      value === null ||
      value === undefined ||
      value === ""
    ) {
      return null;
    }

    if (
      typeof value === "number"
    ) {
      return Number.isFinite(value)
        ? value
        : null;
    }

    const match =
      String(value)
        .replace(/,/g, "")
        .match(
          /-?\d+(?:\.\d+)?/
        );

    return match
      ? Number(match[0])
      : null;
  }

  function moneyFrom(value) {
    if (!value)
      return null;

    const match =
      String(value)
        .replace(/,/g, "")
        .match(
          /₹?\s*(\d+(?:\.\d+)?)/
        );

    if (!match)
      return null;

    const valueNumber =
      Number(match[1]);

    return Number.isFinite(
      valueNumber
    )
      ? valueNumber
      : null;
  }

  function unique(values) {
    return [
      ...new Set(
        values.filter(
          value =>
            value !== null &&
            value !== undefined &&
            value !== ""
        )
      )
    ];
  }

  function absoluteURL(href) {
    if (!href)
      return null;

    try {
      return new URL(
        href,
        location.origin
      ).href;
    } catch {
      return null;
    }
  }

  /********************************************************************
   * CLEAN PRODUCT LINK
   ********************************************************************/

  function cleanProductURL(
    href,
    pid
  ) {
    if (!href)
      return null;

    try {
      const url =
        new URL(
          href,
          location.origin
        );

      /*
       * Strip tracking parameters.
       */

      [
        "otracker",
        "otracker1",
        "lid",
        "fm",
        "ppt",
        "ppn",
        "srno",
        "spotlightTagId"
      ].forEach(
        key =>
          url.searchParams.delete(
            key
          )
      );

      if (
        pid &&
        !url.searchParams.get(
          "pid"
        )
      ) {
        url.searchParams.set(
          "pid",
          pid
        );
      }

      if (
        !url.searchParams.get(
          "marketplace"
        )
      ) {
        url.searchParams.set(
          "marketplace",
          "FLIPKART"
        );
      }

      return url.href;

    } catch {
      return href;
    }
  }

  /********************************************************************
   * CANONICAL REPORTED TOTAL
   ********************************************************************/

  function detectReportedTotal(
    doc
  ) {
    const text =
      clean(
        doc?.body
          ?.innerText ||
        doc?.body
          ?.textContent ||
        ""
      );

    const patterns = [
      /Showing\s+[\d,]+\s*[–—-]\s*[\d,]+\s+products?\s+of\s+([\d,]+)\s+products?/i,

      /Showing\s+[\d,]+\s*[–—-]\s*[\d,]+\s+of\s+([\d,]+)/i,

      /\bof\s+([\d,]+)\s+products?\b/i
    ];

    for (
      const pattern
      of patterns
    ) {
      const match =
        text.match(pattern);

      if (!match)
        continue;

      const value =
        Number(
          match[1]
            .replace(/,/g, "")
        );

      if (
        Number.isFinite(value) &&
        value > 0
      ) {
        return value;
      }
    }

    return null;
  }

  /*
   * THIS IS THE AUTHORITATIVE COUNT.
   *
   * It will never be replaced by counts returned from synthetic
   * sort/page requests.
   */

  const canonicalReportedTotal =
    detectReportedTotal(
      document
    );

  /********************************************************************
   * FINENESS / KARAT
   ********************************************************************/

  function normalizeFineness(
    raw
  ) {
    if (
      raw === null ||
      raw === undefined
    ) {
      return null;
    }

    let value =
      String(raw)
        .trim()
        .replace(/,/g, ".")
        .replace(/[^\d.]/g, "");

    if (!value)
      return null;

    /*
     * Flipkart representations:
     *
     * 9999 => 999.9
     * 9167 => 916.7
     */

    if (value === "9999")
      return "999.9";

    if (value === "9167")
      return "916.7";

    const numeric =
      Number(value);

    if (
      !Number.isFinite(
        numeric
      )
    ) {
      return null;
    }

    if (
      numeric >= 1000 &&
      numeric <= 9999
    ) {
      return String(
        numeric / 10
      );
    }

    if (
      numeric >= 300 &&
      numeric <= 1000
    ) {
      return String(
        numeric
      );
    }

    return null;
  }

  function standardFineness(
    karat
  ) {
    /*
     * 24K deliberately excluded.
     *
     * This catalogue contains explicit:
     *   24K 995
     *   24K 999
     *   24K 999.9
     */

    return ({
      23: "958",
      22: "916",
      21: "875",
      20: "833",
      18: "750",
      14: "585",
      10: "417",
      9: "375"
    })[karat] ?? null;
  }

  function karatFromFineness(
    fineness
  ) {
    if (!fineness)
      return null;

    const value =
      Number(fineness);

    if (
      !Number.isFinite(value)
    ) {
      return null;
    }

    if (value >= 990)
      return 24;

    if (
      value >= 957 &&
      value <= 959
    ) {
      return 23;
    }

    if (
      value >= 915 &&
      value <= 918
    ) {
      return 22;
    }

    if (
      value >= 874 &&
      value <= 876
    ) {
      return 21;
    }

    if (
      value >= 832 &&
      value <= 834
    ) {
      return 20;
    }

    if (
      value >= 749 &&
      value <= 751
    ) {
      return 18;
    }

    if (
      value >= 584 &&
      value <= 586
    ) {
      return 14;
    }

    if (
      value >= 416 &&
      value <= 418
    ) {
      return 10;
    }

    if (
      value >= 374 &&
      value <= 376
    ) {
      return 9;
    }

    return null;
  }

  /********************************************************************
   * PURITY PARSER
   ********************************************************************/

  function parsePurity(
    text
  ) {
    const sourceText =
      clean(text);

    let karat = null;
    let fineness = null;
    let puritySource = null;

    let match;

    /*
     * --------------------------------------------------------------
     * 24 (999) K
     * 24 (995) K
     * 24 (9999) K
     * 22 (916.7) K
     * --------------------------------------------------------------
     */

    match =
      sourceText.match(
        /\b(24|23|22|21|20|18|14|10|9)\s*\(\s*(9999|999\.9\+?|999|995|990|958|916\.7|9167|916|875|833|750|585|417|375)\s*\)\s*(?:k|kt|karat)\b/i
      );

    if (match) {
      return {
        karat:
          Number(match[1]),

        fineness:
          normalizeFineness(
            match[2]
          ),

        puritySource:
          "explicit-karat-fineness"
      };
    }

    /*
     * --------------------------------------------------------------
     * 24KT (999)
     * --------------------------------------------------------------
     */

    match =
      sourceText.match(
        /\b(24|23|22|21|20|18|14|10|9)\s*(?:kt|k|karat)\s*\(\s*(9999|999\.9\+?|999|995|990|958|916\.7|9167|916|875|833|750|585|417|375)\s*\)/i
      );

    if (match) {
      return {
        karat:
          Number(match[1]),

        fineness:
          normalizeFineness(
            match[2]
          ),

        puritySource:
          "explicit-karat-fineness"
      };
    }

    /*
     * --------------------------------------------------------------
     * 24K 999
     * 22K 916
     * --------------------------------------------------------------
     */

    match =
      sourceText.match(
        /\b(24|23|22|21|20|18|14|10|9)\s*(?:kt|k|karat)\s*[-:/]?\s*(9999|999\.9\+?|999|995|990|958|916\.7|9167|916|875|833|750|585|417|375)\b/i
      );

    if (match) {
      return {
        karat:
          Number(match[1]),

        fineness:
          normalizeFineness(
            match[2]
          ),

        puritySource:
          "explicit-karat-fineness"
      };
    }

    /*
     * --------------------------------------------------------------
     * KARAT ALONE
     * --------------------------------------------------------------
     */

    match =
      sourceText.match(
        /\b(24|23|22|21|20|18|14|10|9)\s*(?:kt|k|karat|carat)\b/i
      );

    if (match) {
      karat =
        Number(match[1]);

      puritySource =
        "karat";
    }

    /*
     * --------------------------------------------------------------
     * EXPLICIT FINENESS
     *
     * 999 fine
     * 995 purity
     * --------------------------------------------------------------
     */

    match =
      sourceText.match(
        /\b(9999|999\.9\+?|999|995|990|958|916\.7|9167|916|875|833|750|585|417|375)\s*(?:fine|fineness|purity)\b/i
      );

    if (match) {
      fineness =
        normalizeFineness(
          match[1]
        );

      puritySource =
        "explicit-fineness";
    }

    /*
     * --------------------------------------------------------------
     * EMBEDDED FINENESS
     *
     * Example:
     *
     * AGC1GMGANESH995 1 g Gold Coin
     *
     * Only do this in GOLD context.
     * --------------------------------------------------------------
     */

    if (
      !fineness &&
      /\bgold\b/i.test(
        sourceText
      )
    ) {
      const embedded =
        sourceText.match(
          /(?:^|[^0-9])(9999|999|995|9167|916)(?=$|[^0-9])/i
        );

      if (embedded) {
        fineness =
          normalizeFineness(
            embedded[1]
          );

        puritySource =
          "embedded-fineness";
      }
    }

    /*
     * --------------------------------------------------------------
     * FINENESS -> KARAT
     * --------------------------------------------------------------
     */

    if (
      karat == null &&
      fineness
    ) {
      karat =
        karatFromFineness(
          fineness
        );
    }

    /*
     * --------------------------------------------------------------
     * STANDARD FINENESS
     *
     * User rule:
     *
     * 22K with no explicit fineness => 916.
     *
     * Explicit 916.7 remains 916.7.
     * --------------------------------------------------------------
     */

    if (
      !fineness &&
      karat != null &&
      karat !== 24
    ) {
      const standard =
        standardFineness(
          karat
        );

      if (standard) {
        fineness =
          standard;

        puritySource =
          "karat-standard";
      }
    }

    return {
      karat,
      fineness,
      puritySource
    };
  }

  /********************************************************************
   * WEIGHT
   ********************************************************************/

  function toGrams(
    value,
    unit
  ) {
    const numeric =
      Number(value);

    if (
      !Number.isFinite(
        numeric
      )
    ) {
      return null;
    }

    const normalizedUnit =
      String(unit || "")
        .toLowerCase()
        .replace(/\./g, "");

    if (
      normalizedUnit === "mg" ||
      normalizedUnit ===
        "milligram" ||
      normalizedUnit ===
        "milligrams"
    ) {
      return numeric / 1000;
    }

    return numeric;
  }

  /********************************************************************
   * WEIGHT PARSER
   *
   * CRITICAL RULE:
   *
   * Flipkart's final normalized title weight:
   *
   *    "... 0.75 g Gold Coin"
   *    "... 1 g Gold Coin"
   *    "... 6 g Gold Bar"
   *
   * is treated as the TOTAL SELLABLE PRODUCT WEIGHT.
   *
   * Therefore:
   *
   *   "250 MG ... 0.5 g Gold Coin Pack of 2"
   *
   * total = 0.5g
   *
   * NOT:
   *
   * 0.5 * 2 = 1g
   ********************************************************************/

  function parseWeight(
    text
  ) {
    const original =
      clean(text);

    const sourceText =
      original
        .toLowerCase()
        .replace(
          /\bgrams?\b/g,
          " g "
        )
        .replace(
          /\bgms?\b/g,
          " g "
        )
        .replace(
          /\bmilligrams?\b/g,
          " mg "
        )
        .replace(
          /\bmgs?\b/g,
          " mg "
        )
        .replace(
          /\s+/g,
          " "
        )
        .trim();

    /*
     * --------------------------------------------------------------
     * STEP 1
     *
     * FLIPKART NORMALIZED TOTAL
     * --------------------------------------------------------------
     */

    const normalizedMatches =
      [
        ...sourceText.matchAll(
          /(\d+(?:\.\d+)?)\s*(mg|g)\s+(?:yellow\s+)?gold\s+(?:coin|bar|vedhani|biscuit)\b/gi
        )
      ];

    let normalizedTotal =
      null;

    if (
      normalizedMatches.length
    ) {
      const match =
        normalizedMatches[
          normalizedMatches.length -
          1
        ];

      normalizedTotal =
        toGrams(
          match[1],
          match[2]
        );
    }

    /*
     * --------------------------------------------------------------
     * STEP 2
     *
     * DECLARED QUANTITY
     * --------------------------------------------------------------
     */

    let declaredQuantity =
      null;

    const quantityPatterns = [
      /\bpack\s+of\s+(\d+)\b/i,
      /\bset\s+of\s+(\d+)\b/i,
      /\bcombo\s+of\s+(\d+)\b/i,
      /\b(\d+)\s*(?:pcs?|pieces?)\b/i
    ];

    for (
      const pattern
      of quantityPatterns
    ) {
      const match =
        sourceText.match(
          pattern
        );

      if (!match)
        continue;

      declaredQuantity =
        Number(match[1]);

      break;
    }

    /*
     * --------------------------------------------------------------
     * STEP 3
     *
     * COMPONENT AREA
     *
     * Only inspect text before purity notation for combo components.
     * --------------------------------------------------------------
     */

    const componentArea =
      sourceText
        .split(
          /\b(?:18|20|21|22|23|24)\s*(?:\(|k|kt|karat)/i
        )[0];

    const rawComponents = [];

    const componentRegex =
      /(\d+(?:\.\d+)?)\s*(mg|g)\b/gi;

    let componentMatch;

    while (
      (
        componentMatch =
          componentRegex.exec(
            componentArea
          )
      )
    ) {
      const grams =
        toGrams(
          componentMatch[1],
          componentMatch[2]
        );

      if (
        grams != null &&
        grams > 0 &&
        grams <= 100
      ) {
        rawComponents.push(
          grams
        );
      }
    }

    /*
     * --------------------------------------------------------------
     * STEP 4
     *
     * STRONG COMBO SIGNAL
     * --------------------------------------------------------------
     */

    const comboSignal =
      /\bcombo\b/i.test(
        sourceText
      ) ||
      /\d+(?:\.\d+)?\s*(?:mg|g)\s*(?:\+|&|and)\s*\d+(?:\.\d+)?\s*(?:mg|g)/i
        .test(
          sourceText
        );

    let components = [];

    if (
      comboSignal &&
      rawComponents.length >= 2
    ) {
      components =
        rawComponents;
    }

    /*
     * Example:
     *
     * 250 MG Combo ... 0.5 g Gold Coin Pack of 2
     *
     * Only one component weight appears before purity,
     * but declared quantity tells us it is repeated.
     */

    if (
      comboSignal &&
      components.length === 0 &&
      rawComponents.length === 1 &&
      declaredQuantity &&
      declaredQuantity > 1
    ) {
      components =
        Array.from(
          {
            length:
              declaredQuantity
          },
          () =>
            rawComponents[0]
        );
    }

    /*
     * --------------------------------------------------------------
     * STEP 5
     *
     * COMPONENT METADATA
     * --------------------------------------------------------------
     */

    let quantity =
      null;

    let unitWeightGrams =
      null;

    let componentTotal =
      null;

    if (
      components.length >= 2
    ) {
      quantity =
        declaredQuantity ||
        components.length;

      componentTotal =
        components.reduce(
          (sum, value) =>
            sum + value,
          0
        );

      const first =
        components[0];

      const allSame =
        components.every(
          value =>
            Math.abs(
              value - first
            ) <
            1e-9
        );

      unitWeightGrams =
        allSame
          ? first
          : null;
    }

    /*
     * --------------------------------------------------------------
     * STEP 6
     *
     * NORMALIZED TOTAL WINS
     * --------------------------------------------------------------
     */

    if (
      normalizedTotal != null
    ) {
      if (!quantity) {
        quantity =
          declaredQuantity ||
          1;

        /*
         * If this says Pack of N but component detail wasn't
         * available, derive per-unit weight from TOTAL/N.
         */

        if (
          quantity > 1
        ) {
          unitWeightGrams =
            normalizedTotal /
            quantity;
        } else {
          unitWeightGrams =
            normalizedTotal;
        }
      }

      /*
       * Sanity-check component sum against Flipkart's normalized total.
       */

      const componentConflict =
        componentTotal != null &&
        Math.abs(
          componentTotal -
          normalizedTotal
        ) >
        0.0001;

      return {
        weightGrams:
          normalizedTotal,

        unitWeightGrams,

        quantity,

        totalWeightGrams:
          normalizedTotal,

        componentWeightsGrams:
          components.length
            ? components
            : null,

        componentTotalGrams:
          componentTotal,

        weightConflict:
          componentConflict,

        weightSource:
          components.length
            ? "normalized-total+combo"
            : declaredQuantity > 1
              ? "normalized-total+pack"
              : "normalized-total"
      };
    }

    /*
     * --------------------------------------------------------------
     * STEP 7
     *
     * NO NORMALIZED TOTAL:
     * FALL BACK TO COMPONENT SUM
     * --------------------------------------------------------------
     */

    if (
      componentTotal != null
    ) {
      return {
        weightGrams:
          componentTotal,

        unitWeightGrams,

        quantity:
          quantity ||
          components.length,

        totalWeightGrams:
          componentTotal,

        componentWeightsGrams:
          components,

        componentTotalGrams:
          componentTotal,

        weightConflict:
          false,

        weightSource:
          "combo-sum"
      };
    }

    /*
     * --------------------------------------------------------------
     * STEP 8
     *
     * GENERIC WEIGHT FALLBACK
     * --------------------------------------------------------------
     */

    const allWeights =
      [
        ...sourceText.matchAll(
          /(\d+(?:\.\d+)?)\s*(mg|g)\b/gi
        )
      ]
        .map(
          match => ({
            grams:
              toGrams(
                match[1],
                match[2]
              ),

            index:
              match.index
          })
        )
        .filter(
          item =>
            item.grams != null &&
            item.grams > 0 &&
            item.grams <= 100
        );

    if (
      allWeights.length
    ) {
      const selected =
        allWeights[
          allWeights.length -
          1
        ].grams;

      return {
        weightGrams:
          selected,

        unitWeightGrams:
          selected,

        quantity:
          1,

        totalWeightGrams:
          selected,

        componentWeightsGrams:
          null,

        componentTotalGrams:
          null,

        weightConflict:
          false,

        weightSource:
          "generic-weight"
      };
    }

    return {
      weightGrams: null,
      unitWeightGrams: null,
      quantity: null,
      totalWeightGrams: null,
      componentWeightsGrams:
        null,
      componentTotalGrams:
        null,
      weightConflict:
        false,
      weightSource: null
    };
  }

  /********************************************************************
   * PRICE PARSER
   *
   * Do NOT simply take every ₹ number in the whole card.
   *
   * First collect leaf elements containing ONLY a currency amount.
   ********************************************************************/

  function parsePrices(
    card
  ) {
    const leafAmounts = [];

    const elements =
      [
        ...card.querySelectorAll(
          "*"
        )
      ];

    for (
      const element
      of elements
    ) {
      if (
        element.children.length
      ) {
        continue;
      }

      const text =
        clean(
          element.textContent
        );

      if (
        !/^₹\s*[\d,]+(?:\.\d+)?$/
          .test(text)
      ) {
        continue;
      }

      const value =
        moneyFrom(text);

      if (
        value != null &&
        value > 0
      ) {
        leafAmounts.push({
          value,
          element
        });
      }
    }

    /*
     * Deduplicate while retaining DOM order.
     */

    const amounts = [];

    for (
      const item
      of leafAmounts
    ) {
      if (
        !amounts.some(
          existing =>
            existing.value ===
            item.value
        )
      ) {
        amounts.push(item);
      }
    }

    let price = null;
    let mrp = null;

    /*
     * Flipkart typically renders current price before MRP.
     */

    if (
      amounts.length >= 1
    ) {
      price =
        amounts[0].value;
    }

    if (
      amounts.length >= 2
    ) {
      /*
       * MRP should be >= price.
       */

      const candidates =
        amounts
          .slice(1)
          .map(
            item =>
              item.value
          )
          .filter(
            value =>
              price == null ||
              value >= price
          );

      if (
        candidates.length
      ) {
        mrp =
          candidates[0];
      }
    }

    /*
     * --------------------------------------------------------------
     * FALLBACK
     * --------------------------------------------------------------
     */

    if (
      price == null
    ) {
      const text =
        clean(
          card.textContent
        );

      const values =
        [
          ...text.matchAll(
            /₹\s*([\d,]+)/g
          )
        ]
          .map(
            match =>
              Number(
                match[1]
                  .replace(
                    /,/g,
                    ""
                  )
              )
          )
          .filter(
            value =>
              Number.isFinite(
                value
              ) &&
              value > 0
          );

      const deduped =
        unique(values);

      if (
        deduped.length
      ) {
        price =
          deduped[0];

        const validMrp =
          deduped
            .slice(1)
            .find(
              value =>
                value >= price
            );

        mrp =
          validMrp ??
          null;
      }
    }

    /*
     * --------------------------------------------------------------
     * SANITY
     * --------------------------------------------------------------
     */

    let priceSuspicious =
      false;

    if (
      price != null &&
      price <= 0
    ) {
      priceSuspicious =
        true;
    }

    if (
      mrp != null &&
      price != null &&
      mrp < price
    ) {
      priceSuspicious =
        true;
    }

    /*
     * Gold listing sanity:
     * absurdly gigantic MRP generally indicates parser contamination.
     */

    if (
      mrp != null &&
      mrp >
        10000000
    ) {
      priceSuspicious =
        true;
    }

    return {
      price,
      mrp,
      priceSuspicious
    };
  }

  /********************************************************************
   * RATING
   ********************************************************************/

  function parseRating(
    card
  ) {
    const elements =
      [
        ...card.querySelectorAll(
          "div,span"
        )
      ];

    for (
      const element
      of elements
    ) {
      if (
        element.children.length
      ) {
        continue;
      }

      const text =
        clean(
          element.textContent
        );

      const match =
        text.match(
          /^([1-5](?:\.\d+)?)\s*(?:★|⭐)?$/
        );

      if (!match)
        continue;

      const rating =
        Number(
          match[1]
        );

      if (
        rating >= 1 &&
        rating <= 5
      ) {
        return rating;
      }
    }

    return null;
  }

  /********************************************************************
   * PRODUCT CARD ROOT
   ********************************************************************/

  function getCardRoot(
    element
  ) {
    if (!element)
      return null;

    let root =
      element;

    /*
     * data-id itself is normally the product wrapper.
     */

    if (
      root.querySelector(
        'a[href*="/p/"]'
      )
    ) {
      return root;
    }

    let parent =
      root.parentElement;

    for (
      let depth = 0;
      depth < 4 && parent;
      depth++
    ) {
      if (
        parent.querySelector(
          'a[href*="/p/"]'
        )
      ) {
        return parent;
      }

      parent =
        parent.parentElement;
    }

    return root;
  }

  /********************************************************************
   * CARD PARSER
   ********************************************************************/

  function parseCard(
    rawCard,
    stream,
    page
  ) {
    const card =
      getCardRoot(
        rawCard
      );

    if (!card)
      return null;

    let pid =
      rawCard.getAttribute(
        "data-id"
      ) ||
      card.getAttribute(
        "data-id"
      );

    const anchors =
      [
        ...card.querySelectorAll(
          'a[href*="/p/"]'
        )
      ];

    let anchor =
      anchors.find(
        item =>
          item.href &&
          item.href.includes(
            "pid="
          )
      );

    if (!anchor) {
      anchor =
        anchors[0] ||
        null;
    }

    const href =
      anchor
        ?.getAttribute(
          "href"
        ) ||
      null;

    if (
      !pid &&
      href
    ) {
      try {
        pid =
          new URL(
            href,
            location.origin
          )
            .searchParams
            .get(
              "pid"
            );
      } catch {}
    }

    if (!pid)
      return null;

    /*
     * --------------------------------------------------------------
     * NAME
     * --------------------------------------------------------------
     */

    const titleCandidates =
      [];

    for (
      const item
      of anchors
    ) {
      const title =
        clean(
          item.getAttribute(
            "title"
          )
        );

      if (
        title &&
        title.length > 5
      ) {
        titleCandidates.push(
          title
        );
      }
    }

    const cardLines =
      String(
        card.innerText ||
        ""
      )
        .split("\n")
        .map(clean)
        .filter(Boolean);

    for (
      const line
      of cardLines
    ) {
      if (
        /\b(?:gold|silver)\b/i
          .test(line) &&
        /\b(?:coin|bar|vedhani|biscuit)\b/i
          .test(line)
      ) {
        titleCandidates.push(
          line
        );
      }
    }

    const name =
      unique(
        titleCandidates
      )
        .sort(
          (a, b) =>
            b.length -
            a.length
        )[0] ||
      null;

    /*
     * --------------------------------------------------------------
     * BRAND
     * --------------------------------------------------------------
     */

    let brand =
      clean(
        card.querySelector(
          ".Fo1I0b"
        )?.textContent
      ) ||
      null;

    if (
      !brand &&
      cardLines.length
    ) {
      /*
       * Usually the short first line before title.
       */

      const candidate =
        cardLines.find(
          line =>
            line !== name &&
            line.length >= 2 &&
            line.length <= 60 &&
            !/^₹/.test(line) &&
            !/%\s*off/i.test(
              line
            )
        );

      if (
        candidate &&
        (
          !name ||
          name
            .toLowerCase()
            .includes(
              candidate
                .toLowerCase()
            )
        )
      ) {
        brand =
          candidate;
      }
    }

    /*
     * --------------------------------------------------------------
     * LINK
     * --------------------------------------------------------------
     */

    const link =
      cleanProductURL(
        absoluteURL(
          href
        ),
        pid
      );

    /*
     * --------------------------------------------------------------
     * PARSE TEXT
     * --------------------------------------------------------------
     */

    const parseText =
      clean([
        name,
        link
      ].join(" "));

    const metal =
      /\bgold\b/i.test(
        parseText
      )
        ? "gold"
        : /\bsilver\b/i.test(
            parseText
          )
          ? "silver"
          : null;

    const purity =
      parsePurity(
        parseText
      );

    // Listing/title evidence is product-specific and outranks PDP body/spec fallbacks.
    if (purity.puritySource === "explicit-karat-fineness") {
      purity.puritySource = "listing-explicit-karat-fineness";
    } else if (purity.puritySource === "explicit-fineness") {
      purity.puritySource = "listing-explicit-fineness";
    }

    const weight =
      parseWeight(
        name ||
        parseText
      );

    const prices =
      parsePrices(
        card
      );

    /*
     * --------------------------------------------------------------
     * DISCOUNT
     * --------------------------------------------------------------
     */

    const cardText =
      clean(
        card.textContent
      );

    let discountPercent =
      null;

    const discountMatch =
      cardText.match(
        /\b(\d+(?:\.\d+)?)\s*%\s*off\b/i
      );

    if (
      discountMatch
    ) {
      discountPercent =
        Number(
          discountMatch[1]
        );
    } else if (
      prices.price != null &&
      prices.mrp != null &&
      prices.mrp > 0 &&
      prices.mrp >=
        prices.price
    ) {
      discountPercent =
        +(
          (
            (
              prices.mrp -
              prices.price
            ) /
            prices.mrp *
            100
          ).toFixed(2)
        );
    }

    /*
     * --------------------------------------------------------------
     * PRICE SANITY
     * --------------------------------------------------------------
     */

    let priceSuspicious =
      prices.priceSuspicious;

    if (
      discountPercent != null &&
      (
        discountPercent < 0 ||
        discountPercent > 95
      )
    ) {
      priceSuspicious =
        true;
    }

    /*
     * --------------------------------------------------------------
     * IMAGE
     * --------------------------------------------------------------
     */

    const imageElement =
      card.querySelector(
        "img"
      );

    const image =
      imageElement
        ?.currentSrc ||
      imageElement
        ?.src ||
      imageElement
        ?.getAttribute(
          "src"
        ) ||
      null;

    return {
      pid,

      brand,

      name,

      metal,

      weight:
        weight.totalWeightGrams != null
          ? `${weight.totalWeightGrams} g`
          : null,

      weightGrams:
        weight.totalWeightGrams,

      unitWeightGrams:
        weight.unitWeightGrams,

      quantity:
        weight.quantity,

      totalWeightGrams:
        weight.totalWeightGrams,

      componentWeightsGrams:
        weight.componentWeightsGrams,

      componentTotalGrams:
        weight.componentTotalGrams,

      weightSource:
        weight.weightSource,

      weightConflict:
        weight.weightConflict,

      karat:
        purity.karat,

      fineness:
        purity.fineness,

      puritySource:
        purity.puritySource,

      price:
        prices.price,

      mrp:
        prices.mrp,

      discountPercent,

      priceSuspicious,

      unavailable:
        /not deliverable|out of stock|sold out|currently unavailable|change address|enter pincode/i.test(cardText),

      rating:
        parseRating(
          card
        ),

      image,

      link,

      streams:
        new Set([
          stream
        ]),

      firstStream:
        stream,

      firstPage:
        page
    };
  }

  /********************************************************************
   * PAGE PARSER
   ********************************************************************/

  function parseDocument(
    doc,
    stream,
    page
  ) {
    const candidates =
      [
        ...doc.querySelectorAll(
          '[data-id^="CON"]'
        )
      ];

    const pageProducts =
      new Map();

    for (
      const candidate
      of candidates
    ) {
      const pid =
        candidate.getAttribute(
          "data-id"
        );

      if (
        !pid ||
        pageProducts.has(
          pid
        )
      ) {
        continue;
      }

      const product =
        parseCard(
          candidate,
          stream,
          page
        );

      if (
        product?.pid
      ) {
        pageProducts.set(
          product.pid,
          product
        );
      }
    }

    /*
     * Anchor fallback if Flipkart changes data-id placement.
     */

    if (
      pageProducts.size === 0
    ) {
      const anchors =
        [
          ...doc.querySelectorAll(
            'a[href*="/p/"][href*="pid=CON"]'
          )
        ];

      for (
        const anchor
        of anchors
      ) {
        const href =
          anchor.getAttribute(
            "href"
          );

        let pid = null;

        try {
          pid =
            new URL(
              href,
              location.origin
            )
              .searchParams
              .get(
                "pid"
              );
        } catch {}

        if (
          !pid ||
          pageProducts.has(
            pid
          )
        ) {
          continue;
        }

        const card =
          anchor.closest(
            '[data-id]'
          ) ||
          anchor.parentElement;

        const product =
          parseCard(
            card,
            stream,
            page
          );

        if (
          product?.pid
        ) {
          pageProducts.set(
            product.pid,
            product
          );
        }
      }
    }

    return {
      products:
        [
          ...pageProducts.values()
        ],

      responseReportedTotal:
        detectReportedTotal(
          doc
        )
    };
  }

  /********************************************************************
   * PRODUCT MERGE
   ********************************************************************/

  const PRODUCTS =
    new Map();

  function sourceRank(
    source
  ) {
    return ({
      "listing-explicit-karat-fineness":
        120,

      "listing-explicit-fineness":
        115,

      "PDP-spec-explicit":
        110,

      "explicit-karat-fineness":
        100,

      "explicit-fineness":
        90,

      "embedded-fineness":
        70,

      "karat-standard":
        20,

      "karat":
        10
    })[source] ?? 0;
  }

  function mergeProduct(
    incoming
  ) {
    if (
      !incoming?.pid
    ) {
      return false;
    }

    const pid =
      incoming.pid;

    const existing =
      PRODUCTS.get(pid);

    if (!existing) {
      PRODUCTS.set(
        pid,
        incoming
      );

      return true;
    }

    /*
     * --------------------------------------------------------------
     * STREAMS
     * --------------------------------------------------------------
     */

    if (
      !(existing.streams
        instanceof Set)
    ) {
      existing.streams =
        new Set(
          existing.streams
            ? [existing.streams]
            : []
        );
    }

    if (
      incoming.streams
        instanceof Set
    ) {
      for (
        const stream
        of incoming.streams
      ) {
        existing.streams.add(
          stream
        );
      }
    }

    /*
     * --------------------------------------------------------------
     * SIMPLE FIELDS
     * --------------------------------------------------------------
     */

    for (
      const field
      of [
        "brand",
        "name",
        "metal",
        "rating",
        "image",
        "link"
      ]
    ) {
      if (
        (
          existing[field] ===
            null ||
          existing[field] ===
            undefined ||
          existing[field] ===
            ""
        ) &&
        incoming[field] !==
          null &&
        incoming[field] !==
          undefined &&
        incoming[field] !==
          ""
      ) {
        existing[field] =
          incoming[field];
      }
    }

    /*
     * Prefer longer product title.
     */

    if (
      incoming.name &&
      (
        !existing.name ||
        incoming.name.length >
          existing.name.length
      )
    ) {
      existing.name =
        incoming.name;
    }

    /*
     * --------------------------------------------------------------
     * PURITY
     * --------------------------------------------------------------
     */

    if (
      sourceRank(
        incoming.puritySource
      ) >
      sourceRank(
        existing.puritySource
      )
    ) {
      if (
        incoming.karat != null
      ) {
        existing.karat =
          incoming.karat;
      }

      if (
        incoming.fineness
      ) {
        existing.fineness =
          incoming.fineness;
      }

      existing.puritySource =
        incoming.puritySource;
    } else {
      if (
        existing.karat == null &&
        incoming.karat != null
      ) {
        existing.karat =
          incoming.karat;
      }

      if (
        !existing.fineness &&
        incoming.fineness
      ) {
        existing.fineness =
          incoming.fineness;
      }
    }

    /*
     * --------------------------------------------------------------
     * WEIGHT
     *
     * Prefer normalized-total evidence.
     * --------------------------------------------------------------
     */

    const weightRank =
      source => ({
        "PDP":
          100,

        "normalized-total+combo":
          90,

        "normalized-total+pack":
          85,

        "normalized-total":
          80,

        "combo-sum":
          70,

        "generic-weight":
          30
      })[source] ?? 0;

    if (
      weightRank(
        incoming.weightSource
      ) >
      weightRank(
        existing.weightSource
      )
    ) {
      existing.weight =
        incoming.weight;

      existing.weightGrams =
        incoming.weightGrams;

      existing.unitWeightGrams =
        incoming.unitWeightGrams;

      existing.quantity =
        incoming.quantity;

      existing.totalWeightGrams =
        incoming.totalWeightGrams;

      existing.componentWeightsGrams =
        incoming.componentWeightsGrams;

      existing.componentTotalGrams =
        incoming.componentTotalGrams;

      existing.weightSource =
        incoming.weightSource;

      existing.weightConflict =
        incoming.weightConflict;
    }

    /*
     * --------------------------------------------------------------
     * PRICE
     *
     * We don't blindly choose minimum anymore.
     *
     * Prefer a non-suspicious observation.
     * --------------------------------------------------------------
     */

    if (
      existing.price == null &&
      incoming.price != null
    ) {
      existing.price =
        incoming.price;
    }

    if (
      existing.mrp == null &&
      incoming.mrp != null
    ) {
      existing.mrp =
        incoming.mrp;
    }

    if (
      existing.priceSuspicious &&
      !incoming.priceSuspicious &&
      incoming.price != null
    ) {
      existing.price =
        incoming.price;

      existing.mrp =
        incoming.mrp;

      existing.discountPercent =
        incoming.discountPercent;

      existing.priceSuspicious =
        false;
    }

    /*
     * Same valid price seen elsewhere can fill missing MRP.
     */

    if (
      !incoming.priceSuspicious &&
      incoming.price != null &&
      existing.price != null &&
      incoming.price ===
        existing.price
    ) {
      if (
        existing.mrp == null &&
        incoming.mrp != null
      ) {
        existing.mrp =
          incoming.mrp;
      }

      if (
        existing.discountPercent ==
          null &&
        incoming.discountPercent !=
          null
      ) {
        existing.discountPercent =
          incoming.discountPercent;
      }
    }

    PRODUCTS.set(
      pid,
      existing
    );

    return false;
  }

  /********************************************************************
   * HTTP FETCH
   ********************************************************************/

  async function fetchHTML(
    url,
    label
  ) {
    for (
      let attempt = 0;
      attempt <=
        CFG.listingRetries;
      attempt++
    ) {
      try {
        const response =
          await fetch(
            url,
            {
              credentials:
                "include",

              cache:
                "no-store",

              headers: {
                accept:
                  "text/html,application/xhtml+xml"
              }
            }
          );

        if (
          response.status ===
            403 ||
          response.status ===
            429
        ) {
          const wait =
            CFG.retryBaseMs *
            Math.pow(
              2,
              attempt
            );

          console.warn(
            `⏳ ${label}: HTTP ${response.status}; retrying in ${wait}ms`
          );

          await sleep(
            wait
          );

          continue;
        }

        if (
          !response.ok
        ) {
          return {
            ok: false,
            status:
              response.status,
            url
          };
        }

        return {
          ok: true,

          status:
            response.status,

          url:
            response.url ||
            url,

          html:
            await response.text()
        };

      } catch (error) {
        if (
          attempt >=
          CFG.listingRetries
        ) {
          return {
            ok: false,

            status:
              null,

            url,

            error:
              String(error)
          };
        }

        await sleep(
          CFG.retryBaseMs *
          Math.pow(
            2,
            attempt
          )
        );
      }
    }

    return {
      ok: false,
      status: null,
      url
    };
  }

  /********************************************************************
   * LISTING URL
   ********************************************************************/

  const BASE_URL =
    new URL(
      location.href
    );

  function makeListingURL(
    stream,
    page
  ) {
    const url =
      new URL(
        BASE_URL
      );

    url.searchParams.delete(
      "page"
    );

    url.searchParams.delete(
      "sort"
    );

    if (
      stream.sort
    ) {
      url.searchParams.set(
        "sort",
        stream.sort
      );
    }

    if (
      page > 1
    ) {
      url.searchParams.set(
        "page",
        String(page)
      );
    }

    return url.href;
  }

  /********************************************************************
   * INITIAL PAGE
   ********************************************************************/

  const initialResult =
    parseDocument(
      document,
      "default",
      1
    );

  for (
    const product
    of initialResult.products
  ) {
    mergeProduct(
      product
    );
  }

  const STREAM_LOG = [];

  console.log(
    "================================================"
  );

  console.log(
    "🚀 FLIPKART COINS & BARS MASTER"
  );

  console.log(
    "Canonical reported products:",
    canonicalReportedTotal ??
      "unknown"
  );

  console.log(
    "Current page captured:",
    PRODUCTS.size
  );

  console.log(
    "================================================"
  );

  /********************************************************************
   * CRAWL STREAM
   ********************************************************************/

  async function crawlStream(
    stream
  ) {
    console.log("");
    console.log(
      `🔎 STREAM: ${stream.name}`
    );

    const streamSeen =
      new Set();

    let emptyStreak = 0;

    for (
      let page = 1;
      page <=
        CFG.maxPagesPerStream;
      page++
    ) {
      let parsed;
      let status = 200;
      let responseCount =
        null;

      /*
       * Reuse currently loaded page for default page 1.
       */

      if (
        stream.name ===
          "default" &&
        page === 1
      ) {
        parsed =
          initialResult;

        responseCount =
          canonicalReportedTotal;
      } else {
        const url =
          makeListingURL(
            stream,
            page
          );

        const response =
          await fetchHTML(
            url,
            `${stream.name} page ${page}`
          );

        status =
          response.status;

        if (
          !response.ok
        ) {
          emptyStreak++;

          STREAM_LOG.push({
            stream:
              stream.name,

            page,

            http:
              response.status,

            cards:
              0,

            streamGained:
              0,

            globalGained:
              0,

            globalUnique:
              PRODUCTS.size,

            result:
              "failed"
          });

          console.warn(
            `⚠️ ${stream.name} p${page}: HTTP ${response.status}`
          );

          if (
            emptyStreak >=
            CFG.emptyPagesToStop
          ) {
            break;
          }

          continue;
        }

        const doc =
          new DOMParser()
            .parseFromString(
              response.html,
              "text/html"
            );

        parsed =
          parseDocument(
            doc,
            stream.name,
            page
          );

        responseCount =
          parsed
            .responseReportedTotal;

        /*
         * DIAGNOSTIC ONLY.
         *
         * Never overwrite canonicalReportedTotal.
         */

        if (
          responseCount &&
          canonicalReportedTotal &&
          responseCount !==
            canonicalReportedTotal
        ) {
          console.debug(
            `ℹ️ Count drift ${stream.name} p${page}: canonical=${canonicalReportedTotal}, response=${responseCount}`
          );
        }
      }

      const pageProducts =
        parsed.products ||
        [];

      if (
        pageProducts.length ===
        0
      ) {
        emptyStreak++;

        STREAM_LOG.push({
          stream:
            stream.name,

          page,

          http:
            status,

          cards:
            0,

          streamGained:
            0,

          globalGained:
            0,

          streamUnique:
            streamSeen.size,

          globalUnique:
            PRODUCTS.size,

          responseReportedTotal:
            responseCount,

          result:
            "empty"
        });

        console.log(
          `⬜ ${stream.name} p${page} empty`
        );

        if (
          emptyStreak >=
          CFG.emptyPagesToStop
        ) {
          console.log(
            `🛑 ${stream.name} finished`
          );

          break;
        }

        continue;
      }

      emptyStreak = 0;

      let streamGained = 0;
      let globalGained = 0;

      for (
        const product
        of pageProducts
      ) {
        if (
          !streamSeen.has(
            product.pid
          )
        ) {
          streamSeen.add(
            product.pid
          );

          streamGained++;
        }

        if (
          mergeProduct(
            product
          )
        ) {
          globalGained++;
        }
      }

      STREAM_LOG.push({
        stream:
          stream.name,

        page,

        http:
          status,

        cards:
          pageProducts.length,

        streamGained,

        globalGained,

        streamUnique:
          streamSeen.size,

        globalUnique:
          PRODUCTS.size,

        canonicalReportedTotal,

        responseReportedTotal:
          responseCount,

        result:
          "data"
      });

      console.log(
        `📦 ${stream.name} p${page}` +
        ` | cards=${pageProducts.length}` +
        ` | stream +${streamGained}` +
        ` | GLOBAL +${globalGained}` +
        ` | UNIQUE=${PRODUCTS.size}` +
        (
          canonicalReportedTotal
            ? ` / ${canonicalReportedTotal}`
            : ""
        )
      );

      /*
       * ------------------------------------------------------------
       * SUCCESS
       * ------------------------------------------------------------
       */

      if (
        CFG.stopAtCanonicalCount &&
        canonicalReportedTotal &&
        PRODUCTS.size >=
          canonicalReportedTotal
      ) {
        console.log(
          `🎯 Reached canonical catalogue size: ${PRODUCTS.size}/${canonicalReportedTotal}`
        );

        break;
      }

      await sleep(
        CFG.listingDelayMs
      );
    }

    console.log(
      `✅ ${stream.name}: ${streamSeen.size} stream-unique | global=${PRODUCTS.size}`
    );

    return streamSeen;
  }

  /********************************************************************
   * CRAWL THREE USEFUL STREAMS
   ********************************************************************/

  const STREAM_RESULTS =
    {};

  for (
    const stream
    of CFG.streams
  ) {
    if (
      CFG.stopAtCanonicalCount &&
      canonicalReportedTotal &&
      PRODUCTS.size >=
        canonicalReportedTotal
    ) {
      break;
    }

    STREAM_RESULTS[
      stream.name
    ] =
      await crawlStream(
        stream
      );
  }

  /********************************************************************
   * FINAL DISCOVERY RESCUE
   *
   * Flipkart can omit a tiny number of products from every normal
   * pagination stream. If still short, probe alternate deterministic
   * projections and merge only new PIDs. Stops immediately at target.
   ********************************************************************/

  const RESCUE_LOG = [];

  async function rescueDiscovery() {
    if (!canonicalReportedTotal || PRODUCTS.size >= canonicalReportedTotal) {
      return;
    }

    const rescueStreams = [
      { name: "rescue_discount", sort: "discount_desc" },
      { name: "rescue_rating", sort: "rating_desc" },
      { name: "rescue_relevance", sort: "relevance" }
    ];

    console.log(`\n🛟 RESCUE DISCOVERY: short by ${canonicalReportedTotal - PRODUCTS.size}`);

    for (const stream of rescueStreams) {
      if (PRODUCTS.size >= canonicalReportedTotal) break;

      let noGain = 0;

      for (let page = 1; page <= CFG.maxPagesPerStream; page++) {
        if (PRODUCTS.size >= canonicalReportedTotal) break;

        const url = makeListingURL(stream, page);
        const response = await fetchHTML(url, `${stream.name} page ${page}`);

        if (!response.ok) {
          noGain++;
          if (noGain >= 2) break;
          continue;
        }

        const doc = new DOMParser().parseFromString(response.html, "text/html");
        const parsed = parseDocument(doc, stream.name, page);

        let gained = 0;
        for (const product of parsed.products) {
          if (mergeProduct(product)) gained++;
        }

        RESCUE_LOG.push({
          stream: stream.name,
          page,
          cards: parsed.products.length,
          gained,
          unique: PRODUCTS.size
        });

        console.log(`🛟 ${stream.name} p${page} | +${gained} | UNIQUE=${PRODUCTS.size}/${canonicalReportedTotal}`);

        if (gained === 0) noGain++;
        else noGain = 0;

        if (noGain >= 3 && page >= 12) break;
        await sleep(CFG.listingDelayMs);
      }
    }

    window.flipkartRescueLog = RESCUE_LOG;
  }

  await rescueDiscovery();

  /********************************************************************
   * POST-MERGE REPARSE
   ********************************************************************/

  for (
    const [pid, product]
    of PRODUCTS
  ) {
    const combined =
      clean([
        product.name,
        product.link
      ].join(" "));

    /*
     * PURITY
     */

    const purity =
      parsePurity(
        combined
      );

    if (
      sourceRank(
        purity.puritySource
      ) >
      sourceRank(
        product.puritySource
      )
    ) {
      if (
        purity.karat != null
      ) {
        product.karat =
          purity.karat;
      }

      if (
        purity.fineness
      ) {
        product.fineness =
          purity.fineness;
      }

      product.puritySource =
        purity.puritySource;
    }

    /*
     * 22K fallback.
     */

    if (
      product.karat === 22 &&
      !product.fineness
    ) {
      product.fineness =
        "916";

      product.puritySource =
        "karat-standard";
    }

    /*
     * FINENESS -> KARAT
     */

    if (
      product.karat == null &&
      product.fineness
    ) {
      product.karat =
        karatFromFineness(
          product.fineness
        );
    }

    /*
     * WEIGHT
     */

    const reparsedWeight =
      parseWeight(
        product.name ||
        combined
      );

    if (
      reparsedWeight
        .totalWeightGrams !=
      null
    ) {
      product.weight =
        `${reparsedWeight.totalWeightGrams} g`;

      product.weightGrams =
        reparsedWeight.totalWeightGrams;

      product.unitWeightGrams =
        reparsedWeight.unitWeightGrams;

      product.quantity =
        reparsedWeight.quantity;

      product.totalWeightGrams =
        reparsedWeight.totalWeightGrams;

      product.componentWeightsGrams =
        reparsedWeight.componentWeightsGrams;

      product.componentTotalGrams =
        reparsedWeight.componentTotalGrams;

      product.weightSource =
        reparsedWeight.weightSource;

      product.weightConflict =
        reparsedWeight.weightConflict;
    }

    PRODUCTS.set(
      pid,
      product
    );
  }

  /********************************************************************
   * SUSPICIOUS DETECTION
   ********************************************************************/

  function calculateSuspicion(
    product
  ) {
    const reasons = [];

    if (
      !product.name
    ) {
      reasons.push(
        "missing-name"
      );
    }

    if (
      product.metal ===
        "gold" &&
      product.totalWeightGrams ==
        null
    ) {
      reasons.push(
        "missing-weight"
      );
    }

    if (
      product.metal ===
        "gold" &&
      product.karat == null
    ) {
      reasons.push(
        "missing-karat"
      );
    }

    if (
      product.metal ===
        "gold" &&
      !product.fineness
    ) {
      reasons.push(
        "missing-fineness"
      );
    }

    if (
      product.price == null
    ) {
      reasons.push(
        "missing-price"
      );
    }

    if (
      product.priceSuspicious
    ) {
      reasons.push(
        "suspicious-price"
      );
    }

    if (
      product.price != null &&
      product.mrp != null &&
      product.mrp <
        product.price
    ) {
      reasons.push(
        "mrp-below-price"
      );
    }

    if (
      product.discountPercent !=
        null &&
      (
        product.discountPercent <
          0 ||
        product.discountPercent >
          95
      )
    ) {
      reasons.push(
        "suspicious-discount"
      );
    }

    if (
      product.weightConflict
    ) {
      reasons.push(
        "weight-component-conflict"
      );
    }

    if (product.purityConflicts?.length) {
      reasons.push("purity-source-conflict");
    }

    if (
      product.totalWeightGrams !=
        null &&
      (
        product.totalWeightGrams <=
          0 ||
        product.totalWeightGrams >
          100
      )
    ) {
      reasons.push(
        "suspicious-weight"
      );
    }

    return reasons;
  }

  for (
    const product
    of PRODUCTS.values()
  ) {
    product.suspiciousReasons =
      calculateSuspicion(
        product
      );

    product.suspicious =
      product
        .suspiciousReasons
        .length > 0;
  }

  /********************************************************************
   * PDP PARSER
   ********************************************************************/

  function parsePDP(
    html,
    existing
  ) {
    const doc =
      new DOMParser()
        .parseFromString(
          html,
          "text/html"
        );

    const bodyText =
      clean(
        doc.body?.innerText || ""
      );

    const jsonLdObjects = [];

    for (
      const script
      of doc.querySelectorAll(
        'script[type="application/ld+json"]'
      )
    ) {
      try {
        const parsed =
          JSON.parse(
            script.textContent
          );

        if (Array.isArray(parsed)) {
          jsonLdObjects.push(...parsed);
        } else {
          jsonLdObjects.push(parsed);
        }
      } catch {}
    }

    const name =
      clean(
        doc.querySelector("h1")?.textContent
      ) ||
      existing.name ||
      null;

    let price = null;
    let mrp = null;

    for (const object of jsonLdObjects) {
      const offers = object?.offers;
      const offerList =
        Array.isArray(offers)
          ? offers
          : offers
            ? [offers]
            : [];

      for (const offer of offerList) {
        const value =
          numberFrom(offer?.price);

        if (value != null && value > 0) {
          price = value;
          break;
        }
      }

      if (price != null) break;
    }

    if (price == null) {
      const currencyValues =
        [...bodyText.matchAll(/₹\s*([\d,]+)/g)]
          .map(match =>
            Number(match[1].replace(/,/g, ""))
          )
          .filter(value =>
            Number.isFinite(value) && value > 0
          );

      if (currencyValues.length) {
        price = currencyValues[0];
        mrp =
          currencyValues
            .slice(1)
            .find(value => value >= price) ?? null;
      }
    }

    /*
     * IMPORTANT: do NOT parse purity from the whole PDP body.
     * Recommendation widgets and unrelated text can contain another
     * product's 24K/999 values. Only inspect product-specific title and
     * specification/value pairs.
     */
    const specPairs = [];

    const addPair = (label, value) => {
      label = clean(label);
      value = clean(value);
      if (!label || !value) return;
      if (label.length > 120 || value.length > 300) return;
      specPairs.push({ label, value });
    };

    for (const tr of doc.querySelectorAll("tr")) {
      const cells = [...tr.querySelectorAll("th,td")]
        .map(x => clean(x.textContent))
        .filter(Boolean);
      if (cells.length >= 2) addPair(cells[0], cells.slice(1).join(" "));
    }

    for (const dl of doc.querySelectorAll("dl")) {
      const dts = [...dl.querySelectorAll("dt")];
      for (const dt of dts) {
        const dd = dt.nextElementSibling;
        if (dd && dd.tagName?.toLowerCase() === "dd") {
          addPair(dt.textContent, dd.textContent);
        }
      }
    }

    for (const node of doc.querySelectorAll("li,div")) {
      const children = [...node.children];
      if (children.length === 2) {
        const a = clean(children[0].textContent);
        const b = clean(children[1].textContent);
        if (/purity|fineness|karat|carat|gold purity|metal purity/i.test(a)) {
          addPair(a, b);
        }
      }
    }

    const puritySpecText =
      specPairs
        .filter(x =>
          /purity|fineness|karat|carat|gold purity|metal purity/i.test(x.label)
        )
        .map(x => `${x.label}: ${x.value}`)
        .join(" | ");

    const titlePurity =
      parsePurity(name || "");

    const specPurity =
      puritySpecText
        ? parsePurity(puritySpecText)
        : { karat: null, fineness: null, puritySource: null };

    let purity = {
      karat: null,
      fineness: null,
      puritySource: null
    };

    // PDP title is product-specific. Specs are also product-specific.
    // Prefer an explicit title expression; otherwise use explicit specs.
    if (
      titlePurity.puritySource === "explicit-karat-fineness" ||
      titlePurity.puritySource === "explicit-fineness" ||
      titlePurity.puritySource === "karat"
    ) {
      purity = {
        ...titlePurity,
        puritySource: "PDP-spec-explicit"
      };
    } else if (
      specPurity.karat != null ||
      specPurity.fineness
    ) {
      purity = {
        ...specPurity,
        puritySource: "PDP-spec-explicit"
      };
    }

    /* Weight: prefer PDP title first. Whole-body weight is unsafe. */
    let weight =
      parseWeight(name || "");

    if (weight.totalWeightGrams == null) {
      const weightSpecText =
        specPairs
          .filter(x =>
            /weight|net weight|product weight|gold weight/i.test(x.label)
          )
          .map(x => `${x.label}: ${x.value}`)
          .join(" | ");

      if (weightSpecText) {
        weight = parseWeight(weightSpecText);
      }
    }

    return {
      name,
      karat: purity.karat,
      fineness: purity.fineness,
      puritySource: purity.puritySource,
      puritySpecText,
      weight:
        weight.totalWeightGrams != null
          ? `${weight.totalWeightGrams} g`
          : null,
      weightGrams: weight.totalWeightGrams,
      unitWeightGrams: weight.unitWeightGrams,
      quantity: weight.quantity,
      totalWeightGrams: weight.totalWeightGrams,
      componentWeightsGrams: weight.componentWeightsGrams,
      weightSource:
        weight.totalWeightGrams != null
          ? "PDP"
          : null,
      price,
      mrp
    };
  }

  /********************************************************************
   * PDP TARGETS
   ********************************************************************/

  let PDP_TARGETS =
    [
      ...PRODUCTS.values()
    ]
      .filter(
        product => {
          if (
            !CFG.enablePdpFallback
          ) {
            return false;
          }

          if (
            product.metal !==
              "gold"
          ) {
            return false;
          }

          const missingCritical =
            product.totalWeightGrams ==
              null ||
            product.karat == null ||
            !product.fineness ||
            product.price == null;

          const suspicious =
            CFG.enrichSuspicious &&
            product.suspicious;

          return (
            missingCritical ||
            suspicious
          );
        }
      );

  /*
   * No duplicate PIDs.
   */

  PDP_TARGETS =
    [
      ...new Map(
        PDP_TARGETS.map(
          product => [
            product.pid,
            product
          ]
        )
      ).values()
    ];

  const PDP_LOG = [];

  console.log("");
  console.log(
    `🔬 PDP ENRICHMENT REQUIRED: ${PDP_TARGETS.length}`
  );

  /********************************************************************
   * CONCURRENCY POOL
   ********************************************************************/

  async function runPool(
    items,
    concurrency,
    worker
  ) {
    let cursor = 0;

    async function runner() {
      while (true) {
        const index =
          cursor++;

        if (
          index >=
          items.length
        ) {
          return;
        }

        await worker(
          items[index],
          index
        );
      }
    }

    await Promise.all(
      Array.from(
        {
          length:
            Math.min(
              concurrency,
              items.length
            )
        },
        runner
      )
    );
  }

  /********************************************************************
   * PDP ENRICHMENT
   ********************************************************************/

  if (
    PDP_TARGETS.length
  ) {
    await runPool(
      PDP_TARGETS,
      CFG.pdpConcurrency,

      async (
        product,
        index
      ) => {
        if (
          !product.link
        ) {
          PDP_LOG.push({
            pid:
              product.pid,

            success:
              false,

            reason:
              "missing-link"
          });

          return;
        }

        const response =
          await fetchHTML(
            product.link,
            `PDP ${product.pid}`
          );

        if (
          !response.ok
        ) {
          PDP_LOG.push({
            pid:
              product.pid,

            success:
              false,

            http:
              response.status
          });

          return;
        }

        const enriched =
          parsePDP(
            response.html,
            product
          );

        const before =
          JSON.stringify({
            name:
              product.name,

            karat:
              product.karat,

            fineness:
              product.fineness,

            weight:
              product.totalWeightGrams,

            price:
              product.price,

            mrp:
              product.mrp
          });

        /*
         * ----------------------------------------------------------
         * NAME
         * ----------------------------------------------------------
         */

        if (
          enriched.name &&
          (
            !product.name ||
            enriched.name.length >
              product.name.length
          )
        ) {
          product.name =
            enriched.name;
        }

        /*
         * ----------------------------------------------------------
         * PURITY — CONFLICT SAFE
         * ----------------------------------------------------------
         */

        product.purityConflicts =
          product.purityConflicts || [];

        const listingHasExplicitPurity =
          product.puritySource === "listing-explicit-karat-fineness" ||
          product.puritySource === "listing-explicit-fineness";

        const karatConflict =
          product.karat != null &&
          enriched.karat != null &&
          product.karat !== enriched.karat;

        const finenessConflict =
          product.fineness &&
          enriched.fineness &&
          String(product.fineness) !== String(enriched.fineness);

        if (karatConflict || finenessConflict) {
          product.purityConflicts.push({
            listing: {
              karat: product.karat,
              fineness: product.fineness,
              source: product.puritySource
            },
            pdp: {
              karat: enriched.karat,
              fineness: enriched.fineness,
              source: enriched.puritySource,
              specText: enriched.puritySpecText || null
            }
          });
        }

        // Never overwrite explicit listing/title purity with conflicting PDP data.
        if (!listingHasExplicitPurity) {
          if (
            enriched.karat != null &&
            (
              product.karat == null ||
              sourceRank(enriched.puritySource) > sourceRank(product.puritySource)
            )
          ) {
            product.karat = enriched.karat;
          }

          if (
            enriched.fineness &&
            (
              !product.fineness ||
              sourceRank(enriched.puritySource) > sourceRank(product.puritySource)
            )
          ) {
            product.fineness = enriched.fineness;
            product.puritySource = enriched.puritySource;
          }
        }

        /*
         * ----------------------------------------------------------
         * WEIGHT
         *
         * PDP replaces suspicious/missing listing weight.
         * ----------------------------------------------------------
         */

        if (
          enriched.totalWeightGrams !=
            null &&
          (
            product.totalWeightGrams ==
              null ||
            product.weightConflict ||
            product.suspiciousReasons
              ?.includes(
                "suspicious-weight"
              )
          )
        ) {
          product.weight =
            enriched.weight;

          product.weightGrams =
            enriched.weightGrams;

          product.unitWeightGrams =
            enriched.unitWeightGrams;

          product.quantity =
            enriched.quantity;

          product.totalWeightGrams =
            enriched.totalWeightGrams;

          product.componentWeightsGrams =
            enriched.componentWeightsGrams;

          product.weightSource =
            "PDP";

          product.weightConflict =
            false;
        }

        /*
         * ----------------------------------------------------------
         * PRICE
         * ----------------------------------------------------------
         */

        if (
          enriched.price != null &&
          (
            product.price == null ||
            product.priceSuspicious ||
            product.suspiciousReasons
              ?.includes(
                "suspicious-price"
              ) ||
            product.suspiciousReasons
              ?.includes(
                "mrp-below-price"
              )
          )
        ) {
          product.price =
            enriched.price;

          if (
            enriched.mrp != null
          ) {
            product.mrp =
              enriched.mrp;
          }

          product.priceSuspicious =
            false;
        }

        /*
         * Recalculate discount.
         */

        if (
          product.price != null &&
          product.mrp != null &&
          product.mrp >=
            product.price &&
          product.mrp > 0
        ) {
          product.discountPercent =
            +(
              (
                (
                  product.mrp -
                  product.price
                ) /
                product.mrp *
                100
              ).toFixed(2)
            );
        }

        /*
         * Final 22K fallback.
         */

        if (
          product.karat === 22 &&
          !product.fineness
        ) {
          product.fineness =
            "916";

          product.puritySource =
            "karat-standard";
        }

        product.suspiciousReasons =
          calculateSuspicion(
            product
          );

        product.suspicious =
          product
            .suspiciousReasons
            .length > 0;

        PRODUCTS.set(
          product.pid,
          product
        );

        const after =
          JSON.stringify({
            name:
              product.name,

            karat:
              product.karat,

            fineness:
              product.fineness,

            weight:
              product.totalWeightGrams,

            price:
              product.price,

            mrp:
              product.mrp
          });

        const improved =
          before !== after;

        PDP_LOG.push({
          pid:
            product.pid,

          http:
            response.status,

          improved,

          remainingIssues:
            product
              .suspiciousReasons
              .join(" | ")
        });

        console.log(
          `🔬 ${index + 1}/${PDP_TARGETS.length}` +
          ` ${product.pid}` +
          ` | ${product.totalWeightGrams ?? "?"}g` +
          ` | ${product.karat ?? "?"}K` +
          ` | ${product.fineness ?? "?"}` +
          ` | ₹${product.price ?? "?"}` +
          (
            product.suspicious
              ? ` | ⚠ ${product.suspiciousReasons.join(", ")}`
              : " | ✅"
          )
        );

        await sleep(
          CFG.pdpDelayMs
        );
      }
    );
  }

  /********************************************************************
   * FINAL NORMALIZATION
   ********************************************************************/

  const FINAL =
    [
      ...PRODUCTS.values()
    ];

  for (
    const product
    of FINAL
  ) {
    /*
     * Convert Set -> string for output.
     */

    product.streams =
      product.streams
        instanceof Set
        ? [
            ...product.streams
          ].join(" | ")
        : clean(
            product.streams
          );

    /*
     * Weight display.
     */

    if (
      product.totalWeightGrams !=
        null
    ) {
      product.weight =
        `${+Number(
          product.totalWeightGrams
        ).toFixed(4)} g`;

      product.weightGrams =
        product.totalWeightGrams;
    }

    /*
     * Final purity inference.
     */

    if (
      product.karat == null &&
      product.fineness
    ) {
      product.karat =
        karatFromFineness(
          product.fineness
        );
    }

    if (
      product.karat === 22 &&
      !product.fineness
    ) {
      product.fineness =
        "916";

      product.puritySource =
        "karat-standard";
    }

    product.suspiciousReasons =
      calculateSuspicion(
        product
      );

    product.suspicious =
      product
        .suspiciousReasons
        .length > 0;
  }

  /********************************************************************
   * SORT
   ********************************************************************/

  FINAL.sort(
    (a, b) => {
      const aw =
        a.totalWeightGrams ??
        Infinity;

      const bw =
        b.totalWeightGrams ??
        Infinity;

      if (
        aw !== bw
      ) {
        return aw - bw;
      }

      const ap =
        a.price ??
        Infinity;

      const bp =
        b.price ??
        Infinity;

      return ap - bp;
    }
  );

  /********************************************************************
   * FINAL GROUPS
   ********************************************************************/

  const GOLD =
    FINAL.filter(
      product =>
        product.metal ===
        "gold"
    );

  const INCOMPLETE =
    GOLD.filter(
      product =>
        product.totalWeightGrams ==
          null ||
        product.karat == null ||
        !product.fineness ||
        product.price == null
    );

  const SUSPICIOUS =
    GOLD.filter(
      product =>
        product.suspicious
    );

  const MISSING_WEIGHT =
    GOLD.filter(
      product =>
        product.totalWeightGrams ==
        null
    );

  const MISSING_KARAT =
    GOLD.filter(
      product =>
        product.karat == null
    );

  const MISSING_FINENESS =
    GOLD.filter(
      product =>
        !product.fineness
    );

  const MISSING_PRICE =
    GOLD.filter(
      product =>
        product.price == null
    );

  /********************************************************************
   * PUBLIC DATA
   ********************************************************************/

  window.flipkartProducts =
    FINAL;

  window.flipkartGold =
    GOLD;

  window.flipkartIncomplete =
    INCOMPLETE;

  window.flipkartSuspicious =
    SUSPICIOUS;

  window.flipkartMissingWeight =
    MISSING_WEIGHT;

  window.flipkartMissingKarat =
    MISSING_KARAT;

  window.flipkartMissingFineness =
    MISSING_FINENESS;

  window.flipkartMissingPrice =
    MISSING_PRICE;

  window.flipkartStreamLog =
    STREAM_LOG;

  window.flipkartPdpLog =
    PDP_LOG;

  const bridgeResult = await fetch("http://localhost:8788/api/browser-bridge/products", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      store: "flipkart.com",
      records: GOLD.map(product => ({
        bridgeSnapshot: true,
        productId: product.pid,
        url: product.link,
        name: product.name,
        brand: product.brand,
        price: product.price,
        metal: product.metal,
        grams: product.totalWeightGrams,
        karat: product.karat,
        purity: product.fineness
      }))
    })
  }).then(r => r.json()).catch(error => ({ error: String(error) }));
  console.log("Aurum Flipkart bridge:", bridgeResult);

  /********************************************************************
   * STATS
   ********************************************************************/

  window.flipkartStats = {
    canonicalReportedTotal,

    unique:
      FINAL.length,

    differenceVsReported:
      canonicalReportedTotal ==
        null
        ? null
        : FINAL.length -
          canonicalReportedTotal,

    gold:
      GOLD.length,

    missingWeight:
      MISSING_WEIGHT.length,

    missingKarat:
      MISSING_KARAT.length,

    missingFineness:
      MISSING_FINENESS.length,

    missingPrice:
      MISSING_PRICE.length,

    incomplete:
      INCOMPLETE.length,

    suspicious:
      SUSPICIOUS.length,

    purityConflicts:
      GOLD.filter(p => p.purityConflicts?.length).length,

    pdpRequested:
      PDP_TARGETS.length,

    pdpImproved:
      PDP_LOG.filter(
        item =>
          item.improved
      ).length
  };

  /********************************************************************
   * OUTPUT TABLE
   ********************************************************************/

  function tableRows(
    products
  ) {
    return products.map(
      product => ({
        pid:
          product.pid,

        brand:
          product.brand,

        name:
          product.name,

        metal:
          product.metal,

        weight:
          product.weight,

        weightGrams:
          product.weightGrams,

        unitWeightGrams:
          product.unitWeightGrams,

        quantity:
          product.quantity,

        totalWeightGrams:
          product.totalWeightGrams,

        components:
          Array.isArray(
            product.componentWeightsGrams
          )
            ? product
                .componentWeightsGrams
                .join(" + ")
            : null,

        karat:
          product.karat,

        fineness:
          product.fineness,

        puritySource:
          product.puritySource,

        purityConflicts:
          product.purityConflicts?.length || 0,

        price:
          product.price,

        mrp:
          product.mrp,

        discountPercent:
          product.discountPercent,

        rating:
          product.rating,

        suspicious:
          product.suspicious,

        issues:
          product
            .suspiciousReasons
            ?.join(" | ") ||
          null,

        streams:
          product.streams,

        image:
          product.image,

        link:
          product.link
      })
    );
  }

  window.flipkartTableData =
    tableRows(
      FINAL
    );

  /********************************************************************
   * CONSOLE COMMANDS
   ********************************************************************/

  window.flipkartTable =
    () => {
      console.table(
        tableRows(
          FINAL
        )
      );

      return FINAL;
    };

  window.flipkartIncompleteTable =
    () => {
      console.table(
        tableRows(
          INCOMPLETE
        )
      );

      return INCOMPLETE;
    };

  window.flipkartSuspiciousTable =
    () => {
      console.table(
        tableRows(
          SUSPICIOUS
        )
      );

      return SUSPICIOUS;
    };

  window.flipkartMissingWeightTable =
    () => {
      console.table(
        tableRows(
          MISSING_WEIGHT
        )
      );

      return MISSING_WEIGHT;
    };

  window.flipkartMissingPurityTable =
    () => {
      const rows =
        GOLD.filter(
          product =>
            product.karat ==
              null ||
            !product.fineness
        );

      console.table(
        tableRows(
          rows
        )
      );

      return rows;
    };

  window.flipkartStreamTable =
    () => {
      console.table(
        STREAM_LOG
      );

      return STREAM_LOG;
    };

  window.flipkartPdpTable =
    () => {
      console.table(
        PDP_LOG
      );

      return PDP_LOG;
    };

  /********************************************************************
   * DIAGNOSTIC
   ********************************************************************/

  window.flipkartDiagnostic =
    () => {
      console.log(
        "=== FLIPKART DIAGNOSTIC ==="
      );

      console.log(
        window.flipkartStats
      );

      console.log("");
      console.log(
        "SUSPICIOUS"
      );

      console.table(
        tableRows(
          SUSPICIOUS
        )
      );

      /*
       * Weight distribution.
       */

      const weightMap = {};

      for (
        const product
        of GOLD
      ) {
        const key =
          product.totalWeightGrams ??
          "MISSING";

        weightMap[key] =
          (
            weightMap[key] ||
            0
          ) + 1;
      }

      console.log("");
      console.log(
        "WEIGHT DISTRIBUTION"
      );

      console.table(
        Object.entries(
          weightMap
        )
          .map(
            ([
              weight,
              count
            ]) => ({
              weight,
              count
            })
          )
          .sort(
            (a, b) => {
              if (
                a.weight ===
                "MISSING"
              ) {
                return 1;
              }

              if (
                b.weight ===
                "MISSING"
              ) {
                return -1;
              }

              return (
                Number(a.weight) -
                Number(b.weight)
              );
            }
          )
      );

      /*
       * Purity distribution.
       */

      const purityMap = {};

      for (
        const product
        of GOLD
      ) {
        const key =
          `${product.karat ?? "?"}K / ${product.fineness ?? "?"}`;

        purityMap[key] =
          (
            purityMap[key] ||
            0
          ) + 1;
      }

      console.log("");
      console.log(
        "PURITY DISTRIBUTION"
      );

      console.table(
        Object.entries(
          purityMap
        )
          .map(
            ([
              purity,
              count
            ]) => ({
              purity,
              count
            })
          )
          .sort(
            (a, b) =>
              b.count -
              a.count
          )
      );

      return {
        stats:
          window.flipkartStats,

        suspicious:
          SUSPICIOUS
      };
    };

  /********************************************************************
   * CSV
   ********************************************************************/

  const CSV_COLUMNS = [
    "pid",
    "brand",
    "name",
    "metal",

    "weight",
    "weightGrams",
    "unitWeightGrams",
    "quantity",
    "totalWeightGrams",
    "components",

    "karat",
    "fineness",
    "puritySource",
    "purityConflicts",

    "price",
    "mrp",
    "discountPercent",

    "rating",

    "suspicious",
    "issues",

    "streams",

    "image",
    "link"
  ];

  function csvEscape(
    value
  ) {
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

  function makeCSV() {
    const rows =
      tableRows(
        FINAL
      );

    return [
      CSV_COLUMNS
        .map(
          csvEscape
        )
        .join(","),

      ...rows.map(
        row =>
          CSV_COLUMNS
            .map(
              column =>
                csvEscape(
                  row[column]
                )
            )
            .join(",")
      )
    ].join("\n");
  }

  /********************************************************************
   * DOWNLOAD
   ********************************************************************/

  function download(
    content,
    filename,
    mime
  ) {
    const blob =
      new Blob(
        [content],
        {
          type:
            mime
        }
      );

    const url =
      URL.createObjectURL(
        blob
      );

    const anchor =
      document.createElement(
        "a"
      );

    anchor.href =
      url;

    anchor.download =
      filename;

    document.body
      .appendChild(
        anchor
      );

    anchor.click();

    anchor.remove();

    setTimeout(
      () =>
        URL.revokeObjectURL(
          url
        ),
      1000
    );
  }

  window.flipkartDownloadCSV =
    () =>
      download(
        "\uFEFF" +
        makeCSV(),

        `flipkart-coins-bars-${FINAL.length}.csv`,

        "text/csv;charset=utf-8"
      );

  window.flipkartDownloadJSON =
    () =>
      download(
        JSON.stringify(
          FINAL,
          null,
          2
        ),

        `flipkart-coins-bars-${FINAL.length}.json`,

        "application/json;charset=utf-8"
      );

  window.flipkartCopyCSV =
    async () => {
      await navigator.clipboard
        .writeText(
          makeCSV()
        );

      console.log(
        "📋 CSV copied."
      );
    };

  window.flipkartCopyJSON =
    async () => {
      await navigator.clipboard
        .writeText(
          JSON.stringify(
            FINAL,
            null,
            2
          )
        );

      console.log(
        "📋 JSON copied."
      );
    };

  /********************************************************************
   * FINISH
   ********************************************************************/

  console.log("");
  console.log(
    "================================================"
  );

  console.log(
    "✅ FLIPKART EXTRACTION COMPLETE"
  );

  console.log(
    "================================================"
  );

  console.log(
    window.flipkartStats
  );

  console.log("");
  console.log(
    `FULL TABLE (${FINAL.length})`
  );

  console.table(
    tableRows(
      FINAL
    )
  );

  if (
    INCOMPLETE.length === 0
  ) {
    console.log(
      "🎯 NO REQUIRED FIELDS MISSING"
    );
  } else {
    console.warn(
      `⚠️ ${INCOMPLETE.length} INCOMPLETE PRODUCTS`
    );

    console.table(
      tableRows(
        INCOMPLETE
      )
    );
  }

  if (
    SUSPICIOUS.length
  ) {
    console.warn(
      `⚠️ ${SUSPICIOUS.length} PRODUCTS NEED REVIEW`
    );

    console.table(
      tableRows(
        SUSPICIOUS
      )
    );
  } else {
    console.log(
      "✅ NO SUSPICIOUS PRODUCT VALUES"
    );
  }

  console.log("");
  console.log(
    "COMMANDS:"
  );

  console.log(
    "window.flipkartStats"
  );

  console.log(
    "window.flipkartProducts"
  );

  console.log(
    "window.flipkartGold"
  );

  console.log(
    "window.flipkartIncomplete"
  );

  console.log(
    "window.flipkartSuspicious"
  );

  console.log("");

  console.log(
    "flipkartTable()"
  );

  console.log(
    "flipkartIncompleteTable()"
  );

  console.log(
    "flipkartSuspiciousTable()"
  );

  console.log(
    "flipkartMissingWeightTable()"
  );

  console.log(
    "flipkartMissingPurityTable()"
  );

  console.log(
    "flipkartStreamTable()"
  );

  console.log(
    "flipkartPdpTable()"
  );

  console.log(
    "flipkartDiagnostic()"
  );

  console.log("");

  console.log(
    "flipkartDownloadCSV()"
  );

  console.log(
    "flipkartDownloadJSON()"
  );

  console.log(
    "flipkartCopyCSV()"
  );

  console.log(
    "flipkartCopyJSON()"
  );

  console.log(
    "================================================"
  );

  return {
    stats:
      window.flipkartStats,

    products:
      FINAL,

    incomplete:
      INCOMPLETE,

    suspicious:
      SUSPICIOUS
  };
})();