window.ajioDone = (async () => {
  "use strict";

  const priorRun =
    window.__AJIO_GOLD_RUN__;

  if (priorRun?.cancel) {
    priorRun.cancel();
  }

  const runToken =
    Symbol("ajio-gold-run");

  let cancelled = false;

  window.__AJIO_GOLD_RUN__ = {
    token: runToken,
    cancel: () => {
      cancelled = true;
    }
  };

  for (const name of [
    "ajioStats",
    "ajioProducts",
    "ajioGold",
    "ajioAllSearchResults",
    "ajioRawProducts",
    "ajioExcluded",
    "ajioIncomplete",
    "ajioSearchLog",
    "ajioPageCoverage",
    "ajioPdpLog",
    "ajioTable",
    "ajioIncompleteTable",
    "ajioExcludedTable",
    "ajioSearchTable",
    "ajioCoverageTable",
    "ajioPdpTable",
    "ajioDiagnostic",
    "ajioDownloadCSV",
    "ajioDownloadJSON",
    "ajioDownloadRawJSON",
    "ajioDownloadAllJSON",
    "ajioDownloadFullJSON",
    "ajioCopyCSV",
    "ajioCopyJSON",
    "ajioCopyRawJSON",
    "ajioCopyAllJSON",
    "ajioAllData"
  ]) {
    delete window[name];
  }

  window.ajioProgress = {
    stage: "starting",
    cancelled: false
  };


  console.clear();

  /******************************************************************
   * AJIO GOLD MASTER V8 — UNIFIED API / PARALLEL / FINAL
   * ================================================================
   *
   * DISCOVERY
   * - Reads AJIO's own plpRequestMobile when available.
   * - Calls /api/search directly.
   * - Uses LIVE pagination.totalPages / totalResults.
   * - Fetches all remaining pages in parallel.
   * - NO ReactVirtualized scrolling.
   *
   * DATA
   * - code / name / brand
   * - metal
   * - weight
   * - karat
   * - purity / fineness
   * - price
   * - wasPrice
   * - offerPrice
   * - discount
   * - image
   * - URL
   *
   * CORRECTNESS
   * - Silver / sterling / gold-plated non-gold products are excluded
   *   from the genuine-gold result.
   * - 22K => 916 when fineness isn't explicitly supplied.
   * - 24K => 999 when fineness isn't explicitly supplied.
   * - mg -> g.
   * - component expressions supported.
   * - each x pieces supported.
   * - set/pack trailing weight is treated as stated TOTAL unless
   *   title explicitly says "each" / "per piece".
   * - PDP HTML is fetched ONLY for genuine-gold unresolved rows.
   *
   * Run directly on the current AJIO PLP/search page.
   ******************************************************************/

  const CFG = {
    searchConcurrency: 1,
    searchDelayMs: 300,
    pdpConcurrency: 2,
    pdpDelayMs: 300,

    searchTimeoutMs: 20000,
    pdpTimeoutMs: 8000,

    retries: 3,
    pdpRetries: 1,

    transientBackoffMs: [1000, 2000, 4000],

    /*
     * We want requested gold fields complete.
     *
     * If a 24K title gives no explicit fineness, this script uses
     * the conventional 999 value, matching the behaviour of your
     * earlier AJIO extractor.
     */
    infer24K999: true,

    /*
     * Do not print hundreds/thousands of rows automatically.
     * Run ajioTable() when wanted.
     */
    autoPrintFullTable: false,

    // Do not trust stale AJIO localStorage request state for master/category pages.
    preferCurrentPageRequest: true
  };

  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

  const startedAt = performance.now();

  const PRODUCT_MAP = new Map();

  // Preserve untouched AJIO /api/search product objects for debugging/export.
  const RAW_PRODUCT_MAP = new Map();

  const SEARCH_LOG = [];
  const PDP_LOG = [];

  let searchRequests = 0;
  let pdpRequests = 0;
  let accessDenied = false;
  let pdpAccessDenied = false;

  function isCancelled() {
    return cancelled ||
      window.__AJIO_GOLD_RUN__?.token !== runToken;
  }

  /******************************************************************
   * BASIC
   ******************************************************************/

  const clean = value =>
    String(value ?? "")
      .replace(/<br\s*\/?>/gi, " ")
      .replace(/<[^>]+>/g, " ")
      .replace(/&nbsp;/gi, " ")
      .replace(/&amp;/gi, "&")
      .replace(/\u00a0/g, " ")
      .replace(/\s+/g, " ")
      .trim();

  function number(value) {
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

    /*
     * AJIO price objects commonly contain value.
     */
    if (
      typeof value === "object"
    ) {
      for (
        const key
        of [
          "value",
          "formattedValue",
          "price",
          "amount",
          "mrp"
        ]
      ) {
        if (
          value[key] !== undefined
        ) {
          const n =
            number(value[key]);

          if (n != null)
            return n;
        }
      }

      return null;
    }

    const m =
      String(value)
        .replace(/,/g, "")
        .match(
          /-?\d+(?:\.\d+)?/
        );

    if (!m)
      return null;

    const n =
      Number(m[0]);

    return Number.isFinite(n)
      ? n
      : null;
  }

  function absoluteUrl(path) {
    if (!path)
      return null;

    try {
      const u =
        new URL(
          path,
          location.origin
        );

      /*
       * Keep product identity query-free.
       */
      u.hash = "";

      return u.href;
    } catch {
      return null;
    }
  }

  function imageUrl(raw) {
    const candidates = [
      raw?.images?.[0]?.url,
      raw?.images?.[0]?.images?.[0]?.url,
      raw?.fnlProductData?.images?.[0]?.url,
      raw?.extraImages?.[0]?.images?.[0]?.url
    ];

    const x =
      candidates.find(Boolean);

    if (!x)
      return null;

    return String(x)
      .replace(
        /^http:/i,
        "https:"
      );
  }

  /******************************************************************
   * METAL
   ******************************************************************/

  const SILVER_RE =
    /\b(?:silver|siilver|sliver|sterling|925\s*silver|silverware|chandi)\b/i;

  const NON_GOLD_RE =
    /\b(?:silver|siilver|sliver|chandi|platinum|brass|copper|bronze|steel|stainless\s+steel|zinc|alloy|aluminium|aluminum|iron|plastic|wood|wooden)\b/i;

  const GOLD_PLATED_RE =
    /\b(?:gold[\s-]*plated|gold[\s-]*toned?|gold[\s-]*finish|gold[\s-]*colou?r(?:ed)?|gold[\s-]*polished|gold[\s-]*coated)\b/i;

  const GOLD_RE =
    /\b(?:gold|yellow\s+gold|rose\s+gold|white\s+gold)\b/i;

  function detectMetal(text) {
    const s = clean(text);

    if (SILVER_RE.test(s)) {
      return "silver";
    }

    if (NON_GOLD_RE.test(s)) {
      return "non-gold";
    }

    if (GOLD_PLATED_RE.test(s)) {
      return "non-gold";
    }

    if (
      GOLD_RE.test(s) ||
      /\b(?:24|23|22|21|20|18|14|10|9)\s*(?:k|kt)(?![a-z])/i.test(s) ||
      /(?:^|[^0-9])(?:999\.9|999|995|916)(?!\d)/i.test(s)
    ) {
      return "gold";
    }

    return null;
  }

  /******************************************************************
   * PURITY
   ******************************************************************/

  function normalizeFineness(raw) {
    if (raw == null)
      return null;

    let s =
      String(raw)
        .replace(",", ".")
        .replace(/[^\d.]/g, "");

    if (!s)
      return null;

    if (s === "9999")
      return "999.9";

    if (s === "9167")
      return "916.7";

    const n =
      Number(s);

    if (!Number.isFinite(n))
      return null;

    if (
      n >= 1000 &&
      n <= 9999
    ) {
      return String(
        n / 10
      );
    }

    if (
      n >= 300 &&
      n <= 1000
    ) {
      return String(n);
    }

    return null;
  }

  function standardFineness(k) {
    return ({
      24: CFG.infer24K999
        ? "999"
        : null,

      23: "958",
      22: "916",
      21: "875",
      20: "833",
      18: "750",
      14: "585",
      10: "417",
      9: "375"
    })[k] ?? null;
  }

  function karatFromFineness(f) {
    const n =
      Number(f);

    if (!Number.isFinite(n))
      return null;

    if (n >= 990)
      return 24;

    if (
      n >= 957 &&
      n <= 959
    )
      return 23;

    if (
      n >= 915 &&
      n <= 918
    )
      return 22;

    if (
      n >= 874 &&
      n <= 876
    )
      return 21;

    if (
      n >= 832 &&
      n <= 834
    )
      return 20;

    if (
      n >= 749 &&
      n <= 751
    )
      return 18;

    if (
      n >= 584 &&
      n <= 586
    )
      return 14;

    if (
      n >= 416 &&
      n <= 418
    )
      return 10;

    if (
      n >= 374 &&
      n <= 376
    )
      return 9;

    return null;
  }

  function parsePurity(text, metal = null) {
    const s = clean(text);

    const detectedMetal =
      metal || detectMetal(s);

    if (
      detectedMetal === "silver" ||
      detectedMetal === "non-gold"
    ) {
      return {
        karat: null,
        purity: null,
        source: null
      };
    }

    let karat = null;
    let purity = null;
    let source = null;

    let m;

    const K =
      "(24|23|22|21|20|18|14|10|9)";

    const F =
      "(9999|999\\.9\\+?|999|995|990|958|916\\.7|9167|916|875|833|750|585|417|375)";

    /*
     * Explicit pair:
     *
     * 24 KT (999)
     * 22K (916)
     */
    m = s.match(
      new RegExp(
        `\\b${K}\\s*(?:kt|k|karat|carat)\\s*\\(\\s*${F}\\s*\\)`,
        "i"
      )
    );

    if (m) {
      return {
        karat: Number(m[1]),
        purity: normalizeFineness(m[2]),
        source: "title-explicit-both"
      };
    }

    /*
     * Explicit pair without parentheses.
     *
     * IMPORTANT:
     * Do NOT require \b after fineness.
     *
     * Handles:
     *   24 KT 995Yellow Gold
     *   22KT 916Gold
     *   24K 999Yellow Gold
     */
    m = s.match(
      new RegExp(
        `\\b${K}\\s*(?:kt|k|karat|carat)\\s*[-:/]?\\s*${F}(?!\\d)`,
        "i"
      )
    );

    if (m) {
      return {
        karat: Number(m[1]),
        purity: normalizeFineness(m[2]),
        source: "title-explicit-both"
      };
    }

    /*
     * Karat alone.
     */
    m = s.match(
      /\b(24|23|22|21|20|18|14|10|9)\s*(?:kt|k|karat|carat)(?![a-z])/i
    );

    if (m) {
      karat = Number(m[1]);
      source = "title-karat";
    }

    /*
     * Standalone fineness.
     *
     * (?!\d) is intentional instead of \b.
     * Therefore:
     *
     *   995Yellow
     *
     * is valid.
     *
     * But:
     *
     *   9950
     *
     * is not interpreted as 995.
     */
    const fm = s.match(
      /(?:^|[^0-9])(999\.9\+?|999|995|990|958|916\.7|916|875|833|750|585|417|375)(?!\d)(?!\s*(?:mg|g|gm|gms|gram|grams)\b)/i
    );

    if (fm) {
      purity =
        normalizeFineness(
          fm[1]
        );

      source =
        "title-fineness";
    }

    if (
      karat == null &&
      purity
    ) {
      karat =
        karatFromFineness(
          purity
        );
    }

    /*
     * Only infer fineness AFTER exhausting explicit fineness.
     */
    if (
      karat &&
      !purity
    ) {
      purity =
        standardFineness(
          karat
        );

      if (purity) {
        source =
          "karat-standard";
      }
    }

    return {
      karat,
      purity,
      source
    };
  }

  /******************************************************************
   * WEIGHT
   ******************************************************************/

  function normalizeWeightText(text) {
    return clean(text)
      .toLowerCase()
      .replace(/[×✕✖]/g, "x")
      .replace(
        /(\d)\s*milligrams?/gi,
        "$1 mg"
      )
      .replace(
        /(\d)\s*mgs?/gi,
        "$1 mg"
      )
      .replace(
        /(\d)\s*kilograms?/gi,
        "$1 kg"
      )
      .replace(
        /(\d)\s*kgs?/gi,
        "$1 kg"
      )
      .replace(
        /(\d)\s*grams?/gi,
        "$1 g"
      )
      .replace(
        /(\d)\s*gms?/gi,
        "$1 g"
      )
      .replace(
        /(\d)\s*gm/gi,
        "$1 g"
      )
      .replace(
        /\bpieces?\b/gi,
        "pcs"
      )
      .replace(
        /\s+/g,
        " "
      )
      .trim();
  }

  function grams(value, unit = "g") {
    let n =
      Number(value);

    if (!Number.isFinite(n))
      return null;

    unit =
      String(unit)
        .toLowerCase();

    if (unit === "mg")
      n /= 1000;

    if (unit === "kg")
      n *= 1000;

    return n;
  }

  function validWeight(n) {
    return (
      Number.isFinite(n) &&
      n > 0 &&
      n <= 100
    );
  }

  function weightResult(x = {}) {
    return {
      total: null,
      unit: null,
      quantity: null,
      components: null,
      source: null,
      confidence: 0,
      ambiguous: false,
      conflict: false,
      ...x
    };
  }

  function parseWeight(text) {
    const s =
      normalizeWeightText(
        text
      );

    if (!s)
      return weightResult();

    let m;

    /*
     * 5g each x 5 pcs
     */
    m = s.match(
      /(\d+(?:\.\d+)?)\s*(mg|g|kg)\s*each\s*x\s*(\d+)\s*pcs\b/i
    );

    if (m) {
      const unit =
        grams(
          m[1],
          m[2]
        );

      const quantity =
        Number(m[3]);

      const calculated =
        unit * quantity;

      /*
       * Check nearest preceding weight for an explicit total.
       */
      const before =
        s.slice(
          0,
          m.index
        );

      const prior =
        [
          ...before.matchAll(
            /(\d+(?:\.\d+)?)\s*(mg|g|kg)\b/gi
          )
        ]
          .map(x =>
            grams(
              x[1],
              x[2]
            )
          )
          .filter(
            validWeight
          );

      const stated =
        prior.length
          ? prior[
          prior.length - 1
          ]
          : null;

      const verified =
        stated != null &&
        Math.abs(
          stated -
          calculated
        ) < 0.0001;

      return weightResult({
        total:
          verified
            ? stated
            : calculated,

        unit,
        quantity,

        components:
          Array(quantity)
            .fill(unit),

        source:
          verified
            ? "verified-total+each-x-pcs"
            : "each-x-pcs",

        confidence: 130
      });
    }

    /*
     * 3 pcs ... 5g each
     */
    m = s.match(
      /\b(\d+)\s*-?\s*pcs\b[\s\S]*?(\d+(?:\.\d+)?)\s*(mg|g|kg)\s*each\b/i
    );

    if (m) {
      const quantity =
        Number(m[1]);

      const unit =
        grams(
          m[2],
          m[3]
        );

      return weightResult({
        total:
          unit * quantity,

        unit,
        quantity,

        components:
          Array(quantity)
            .fill(unit),

        source:
          "pcs-x-each",

        confidence: 130
      });
    }

    /*
     * Explicit total + components:
     * 4g (2g + 2g)
     */
    m = s.match(
      /(\d+(?:\.\d+)?)\s*(mg|g|kg)\s*\(\s*([^)]*\+[^)]*)\)/i
    );

    if (m) {
      const total =
        grams(
          m[1],
          m[2]
        );

      const defaultUnit =
        m[2];

      const components =
        m[3]
          .split("+")
          .map(part => {
            const x =
              part.match(
                /(\d+(?:\.\d+)?)\s*(mg|g|kg)?/i
              );

            return x
              ? grams(
                x[1],
                x[2] ||
                defaultUnit
              )
              : null;
          })
          .filter(
            validWeight
          );

      if (
        components.length >= 2
      ) {
        const calculated =
          components.reduce(
            (a, b) =>
              a + b,
            0
          );

        return weightResult({
          total,

          unit:
            components.every(
              x =>
                Math.abs(
                  x -
                  components[0]
                ) < 0.0001
            )
              ? components[0]
              : null,

          quantity:
            components.length,

          components,

          conflict:
            Math.abs(
              total -
              calculated
            ) > 0.0001,

          source:
            "explicit-total+components",

          confidence: 130
        });
      }
    }

    /*
     * Components without explicit total.
     */
    m = s.match(
      /((?:\d+(?:\.\d+)?\s*(?:mg|g|kg)?\s*\+\s*)+\d+(?:\.\d+)?\s*(?:mg|g|kg))/i
    );

    if (m) {
      const finalUnit =
        m[1].match(
          /(mg|g|kg)\s*$/i
        )?.[1] || "g";

      const components =
        m[1]
          .split("+")
          .map(part => {
            const x =
              part.match(
                /(\d+(?:\.\d+)?)\s*(mg|g|kg)?/i
              );

            return x
              ? grams(
                x[1],
                x[2] ||
                finalUnit
              )
              : null;
          })
          .filter(
            validWeight
          );

      if (
        components.length >= 2
      ) {
        const total =
          components.reduce(
            (a, b) =>
              a + b,
            0
          );

        return weightResult({
          total,

          unit:
            components.every(
              x =>
                Math.abs(
                  x -
                  components[0]
                ) < 0.0001
            )
              ? components[0]
              : null,

          quantity:
            components.length,

          components,

          source:
            "components",

          confidence: 125
        });
      }
    }

    /*
     * Set/pack with explicit EACH.
     */
    m = s.match(
      /\b(?:set|pack|combo)\s+of\s+(\d+)\b[\s\S]*?(\d+(?:\.\d+)?)\s*(mg|g|kg)\s*each\b/i
    );

    if (m) {
      const quantity =
        Number(m[1]);

      const unit =
        grams(
          m[2],
          m[3]
        );

      return weightResult({
        total:
          quantity * unit,

        unit,
        quantity,

        components:
          Array(quantity)
            .fill(unit),

        source:
          "set-x-each",

        confidence: 125
      });
    }

    /*
     * Set/pack WITHOUT "each":
     *
     * trailing stated weight = listing total.
     */
    const set =
      s.match(
        /\b(?:set|pack|combo)\s+of\s+(\d+)\b/i
      );

    if (set) {
      const quantity =
        Number(set[1]);

      const weights =
        [
          ...s.matchAll(
            /(\d+(?:\.\d+)?)\s*(mg|g|kg)\b/gi
          )
        ]
          .map(x =>
            grams(
              x[1],
              x[2]
            )
          )
          .filter(
            validWeight
          );

      if (weights.length) {
        const total =
          weights[
          weights.length - 1
          ];

        return weightResult({
          total,
          unit: null,
          quantity,

          source:
            "set-pack-stated-total",

          confidence: 110
        });
      }
    }

    /*
     * Labeled weight.
     */
    m = s.match(
      /\b(?:total\s+(?:gold\s+)?weight|net\s+weight|gold\s+weight|product\s+weight|item\s+weight|weight)\s*:?\s*(\d+(?:\.\d+)?)\s*(mg|g|kg)\b/i
    );

    if (m) {
      const total =
        grams(
          m[1],
          m[2]
        );

      return weightResult({
        total,
        unit: total,
        quantity: 1,

        source:
          "weight-label",

        confidence: 115
      });
    }

    /*
     * Product contextual weight.
     */
    const patterns = [
      /\b(?:gold\s+)?(?:coin|bar|pendant|vedhani|biscuit)\b[^0-9]{0,50}(\d+(?:\.\d+)?)\s*(mg|g|kg)\b/i,

      /(\d+(?:\.\d+)?)\s*(mg|g|kg)\b[^0-9]{0,60}(?:gold\s+)?(?:coin|bar|pendant|vedhani|biscuit)\b/i
    ];

    for (
      const re
      of patterns
    ) {
      m = s.match(re);

      if (!m)
        continue;

      let total =
        grams(
          m[1],
          m[2]
        );

      /*
       * Known catalogue typo protection:
       * 500g bullion is almost certainly 500mg.
       */
      if (
        String(m[2])
          .toLowerCase() === "g" &&
        total >= 100 &&
        total <= 999 &&
        /\bgold\b/i.test(s) &&
        /\b(?:coin|bar|bullion|biscuit)\b/i
          .test(s)
      ) {
        total /= 1000;

        return weightResult({
          total,
          unit: total,
          quantity: 1,

          source:
            "corrected-g-to-mg",

          confidence: 95
        });
      }

      return weightResult({
        total,
        unit: total,
        quantity: 1,

        source:
          "product-weight",

        confidence: 110
      });
    }

    /*
     * Final weight in title.
     */
    const weights =
      [
        ...s.matchAll(
          /(\d+(?:\.\d+)?)\s*(mg|g|kg)\b/gi
        )
      ]
        .map(x => ({
          value:
            grams(
              x[1],
              x[2]
            ),

          unit:
            x[2]
        }))
        .filter(x =>
          validWeight(
            x.value
          )
        );

    if (weights.length) {
      const last =
        weights[
        weights.length - 1
        ];

      let total =
        last.value;

      if (
        String(last.unit)
          .toLowerCase() === "g" &&
        total >= 100 &&
        total <= 999 &&
        /\bgold\b/i.test(s) &&
        /\b(?:coin|bar|bullion|biscuit)\b/i
          .test(s)
      ) {
        total /= 1000;

        return weightResult({
          total,
          unit: total,
          quantity: 1,

          source:
            "corrected-last-g-to-mg",

          confidence: 90
        });
      }

      return weightResult({
        total,
        unit: total,
        quantity: 1,

        source:
          "title-last-weight",

        confidence: 90
      });
    }

    return weightResult();
  }

  /******************************************************************
   * SEARCH TEXT FROM RAW AJIO PRODUCT
   ******************************************************************/

  function rawProductText(raw) {
    const pieces = [
      raw?.name,
      raw?.catalogName,
      raw?.brickName,
      raw?.verticalName,
      raw?.segmentName,
      raw?.brandTypeName,
      raw?.segmentNameText,
      raw?.verticalNameText,
      raw?.brickNameText
    ];

    /*
     * fnlProductData contains useful material/product metadata on
     * some AJIO responses.
     */
    try {
      if (
        raw?.fnlProductData
      ) {
        pieces.push(
          JSON.stringify(
            raw.fnlProductData
          )
        );
      }
    } catch { }

    return clean(
      pieces
        .filter(Boolean)
        .join(" ")
    );
  }

  function productTitleText(raw) {
    return clean(
      [
        raw?.name,
        raw?.catalogName
      ]
        .filter(Boolean)
        .join(" ")
    );
  }

  function detectProductMetal(title, metadata = "") {
    return (
      detectMetal(title) ||
      detectMetal(metadata)
    );
  }

  /******************************************************************
   * BRAND
   ******************************************************************/

  function brandOf(raw) {
    const direct = [
      raw?.brandName,
      raw?.brandTypeName,
      raw?.fnlProductData
        ?.brandName,
      raw?.fnlProductData
        ?.brand
    ]
      .map(clean)
      .find(Boolean);

    if (direct)
      return direct;

    /*
     * AJIO often excludes brand from `name`, so catalogName may be
     * useful, but don't fabricate if unavailable.
     */
    return null;
  }

  /******************************************************************
   * PRICE
   ******************************************************************/

  function pricesOf(raw) {
    const price =
      number(
        raw?.price
      );

    const wasPrice =
      number(
        raw?.wasPriceData
      );

    const offerPrice =
      number(
        raw?.offerPrice
      );

    let discount =
      clean(
        raw?.discountPercent
      ) || null;

    if (
      discount &&
      /^\d+(?:\.\d+)?$/
        .test(discount)
    ) {
      discount =
        `(${discount}% off)`;
    }

    return {
      price,
      wasPrice,
      offerPrice,
      discount
    };
  }

  /******************************************************************
   * NORMALIZE API PRODUCT
   ******************************************************************/

  function normalizeProduct(
    raw,
    page
  ) {
    const code =
      raw?.code;

    if (!code)
      return null;

    const id =
      String(code)
        .endsWith("_multi")
        ? String(code)
        : `${code}0_multi`;

    const name =
      clean(raw?.name) ||
      null;

    const searchText =
      rawProductText(raw);

    const titleText =
      productTitleText(raw);

    const metal =
      detectProductMetal(
        titleText,
        searchText
      );

    const purity =
      parsePurity(
        searchText,
        metal
      );

    const weight =
      parseWeight(
        name ||
        searchText
      );

    const prices =
      pricesOf(raw);

    const link =
      absoluteUrl(
        raw?.url
      );

    return {
      id,
      code:
        String(code),

      brand:
        brandOf(raw),

      name,

      metal,

      /*
       * Strong false-positive flag.
       */
      explicitNonGold:
        metal === "silver" ||
        metal === "non-gold",

      weight:
        weight.total != null
          ? `${+Number(
            weight.total
          ).toFixed(4)} g`
          : null,

      weightGrams:
        weight.total,

      unavailable:
        Boolean(
          raw?.purchasable === false ||
          raw?.stockLevelStatus === "outOfStock" ||
          raw?.fnlColorVariantData?.outOfStock === true ||
          raw?.outOfStock === true
        ),

      unitWeightGrams:
        weight.unit,

      quantity:
        weight.quantity,

      componentWeightsGrams:
        weight.components,

      weightSource:
        weight.source,

      weightConfidence:
        weight.confidence,

      karat:
        purity.karat,

      purity:
        purity.purity,

      puritySource:
        purity.source,

      price:
        prices.price,

      wasPrice:
        prices.wasPrice,

      offerPrice:
        prices.offerPrice,

      discount:
        prices.discount,

      image:
        imageUrl(raw),

      link,

      catalogName:
        clean(
          raw?.catalogName
        ) || null,

      brickName:
        clean(
          raw?.brickName
        ) || null,

      verticalName:
        clean(
          raw?.verticalName
        ) || null,

      segmentName:
        clean(
          raw?.segmentName
        ) || null,

      page,

      pdpLoaded: false,

      issues: []
    };
  }

  /******************************************************************
   * MERGE
   ******************************************************************/

  function mergeProduct(p) {
    if (!p?.code)
      return false;

    const key =
      String(p.code);

    const old =
      PRODUCT_MAP.get(key);

    if (!old) {
      PRODUCT_MAP.set(
        key,
        p
      );

      return true;
    }

    /*
     * Prefer whichever copy contains more data.
     */
    for (
      const field
      of [
        "brand",
        "name",
        "metal",
        "price",
        "wasPrice",
        "offerPrice",
        "discount",
        "image",
        "link",
        "catalogName",
        "brickName",
        "verticalName",
        "segmentName"
      ]
    ) {
      if (
        old[field] == null &&
        p[field] != null
      ) {
        old[field] =
          p[field];
      }
    }

    if (
      p.weightGrams != null &&
      (
        old.weightGrams == null ||
        p.weightConfidence >
        old.weightConfidence
      )
    ) {
      for (
        const field
        of [
          "weight",
          "weightGrams",
          "unitWeightGrams",
          "quantity",
          "componentWeightsGrams",
          "weightSource",
          "weightConfidence"
        ]
      ) {
        old[field] =
          p[field];
      }
    }

    if (
      old.karat == null &&
      p.karat != null
    ) {
      old.karat =
        p.karat;
    }

    if (
      !old.purity &&
      p.purity
    ) {
      old.purity =
        p.purity;

      old.puritySource =
        p.puritySource;
    }

    if (p.explicitNonGold) {
      old.explicitNonGold =
        true;
    }

    PRODUCT_MAP.set(
      key,
      old
    );

    return false;
  }

  /******************************************************************
   * REQUEST CONFIG — ONE API ENGINE FOR ALL 4 MASTER PAGES
   *
   * Normal category:
   *   /women/c/8303 -> /api/category/8303
   *
   * Curated landing:
   *   /s/boys-169373       -> /api/category/83 + curated query
   *   /s/girls-169379      -> /api/category/83 + curated query
   *   /s/jewellery-176606  -> /api/category/83 + curated query
   *
   * The curated query format comes from AJIO's own loaded state:
   * :relevance:curated:true:curatedId:<id>:head:<label>
   * :relevance:undefined:<selected facets>
   ******************************************************************/

  function cleanFacetTail(pageQuery) {
    return String(pageQuery || "")
      .replace(/^:relevance:?/i, "")
      .replace(/^relevance:?/i, "")
      .replace(/^:+/, "")
      .trim();
  }

  function titleFromCuratedId(id) {
    const slug = String(id || "").replace(/-\d+$/, "");
    return slug ? slug.charAt(0).toUpperCase() + slug.slice(1) : "";
  }

  function getPLPConfig() {
    const injected = globalThis.__AURUM_AJIO_REQUEST__;
    if (injected?.pathname && injected?.query) {
      return {
        pathname: injected.pathname,
        query: { ...injected.query },
        source: "Aurum page request",
        mode: "injected"
      };
    }

    const current = new URL(location.href);
    const pageQuery = current.searchParams.get("query") || ":relevance";
    const categoryId = current.pathname.match(/\/c\/(\d+)(?:\/|$)/i)?.[1] || null;
    const curatedId = current.pathname.match(/^\/s\/([^/?#]+)/i)?.[1] || null;

    if (curatedId) {
      const tail = cleanFacetTail(pageQuery);
      const head = titleFromCuratedId(curatedId);

      // This is the exact logical query shape exposed by AJIO's page-0 state.
      const internalQuery = [
        ":relevance",
        "curated:true",
        `curatedId:${curatedId}`,
        `head:${head}`,
        "relevance:undefined",
        tail
      ].filter(Boolean).join(":");

      return {
        pathname: "/api/category/83",
        query: {
          currentPage: 0,
          pageSize: 45,
          format: "json",
          query: internalQuery,
          curated: "true",
          curatedid: curatedId,
          facets: tail ? `relevance:undefined:${tail}` : "relevance",
          gridColumns: current.searchParams.get("gridColumns") || 3,
          advfilter: "true",
          platform: "Desktop",
          sort: current.searchParams.get("sort") || "relevance",
          ...(current.searchParams.get("segmentIds") !== null
            ? { segmentIds: current.searchParams.get("segmentIds") }
            : {}),
          ...(current.searchParams.get("pincode")
            ? { pincode: current.searchParams.get("pincode") }
            : {})
        },
        source: `curated-category-83:${curatedId}`,
        mode: "curated"
      };
    }

    if (categoryId) {
      return {
        pathname: `/api/category/${categoryId}`,
        query: {
          fields: "SITE",
          currentPage: 0,
          pageSize: 45,
          format: "json",
          query: pageQuery,
          gridColumns: current.searchParams.get("gridColumns") || 3,
          ...(current.searchParams.get("cohortIds")
            ? { cohortIds: current.searchParams.get("cohortIds") }
            : {}),
          ...(current.searchParams.get("segmentIds") !== null
            ? { segmentIds: current.searchParams.get("segmentIds") }
            : {}),
          ...(current.searchParams.get("pincode")
            ? { pincode: current.searchParams.get("pincode") }
            : {})
        },
        source: `category-api:${categoryId}`,
        mode: "category"
      };
    }

    throw new Error(`Unsupported AJIO master page: ${current.pathname}`);
  }

  const PLP = getPLPConfig();

  function searchURL(page) {
    const u = new URL(PLP.pathname, location.origin);

    for (const [key, value] of Object.entries(PLP.query || {})) {
      if (value === null || value === undefined || value === "") continue;
      u.searchParams.set(key, String(value));
    }

    u.searchParams.set("currentPage", String(page));
    u.searchParams.set("pageSize", "45");
    u.searchParams.set("format", "json");
    return u.href;
  }

  /******************************************************************
   * FETCH
   ******************************************************************/

  async function fetchWithTimeout(
    url,
    timeoutMs,
    accept,
    retries = CFG.retries
  ) {
    for (
      let attempt = 0;
      attempt <= retries;
      attempt++
    ) {
      const controller =
        new AbortController();

      const timer =
        setTimeout(
          () =>
            controller.abort(),
          timeoutMs
        );

      try {
        const r =
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
                accept
              }
            }
          );

        clearTimeout(timer);

        if (r.status === 401 || r.status === 403) {
          return r;
        }

        if (
          !r.ok &&
          (r.status === 429 || r.status >= 500) &&
          attempt < retries
        ) {
          const retryAfter = Number(r.headers.get("retry-after"));
          const waitMs = Number.isFinite(retryAfter) && retryAfter > 0
            ? retryAfter * 1000
            : (CFG.transientBackoffMs[attempt] ?? 4000);
          await sleep(waitMs);
          continue;
        }

        return r;

      } catch (error) {
        clearTimeout(timer);

        if (
          attempt >=
          retries
        ) {
          throw error;
        }
      }
    }

    throw new Error(
      "fetch failed"
    );
  }

  function processSearchPage(
    page,
    json,
    http,
    elapsedMs
  ) {
    const products =
      Array.isArray(
        json?.products
      )
        ? json.products
        : [];

    let gained = 0;

    for (
      const raw
      of products
    ) {
      const rawKey = String(
        raw?.code ?? raw?.baseOptions?.[0]?.selected?.code ?? raw?.baseProduct ?? raw?.url ?? `${page}:${RAW_PRODUCT_MAP.size}`
      );

      if (!RAW_PRODUCT_MAP.has(rawKey)) {
        RAW_PRODUCT_MAP.set(rawKey, raw);
      }

      const p =
        normalizeProduct(
          raw,
          page
        );

      if (
        p &&
        mergeProduct(p)
      ) {
        gained++;
      }
    }

    const row = {
      page,
      http,
      returned:
        products.length,
      gained,
      unique:
        PRODUCT_MAP.size,
      totalResults:
        number(
          json?.pagination
            ?.totalResults
        ),
      totalPages:
        number(
          json?.pagination
            ?.totalPages
        ),
      pageSize:
        number(
          json?.pagination
            ?.pageSize
        ),
      currentPage:
        number(
          json?.pagination
            ?.currentPage
        ),
      ms:
        Math.round(elapsedMs)
    };

    SEARCH_LOG.push(row);

    return {
      ok: true,
      page,
      json,
      row
    };
  }

  async function fetchSearchPage(
    page
  ) {
    const url =
      searchURL(page);

    const started =
      performance.now();

    searchRequests++;

    try {
      const r =
        await fetchWithTimeout(
          url,
          CFG.searchTimeoutMs,
          "application/json, text/plain, */*"
        );

      if (!r.ok) {
        if (r.status === 401 || r.status === 403) {
          accessDenied = true;
          console.error(`⛔ AJIO access denied on page ${page} (HTTP ${r.status}). Halting new search work.`);
        }
        const row = {
          page,
          http: r.status,
          returned: 0,
          gained: 0,
          ms:
            Math.round(
              performance.now() -
              started
            )
        };

        SEARCH_LOG.push(row);

        return {
          ok: false,
          page,
          status: r.status
        };
      }

      const json =
        await r.json();

      return processSearchPage(
        page,
        json,
        r.status,
        performance.now() -
        started
      );

    } catch (error) {
      const row = {
        page,
        http: null,
        returned: 0,
        gained: 0,

        error:
          String(
            error?.message ||
            error
          ),

        ms:
          Math.round(
            performance.now() -
            started
          )
      };

      SEARCH_LOG.push(row);

      return {
        ok: false,
        page,
        error
      };
    }
  }

  /******************************************************************
   * POOL
   ******************************************************************/

  async function pool(
    jobs,
    concurrency,
    worker
  ) {
    let cursor = 0;

    async function runner() {
      while (true) {
        if (accessDenied || pdpAccessDenied || isCancelled()) return;
        const i =
          cursor++;

        if (
          i >= jobs.length
        )
          return;

        await worker(
          jobs[i],
          i
        );
      }
    }

    await Promise.all(
      Array.from(
        {
          length:
            Math.min(
              concurrency,
              jobs.length
            )
        },
        runner
      )
    );
  }

  /******************************************************************
   * DISCOVERY
   ******************************************************************/

  console.log(
    "=============================================="
  );

  console.log(
    "🚀 AJIO GOLD MASTER V8 — UNIFIED API"
  );

  console.log(
    "PLP config:",
    PLP.source
  );

  console.log(
    "=============================================="
  );

  /*
   * Always fetch live page 0.
   *
   * This avoids stale totalResults from localStorage.
   */
  console.log(
    "🌐 Reading live pagination..."
  );

  window.ajioProgress = {
    stage: "search",
    completed: 0,
    total: null,
    cancelled: false
  };

  const suppliedPageZero =
    window.__AURUM_AJIO_PAGE0__;

  delete window.__AURUM_AJIO_PAGE0__;

  const first =
    suppliedPageZero
      ? processSearchPage(
        0,
        suppliedPageZero,
        200,
        0
      )
      : await fetchSearchPage(0);

  if (!first.ok) {
    console.error("❌ AJIO page 0 failed — stopping before pagination.");
    console.error({
      source: PLP.source,
      pathname: PLP.pathname,
      requestUrl: searchURL(0),
      status: first.status ?? null,
      error: first.error ? String(first.error?.message || first.error) : null
    });
    window.ajioStats = {
      aborted: true,
      stage: "page0",
      source: PLP.source,
      requestUrl: searchURL(0),
      status: first.status ?? null
    };
    return { stats: window.ajioStats, products: [], incomplete: [], excluded: [] };
  }

  let livePagination =
    first.json?.pagination ||
    {};

  let totalResults =
    number(
      livePagination.totalResults
    );

  let totalPages =
    number(
      livePagination.totalPages
    );

  const pageSize =
    number(
      livePagination.pageSize
    ) ||
    number(
      PLP.query?.pageSize
    ) ||
    45;

  /*
   * Defensive fallback.
   */
  if (
    !totalPages &&
    totalResults
  ) {
    totalPages =
      Math.ceil(
        totalResults /
        pageSize
      );
  }

  if (!totalPages) {
    /*
     * We still know page zero worked.
     */
    totalPages = 1;
  }

  console.log({
    totalResults,
    totalPages,
    pageSize,
    page0:
      first.json?.products
        ?.length || 0
  });

  /*
   * AJIO currentPage is zero-based.
   */
  const remainingPages =
    Array.from(
      {
        length:
          Math.max(
            0,
            totalPages - 1
          )
      },
      (_, i) =>
        i + 1
    );

  console.log(
    `⚡ Fetching ${remainingPages.length} remaining pages via paced API queue...`
  );

  await pool(
    remainingPages,
    CFG.searchConcurrency,

    async page => {
      if (CFG.searchDelayMs > 0) {
        await new Promise(resolve => setTimeout(resolve, CFG.searchDelayMs));
      }

      const r =
        await fetchSearchPage(
          page
        );

      if (r.ok) {
        window.ajioProgress = {
          stage: "search",
          completed: SEARCH_LOG.filter(x => x.http === 200).length,
          total: totalPages,
          cancelled: false
        };

        console.log(
          `📦 page ${page}` +
          ` | ${r.row.returned}` +
          ` | +${r.row.gained}` +
          ` | unique ${PRODUCT_MAP.size}`
        );

        /*
         * Keep freshest live totals.
         */
        if (
          r.row.totalResults != null
        ) {
          totalResults =
            r.row.totalResults;
        }

        if (
          r.row.totalPages != null
        ) {
          totalPages =
            Math.max(
              totalPages,
              r.row.totalPages
            );
        }
      }
    }
  );

  /*
   * Dynamic extension:
   * If a response says totalPages grew after our first request,
   * fetch the newly exposed pages.
   */
  const fetchedPages =
    new Set(
      SEARCH_LOG
        .filter(
          x =>
            x.http === 200
        )
        .map(
          x => x.page
        )
    );

  const extensionPages = [];

  for (
    let page = 0;
    page < totalPages;
    page++
  ) {
    if (
      !fetchedPages.has(page)
    ) {
      extensionPages.push(
        page
      );
    }
  }

  if (
    extensionPages.length &&
    !accessDenied
  ) {
    console.log(
      `🛟 Dynamic extension: ${extensionPages.length} pages`
    );

    await pool(
      extensionPages,
      CFG.searchConcurrency,
      async page => {
        await fetchSearchPage(
          page
        );
      }
    );
  }

  if (isCancelled()) {
    return {
      cancelled: true
    };
  }

  console.log(
    `✅ API discovery complete: ${PRODUCT_MAP.size} unique search products`
  );

  /******************************************************************
   * REPARSE + GOLD FILTER
   ******************************************************************/

  for (
    const [key, p]
    of PRODUCT_MAP
  ) {
    const text =
      clean([
        p.name,
        p.brand,
        p.catalogName,
        p.brickName,
        p.verticalName,
        p.segmentName,
        p.link
      ].join(" "));

    const metal =
      detectMetal(text);

    if (
      metal === "silver" ||
      metal === "non-gold"
    ) {
      p.metal =
        metal;

      p.explicitNonGold =
        true;

      p.karat = null;
      p.purity = null;

      PRODUCT_MAP.set(
        key,
        p
      );

      continue;
    }

    if (metal === "gold") {
      p.metal =
        "gold";
    }

    const kp =
      parsePurity(
        text,
        p.metal
      );

    if (
      p.karat == null &&
      kp.karat != null
    ) {
      p.karat =
        kp.karat;

      p.puritySource =
        kp.source;
    }

    if (
      !p.purity &&
      kp.purity
    ) {
      p.purity =
        kp.purity;

      p.puritySource =
        kp.source;
    }

    const w =
      parseWeight(
        p.name
      );

    if (
      w.total != null &&
      (
        p.weightGrams == null ||
        w.confidence >
        p.weightConfidence
      )
    ) {
      p.weightGrams =
        w.total;

      p.weight =
        `${+Number(
          w.total
        ).toFixed(4)} g`;

      p.unitWeightGrams =
        w.unit;

      p.quantity =
        w.quantity;

      p.componentWeightsGrams =
        w.components;

      p.weightSource =
        w.source;

      p.weightConfidence =
        w.confidence;
    }

    PRODUCT_MAP.set(
      key,
      p
    );
  }

  /******************************************************************
   * GENUINE GOLD
   *
   * Search results include things like:
   * "Sterling Silver Gold-Plated Earrings".
   *
   * Only genuine gold enters the gold extraction.
   ******************************************************************/

  function isGenuineGold(p) {
    if (
      p.explicitNonGold
    )
      return false;

    if (
      p.metal === "silver" ||
      p.metal === "non-gold"
    )
      return false;

    const text =
      clean([
        p.name,
        p.catalogName,
        p.brickName,
        p.verticalName,
        p.segmentName
      ].join(" "));

    if (
      SILVER_RE.test(text) ||
      NON_GOLD_RE.test(text) ||
      GOLD_PLATED_RE.test(text)
    ) {
      return false;
    }

    const explicitGold =
      /\b(?:gold|yellow\s+gold|rose\s+gold|white\s+gold)\b/i
        .test(text);

    const goldKarat =
      /\b(?:24|23|22|21|20|18|14|10|9)\s*(?:k|kt|karat|carat)\b/i
        .test(text);

    const finenessProof =
      /(?:^|[^0-9])(?:999\.9\+?|999|995|916\.7|916|875|750)(?!\d)/i.test(text);

    return explicitGold || goldKarat || finenessProof;
  }

  /******************************************************************
   * PDP HELPERS
   ******************************************************************/

  function stripHtml(html) {
    try {
      const doc =
        new DOMParser()
          .parseFromString(
            html,
            "text/html"
          );

      return clean(
        doc.body
          ?.innerText
      );
    } catch {
      return clean(
        String(html)
          .replace(
            /<[^>]+>/g,
            " "
          )
      );
    }
  }

  function pdpSearchText(html) {
    /*
     * Inline application state can contain catalog records unrelated
     * to the PDP being fetched. Generic parsing must only trust the
     * product's visible text; named option fields are read separately.
     */
    return stripHtml(html);
  }

  function labeledWeight(text) {
    const patterns = [
      /(?:total\s+gold\s+weight|net\s+weight|gold\s+weight|product\s+weight|item\s+weight|weight)\s*[:\-]\s*["']?(\d+(?:\.\d+)?)\s*(mg|kg|g|gm|gms|gram|grams)\b/i
    ];

    for (
      const re
      of patterns
    ) {
      const m =
        text.match(re);

      if (!m)
        continue;

      return parseWeight(
        `${m[1]} ${m[2]}`
      );
    }

    return weightResult();
  }

  function labeledPurity(text) {
    let m =
      text.match(
        /(?:metal\s*)?(?:purity|fineness)\s*[:\-]\s*["']?(999\.9\+?|999|995|990|958|916\.7|916|875|833|750|585|417|375)/i
      );

    if (!m)
      return null;

    return normalizeFineness(
      m[1]
    );
  }

  function labeledKarat(text) {
    const m =
      text.match(
        /(?:metal\s*)?(?:karat|carat|karatage)\s*[:\-]\s*["']?(24|23|22|21|20|18|14|10|9)\s*(?:k|kt)?\b/i
      );

    return m
      ? Number(m[1])
      : null;
  }

  function pdpOptionValue(html, labels) {
    const entries =
      [...String(html).matchAll(
        /\{[^{}]{0,1000}\}/g
      )].map(
        match => match[0]
      );

    for (const label of labels) {
      const escapedLabel =
        label.replace(
          /[.*+?^${}()|[\]\\]/g,
          "\\$&"
        );

      const labelPattern =
        new RegExp(
          `"(?:name|qualifier)"\\s*:\\s*"${escapedLabel}"`,
          "i"
        );

      for (const entry of entries) {
        if (!labelPattern.test(entry)) {
          continue;
        }

        const value =
          entry.match(
            /"value"\s*:\s*"([^"]*)"/i
          );

        if (value?.[1]) {
          return value[1];
        }
      }
    }

    return null;
  }

  /******************************************************************
   * TARGETED PDP
   ******************************************************************/

  function needsPDP(p) {
    if (
      !isGenuineGold(p)
    )
      return false;

    /*
     * Price data is already PLP data.
     *
     * PDP only exists to resolve requested jewellery attributes.
     */
    return (
      p.weightGrams == null ||
      p.karat == null ||
      !p.purity
    );
  }

  async function fetchPDP(p) {
    const started =
      performance.now();

    pdpRequests++;

    try {
      const r =
        await fetchWithTimeout(
          p.link,
          CFG.pdpTimeoutMs,
          "text/html,application/xhtml+xml",
          CFG.pdpRetries
        );

      if (!r.ok) {
        if (r.status === 401 || r.status === 403) {
          pdpAccessDenied = true;
          console.error(`⛔ AJIO PDP access denied for ${p.id} (HTTP ${r.status}). Halting PDP enrichment.`);
        }
        return {
          ok: false,
          status: r.status,
          error: `HTTP ${r.status}`,
          ms:
            Math.round(
              performance.now() -
              started
            )
        };
      }

      const html =
        await r.text();

      const text =
        pdpSearchText(
          html
        );

      const metal =
        detectProductMetal(
          p.name,
          text
        );

      /*
       * If PDP explicitly says silver/non-gold, protect dataset.
       */
      if (
        metal === "silver" ||
        metal === "non-gold"
      ) {
        return {
          ok: true,
          status:
            r.status,

          metal,
          weight:
            weightResult(),

          karat: null,
          purity: null,

          ms:
            Math.round(
              performance.now() -
              started
            )
        };
      }

      const rawWeight =
        pdpOptionValue(
          html,
          [
            "netWeight",
            "Net Weight",
            "metalWeight",
            "Metal Weight",
            "grossWeight",
            "Gross Weight"
          ]
        );

      const unit =
        pdpOptionValue(
          html,
          ["uom", "UOM"]
        );

      let weight =
        parseWeight(
          rawWeight && unit
            ? `${rawWeight} ${unit}`
            : rawWeight || ""
        );

      if (
        weight.total == null
      ) {
        weight =
          labeledWeight(
            text
          );
      }

      if (
        weight.total == null
      ) {
        /*
         * Title remains preferred to generic PDP prose.
         */
        weight =
          parseWeight(
            p.name
          );
      }

      const optionPurity =
        parsePurity(
          pdpOptionValue(
            html,
            ["metalPurity", "METAL PURITY"]
          ),
          "gold"
        );

      let karat =
        optionPurity.karat ??
        labeledKarat(
          text
        );

      let purity =
        optionPurity.purity ||
        labeledPurity(
          text
        );

      purity =
        purity ||
        null;

      const kp =
        parsePurity(
          `${p.name} ${text}`,
          "gold"
        );

      karat =
        karat ??
        kp.karat;

      purity =
        purity ||
        kp.purity;

      if (
        karat &&
        purity &&
        karatFromFineness(purity) !== karat
      ) {
        purity =
          standardFineness(
            karat
          );
      }

      if (
        karat &&
        !purity
      ) {
        purity =
          standardFineness(
            karat
          );
      }

      if (
        karat == null &&
        purity
      ) {
        karat =
          karatFromFineness(
            purity
          );
      }

      return {
        ok: true,
        status:
          r.status,

        metal:
          metal || "gold",

        weight,
        karat,
        purity,

        ms:
          Math.round(
            performance.now() -
            started
          )
      };

    } catch (error) {
      return {
        ok: false,
        status: null,

        error:
          String(
            error?.message ||
            error
          ),

        ms:
          Math.round(
            performance.now() -
            started
          )
      };
    }
  }

  let GOLD =
    [...PRODUCT_MAP.values()]
      .filter(
        isGenuineGold
      );

  const PDP_TARGETS =
    GOLD.filter(
      needsPDP
    );

  console.log(
    `🥇 Genuine gold: ${GOLD.length}`
  );

  console.log(
    `🔬 PDP targets: ${PDP_TARGETS.length}`
  );

  let pdpCompleted = 0;

  window.ajioProgress = {
    stage: CFG.enablePdpEnrichment ? "pdp" : "complete",
    completed: 0,
    total: PDP_TARGETS.length,
    cancelled: false
  };

  if (CFG.enablePdpEnrichment) await pool(
    PDP_TARGETS,
    CFG.pdpConcurrency,

    async p => {
      if (pdpAccessDenied || isCancelled()) return;
      if (CFG.pdpDelayMs > 0) {
        await sleep(CFG.pdpDelayMs);
      }
      const data =
        await fetchPDP(p);

      PDP_LOG.push({
        id:
          p.id,

        status:
          data.status,

        ok:
          data.ok,

        weight:
          data.weight
            ?.total ??
          null,

        karat:
          data.karat ??
          null,

        purity:
          data.purity ??
          null,

        error:
          data.error ??
          null,

        ms:
          data.ms ??
          null
      });

      pdpCompleted++;

      window.ajioProgress = {
        stage: "pdp",
        completed: pdpCompleted,
        total: PDP_TARGETS.length,
        cancelled: false
      };

      if (
        pdpCompleted === PDP_TARGETS.length ||
        pdpCompleted % 25 === 0
      ) {
        console.log(
          `🔬 PDP ${pdpCompleted}/${PDP_TARGETS.length}`
        );
      }

      if (!data.ok)
        return;

      const current =
        PRODUCT_MAP.get(
          String(p.code)
        );

      if (!current)
        return;

      /*
       * PDP explicit non-gold wins.
       */
      if (
        data.metal === "silver" ||
        data.metal === "non-gold"
      ) {
        current.metal =
          data.metal;

        current.explicitNonGold =
          true;

        current.karat = null;
        current.purity = null;

        current.pdpLoaded =
          true;

        PRODUCT_MAP.set(
          String(p.code),
          current
        );

        return;
      }

      /*
       * Weight:
       * only fill missing.
       *
       * Never replace a strong parsed title weight with generic PDP.
       */
      if (
        current.weightGrams == null &&
        data.weight?.total != null
      ) {
        current.weightGrams =
          data.weight.total;

        current.weight =
          `${+Number(
            data.weight.total
          ).toFixed(4)} g`;

        current.unitWeightGrams =
          data.weight.unit;

        current.quantity =
          data.weight.quantity;

        current.componentWeightsGrams =
          data.weight.components;

        current.weightSource =
          "PDP";

        current.weightConfidence =
          data.weight.confidence;
      }

      if (
        current.karat == null &&
        data.karat != null
      ) {
        current.karat =
          data.karat;

        current.puritySource =
          current.puritySource ||
          "PDP";
      }

      if (
        !current.purity &&
        data.purity
      ) {
        current.purity =
          data.purity;

        current.puritySource =
          "PDP";
      }

      current.pdpLoaded =
        true;

      PRODUCT_MAP.set(
        String(p.code),
        current
      );
    }
  );

  if (isCancelled()) {
    return {
      cancelled: true
    };
  }

  /******************************************************************
   * FINAL NORMALIZATION
   ******************************************************************/

  const ALL =
    [...PRODUCT_MAP.values()];

  for (
    const p
    of ALL
  ) {
    if (
      p.explicitNonGold ||
      p.metal === "silver" ||
      p.metal === "non-gold"
    ) {
      p.karat = null;

      /*
       * Purity is deliberately null for excluded non-gold rows in
       * this gold extraction.
       */
      p.purity = null;
    }

    if (
      p.metal === "gold"
    ) {
      if (
        p.karat == null &&
        p.purity
      ) {
        p.karat =
          karatFromFineness(
            p.purity
          );
      }

      if (
        p.karat &&
        !p.purity
      ) {
        p.purity =
          standardFineness(
            p.karat
          );

        if (p.purity) {
          p.puritySource =
            "karat-standard";
        }
      }
    }

    if (
      p.weightGrams != null
    ) {
      p.weight =
        `${+Number(
          p.weightGrams
        ).toFixed(4)} g`;
    }

    const issues = [];

    if (
      isGenuineGold(p)
    ) {
      if (
        p.weightGrams == null
      ) {
        issues.push(
          "missing-weight"
        );
      }

      if (
        p.karat == null
      ) {
        issues.push(
          "missing-karat"
        );
      }

      if (
        !p.purity
      ) {
        issues.push(
          "missing-purity"
        );
      }

      if (
        p.price == null
      ) {
        issues.push(
          "missing-price"
        );
      }
    }

    p.issues =
      issues;

    p.incomplete =
      issues.length > 0;
  }

  GOLD =
    ALL.filter(
      isGenuineGold
    );

  /*
   * Keep API/listing order.
   */
  GOLD.sort(
    (a, b) =>
      a.page -
      b.page
  );

  const EXCLUDED =
    ALL.filter(
      p =>
        !isGenuineGold(p)
    );

  const INCOMPLETE =
    GOLD.filter(
      p =>
        p.incomplete
    );

  /******************************************************************
   * TABLE
   ******************************************************************/

  function rows(array) {
    return array.map(
      p => ({
        id:
          p.id,

        code:
          p.code,

        brand:
          p.brand,

        name:
          p.name,

        metal:
          p.metal,

        weight:
          p.weight,

        weightGrams:
          p.weightGrams,

        unitWeightGrams:
          p.unitWeightGrams,

        quantity:
          p.quantity,

        karat:
          p.karat,

        purity:
          p.purity,

        price:
          p.price,

        wasPrice:
          p.wasPrice,

        offerPrice:
          p.offerPrice,

        discount:
          p.discount,

        weightSource:
          p.weightSource,

        puritySource:
          p.puritySource,

        page:
          p.page,

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
      startedAt
    );

  const pageCoverage =
    Array.from(
      { length: totalPages },
      (_, page) => {
        const attempts =
          SEARCH_LOG.filter(
            row => row.page === page
          );

        const success =
          attempts.find(
            row => row.http === 200
          );

        return {
          page,
          expected: true,
          fetched: Boolean(success),
          returned: success?.returned ?? 0,
          gained: success?.gained ?? 0,
          attempts: attempts.length,
          lastHttp: attempts.at(-1)?.http ?? null,
          error: attempts.at(-1)?.error ?? null
        };
      }
    );

  const missingPages =
    pageCoverage.filter(
      row => !row.fetched
    );

  const stats = {
    liveReportedTotal:
      totalResults,

    liveTotalPages:
      totalPages,

    pageSize,

    rawUniqueSearchProducts:
      ALL.length,

    reportedResultGap:
      totalResults == null
        ? null
        : totalResults - ALL.length,

    missingSearchPages:
      missingPages.map(
        row => row.page
      ),

    gold:
      GOLD.length,

    excludedNonGold:
      EXCLUDED.length,

    incomplete:
      INCOMPLETE.length,

    missingWeight:
      GOLD.filter(
        p =>
          p.weightGrams ==
          null
      ).length,

    missingKarat:
      GOLD.filter(
        p =>
          p.karat ==
          null
      ).length,

    missingPurity:
      GOLD.filter(
        p =>
          !p.purity
      ).length,

    missingPrice:
      GOLD.filter(
        p =>
          p.price ==
          null
      ).length,

    missingWasPrice:
      GOLD.filter(
        p =>
          p.wasPrice ==
          null
      ).length,

    missingOfferPrice:
      GOLD.filter(
        p =>
          p.offerPrice ==
          null
      ).length,

    searchRequests,

    searchSuccess:
      SEARCH_LOG.filter(
        x =>
          x.http === 200
      ).length,

    pdpTargets:
      PDP_TARGETS.length,

    pdpRequests,

    pdpSuccess:
      PDP_LOG.filter(
        x =>
          x.ok
      ).length,

    elapsedMs,

    elapsedSeconds:
      +(elapsedMs / 1000)
        .toFixed(2)
  };

  /******************************************************************
   * GLOBALS
   ******************************************************************/

  window.ajioStats =
    stats;

  /*
   * Primary result = genuine gold only.
   */
  window.ajioProducts =
    GOLD;

  window.ajioGold =
    GOLD;

  window.ajioAllSearchResults =
    ALL;

  // Untouched product objects returned by AJIO search API.
  window.ajioRawProducts =
    [...RAW_PRODUCT_MAP.values()];

  window.ajioExcluded =
    EXCLUDED;

  window.ajioIncomplete =
    INCOMPLETE;

  window.ajioSearchLog =
    SEARCH_LOG;

  window.ajioPageCoverage =
    pageCoverage;

  window.ajioPdpLog =
    PDP_LOG;

  window.ajioProgress = {
    stage: "complete",
    completed: pdpCompleted,
    total: PDP_TARGETS.length,
    cancelled: false
  };

  // Browser bridge intentionally disabled in the standalone master.


  /******************************************************************
   * COMMANDS
   ******************************************************************/

  window.ajioTable =
    () => {
      console.table(
        rows(GOLD)
      );

      return GOLD;
    };

  window.ajioIncompleteTable =
    () => {
      console.table(
        rows(INCOMPLETE)
      );

      return INCOMPLETE;
    };

  window.ajioExcludedTable =
    () => {
      console.table(
        rows(EXCLUDED)
      );

      return EXCLUDED;
    };

  window.ajioSearchTable =
    () => {
      console.table(
        SEARCH_LOG
          .slice()
          .sort(
            (a, b) =>
              a.page -
              b.page
          )
      );

      return SEARCH_LOG;
    };

  window.ajioCoverageTable =
    () => {
      console.table(
        pageCoverage
      );

      return {
        reportedResultGap:
          stats.reportedResultGap,
        missingPages,
        pages: pageCoverage
      };
    };

  window.ajioPdpTable =
    () => {
      console.table(
        PDP_LOG
      );

      return PDP_LOG;
    };

  window.ajioDiagnostic =
    () => {
      console.log(
        "=== AJIO DIAGNOSTIC ==="
      );

      console.log(stats);

      console.log(
        "\nINCOMPLETE"
      );

      console.table(
        rows(INCOMPLETE)
      );

      console.log(
        "\nSEARCH PAGES"
      );

      console.table(
        SEARCH_LOG
          .slice()
          .sort(
            (a, b) =>
              a.page -
              b.page
          )
      );

      console.log(
        "\nPDP"
      );

      console.table(
        PDP_LOG
      );

      return {
        stats,
        incomplete:
          INCOMPLETE,
        search:
          SEARCH_LOG,
        pdp:
          PDP_LOG
      };
    };

  window.ajioParseWeight =
    text =>
      parseWeight(text);

  window.ajioParsePurity =
    text =>
      parsePurity(
        text,
        detectMetal(text)
      );

  /******************************************************************
   * CSV
   ******************************************************************/

  const CSV_FIELDS = [
    "id",
    "code",
    "brand",
    "name",
    "metal",

    "weight",
    "weightGrams",
    "unitWeightGrams",
    "quantity",

    "karat",
    "purity",

    "price",
    "wasPrice",
    "offerPrice",
    "discount",

    "weightSource",
    "puritySource",

    "page",
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

  function makeCSV() {
    const data =
      rows(GOLD);

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
      URL.createObjectURL(
        blob
      );

    const a =
      document.createElement(
        "a"
      );

    a.href = url;
    a.download =
      filename;

    document.body
      .appendChild(a);

    a.click();

    a.remove();

    setTimeout(
      () =>
        URL.revokeObjectURL(
          url
        ),
      1000
    );
  }

  window.ajioDownloadCSV =
    () =>
      download(
        "\uFEFF" +
        makeCSV(),

        `ajio-gold-${GOLD.length}.csv`,

        "text/csv;charset=utf-8"
      );

  window.ajioDownloadJSON =
    () =>
      download(
        JSON.stringify(
          rows(GOLD),
          null,
          2
        ),

        `ajio-gold-${GOLD.length}.json`,

        "application/json;charset=utf-8"
      );

  window.ajioCopyCSV =
    async () => {
      const text =
        makeCSV();

      try {
        await navigator
          .clipboard
          .writeText(text);
      } catch {
        copy(text);
      }

      console.log(
        "📋 CSV copied"
      );
    };

  window.ajioCopyJSON =
    async () => {
      const text =
        JSON.stringify(
          rows(GOLD),
          null,
          2
        );

      try {
        await navigator
          .clipboard
          .writeText(text);
      } catch {
        copy(text);
      }

      console.log(
        "📋 JSON copied"
      );
    };

  // RAW AJIO /api/search product objects: no rows(), normalization or gold filtering.
  window.ajioDownloadRawJSON =
    () =>
      download(
        JSON.stringify(
          window.ajioRawProducts,
          null,
          2
        ),
        `ajio-raw-search-${window.ajioRawProducts.length}.json`,
        "application/json;charset=utf-8"
      );

  window.ajioCopyRawJSON =
    async () => {
      const text = JSON.stringify(
        window.ajioRawProducts,
        null,
        2
      );

      try {
        await navigator.clipboard.writeText(text);
      } catch {
        copy(text);
      }

      console.log(
        `📋 RAW JSON copied (${window.ajioRawProducts.length} API products)`
      );
    };

  // COMPLETE RUN EXPORT: every captured product plus every dataset/log.
  // This is intentionally broader than ajioRawProducts (which is only untouched
  // /api/search product objects). It preserves the raw API object alongside the
  // normalized record/classification for every product we actually discovered.
  const buildCompleteExport = () => {
    const rawByCode = new Map(
      (window.ajioRawProducts || []).map(p => [String(p?.code ?? p?.id ?? ""), p])
    );

    const goldCodes = new Set((window.ajioProducts || []).map(p => String(p?.code ?? p?.id ?? "")));
    const excludedCodes = new Set((window.ajioExcluded || []).map(p => String(p?.code ?? p?.id ?? "")));
    const incompleteCodes = new Set((window.ajioIncomplete || []).map(p => String(p?.code ?? p?.id ?? "")));

    const products = (window.ajioAllSearchResults || []).map(normalized => {
      const code = String(normalized?.code ?? normalized?.id ?? "");
      return {
        code,
        classification: goldCodes.has(code)
          ? "gold"
          : excludedCodes.has(code)
            ? "excluded-non-gold"
            : "unclassified",
        incomplete: incompleteCodes.has(code),
        normalized,
        rawSearchProduct: rawByCode.get(code) ?? null
      };
    });

    return {
      exportedAt: new Date().toISOString(),
      pageUrl: location.href,
      stats: window.ajioStats ?? null,
      counts: {
        allProducts: products.length,
        rawSearchProducts: (window.ajioRawProducts || []).length,
        gold: (window.ajioProducts || []).length,
        excluded: (window.ajioExcluded || []).length,
        incomplete: (window.ajioIncomplete || []).length,
        searchLog: (window.ajioSearchLog || []).length,
        pdpLog: (window.ajioPdpLog || []).length
      },
      products,
      rawSearchProducts: window.ajioRawProducts || [],
      normalizedAllProducts: window.ajioAllSearchResults || [],
      goldProducts: window.ajioProducts || [],
      excludedProducts: window.ajioExcluded || [],
      incompleteProducts: window.ajioIncomplete || [],
      pageCoverage: window.ajioPageCoverage || [],
      searchLog: window.ajioSearchLog || [],
      pdpLog: window.ajioPdpLog || []
    };
  };

  window.ajioAllData = buildCompleteExport;

  window.ajioDownloadFullJSON = () => {
    const data = buildCompleteExport();
    download(
      JSON.stringify(data, null, 2),
      `ajio-full-${new Date().toISOString().replace(/[:.]/g, "-")}.json`,
      "application/json;charset=utf-8"
    );
  };

  // Backward-compatible alias.
  window.ajioDownloadAllJSON = window.ajioDownloadFullJSON;

  window.ajioCopyAllJSON = async () => {
    const data = buildCompleteExport();
    const text = JSON.stringify(data, null, 2);
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      copy(text);
    }
    console.log(`📋 ALL AJIO DATA copied (${data.counts.allProducts} discovered products)`);
  };

  /******************************************************************
   * SELF TESTS
   ******************************************************************/

  const weightTests = [
    {
      name:
        "0.2g simple",

      text:
        "0.2 G 24 Kt (999) Lightweight Gold Coin",

      total: 0.2
    },

    {
      name:
        "2gm simple",

      text:
        "MMTC PAMP 2 gm 24 Kt 999.9 Gold Coin",

      total: 2
    },

    {
      name:
        "500mg",

      text:
        "24K Gold Coin 500mg",

      total: 0.5
    },

    {
      name:
        "each x pcs",

      text:
        "25 Gm (5gm each x 5 Pcs)",

      total: 25
    },

    {
      name:
        "components",

      text:
        "4gm (2gm + 2gm)",

      total: 4
    },

    {
      name:
        "set stated total",

      text:
        "Set Of 5 24Kt Gold Coin-0.5g",

      total: 0.5
    }
  ];

  const selfTests =
    weightTests.map(
      t => {
        const r =
          parseWeight(
            t.text
          );

        return {
          test:
            t.name,

          pass:
            r.total != null &&
            Math.abs(
              r.total -
              t.total
            ) < 0.0001,

          total:
            r.total,

          unit:
            r.unit,

          quantity:
            r.quantity,

          source:
            r.source,

          expected:
            t.total
        };
      }
    );

  const selfTestPassed =
    selfTests.every(
      x => x.pass
    );

  /******************************************************************
   * FINAL
   ******************************************************************/

  console.log("");
  console.log(
    "================================================"
  );

  console.log(
    "✅ AJIO GOLD MASTER V8 — UNIFIED API COMPLETE"
  );

  console.log(
    "================================================"
  );

  console.log(stats);

  console.log("");

  if (selfTestPassed) {
    console.log(
      "✅ WEIGHT PARSER SELF-TESTS PASSED"
    );
  } else {
    console.warn(
      "⚠️ WEIGHT PARSER SELF-TEST FAILURE"
    );
  }

  console.table(
    selfTests
  );

  console.log("");

  if (
    !INCOMPLETE.length
  ) {
    console.log(
      "🎯 ALL REQUIRED GOLD FIELDS FILLED"
    );
  } else {
    console.warn(
      `⚠️ REQUIRED INCOMPLETE: ${INCOMPLETE.length}`
    );
  }

  console.log(
    `🚫 NON-GOLD SEARCH RESULTS EXCLUDED: ${EXCLUDED.length}`
  );

  console.log(
    `🥇 FINAL GOLD PRODUCTS: ${GOLD.length}`
  );

  console.log(
    `⏱️ ${stats.elapsedSeconds}s`
  );

  console.log("");

  console.log(
    `FULL TABLE READY: ${GOLD.length} (run ajioTable())`
  );

  if (
    CFG.autoPrintFullTable
  ) {
    console.table(
      rows(GOLD)
    );
  }

  console.log("");
  console.log(
    "COMMANDS:"
  );

  console.log(
    "window.ajioStats"
  );

  console.log(
    "window.ajioProducts"
  );

  console.log("");

  console.log(
    "ajioTable()"
  );

  console.log(
    "ajioIncompleteTable()"
  );

  console.log(
    "ajioExcludedTable()"
  );

  console.log(
    "ajioSearchTable()"
  );

  console.log(
    "ajioPdpTable()"
  );

  console.log(
    "ajioDiagnostic()"
  );

  console.log("");

  console.log(
    'ajioParseWeight("25 Gm (5gm each x 5 Pcs)")'
  );

  console.log(
    'ajioParsePurity("2 gm 24 Kt (999.9) Gold Coin")'
  );

  console.log("");

  console.log(
    "ajioDownloadCSV()"
  );

  console.log(
    "ajioDownloadJSON()",
    "ajioDownloadRawJSON()"
  );

  console.log(
    "ajioCopyCSV()"
  );

  console.log(
    "ajioCopyJSON()",
    "ajioCopyRawJSON()"
  );

  console.log(
    "================================================"
  );

  return {
    stats,
    products:
      GOLD,
    incomplete:
      INCOMPLETE,
    excluded:
      EXCLUDED
  };
})();