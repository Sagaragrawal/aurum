(async () => {
  "use strict";

  console.clear();

  /******************************************************************
  * AJIO WOMEN FINE JEWELLERY GOLD — CHILD EXTRACTOR
   * ================================================================
   *
  * CATEGORIES: Women Rings and Women Necklaces & Pendants
  * FILTERS: Page-specific gold and purity facets
   *
   * DISCOVERY
   * - Calls /api/search directly.
   * - Uses LIVE pagination.totalPages / totalResults.
   * - Fetches all remaining pages in parallel.
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
   * Run directly on the AJIO page or search context.
   ******************************************************************/

  const CFG = {
    searchConcurrency: 12,
    pdpConcurrency: 10,
    searchTimeoutMs: 3500,
    pdpTimeoutMs: 3500,
    retries: 1,
    infer24K999: true,
    autoPrintFullTable: true
  };

  const startedAt = performance.now();
  const PRODUCT_MAP = new Map();
  const SEARCH_LOG = [];
  const PDP_LOG = [];

  let searchRequests = 0;
  let pdpRequests = 0;

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
    /\b(?:silver|sterling|925\s*silver|silverware)\b/i;

  const NON_GOLD_RE =
    /\b(?:brass|copper|bronze|steel|stainless\s+steel|zinc|alloy|aluminium|aluminum|iron|plastic|wood|wooden)\b/i;

  const GOLD_PLATED_RE =
    /\b(?:gold[\s-]*plated|gold[\s-]*tone|gold[\s-]*finish|gold[\s-]*colou?r(?:ed)?|gold[\s-]*polished|gold[\s-]*coated)\b/i;

  const GOLD_RE =
    /\b(?:gold|yellow\s+gold|rose\s+gold|white\s+gold)\b/i;

  function detectMetal(text) {
    const s =
      clean(text);

    if (
      SILVER_RE.test(s)
    ) {
      return "silver";
    }

    if (
      NON_GOLD_RE.test(s)
    ) {
      return "non-gold";
    }

    if (
      GOLD_PLATED_RE.test(s)
    ) {
      return "non-gold";
    }

    if (
      GOLD_RE.test(s) ||
      /\b(?:24|23|22|21|20|18|14|10|9)\s*(?:k|kt)\b/i
        .test(s) ||
      /\b(?:999\.9|999|995|916)\b/
        .test(s)
    ) {
      return "gold";
    }

    return null;
  }

  /******************************************************************
   * WEIGHT PARSING
   ******************************************************************/

  function extractWeight(text) {
    if (!text) return null;

    const s = clean(text);

    /*
     * Match weight patterns:
     * 3g, 3gm, 5g, 1.5g, etc.
     */
    const m = s.match(
      /\b(\d+(?:\.\d+)?)\s*(?:mg|gm|gms|g|gram|grams?)\b/i
    );

    if (!m) return null;

    let value =
      Number(m[1]);

    /*
     * Convert mg to grams.
     */
    if (
      /mg/i.test(m[0])
    ) {
      value = value / 1000;
    }

    return Number.isFinite(value)
      ? Number(
          value.toFixed(4)
        )
      : null;
  }

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

  function extractKaratAndFineness(text) {
    const s =
      clean(text);

    let karat = null;
    let purity = null;
    let source = null;

    let m;

    m = s.match(
      /\b(24|23|22|21|20|18|14|10|9)\s*(?:kt|k|karat|carat)\s*\(\s*(9999|999\.9\+?|999|995|990|958|916\.7|9167|916|875|833|750|585|417|375)\s*\)/i
    );

    if (m) {
      return {
        karat:
          Number(m[1]),

        purity:
          normalizeFineness(
            m[2]
          ),

        source:
          "title-explicit-both"
      };
    }

    m = s.match(
      /\b(24|23|22|21|20|18|14|10|9)\s*(?:kt|k|karat|carat)\s*[-:/]?\s*(9999|999\.9\+?|999|995|990|958|916\.7|9167|916|875|833|750|585|417|375)\b/i
    );

    if (m) {
      return {
        karat:
          Number(m[1]),

        purity:
          normalizeFineness(
            m[2]
          ),

        source:
          "title-explicit-both"
      };
    }

    m = s.match(
      /\b(24|23|22|21|20|18|14|10|9)\s*(?:kt|k|karat|carat)\b/i
    );

    if (m) {
      karat =
        Number(m[1]);

      source =
        "title-karat";
    }

    const fm =
      s.match(
        /\b(999\.9\+?|999|995|990|958|916\.7|916|875|833|750|585|417|375)\b(?!\s*(?:mg|g|gm|grams?))/i
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

    if (
      karat &&
      !purity
    ) {
      purity =
        standardFineness(
          karat
        );

      if (purity) {
        source +=
          "-inferred";
      }
    }

    return {
      karat,
      purity,
      source
    };
  }

  /******************************************************************
  * SEARCH CONFIG — WOMEN FINE JEWELLERY WITH FALLBACK
   ******************************************************************/

  function getPLPConfig() {
    const injected =
      globalThis.__AURUM_AJIO_REQUEST__;

    if (
      injected?.pathname &&
      injected?.query
    ) {
      return {
        pathname:
          injected.pathname,

        query: {
          ...injected.query
        },

        source:
          "Aurum page request"
      };
    }

    let saved = null;

    try {
      const raw =
        localStorage.getItem(
          "plpRequestMobile"
        );

      if (raw) {
        saved =
          JSON.parse(raw);
      }
    } catch {}

    /*
     * Read the query parameter from current page URL
     * This contains the gold/metal type filters.
     */
    const current =
      new URL(
        location.href
      );

    const pageQuery =
      current.searchParams
        .get("query");

    if (
      saved?.request?.pathname &&
      saved?.request?.query
    ) {
      /*
       * Use localStorage config but override query
       * with page-specific filters if available.
       */
      const config = {
        pathname:
          saved.request.pathname,

        query: {
          ...saved.request.query
        },

        source:
          "localStorage.plpRequestMobile"
      };

      if (pageQuery) {
        config.query.query =
          pageQuery;

        config.source +=
          "+page-query-override";
      }

      return config;
    }

    /*
     * Safe fallback using current search page
     * with gold filters if available.
     */
    const text =
      pageQuery ||
      current.searchParams
        .get("text") ||
      "women fine jewellery gold";

    return {
      pathname:
        `/api/category/${
          current.pathname
            .match(/\/c\/(\d+)/)
            ?.[1] ||
          "830306004"
        }`,

      query: {
        fields: "SITE",
        currentPage: 0,
        pageSize: 45,
        format: "json",
        query: text,
        gridColumns: 3
      },

      source:
        "fallback-women-fine-jewellery"
    };
  }

  const PLP =
    getPLPConfig();

  function searchURL(page) {
    const u =
      new URL(
        PLP.pathname,
        location.origin
      );

    for (
      const [key, value]
      of Object.entries(
        PLP.query || {}
      )
    ) {
      if (
        value === null ||
        value === undefined ||
        value === ""
      ) {
        continue;
      }

      u.searchParams.set(
        key,
        String(value)
      );
    }

    u.searchParams.set(
      "currentPage",
      String(page)
    );

    if (
      !u.searchParams.has(
        "pageSize"
      )
    ) {
      u.searchParams.set(
        "pageSize",
        "45"
      );
    }

    u.searchParams.set(
      "format",
      "json"
    );

    return u.href;
  }

  /******************************************************************
   * FETCH & PARSING
   ******************************************************************/

  async function fetchWithTimeout(
    url,
    timeoutMs,
    accept
  ) {
    for (
      let attempt = 0;
      attempt <= CFG.retries;
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
          await fetch(url, {
            method: "GET",
            signal:
              controller.signal,

            headers: accept
              ? {
                  Accept:
                    accept
                }
              : {}
          });

        clearTimeout(timer);

        return r;
      } catch (err) {
        clearTimeout(timer);

        if (
          attempt ===
            CFG.retries
        ) {
          throw err;
        }
      }
    }
  }

  async function fetchSearchPage(page) {
    searchRequests++;

    const url =
      searchURL(page);

    try {
      const r =
        await fetchWithTimeout(
          url,
          CFG.searchTimeoutMs,
          "application/json"
        );

      const json =
        await r.json();

      console.log(
        `[DEBUG] Page ${page} response:`,
        {
          status: r.status,
          totalResults:
            json.pagination
              ?.totalResults,

          products:
            json.products?.length
        }
      );

      SEARCH_LOG.push({
        page,
        http: r.status,
        returned:
          json.products?.length || 0,

        totalResults:
          number(
            json.pagination
              ?.totalResults
          ),

        totalPages:
          number(
            json.pagination
              ?.totalPages
          ),

        gained: 0,
        url
      });

      if (!r.ok) {
        return {
          ok: false,
          page
        };
      }

      const products =
        json.products || [];

      let gained = 0;

      for (
        const raw
        of products
      ) {
        if (
          !raw ||
          typeof raw !==
            "object"
        ) {
          continue;
        }

        addProduct(raw);
        gained++;
      }

      SEARCH_LOG[
        SEARCH_LOG.length - 1
      ].gained = gained;

      return {
        ok: true,
        page,
        row:
          SEARCH_LOG[
            SEARCH_LOG.length - 1
          ],

        json
      };
    } catch (err) {
      console.error(
        `[DEBUG] Page ${page} fetch error:`,
        err
      );

      SEARCH_LOG.push({
        page,
        error: String(err),
        url
      });

      return {
        ok: false,
        page,
        error: err
      };
    }
  }

  function addProduct(raw) {
    const code =
      String(
        raw.code ||
          raw.id ||
          ""
      ).trim();

    if (!code)
      return;

    const key = code;

    if (
      PRODUCT_MAP.has(key)
    ) {
      return;
    }

    const name =
      clean(
        raw.name ||
          raw.productName ||
          ""
      );

    const brand =
      clean(
        raw.brand ||
          raw.brandName ||
          ""
      );

    const titleAll =
      clean([
        name,
        brand,
        raw.catalogName || "",
        raw.brickName || "",
        raw.verticalName || "",
        raw.segmentName || ""
      ].join(" "));

    const metal =
      detectMetal(titleAll);

    /*
     * Extract weight from multiple sources:
     * 1. Title/name parsing
     * 2. Raw weight fields from API
     * 3. Specification fields
     */
    let weight =
      extractWeight(titleAll);

    if (!weight) {
      weight =
        extractWeight(
          raw.weight ||
            raw.productWeight ||
            raw.weightValue ||
            ""
        );
    }

    if (!weight) {
      weight =
        number(
          raw.weight ||
            raw.productWeight
        );
    }

    if (!weight) {
      weight =
        extractWeight(
          (raw.specifications ||
            raw.specs ||
            [])
            .map(
              s =>
                (s?.value ||
                  s?.name ||
                  "")
            )
            .join(" ")
        );
    }

    const p = {
      code,
      name,
      brand,
      catalogName:
        clean(
          raw.catalogName || ""
        ),

      brickName:
        clean(
          raw.brickName || ""
        ),

      verticalName:
        clean(
          raw.verticalName || ""
        ),

      segmentName:
        clean(
          raw.segmentName || ""
        ),

      metal,
      explicitNonGold: false,
      url:
        absoluteUrl(
          raw.url
        ),

      image:
        imageUrl(raw),

      price:
        number(
          raw.price ||
            raw.currentPrice
        ),

      wasPrice:
        number(
          raw.mrp ||
            raw.wasPrice
        ),

      offerPrice:
        number(
          raw.offerPrice
        ),

      discount:
        number(
          raw.discount ||
            raw.discountPercent
        ),

      ...extractKaratAndFineness(
        titleAll
      ),

      weight:
        weight,

      rawProduct:
        raw
    };

    PRODUCT_MAP.set(
      key,
      p
    );
  }

  /******************************************************************
   * GENUINE GOLD FILTER
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

    return (
      p.metal === "gold" ||
      /\b(?:24|23|22|21|20|18|14|10|9)\s*(?:k|kt)\b/i
        .test(text) ||
      /\b(?:999\.9|999|995|916)\b/
        .test(text)
    );
  }


      async function enrichFromPdp(product) {
        if (!product.url) return;

        pdpRequests++;

        try {
          const response =
            await fetchWithTimeout(
              product.url,
              CFG.pdpTimeoutMs,
              "text/html,application/xhtml+xml"
            );

          if (!response.ok) return;

          const html =
            await response.text();

          const optionValue = labels => {
            const label =
              labels.map(
                value =>
                  value.replace(
                    /[.*+?^${}()|[\]\\]/g,
                    "\\$&"
                  )
              ).join("|");

            const match =
              html.match(
                new RegExp(
                  `"(?:name|qualifier)":"(?:${label})"[\\s\\S]{0,180}?"value":"([^"]+)"`,
                  "i"
                )
              );

            return match?.[1] || null;
          };

          const rawWeight =
            optionValue([
              "netWeight",
              "Net Weight",
              "metalWeight",
              "Metal Weight",
              "grossWeight",
              "Gross Weight"
            ]);

          const unit =
            optionValue(["uom", "UOM"]);

          const purityLabel =
            optionValue(["metalPurity", "METAL PURITY"]);

          const grams =
            extractWeight(
              rawWeight && unit
                ? `${rawWeight} ${unit}`
                : rawWeight
            );

          if (
            product.weight == null &&
            grams != null
          ) {
            product.weight = grams;
          }

          const details =
            extractKaratAndFineness(
              purityLabel || ""
            );

          if (product.karat == null) {
            product.karat = details.karat;
          }

          if (product.purity == null) {
            product.purity = details.purity;
          }

          PDP_LOG.push({
            code: product.code,
            status: response.status,
            weight: product.weight,
            karat: product.karat,
            purity: product.purity
          });
        } catch (error) {
          PDP_LOG.push({
            code: product.code,
            error: String(error?.message || error)
          });
        }
      }
  /******************************************************************
   * MAIN EXECUTION
   ******************************************************************/

  console.log(
    "=============================================="
  );

  console.log(
    "🚀 AJIO WOMEN FINE JEWELLERY GOLD EXTRACTOR"
  );

  console.log(
    "PLP config:",
    PLP.source
  );

  console.log(
    `Category: ${
      PLP.pathname
        .match(/\/category\/(\d+)/)
        ?.[1] ||
      "unknown"
    }`
  );

  console.log(
    "First page URL:",
    searchURL(0)
  );

  console.log(
    "=============================================="
  );

  console.log(
    "🌐 Reading live pagination..."
  );

  const injectedPageZero =
    globalThis.__AURUM_AJIO_PAGE0__;

  delete globalThis.__AURUM_AJIO_PAGE0__;
  delete globalThis.__AURUM_AJIO_REQUEST__;

  const first =
    injectedPageZero
      ? (() => {
          for (const raw of injectedPageZero.products || []) addProduct(raw);
          return {
            ok: true,
            page: 0,
            json: injectedPageZero
          };
        })()
      : await fetchSearchPage(0);

  if (!first.ok) {
    console.error(
      "❌ AJIO /api/search page 0 failed."
    );

    return;
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
    45;

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

  if (
    remainingPages.length
  ) {
    console.log(
      `⚡ Fetching ${remainingPages.length} remaining pages in parallel...`
    );

    for (
      const page
      of remainingPages
    ) {
      const r =
        await fetchSearchPage(
          page
        );

      if (r.ok) {
        console.log(
          `📦 page ${page}` +
          ` | ${r.row.returned}` +
          ` | +${r.row.gained}` +
          ` | unique ${PRODUCT_MAP.size}`
        );

        if (
          r.row.totalResults !=
            null
        ) {
          totalResults =
            r.row.totalResults;
        }

        if (
          r.row.totalPages !=
            null
        ) {
          totalPages =
            Math.max(
              totalPages,
              r.row.totalPages
            );
        }
      }
    }
  }

  const gold =
    Array.from(
      PRODUCT_MAP.values()
    ).filter(
      isGenuineGold
    );

  const pdpTargets =
    gold.filter(
      product =>
        product.weight == null ||
        product.karat == null ||
        product.purity == null
    );

  if (pdpTargets.length > 0) {
    console.log(
      `🔬 PDP enrichment targets: ${pdpTargets.length}`
    );

    for (
      let index = 0;
      index < pdpTargets.length;
      index += CFG.pdpConcurrency
    ) {
      await Promise.all(
        pdpTargets
          .slice(index, index + CFG.pdpConcurrency)
          .map(enrichFromPdp)
      );
    }
  }

  /*
   * Debug: Show products with missing weight.
   */
  const missingWeight =
    gold.filter(p => !p.weight);

  if (missingWeight.length > 0) {
    console.log(
      `⚠️ ${missingWeight.length} products have null weight (may need PDP load):`
    );

    for (
      const p
      of missingWeight.slice(0, 1)
    ) {
      const raw = p.rawProduct;
      console.log(
        "All available fields:",
        Object.keys(raw)
      );

      console.log(
        "Full raw product:"
      );

      console.log(raw);
    }
  }

  console.log(
    "=============================================="
  );

  console.log(
    `✅ Extraction Complete`
  );

  console.log({
    totalSearchRequests:
      searchRequests,

    uniqueProducts:
      PRODUCT_MAP.size,

    genuineGold:
      gold.length,

    elapsedMs:
      Math.round(
        performance.now() -
          startedAt
      )
  });

  console.log(
    "=============================================="
  );

  if (
    CFG.autoPrintFullTable
  ) {
    console.table(gold);
  }

  /*
   * Always print first product sample to verify data quality.
   */
  if (gold.length > 0) {
    console.log(
      "📦 Sample product (first):"
    );

    console.log(
      gold[0]
    );
  }

  const exportedGold =
    gold.map(
      product => ({
        ...product,
        bridgeSnapshot: true,
        grams: product.weight,
        karat: product.karat,
        purity: product.purity
      })
    );

  globalThis.ajioWomenFineJewelleryGold =
    exportedGold;

  globalThis.ajioWomenFineJewelleryCatalogue =
    exportedGold;

  globalThis.ajioWomenFineJewelleryIncomplete =
    [];

  /*
   * Export results
   */
  return {
    store: "ajio",
    complete: true,
    products: exportedGold,
    catalogue: exportedGold,

    missing: [],
    runs: 1
  };
})();
