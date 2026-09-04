(async () => {
  "use strict";

  const navigation = performance.getEntriesByType("navigation")[0];
  if (!globalThis.__aurumMasterRunner && navigation?.type !== "reload") {
    console.info("Refreshing Myntra before extraction. Run this script again after the page reloads.");
    location.reload();
    return;
  }

  console.clear();

  /******************************************************************
   * MYNTRA GOLD MASTER V7 FINAL
   * FAST + EVIDENCE-BASED + PARALLEL
   *
   * Run on:
   * https://www.myntra.com/gold-coin
   *
   * PRINCIPLES
   * ----------
   * 1. Myntra totalCount is informational only.
   * 2. Discover through independent sort streams.
   * 3. Union by productId.
   * 4. Title/listing evidence outranks API descriptions.
   * 5. Explicit title total can NEVER be downgraded by API.
   * 6. Explicit non-gold can NEVER become gold through API.
   * 7. 22K => 916 when exact fineness is absent.
   * 8. Never invent exact 24K fineness.
   * 9. Weight parser extracts independent evidence:
   *      - explicit total
   *      - unit weight
   *      - quantity
   *      - component weights
   *      - calculated total
   *      - set/pack count
   * 10. Product API only fills unresolved fields.
   * 11. No PDP pages are opened.
   ******************************************************************/

  const CFG = {
    rows: 50,

    searchConcurrency: 32,
    apiConcurrency: 32,

    timeoutMs: 2200,
    retries: 0,

    includePLA: true,

    streams: [
      ["default", null],
      ["price_low", "price_asc"],
      ["price_high", "price_desc"],
      ["popularity", "popularity"],
      ["newest", "new"]
    ],

    /*
     * High-yield offsets established by previous runs.
     *
     * We deliberately avoid o=500.
     */
    primaryOffsets: [
      0,
      97,
      194,
      196,
      198,
      200,
      250,
      294,
      300
    ],

    /*
     * Boundary offsets that have actually exposed additional IDs.
     *
     * Rescue is adaptive: we don't blindly run every combination.
     */
    rescueOffsets: [
      193,
      195,
      197,
      199,
      245,
      249,
      291,
      299,
      343,
      349
    ],

    /*
     * Rescue these streams first because previous runs showed
     * price_high is especially useful near boundaries.
     */
    rescueStreams: [
      ["price_high", "price_desc"],
      ["price_low", "price_asc"],
      ["popularity", "popularity"]
    ],

    /*
     * Stop rescue early when actual reported total has been reached.
     * We do NOT require reaching it.
     */
    stopAtReportedIfReached: true,

    /*
     * API only for genuinely unresolved required fields.
     *
     * Missing exact 24K fineness alone does not trigger API.
     */
    enrich24KFineness: false
  };

  /******************************************************************
   * STATE
   ******************************************************************/

  const PRODUCTS = new Map();

  const ORGANIC = new Set();
  const PLA = new Set();

  const SEARCH_LOG = [];
  const API_LOG = [];
  const STREAM_STATS = new Map();

  let searchRequests = 0;
  let apiRequests = 0;

  const startedAt = performance.now();

  /******************************************************************
   * GENERIC HELPERS
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

  const num = value => {
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

    const m =
      String(value)
        .replace(/,/g, "")
        .match(/-?\d+(?:\.\d+)?/);

    if (!m)
      return null;

    const n = Number(m[0]);

    return Number.isFinite(n)
      ? n
      : null;
  };

  const uniq = array =>
    [...new Set(
      array.filter(
        x =>
          x !== null &&
          x !== undefined &&
          x !== ""
      )
    )];

  function nearly(a, b, epsilon = 0.0001) {
    return (
      Number.isFinite(a) &&
      Number.isFinite(b) &&
      Math.abs(a - b) <= epsilon
    );
  }

  /******************************************************************
   * METAL
   ******************************************************************/

  const NON_GOLD_RE =
    /\b(?:brass|copper|bronze|steel|stainless\s+steel|zinc|alloy|aluminium|aluminum|iron|plastic|wood|wooden)\b/i;

  const GOLD_RE =
    /\b(?:gold|yellow\s+gold|rose\s+gold)\b/i;

  function detectMetal(text) {
    const s = clean(text);

    /*
     * Explicit non-gold material is absolute.
     */
    if (NON_GOLD_RE.test(s))
      return "non-gold";

    if (GOLD_RE.test(s))
      return "gold";

    if (/\bsilver\b/i.test(s))
      return "silver";

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

    const n = Number(s);

    if (!Number.isFinite(n))
      return null;

    if (n >= 1000 && n <= 9999)
      return String(n / 10);

    if (n >= 300 && n <= 1000)
      return String(n);

    return null;
  }

  function standardFineness(k) {
    /*
     * 24 intentionally omitted.
     */
    return ({
      23: "958",
      22: "916",
      21: "875",
      20: "833",
      19: "792",
      18: "750",
      15: "625",
      14: "585",
      12: "500",
      10: "417",
      9: "375",
      8: "333"
    })[k] ?? null;
  }

  function karatFromFineness(f) {
    const n = Number(f);

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

    if (n >= 624 && n <= 626)
      return 15;

    if (n >= 584 && n <= 586)
      return 14;

    if (n >= 499 && n <= 501)
      return 12;

    if (n >= 416 && n <= 418)
      return 10;

    if (n >= 374 && n <= 376)
      return 9;

    return null;
  }

  function parsePurity(text) {
    const s = clean(text);

    let karat = null;
    let fineness = null;
    let source = null;

    const K =
      "(24|23|22|21|20|19|18|15|14|12|10|9|8)";

    const F =
      "(9999|999\\.99|999\\.9\\+?|999\\+?|995|990|958|950|925|916\\.7|9167|916|875|833|792|750|625|585|500|417|375|333)";

    let m;

    /*
     * 24KT (999)
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
        fineness:
          normalizeFineness(m[2]),
        source:
          "title-explicit-both"
      };
    }

    /*
     * 24K 999
     */
    m = s.match(
      new RegExp(
        `\\b${K}\\s*(?:kt|k|karat|carat)\\s*[-:/]?\\s*${F}\\b`,
        "i"
      )
    );

    if (m) {
      return {
        karat: Number(m[1]),
        fineness:
          normalizeFineness(m[2]),
        source:
          "title-explicit-both"
      };
    }

    /*
     * 999 Purity 24KT
     */
    m = s.match(
      new RegExp(
        `\\b${F}\\s*(?:purity|fine|fineness)?[^0-9]{0,40}${K}\\s*(?:kt|k)\\b`,
        "i"
      )
    );

    if (m) {
      return {
        karat: Number(m[2]),
        fineness:
          normalizeFineness(m[1]),
        source:
          "title-explicit-both"
      };
    }

    /*
     * Explicit fineness.
     */
    m = s.match(
      new RegExp(
        `\\b${F}\\s*(?:purity|fine|fineness)\\b`,
        "i"
      )
    );

    if (m) {
      fineness =
        normalizeFineness(m[1]);

      karat =
        karatFromFineness(
          fineness
        );

      source =
        "title-explicit-fineness";
    }

    /*
     * Karat.
     */
    const km =
      s.match(
        new RegExp(
          `\\b${K}\\s*(?:kt|k|karat|carat)\\b`,
          "i"
        )
      );

    if (km) {
      karat =
        Number(km[1]);

      source ||=
        "title-karat";
    }

    /*
     * Vedhani 995 etc.
     */
    if (
      !fineness &&
      /\b(?:gold|coin|bar|vedhani|bullion)\b/i.test(s)
    ) {
      const fm =
        s.match(
          /\b(999\.9|999|995|916\.7|916)\b(?!\s*(?:g|gm|gram|mg))/i
        );

      if (fm) {
        fineness =
          normalizeFineness(
            fm[1]
          );

        source =
          "title-explicit-fineness";
      }
    }

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
     * 22K standard.
     */
    if (
      karat === 22 &&
      !fineness
    ) {
      fineness = "916";
      source =
        "karat-standard";
    }

    if (
      karat &&
      karat !== 24 &&
      !fineness
    ) {
      fineness =
        standardFineness(
          karat
        );

      if (fineness) {
        source =
          "karat-standard";
      }
    }

    return {
      karat,
      fineness,
      source
    };
  }

  function purityRank(source) {
    return ({
      "title-explicit-both": 100,
      "title-explicit-fineness": 98,
      "title-karat": 95,
      "karat-standard": 90,

      "API-structured": 80,
      "API-description": 30
    })[source] ?? 0;
  }

  /******************************************************************
   * WEIGHT NORMALIZATION
   ******************************************************************/

  function normalizeWeightText(text) {
    return clean(text)
      .toLowerCase()

      /*
       * Normalize multiplication.
       */
      .replace(/[×✕✖]/g, "x")

      /*
       * Normalize units.
       */
      .replace(/(\d(?:[\d.]*))\s*(?:milligrams?|mgs?|mg)\b/g, "$1 mg ")
      .replace(/(\d(?:[\d.]*))\s*(?:grams?|gms?|gm|g)\b/g, "$1 g ")

      /*
       * Normalize pieces.
       */
      .replace(/\bpieces?\b/g, " pcs ")
      .replace(/\bpc\b/g, " pcs ")
      .replace(/\bpcs\b/g, " pcs ")

      .replace(/\s+/g, " ")
      .trim();
  }

  function toGrams(value, unit) {
    const n = Number(value);

    if (!Number.isFinite(n))
      return null;

    if (
      String(unit)
        .toLowerCase() ===
      "mg"
    ) {
      return n / 1000;
    }

    return n;
  }

  function validWeight(n) {
    return (
      Number.isFinite(n) &&
      n > 0 &&
      n <= 100
    );
  }

  function emptyWeight() {
    return {
      total: null,
      unit: null,
      quantity: null,

      components: null,

      explicitTotals: [],
      allWeights: [],

      statedWeight: null,
      possibleTotal: null,
      calculatedTotal: null,

      conflict: false,
      ambiguous: false,

      source: null,
      confidence: 0,

      evidence: []
    };
  }

  function weightResult(obj = {}) {
    return {
      ...emptyWeight(),
      ...obj
    };
  }

  /******************************************************************
   * EXTRACT ALL WEIGHT TOKENS
   ******************************************************************/

  function extractWeightTokens(s) {
    return [
      ...s.matchAll(
        /(\d+(?:\.\d+)?)\s*(mg|g)\b/gi
      )
    ]
      .map(m => ({
        raw: m[0],
        value:
          toGrams(
            m[1],
            m[2]
          ),
        unit:
          m[2].toLowerCase(),
        index:
          m.index
      }))
      .filter(x =>
        validWeight(x.value)
      );
  }

  /******************************************************************
   * COMPONENT EXPRESSION
   ******************************************************************/

  function extractComponents(s) {
    const matches = [];

    const re =
      /((?:\d+(?:\.\d+)?\s*(?:mg|g)?\s*\+\s*)+\d+(?:\.\d+)?\s*(?:mg|g))/gi;

    for (
      const m
      of s.matchAll(re)
    ) {
      const expression =
        m[1];

      const finalUnit =
        expression.match(
          /(mg|g)\s*$/i
        )?.[1] || "g";

      const components =
        expression
          .split("+")
          .map(clean)
          .map(part => {
            const p =
              part.match(
                /(\d+(?:\.\d+)?)\s*(mg|g)?/i
              );

            if (!p)
              return null;

            return toGrams(
              p[1],
              p[2] ||
              finalUnit
            );
          })
          .filter(validWeight);

      if (
        components.length >= 2
      ) {
        matches.push({
          index: m.index,

          components,

          total:
            components.reduce(
              (sum, x) =>
                sum + x,
              0
            )
        });
      }
    }

    return matches;
  }

  /******************************************************************
   * WEIGHT EVIDENCE PARSER
   ******************************************************************/

function parseWeight(text) {
  const raw = clean(text);

  const s = raw
    .toLowerCase()
    .replace(/[×✕✖]/g, "x")
    .replace(/(\d(?:[\d.]*))\s*(?:milligrams?|mgs?|mg)\b/g, "$1 mg")
    .replace(/(\d(?:[\d.]*))\s*(?:grams?|gms?|gm|g)\b/g, "$1 g")
    .replace(/\bpieces?\b/g, "pcs")
    .replace(/\bpc\b/g, "pcs")
    .replace(/\s+/g, " ")
    .trim();

  const result = {
    total: null,
    unit: null,
    quantity: null,
    components: null,

    explicitTotals: [],
    allWeights: [],

    statedWeight: null,
    possibleTotal: null,
    calculatedTotal: null,

    conflict: false,
    ambiguous: false,

    source: null,
    confidence: 0,
    evidence: []
  };

  const toG = (value, unit = "g") => {
    const n = Number(value);

    if (!Number.isFinite(n))
      return null;

    return String(unit).toLowerCase() === "mg"
      ? n / 1000
      : n;
  };

  const valid = n =>
    Number.isFinite(n) &&
    n > 0 &&
    n <= 100;

  const same = (a, b) =>
    Number.isFinite(a) &&
    Number.isFinite(b) &&
    Math.abs(a - b) < 0.0001;

  const done = ({
    total,
    unit = null,
    quantity = null,
    components = null,

    statedWeight = null,
    possibleTotal = null,
    calculatedTotal = null,

    conflict = false,
    ambiguous = false,

    source,
    confidence,

    evidence = []
  }) => ({
    ...result,

    total,
    unit,
    quantity,
    components,

    statedWeight,
    possibleTotal,
    calculatedTotal,

    conflict,
    ambiguous,

    source,
    confidence,

    evidence
  });

  let m;

  /****************************************************************
   * 0. EXPLICIT HEADLINE DECIMAL WEIGHT (e.g. 3.5g, 3.5 gm, 2.5g)
   ****************************************************************/
  const headlineDecimal = s.match(/\b(\d+\.\d+)\s*(g|gm|grams)\b/i);
  if (headlineDecimal && !s.includes("each")) {
    const headlineGrams = Number(headlineDecimal[1]);
    if (Number.isFinite(headlineGrams) && headlineGrams > 0 && headlineGrams <= 100) {
      return done({
        total: headlineGrams,
        unit: headlineGrams,
        quantity: 1,
        statedWeight: headlineGrams,
        source: "explicit-headline-decimal-weight",
        confidence: 150
      });
    }
  }

  /****************************************************************
   * 1. EACH × PCS — HIGHEST PRIORITY
   *
   * Works anywhere in the title:
   *
   * 5gm each x 5 Pcs
   * 5gm each
   * 3Pcs ... 5gm each
   * 1gm each x 6pcs
   ****************************************************************/

  /*
   * Xg EACH × N PCS
   */
  m = s.match(
    /(\d+(?:\.\d+)?)\s*(mg|g)\s*each\s*x\s*(\d+)\s*pcs\b/i
  );

  if (m) {
    const unit =
      toG(m[1], m[2]);

    const quantity =
      Number(m[3]);

    const calculatedTotal =
      unit * quantity;

    /*
     * Look immediately before the parenthesized expression for an
     * explicit total:
     *
     * 25 Gm (5gm each x 5 Pcs)
     */

    let explicitTotal = null;

    const expressionIndex =
      m.index;

    const before =
      s.slice(
        0,
        expressionIndex
      );

    const beforeWeights =
      [
        ...before.matchAll(
          /(\d+(?:\.\d+)?)\s*(mg|g)\b/gi
        )
      ]
        .map(x =>
          toG(
            x[1],
            x[2]
          )
        )
        .filter(valid);

    if (beforeWeights.length) {
      const nearest =
        beforeWeights[
          beforeWeights.length - 1
        ];

      /*
       * Only accept nearest headline total when it agrees with
       * component calculation.
       *
       * Otherwise component math is safer.
       */
      if (
        same(
          nearest,
          calculatedTotal
        )
      ) {
        explicitTotal =
          nearest;
      }
    }

    const total =
      explicitTotal ??
      calculatedTotal;

    return done({
      total,
      unit,
      quantity,

      components:
        Array(quantity)
          .fill(unit),

      statedWeight:
        explicitTotal,

      calculatedTotal,

      conflict:
        explicitTotal != null &&
        !same(
          explicitTotal,
          calculatedTotal
        ),

      source:
        explicitTotal != null
          ? "verified-total+each-x-pcs"
          : "each-x-pcs",

      confidence: 130,

      evidence: [
        {
          type: "unit",
          value: unit
        },
        {
          type: "quantity",
          value: quantity
        },
        {
          type: "calculated-total",
          value:
            calculatedTotal
        },
        ...(explicitTotal != null
          ? [{
              type:
                "explicit-total",
              value:
                explicitTotal
            }]
          : [])
      ]
    });
  }

  /*
   * N PCS ... Xg EACH
   *
   * This directly fixes:
   *
   * Muthoot Pappachan 3Pcs ... 5gm each
   */
  m = s.match(
    /\b(\d+)\s*-?\s*pcs\b[\s\S]*?(\d+(?:\.\d+)?)\s*(mg|g)\s*each\b/i
  );

  if (m) {
    const quantity =
      Number(m[1]);

    const unit =
      toG(
        m[2],
        m[3]
      );

    const total =
      quantity * unit;

    return done({
      total,
      unit,
      quantity,

      components:
        Array(quantity)
          .fill(unit),

      calculatedTotal:
        total,

      source:
        "pcs-x-each",

      confidence: 130,

      evidence: [
        {
          type:
            "quantity",
          value:
            quantity
        },
        {
          type:
            "unit",
          value:
            unit
        },
        {
          type:
            "calculated-total",
          value:
            total
        }
      ]
    });
  }

  /****************************************************************
   * 2. EXPLICIT TOTAL + COMPONENTS
   *
   * Directly fixes:
   *
   * 4gm (2gm + 2gm)
   * 4.5gm (0.5gm + 2gm + 2gm)
   ****************************************************************/

  m = s.match(
    /(\d+(?:\.\d+)?)\s*(mg|g)\s*\(\s*([^)]*\+[^)]*)\)/i
  );

  if (m) {
    const explicitTotal =
      toG(
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
              /(\d+(?:\.\d+)?)\s*(mg|g)?/i
            );

          if (!x)
            return null;

          return toG(
            x[1],
            x[2] ||
            defaultUnit
          );
        })
        .filter(valid);

    if (
      components.length >= 2
    ) {
      const calculatedTotal =
        components.reduce(
          (sum, value) =>
            sum + value,
          0
        );

      const uniform =
        components.every(
          value =>
            same(
              value,
              components[0]
            )
        );

      return done({
        total:
          explicitTotal,

        unit:
          uniform
            ? components[0]
            : null,

        quantity:
          components.length,

        components,

        statedWeight:
          explicitTotal,

        calculatedTotal,

        conflict:
          !same(
            explicitTotal,
            calculatedTotal
          ),

        source:
          "verified-total+components",

        confidence: 130,

        evidence: [
          {
            type:
              "explicit-total",
            value:
              explicitTotal
          },
          {
            type:
              "components",
            value:
              components
          },
          {
            type:
              "calculated-total",
            value:
              calculatedTotal
          }
        ]
      });
    }
  }

  /****************************************************************
   * 3. COMPONENT EXPRESSION WITHOUT HEADLINE TOTAL
   *
   * 2gm + 2gm
   * 1g + 2g
   * 2+1 GM
   ****************************************************************/

  m = s.match(
    /((?:\d+(?:\.\d+)?\s*(?:mg|g)?\s*\+\s*)+\d+(?:\.\d+)?\s*(?:mg|g))/i
  );

  if (m) {
    const expression =
      m[1];

    const finalUnit =
      expression.match(
        /(mg|g)\s*$/i
      )?.[1] || "g";

    const components =
      expression
        .split("+")
        .map(part => {
          const x =
            part.match(
              /(\d+(?:\.\d+)?)\s*(mg|g)?/i
            );

          if (!x)
            return null;

          return toG(
            x[1],
            x[2] ||
            finalUnit
          );
        })
        .filter(valid);

    if (
      components.length >= 2
    ) {
      const total =
        components.reduce(
          (sum, value) =>
            sum + value,
          0
        );

      const uniform =
        components.every(
          value =>
            same(
              value,
              components[0]
            )
        );

      return done({
        total,

        unit:
          uniform
            ? components[0]
            : null,

        quantity:
          components.length,

        components,

        calculatedTotal:
          total,

        source:
          "components",

        confidence: 125
      });
    }
  }

  /****************************************************************
   * 4. PCS + WEIGHT
   *
   * Example:
   *
   * 5-Pcs ... Weight: 2 gm
   *
   * => 10g
   ****************************************************************/

  m = s.match(
    /\b(\d+)\s*-?\s*pcs\b[\s\S]*?\bweight\s*:?\s*(\d+(?:\.\d+)?)\s*(mg|g)\b/i
  );

  if (m) {
    const quantity =
      Number(m[1]);

    const unit =
      toG(
        m[2],
        m[3]
      );

    const total =
      quantity * unit;

    return done({
      total,
      unit,
      quantity,

      components:
        Array(quantity)
          .fill(unit),

      calculatedTotal:
        total,

      source:
        "pcs-weight",

      confidence: 120
    });
  }

  /****************************************************************
   * 5. SET/PACK OF N + X EACH
   ****************************************************************/

  m = s.match(
    /\b(?:set|pack|combo)\s+of\s+(\d+)\b[\s\S]*?(\d+(?:\.\d+)?)\s*(mg|g)\s*each\b/i
  );

  if (m) {
    const quantity =
      Number(m[1]);

    const unit =
      toG(
        m[2],
        m[3]
      );

    const total =
      quantity * unit;

    return done({
      total,
      unit,
      quantity,

      components:
        Array(quantity)
          .fill(unit),

      calculatedTotal:
        total,

      source:
        "set-x-each",

      confidence: 120
    });
  }

  /****************************************************************
   * 6. EXPLICIT LABELLED TOTAL
   ****************************************************************/

  m = s.match(
    /\b(?:total\s+(?:gold\s+)?weight|net\s+weight|gold\s+weight)\s*:?\s*(\d+(?:\.\d+)?)\s*(mg|g)\b/i
  );

  if (m) {
    const total =
      toG(
        m[1],
        m[2]
      );

    return done({
      total,
      unit: total,
      quantity: 1,

      source:
        "explicit-total",

      confidence: 118
    });
  }

  /****************************************************************
   * 7. SIMPLE WEIGHT LABEL
   ****************************************************************/

  m = s.match(
    /\bweight\s*:?\s*(\d+(?:\.\d+)?)\s*(mg|g)\b/i
  );

  if (m) {
    const total =
      toG(
        m[1],
        m[2]
      );

    return done({
      total,
      unit: total,
      quantity: 1,

      source:
        "weight-label",

      confidence: 112
    });
  }

  /****************************************************************
   * 8. SET / PACK OF N — CONSERVATIVE
   *
   * Without explicit "each" evidence, a trailing weight is
   * ambiguous: it may be the whole set total OR per-piece weight.
   * Do not silently choose either interpretation. API enrichment
   * can resolve it when structured/description evidence exists.
   ****************************************************************/

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
          /(\d+(?:\.\d+)?)\s*(mg|g)\b/gi
        )
      ]
        .map(x => ({
          value:
            toG(
              x[1],
              x[2]
            ),

          unit:
            x[2],

          index:
            x.index
        }))
        .filter(x =>
          valid(x.value)
        );

    if (weights.length) {
      const last =
        weights[
          weights.length - 1
        ];

      return done({
        // Myntra set/pack rule: without explicit EACH / PER PIECE /
        // x N PCS wording, the trailing stated weight is the total
        // listing weight. Quantity is retained separately.
        total: last.value,

        unit: null,

        quantity,

        statedWeight:
          last.value,

        possibleTotal: null,

        calculatedTotal: null,

        components: null,

        conflict: false,

        ambiguous: false,

        source:
          "set-pack-stated-total",

        confidence: 110,

        evidence: [
          {
            type: "set-pack-quantity",
            value: quantity
          },
          {
            type: "stated-total-weight",
            value: last.value
          }
        ]
      });
    }
  }

  /****************************************************************
   * 9. PRODUCT-NOUN WEIGHT
   *
   * Gold Coin 1gm
   * Gold Coin-2gm
   * Gold Bar 20gm
   * 2 GM Lakshmi Gold Coin
   ****************************************************************/

  const patterns = [
    /\b(?:gold\s+)?(?:coin|bar|pendant|vedhani|biscuit)\b[^0-9]{0,40}(\d+(?:\.\d+)?)\s*(mg|g)\b/i,

    /(\d+(?:\.\d+)?)\s*(mg|g)\b[^0-9]{0,60}(?:gold\s+)?(?:coin|bar|pendant|vedhani|biscuit)\b/i
  ];

  for (
    const pattern
    of patterns
  ) {
    m = s.match(pattern);

    if (!m)
      continue;

    let total =
      toG(
        m[1],
        m[2]
      );

    /*
     * 500g typo correction for bullion.
     */
    if (
      String(m[2])
        .toLowerCase() ===
        "g" &&
      total >= 100 &&
      total <= 999 &&
      /\bgold\b/i.test(s) &&
      /\b(?:coin|bar|bullion|biscuit)\b/i
        .test(s)
    ) {
      total /= 1000;

      return done({
        total,
        unit: total,
        quantity: 1,

        source:
          "corrected-g-to-mg",

        confidence: 100
      });
    }

    return done({
      total,
      unit: total,
      quantity: 1,

      source:
        "product-weight",

      confidence: 110
    });
  }

  /****************************************************************
   * 10. FINAL TITLE WEIGHT
   ****************************************************************/

  const weights =
    [
      ...s.matchAll(
        /(\d+(?:\.\d+)?)\s*(mg|g)\b/gi
      )
    ]
      .map(x => ({
        value:
          toG(
            x[1],
            x[2]
          ),

        unit:
          x[2],

        index:
          x.index
      }))
      .filter(x =>
        valid(x.value)
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
        .toLowerCase() ===
        "g" &&
      total >= 100 &&
      total <= 999 &&
      /\bgold\b/i.test(s) &&
      /\b(?:coin|bar|bullion|biscuit)\b/i
        .test(s)
    ) {
      total /= 1000;

      return done({
        total,
        unit: total,
        quantity: 1,

        source:
          "corrected-last-g-to-mg",

        confidence: 95
      });
    }

    return done({
      total,
      unit: total,
      quantity: 1,

      source:
        "title-last-weight",

      confidence: 100
    });
  }

  return result;
}

  /******************************************************************
   * PRODUCT HELPERS
   ******************************************************************/

  function idOf(p) {
    return (
      p?.productId ??
      p?.styleId ??
      p?.id ??
      null
    );
  }

  function nameOf(p) {
    return (
      clean(
        p?.productName ??
        p?.product ??
        p?.name
      ) || null
    );
  }

  function linkOf(p) {
    const path =
      p?.landingPageUrl ??
      p?.productUrl ??
      p?.url;

    if (!path)
      return null;

    try {
      return new URL(
        path,
        location.origin
      ).href;
    } catch {
      return null;
    }
  }

  /******************************************************************
   * COPY WEIGHT
   ******************************************************************/

  function copyWeight(to, w) {
    to.totalWeightGrams =
      w.total;

    to.unitWeightGrams =
      w.unit;

    to.quantity =
      w.quantity;

    to.componentWeightsGrams =
      w.components;

    to.statedWeightGrams =
      w.statedWeight;

    to.possibleTotalWeightGrams =
      w.possibleTotal;

    to.calculatedWeightGrams =
      w.calculatedTotal;

    to.weightConflict =
      Boolean(w.conflict);

    to.weightAmbiguous =
      Boolean(w.ambiguous);

    to.weightSource =
      w.source;

    to.weightConfidence =
      w.confidence;

    to.weightEvidence =
      w.evidence || [];
  }

  /******************************************************************
   * NORMALIZE PLP
   ******************************************************************/

  function normalizePLP(
    raw,
    discoverySource
  ) {
    const id =
      idOf(raw);

    if (!id)
      return null;

    const name =
      nameOf(raw);

    const link =
      linkOf(raw);

    const text =
      clean([
        name,
        raw?.brand,
        link
      ].join(" "));

    const metal =
      detectMetal(text);

    const purity =
      parsePurity(text);

    const weight =
      parseWeight(name);

    const price =
      num(raw?.price) ??
      num(
        raw?.discountedPrice
      );

    const mrp =
      num(raw?.mrp);

    const out = {
      id,

      brand:
        clean(raw?.brand) ||
        null,

      name,

      metal,

      explicitNonGold:
        metal === "non-gold",

      karat:
        metal === "non-gold"
          ? null
          : purity.karat,

      fineness:
        metal === "non-gold"
          ? null
          : purity.fineness,

      puritySource:
        purity.source,

      price,

      mrp,

      discountAmount:
        num(raw?.discount) ??
        (
          price != null &&
          mrp != null
            ? mrp - price
            : null
        ),

      rating:
        num(raw?.rating),

      ratingCount:
        num(raw?.ratingCount),

      sellerId:
        raw
          ?.buyButtonWinnerSellerPartnerId ??
        null,

      skuId:
        raw
          ?.buyButtonWinnerSkuId ??
        null,

      image:
        raw?.searchImage
          ? String(
              raw.searchImage
            ).replace(
              /^http:/,
              "https:"
            )
          : null,

      link,

      discoverySources:
        new Set([
          discoverySource
        ]),

      apiLoaded: false,

      purityConflict: false,
      purityConflictValues: null
    };

    copyWeight(
      out,
      weight
    );

    return out;
  }

  /******************************************************************
   * MERGE PRODUCTS
   ******************************************************************/

  function merge(existing, incoming) {
    if (!existing)
      return incoming;

    const out =
      existing;

    if (
      incoming.discoverySources
        instanceof Set
    ) {
      for (
        const source
        of incoming.discoverySources
      ) {
        out.discoverySources.add(
          source
        );
      }
    }

    /*
     * Explicit non-gold is absolute.
     */
    if (
      incoming.explicitNonGold
    ) {
      out.explicitNonGold = true;
      out.metal = "non-gold";
      out.karat = null;
      out.fineness = null;
    }

    for (
      const field
      of [
        "brand",
        "price",
        "mrp",
        "discountAmount",
        "rating",
        "ratingCount",
        "sellerId",
        "skuId",
        "image",
        "link"
      ]
    ) {
      if (
        out[field] == null &&
        incoming[field] != null
      ) {
        out[field] =
          incoming[field];
      }
    }

    if (
      incoming.name &&
      (
        !out.name ||
        incoming.name.length >
          out.name.length
      )
    ) {
      out.name =
        incoming.name;
    }

    if (
      !out.explicitNonGold &&
      incoming.metal &&
      !out.metal
    ) {
      out.metal =
        incoming.metal;
    }

    /*
     * Purity.
     */
    if (!out.explicitNonGold) {
      if (
        incoming.karat != null &&
        (
          out.karat == null ||
          purityRank(
            incoming.puritySource
          ) >
          purityRank(
            out.puritySource
          )
        )
      ) {
        out.karat =
          incoming.karat;
      }

      if (
        incoming.fineness &&
        (
          !out.fineness ||
          purityRank(
            incoming.puritySource
          ) >
          purityRank(
            out.puritySource
          )
        )
      ) {
        out.fineness =
          incoming.fineness;

        out.puritySource =
          incoming.puritySource;
      }
    }

    /*
     * Weight:
     *
     * Listing evidence wins on confidence.
     */
    if (
      incoming.totalWeightGrams != null
    ) {
      if (
        out.totalWeightGrams == null ||
        (
          incoming.weightConfidence >
          (out.weightConfidence || 0)
        )
      ) {
        copyWeight(
          out,
          {
            total:
              incoming.totalWeightGrams,

            unit:
              incoming.unitWeightGrams,

            quantity:
              incoming.quantity,

            components:
              incoming.componentWeightsGrams,

            statedWeight:
              incoming.statedWeightGrams,

            possibleTotal:
              incoming.possibleTotalWeightGrams,

            calculatedTotal:
              incoming.calculatedWeightGrams,

            conflict:
              incoming.weightConflict,

            ambiguous:
              incoming.weightAmbiguous,

            source:
              incoming.weightSource,

            confidence:
              incoming.weightConfidence,

            evidence:
              incoming.weightEvidence
          }
        );
      }
    }

    if (
      out.totalWeightGrams == null &&
      incoming.weightAmbiguous &&
      incoming.weightConfidence >
        (out.weightConfidence || 0)
    ) {
      copyWeight(
        out,
        {
          total: null,

          unit:
            incoming.unitWeightGrams,

          quantity:
            incoming.quantity,

          components:
            incoming.componentWeightsGrams,

          statedWeight:
            incoming.statedWeightGrams,

          possibleTotal:
            incoming.possibleTotalWeightGrams,

          calculatedTotal:
            incoming.calculatedWeightGrams,

          conflict:
            incoming.weightConflict,

          ambiguous: true,

          source:
            incoming.weightSource,

          confidence:
            incoming.weightConfidence,

          evidence:
            incoming.weightEvidence
        }
      );
    }

    return out;
  }

  /******************************************************************
   * ADD
   ******************************************************************/

  function add(
    raw,
    source,
    type
  ) {
    const p =
      normalizePLP(
        raw,
        source
      );

    if (!p)
      return false;

    const key =
      String(p.id);

    const fresh =
      !PRODUCTS.has(key);

    PRODUCTS.set(
      key,
      merge(
        PRODUCTS.get(key),
        p
      )
    );

    if (
      type === "organic"
    ) {
      ORGANIC.add(key);
    }

    if (
      type === "pla"
    ) {
      PLA.add(key);
    }

    return fresh;
  }

  /******************************************************************
   * BOOTSTRAP
   ******************************************************************/

  const initial =
    window.__myx
      ?.searchData
      ?.results;

  if (!initial) {
    console.error(
      "❌ window.__myx.searchData.results missing."
    );

    return;
  }

  const reportedTotal =
    num(initial.totalCount);

  for (
    const p
    of initial.products || []
  ) {
    add(
      p,
      "bootstrap",
      "organic"
    );
  }

  if (CFG.includePLA) {
    for (
      const p
      of initial.plaProducts || []
    ) {
      add(
        p,
        "bootstrap-PLA",
        "pla"
      );
    }
  }

  /******************************************************************
   * URL / PINCODE
   ******************************************************************/

  const slug =
    location.pathname
      .replace(
        /^\/+|\/+$/g,
        ""
      )
      .split("/")[0];

  function getPincode() {
    try {
      const cookie =
        document.cookie
          .split("; ")
          .find(x =>
            x.startsWith(
              "mynt-ulc="
            )
          );

      if (!cookie)
        return null;

      return (
        decodeURIComponent(
          cookie
        ).match(
          /pincode:(\d{6})/
        )?.[1] ??
        null
      );

    } catch {
      return null;
    }
  }

  const PINCODE =
    getPincode();

  function searchURL(
    offset,
    sort
  ) {
    const u =
      new URL(
        `/gateway/v4/search/${slug}`,
        location.origin
      );

    const current =
      new URL(
        location.href
      );

    for (
      const [key, value]
      of current.searchParams
    ) {
      if (
        ![
          "o",
          "p",
          "rows",
          "sort"
        ].includes(key)
      ) {
        u.searchParams.append(
          key,
          value
        );
      }
    }

    u.searchParams.set(
      "rows",
      String(CFG.rows)
    );

    u.searchParams.set(
      "o",
      String(offset)
    );

    u.searchParams.set(
      "p",
      String(
        Math.max(
          1,
          Math.floor(
            offset /
            CFG.rows
          ) + 1
        )
      )
    );

    if (sort) {
      u.searchParams.set(
        "sort",
        sort
      );
    }

    u.searchParams.set(
      "plaEnabled",
      "true"
    );

    u.searchParams.set(
      "xdEnabled",
      "false"
    );

    u.searchParams.set(
      "isFacet",
      "true"
    );

    if (PINCODE) {
      u.searchParams.set(
        "pincode",
        PINCODE
      );
    }

    return u.href;
  }

  /******************************************************************
   * FETCH
   ******************************************************************/

  async function fetchJSON(
    url,
    kind = "search"
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
          CFG.timeoutMs
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
                accept:
                  "application/json",

                "x-myntraweb":
                  "Yes",

                "x-requested-with":
                  "browser",

                "x-meta-app":
                  "channel=web"
              }
            }
          );

        clearTimeout(timer);

        if (!r.ok) {
          if (
            attempt <
              CFG.retries &&
            (
              r.status === 429 ||
              r.status === 403
            )
          ) {
            continue;
          }

          return {
            ok: false,
            status:
              r.status
          };
        }

        let json;

        try {
          json =
            await r.json();
        } catch {
          return {
            ok: false,
            status:
              r.status,
            parseError:
              true
          };
        }

        return {
          ok: true,
          status:
            r.status,
          json
        };

      } catch (error) {
        clearTimeout(timer);

        if (
          attempt >=
          CFG.retries
        ) {
          return {
            ok: false,
            status: null,
            error:
              String(error)
          };
        }
      }
    }

    return {
      ok: false
    };
  }

  /******************************************************************
   * FIND SEARCH RESULT
   ******************************************************************/

  function findResult(root) {
    const seen =
      new WeakSet();

    function walk(x) {
      if (
        !x ||
        typeof x !== "object"
      ) {
        return null;
      }

      if (seen.has(x))
        return null;

      seen.add(x);

      if (
        Array.isArray(
          x.products
        )
      ) {
        return x;
      }

      for (
        const value
        of Object.values(x)
      ) {
        const found =
          walk(value);

        if (found)
          return found;
      }

      return null;
    }

    return walk(root);
  }

  /******************************************************************
   * CONCURRENCY POOL
   ******************************************************************/

  async function pool(
    jobs,
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
          jobs.length
        ) {
          return;
        }

        await worker(
          jobs[index],
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
              jobs.length
            )
        },
        runner
      )
    );
  }

  /******************************************************************
   * PROBE
   ******************************************************************/

  async function probe(job) {
    const {
      phase,
      stream,
      sort,
      offset
    } = job;

    const started =
      performance.now();

    searchRequests++;

    const response =
      await fetchJSON(
        searchURL(
          offset,
          sort
        )
      );

    if (!response.ok) {
      const row = {
        phase,
        stream,
        offset,

        http:
          response.status,

        organic: 0,
        pla: 0,
        gained: 0,

        unique:
          PRODUCTS.size,

        newIds: [],

        ms:
          Math.round(
            performance.now() -
            started
          )
      };

      SEARCH_LOG.push(row);

      return row;
    }

    const result =
      findResult(
        response.json
      );

    if (!result) {
      const row = {
        phase,
        stream,
        offset,

        http:
          response.status,

        organic: 0,
        pla: 0,
        gained: 0,

        unique:
          PRODUCTS.size,

        newIds: [],

        ms:
          Math.round(
            performance.now() -
            started
          )
      };

      SEARCH_LOG.push(row);

      return row;
    }

    const organic =
      result.products || [];

    const pla =
      CFG.includePLA
        ? result.plaProducts || []
        : [];

    /*
     * Count only IDs contributed by THIS response at merge time.
     * This avoids inflated +N logs when probes finish concurrently.
     */
    const responseIds = new Set(
      [...organic, ...pla]
        .map(idOf)
        .filter(Boolean)
        .map(String)
    );

    const newIds = [...responseIds]
      .filter(id => !PRODUCTS.has(id));

    for (
      const product
      of organic
    ) {
      add(
        product,
        `${stream}:${offset}`,
        "organic"
      );
    }

    for (
      const product
      of pla
    ) {
      add(
        product,
        `${stream}:${offset}:PLA`,
        "pla"
      );
    }

    const stats =
      STREAM_STATS.get(
        stream
      ) || {
        stream,
        requests: 0,
        newIds:
          new Set()
      };

    stats.requests++;

    for (
      const id
      of newIds
    ) {
      stats.newIds.add(id);
    }

    STREAM_STATS.set(
      stream,
      stats
    );

    const row = {
      phase,
      stream,
      offset,

      http:
        response.status,

      organic:
        organic.length,

      pla:
        pla.length,

      gained:
        newIds.length,

      unique:
        PRODUCTS.size,

      newIds,

      responseTotal:
        num(
          result.totalCount
        ),

      hasNext:
        result.hasNextPage ??
        null,

      ms:
        Math.round(
          performance.now() -
          started
        )
    };

    SEARCH_LOG.push(row);

    return row;
  }

  /******************************************************************
   * PRIMARY DISCOVERY
   ******************************************************************/

  console.log(
    "=============================================="
  );

  console.log(
    "🚀 MYNTRA GOLD MASTER V7 FINAL"
  );

  console.log(
    "Reported:",
    reportedTotal
  );

  console.log(
    "Bootstrap:",
    PRODUCTS.size
  );

  console.log(
    "=============================================="
  );

  const primaryJobs = [];

  for (
    const [stream, sort]
    of CFG.streams
  ) {
    for (
      const offset
      of CFG.primaryOffsets
    ) {
      primaryJobs.push({
        phase:
          "primary",

        stream,
        sort,
        offset
      });
    }
  }

  console.log(
    `⚡ PRIMARY: ${primaryJobs.length} probes`
  );

  await pool(
    primaryJobs,
    CFG.searchConcurrency,

    async job => {
      const r =
        await probe(job);

      if (
        r.gained > 0
      ) {
        console.log(
          `📦 ${job.stream}` +
          ` o=${job.offset}` +
          ` +${r.gained}` +
          ` => ${PRODUCTS.size}`
        );
      }
    }
  );

  const afterPrimary =
    PRODUCTS.size;

  /******************************************************************
   * TARGETED RESCUE
   *
   * Instead of 180 unconditional requests, run rescue streams in
   * rounds and stop when a full stream adds nothing useful.
   ******************************************************************/

  let rescueAdded = 0;

  if (!(
    CFG.stopAtReportedIfReached &&
    reportedTotal != null &&
    PRODUCTS.size >= reportedTotal
  )) {
    const beforeRescue = PRODUCTS.size;
    const rescueJobs = [];

    for (const [stream, sort] of CFG.rescueStreams) {
      for (const offset of CFG.rescueOffsets) {
        rescueJobs.push({ phase: "rescue", stream, sort, offset });
      }
    }

    console.log(`🛟 PARALLEL RESCUE: ${rescueJobs.length} probes`);

    await pool(
      rescueJobs,
      CFG.searchConcurrency,
      async job => {
        const r = await probe(job);
        if (r.gained > 0) {
          console.log(`🆕 ${job.stream} o=${job.offset} +${r.gained} => ${PRODUCTS.size}`);
        }
      }
    );

    rescueAdded = PRODUCTS.size - beforeRescue;
  }

  /******************************************************************
   * REPARSE FINAL TITLES
   *
   * This is intentionally done AFTER discovery so every product has
   * its strongest/longest listing name before API enrichment.
   ******************************************************************/

  for (
    const [key, p]
    of PRODUCTS
  ) {
    const text =
      clean([
        p.name,
        p.brand,
        p.link
      ].join(" "));

    /*
     * Material.
     */
    const metal =
      detectMetal(text);

    if (
      metal === "non-gold"
    ) {
      p.explicitNonGold = true;
      p.metal = "non-gold";
      p.karat = null;
      p.fineness = null;

    } else if (
      !p.explicitNonGold &&
      metal
    ) {
      p.metal =
        metal;
    }

    /*
     * Purity.
     */
    if (
      !p.explicitNonGold
    ) {
      const purity =
        parsePurity(text);

      if (
        purity.karat != null &&
        (
          p.karat == null ||
          purityRank(
            purity.source
          ) >
          purityRank(
            p.puritySource
          )
        )
      ) {
        p.karat =
          purity.karat;
      }

      if (
        purity.fineness &&
        (
          !p.fineness ||
          purityRank(
            purity.source
          ) >
          purityRank(
            p.puritySource
          )
        )
      ) {
        p.fineness =
          purity.fineness;

        p.puritySource =
          purity.source;
      }

      if (
        p.karat === 22 &&
        !p.fineness
      ) {
        p.fineness =
          "916";

        p.puritySource =
          "karat-standard";
      }
    }

    /*
     * IMPORTANT:
     *
     * Always reparse title and replace old title-derived weight.
     * API hasn't run yet, so this establishes the locked listing
     * evidence.
     */
    const w =
      parseWeight(
        p.name
      );

    if (
      w.total != null ||
      w.ambiguous ||
      (
        p.totalWeightGrams ==
          null &&
        w.confidence >
          0
      )
    ) {
      copyWeight(
        p,
        w
      );
    }

    p.titleWeightLocked =
      (
        p.totalWeightGrams !=
          null &&
        p.weightConfidence >=
          90
      );

    PRODUCTS.set(
      key,
      p
    );
  }

  /******************************************************************
   * API TARGET SELECTION
   ******************************************************************/

  function needsAPI(p) {
    if (
      p.explicitNonGold ||
      p.metal !== "gold"
    ) {
      return false;
    }

    if (
      p.karat == null
    ) {
      return true;
    }

    if (
      p.totalWeightGrams ==
      null
    ) {
      return true;
    }

    if (
      p.weightAmbiguous
    ) {
      return true;
    }

    if (
      p.weightConflict
    ) {
      return true;
    }

    if (
      CFG.enrich24KFineness &&
      p.karat === 24 &&
      !p.fineness
    ) {
      return true;
    }

    return false;
  }

  const apiTargets =
    [...PRODUCTS.values()]
      .filter(
        needsAPI
      );

  console.log(
    `🔬 API TARGETS: ${apiTargets.length}`
  );

  /******************************************************************
   * FLATTEN API ATTRIBUTES
   ******************************************************************/

  function flattenAttributes(style) {
    const out = [];

    const attrs =
      style
        ?.articleAttributes ||
      {};

    for (
      const [key, value]
      of Object.entries(attrs)
    ) {
      out.push({
        key:
          clean(key),

        value:
          clean(value),

        source:
          "articleAttributes"
      });
    }

    for (
      const group
      of style
        ?.productContentGroupEntries ||
      []
    ) {
      for (
        const a
        of group?.attributes ||
        []
      ) {
        out.push({
          key:
            clean(
              a?.name
            ),

          value:
            clean(
              a?.value
            ),

          source:
            "contentAttributes"
        });
      }
    }

    return out;
  }

  /******************************************************************
   * API STRUCTURED WEIGHT
   ******************************************************************/

  function structuredWeight(attrs) {
    const result =
      emptyWeight();

    /*
     * Strongest: explicit total/net/gold weight.
     */
    const totalRows =
      attrs.filter(
        x =>
          /\b(?:total|net|gross|gold)\b.*\bweight\b/i
            .test(x.key)
      );

    for (
      const row
      of totalRows
    ) {
      const parsed =
        parseWeight(
          `total weight ${row.value}`
        );

      if (
        parsed.total != null
      ) {
        parsed.source =
          "API-structured-total";

        parsed.confidence =
          102;

        return parsed;
      }
    }

    /*
     * Quantity.
     */
    const quantityRows =
      attrs.filter(
        x =>
          /\b(?:quantity|number\s+of\s+(?:pieces|items)|pack\s+size|set\s+size)\b/i
            .test(x.key)
      );

    let quantity = null;

    for (
      const row
      of quantityRows
    ) {
      const n =
        num(row.value);

      if (
        n != null &&
        n >= 1 &&
        n <= 100
      ) {
        quantity = n;
        break;
      }
    }

    /*
     * Unit/each weight.
     */
    const unitRows =
      attrs.filter(
        x =>
          /\b(?:unit\s+weight|weight\s+each|each\s+weight|per\s+piece\s+weight)\b/i
            .test(x.key)
      );

    for (
      const row
      of unitRows
    ) {
      const parsed =
        parseWeight(
          row.value
        );

      const unit =
        parsed.total ??
        parsed.unit;

      if (
        quantity &&
        unit != null
      ) {
        return weightResult({
          total:
            unit * quantity,

          unit,
          quantity,

          components:
            Array(quantity)
              .fill(unit),

          source:
            "API-unit-x-quantity",

          confidence:
            101
        });
      }
    }

    /*
     * Generic "Weight" row.
     *
     * It is NOT assumed to be total if listing explicitly indicates
     * a set/pack.
     */
    const genericWeightRows =
      attrs.filter(
        x =>
          /^weight$/i.test(
            x.key
          )
      );

    for (
      const row
      of genericWeightRows
    ) {
      const parsed =
        parseWeight(
          row.value
        );

      if (
        parsed.total != null
      ) {
        parsed.source =
          "API-structured-weight";

        parsed.confidence =
          82;

        return parsed;
      }
    }

    return result;
  }

  /******************************************************************
   * API PARSER
   ******************************************************************/

  function parseAPI(
    json,
    existing
  ) {
    const style =
      json?.style;

    if (!style)
      return existing;

    const out =
      existing;

    const attrs =
      flattenAttributes(
        style
      );

    /**************************************************************
     * MATERIAL
     **************************************************************/

    const materialRows =
      attrs.filter(
        x =>
          /\b(?:material|metal)\b/i
            .test(x.key)
      );

    for (
      const row
      of materialRows
    ) {
      const material =
        detectMetal(
          row.value
        );

      /*
       * Listing explicit non-gold remains absolute.
       */
      if (
        out.explicitNonGold
      ) {
        out.metal =
          "non-gold";

        break;
      }

      if (
        material ===
        "non-gold"
      ) {
        /*
         * Structured API material can establish non-gold only if
         * listing didn't explicitly establish gold.
         */
        if (
          !GOLD_RE.test(
            out.name || ""
          )
        ) {
          out.metal =
            "non-gold";

          out.explicitNonGold =
            true;

          out.karat = null;
          out.fineness = null;
        }

      } else if (
        material &&
        !out.metal
      ) {
        out.metal =
          material;
      }
    }

    if (
      out.explicitNonGold
    ) {
      out.apiLoaded = true;
      return out;
    }

    /**************************************************************
     * PURITY
     **************************************************************/

    const purityRows =
      attrs.filter(
        x =>
          /\b(?:purity|karat|carat|fineness)\b/i
            .test(x.key)
      );

    let bestApiPurity = null;

    for (
      const row
      of purityRows
    ) {
      const parsed =
        parsePurity(
          `${row.key} ${row.value}`
        );

      if (
        parsed.karat != null ||
        parsed.fineness
      ) {
        bestApiPurity =
          parsed;

        break;
      }
    }

    if (bestApiPurity) {
      if (
        out.karat != null &&
        bestApiPurity.karat !=
          null &&
        out.karat !==
          bestApiPurity.karat
      ) {
        out.purityConflict =
          true;

        out.purityConflictValues =
          `${out.karat}K listing | ${bestApiPurity.karat}K API`;
      }

      /*
       * API only fills missing purity.
       */
      if (
        out.karat == null
      ) {
        out.karat =
          bestApiPurity.karat;
      }

      if (
        !out.fineness &&
        bestApiPurity.fineness
      ) {
        out.fineness =
          bestApiPurity.fineness;

        out.puritySource =
          "API-structured";
      }
    }

    if (
      out.karat === 22 &&
      !out.fineness
    ) {
      out.fineness =
        "916";

      out.puritySource =
        "karat-standard";
    }

    /**************************************************************
     * STRUCTURED WEIGHT
     **************************************************************/

    const sw =
      structuredWeight(
        attrs
      );

    /*
     * CRITICAL RULE:
     *
     * A strong title total is locked.
     *
     * API may verify it, but cannot downgrade/replace it.
     */
    if (
      sw.total != null
    ) {
      if (
        out.totalWeightGrams ==
        null
      ) {
        copyWeight(
          out,
          sw
        );

      } else if (
        out.titleWeightLocked
      ) {
        /*
         * If API agrees, record verification.
         */
        if (
          nearly(
            out.totalWeightGrams,
            sw.total
          )
        ) {
          out.weightVerifiedByAPI =
            true;

        } else {
          /*
           * Do not overwrite title.
           */
          out.apiWeightConflict =
            true;

          out.apiWeightValue =
            sw.total;
        }

      } else if (
        sw.confidence >
        (out.weightConfidence || 0)
      ) {
        copyWeight(
          out,
          sw
        );
      }
    }

    /**************************************************************
     * DESCRIPTION
     **************************************************************/

    const description =
      [
        ...(style.productDetails || [])
          .map(
            x =>
              x?.description
          ),

        ...(style.descriptors || [])
          .map(
            x =>
              x?.description
          )
      ]
        .filter(Boolean)
        .map(clean)
        .join(" ");

    /*
     * Description is fallback only.
     *
     * It cannot overwrite any locked title weight.
     */
    if (
      description &&
      (
        out.totalWeightGrams ==
          null ||
        out.weightAmbiguous
      )
    ) {
      const dw =
        parseWeight(
          description
        );

      dw.confidence =
        Math.min(
          dw.confidence,
          55
        );

      if (
        dw.total != null
      ) {
        if (
          out.totalWeightGrams ==
            null &&
          !out.titleWeightLocked
        ) {
          dw.source =
            "API-description";

          copyWeight(
            out,
            dw
          );

        } else if (
          out.weightAmbiguous
        ) {
          /*
           * Resolve set/pack ambiguity only if description total
           * matches one of the two plausible interpretations.
           */

          const stated =
            out.statedWeightGrams;

          const possible =
            out.possibleTotalWeightGrams;

          if (
            stated != null &&
            nearly(
              dw.total,
              stated
            )
          ) {
            out.totalWeightGrams =
              stated;

            out.weightAmbiguous =
              false;

            out.weightSource =
              "API-resolved-set-total";

            out.weightConfidence =
              88;

          } else if (
            possible != null &&
            nearly(
              dw.total,
              possible
            )
          ) {
            out.totalWeightGrams =
              possible;

            out.weightAmbiguous =
              false;

            out.weightSource =
              "API-resolved-set-unit";

            out.weightConfidence =
              88;
          }
        }
      }
    }

    out.apiLoaded = true;

    return out;
  }

  /******************************************************************
   * API ENRICHMENT
   ******************************************************************/

  await pool(
    apiTargets,
    CFG.apiConcurrency,

    async product => {
      apiRequests++;

      const response =
        await fetchJSON(
          `/gateway/v2/product/${product.id}`,
          "product"
        );

      if (!response.ok) {
        API_LOG.push({
          id:
            product.id,

          http:
            response.status,

          success: false
        });

        return;
      }

      const key =
        String(
          product.id
        );

      const updated =
        parseAPI(
          response.json,
          PRODUCTS.get(key)
        );

      PRODUCTS.set(
        key,
        updated
      );

      API_LOG.push({
        id:
          product.id,

        http:
          response.status,

        success: true,

        karat:
          updated.karat,

        fineness:
          updated.fineness,

        weight:
          updated.totalWeightGrams,

        weightSource:
          updated.weightSource,

        purityConflict:
          updated.purityConflict,

        apiWeightConflict:
          updated.apiWeightConflict ||
          false
      });
    }
  );

  /******************************************************************
   * FINAL REPAIR PASS
   *
   * This pass uses the title ONE FINAL TIME after API.
   *
   * Any strong title evidence wins over accidental API fallback.
   ******************************************************************/

  for (
    const [key, p]
    of PRODUCTS
  ) {
    const titleWeight =
      parseWeight(
        p.name
      );

    /*
     * Strong title evidence always wins.
     */
    if (
      titleWeight.total != null &&
      titleWeight.confidence >= 90
    ) {
      if (
        p.totalWeightGrams ==
          null ||
        p.weightSource ===
          "API-description" ||
        titleWeight.confidence >
          (p.weightConfidence || 0) ||
        p.titleWeightLocked
      ) {
        copyWeight(
          p,
          titleWeight
        );

        p.titleWeightLocked =
          true;
      }
    }

    /*
     * Keep set ambiguity if API did not actually resolve it.
     */
    if (
      titleWeight.ambiguous &&
      p.totalWeightGrams == null
    ) {
      copyWeight(
        p,
        titleWeight
      );
    }

    PRODUCTS.set(
      key,
      p
    );
  }

  /******************************************************************
   * FINAL VALIDATION
   ******************************************************************/

  const FINAL =
    [...PRODUCTS.values()];

  for (
    const p
    of FINAL
  ) {
    /**************************************************************
     * MATERIAL
     **************************************************************/

    if (
      p.explicitNonGold ||
      p.metal === "non-gold"
    ) {
      p.metal =
        "non-gold";

      p.karat = null;
      p.fineness = null;
    }

    /**************************************************************
     * PURITY
     **************************************************************/

    if (
      p.metal === "gold"
    ) {
      if (
        p.karat == null &&
        p.fineness
      ) {
        p.karat =
          karatFromFineness(
            p.fineness
          );
      }

      if (
        p.karat === 22 &&
        !p.fineness
      ) {
        p.fineness =
          "916";

        p.puritySource =
          "karat-standard";
      }
    }

    /**************************************************************
     * DISPLAY WEIGHT
     **************************************************************/

    p.weight =
      p.totalWeightGrams !=
        null
        ? `${+Number(
            p.totalWeightGrams
          ).toFixed(4)} g`
        : null;

    /**************************************************************
     * SOURCES
     **************************************************************/

    if (
      p.discoverySources
        instanceof Set
    ) {
      p.discoverySources =
        [...p.discoverySources]
          .join(" | ");
    }

    /**************************************************************
     * ISSUES
     **************************************************************/

    const issues = [];

    if (
      p.metal === "gold"
    ) {
      if (
        p.karat == null
      ) {
        issues.push(
          "missing-karat"
        );
      }

      if (
        p.totalWeightGrams ==
        null
      ) {
        issues.push(
          "missing-weight"
        );
      }

      if (
        p.price == null
      ) {
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
    }

    if (
      p.weightAmbiguous
    ) {
      issues.push(
        "ambiguous-weight"
      );
    }

    if (
      p.weightConflict
    ) {
      issues.push(
        "weight-conflict"
      );
    }

    if (
      p.purityConflict
    ) {
      issues.push(
        "purity-conflict"
      );
    }

    if (
      p.apiWeightConflict
    ) {
      issues.push(
        "api-weight-disagrees"
      );
    }

    if (
      p.totalWeightGrams !=
        null &&
      (
        p.totalWeightGrams <= 0 ||
        p.totalWeightGrams > 100
      )
    ) {
      issues.push(
        "suspicious-weight"
      );
    }

    p.issues =
      uniq(issues);

    p.incomplete =
      p.issues.some(
        issue =>
          [
            "missing-karat",
            "missing-weight",
            "missing-price"
          ].includes(
            issue
          )
      );

    /*
     * 24K exact fineness absence is informational, not required
     * incompleteness.
     */
    p.needsReview =
      p.issues.some(
        issue =>
          [
            "ambiguous-weight",
            "weight-conflict",
            "purity-conflict",
            "api-weight-disagrees",
            "suspicious-weight"
          ].includes(
            issue
          )
      );
  }

  /******************************************************************
   * SORT
   ******************************************************************/

  FINAL.sort(
    (a, b) =>
      (
        a.totalWeightGrams ??
        Infinity
      ) -
      (
        b.totalWeightGrams ??
        Infinity
      ) ||
      (
        a.price ??
        Infinity
      ) -
      (
        b.price ??
        Infinity
      )
  );

  /******************************************************************
   * GROUPS
   ******************************************************************/

  const GOLD =
    FINAL.filter(
      p =>
        p.metal ===
        "gold"
    );

  const NON_GOLD =
    FINAL.filter(
      p =>
        p.metal ===
        "non-gold"
    );

  const INCOMPLETE =
    GOLD.filter(
      p =>
        p.incomplete
    );

  const REVIEW =
    GOLD.filter(
      p =>
        p.needsReview
    );

  const AMBIGUOUS =
    GOLD.filter(
      p =>
        p.weightAmbiguous
    );

  const WEIGHT_CONFLICTS =
    GOLD.filter(
      p =>
        p.weightConflict ||
        p.apiWeightConflict
    );

  const PURITY_CONFLICTS =
    GOLD.filter(
      p =>
        p.purityConflict
    );

  const MISSING_FINENESS =
    GOLD.filter(
      p =>
        !p.fineness
    );

  /******************************************************************
   * DISCOVERY ANALYSIS
   ******************************************************************/

  const productive =
    SEARCH_LOG.filter(
      x =>
        x.gained > 0
    );

  const maxProductiveOffset =
    productive.length
      ? Math.max(
          ...productive.map(
            x =>
              x.offset
          )
        )
      : 0;

  const rescueProductive =
    SEARCH_LOG.filter(
      x =>
        x.phase ===
          "rescue" &&
        x.gained > 0
    );

  /*
   * Do not claim absolute exhaustion unless:
   *
   * - rescue found nothing, OR
   * - actual union reached/exceeded reported total.
   */
  const catalogueExhausted =
    (
      rescueProductive.length ===
        0 ||
      (
        reportedTotal != null &&
        FINAL.length >=
          reportedTotal
      )
    );

  /******************************************************************
   * TABLE ROWS
   ******************************************************************/

  function rows(array) {
    return array.map(
      p => ({
        id:
          p.id,

        brand:
          p.brand,

        name:
          p.name,

        metal:
          p.metal,

        karat:
          p.karat,

        fineness:
          p.fineness,

        puritySource:
          p.puritySource,

        weight:
          p.weight,

        unitWeightGrams:
          p.unitWeightGrams,

        quantity:
          p.quantity,

        totalWeightGrams:
          p.totalWeightGrams,

        statedWeightGrams:
          p.statedWeightGrams,

        possibleTotalWeightGrams:
          p.possibleTotalWeightGrams,

        calculatedWeightGrams:
          p.calculatedWeightGrams,

        components:
          Array.isArray(
            p.componentWeightsGrams
          )
            ? p
                .componentWeightsGrams
                .join(" + ")
            : null,

        weightSource:
          p.weightSource,

        weightConfidence:
          p.weightConfidence,

        price:
          p.price,

        mrp:
          p.mrp,

        discountAmount:
          p.discountAmount,

        rating:
          p.rating,

        ratingCount:
          p.ratingCount,

        sellerId:
          p.sellerId,

        titleWeightLocked:
          Boolean(
            p.titleWeightLocked
          ),

        weightVerifiedByAPI:
          Boolean(
            p.weightVerifiedByAPI
          ),

        apiWeightConflict:
          Boolean(
            p.apiWeightConflict
          ),

        purityConflict:
          Boolean(
            p.purityConflict
          ),

        weightConflict:
          Boolean(
            p.weightConflict
          ),

        ambiguousWeight:
          Boolean(
            p.weightAmbiguous
          ),

        issues:
          p.issues
            .join(" | "),

        discoverySources:
          p.discoverySources,

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

  const streamStats =
    [...STREAM_STATS.values()]
      .map(
        x => ({
          stream:
            x.stream,

          requests:
            x.requests,

          uniqueContributed:
            x.newIds.size
        })
      );

  const stats = {
    reportedTotal,

    discoveredTotal:
      FINAL.length,

    differenceVsReported:
      reportedTotal != null
        ? FINAL.length -
          reportedTotal
        : null,

    gold:
      GOLD.length,

    nonGold:
      NON_GOLD.length,

    organic:
      ORGANIC.size,

    pla:
      PLA.size,

    catalogueExhausted,

    /*
     * Required fields.
     */
    incomplete:
      INCOMPLETE.length,

    missingKarat:
      GOLD.filter(
        p =>
          p.karat == null
      ).length,

    missingWeight:
      GOLD.filter(
        p =>
          p.totalWeightGrams ==
          null
      ).length,

    missingPrice:
      GOLD.filter(
        p =>
          p.price == null
      ).length,

    /*
     * Informational.
     */
    missingFineness:
      MISSING_FINENESS.length,

    /*
     * Quality.
     */
    ambiguousWeight:
      AMBIGUOUS.length,

    weightConflicts:
      WEIGHT_CONFLICTS.length,

    purityConflicts:
      PURITY_CONFLICTS.length,

    needsReview:
      REVIEW.length,

    /*
     * Discovery.
     */
    afterPrimary,

    rescueAdded,

    maxProductiveOffset,

    searchRequests,

    /*
     * API.
     */
    apiTargets:
      apiTargets.length,

    apiRequests,

    apiSuccess:
      API_LOG.filter(
        x =>
          x.success
      ).length,

    api401:
      API_LOG.filter(
        x =>
          x.http === 401
      ).length,

    /*
     * Performance.
     */
    elapsedMs,

    elapsedSeconds:
      +(
        elapsedMs /
        1000
      ).toFixed(2)
  };

  /******************************************************************
   * GLOBALS
   ******************************************************************/

  window.myntraStats =
    stats;

  window.myntraProducts =
    FINAL;

  window.myntraGold =
    GOLD;

  window.myntraNonGold =
    NON_GOLD;

  window.myntraIncomplete =
    INCOMPLETE;

  window.myntraReview =
    REVIEW;

  window.myntraAmbiguousWeight =
    AMBIGUOUS;

  window.myntraWeightConflicts =
    WEIGHT_CONFLICTS;

  window.myntraPurityConflicts =
    PURITY_CONFLICTS;

  window.myntraMissingFineness =
    MISSING_FINENESS;

  window.myntraSearchLog =
    SEARCH_LOG;

  window.myntraApiLog =
    API_LOG;

  window.myntraStreamStats =
    streamStats;

  /******************************************************************
   * BUILT-IN WEIGHT PARSER SELF-TESTS
   ******************************************************************/

  const WEIGHT_SELF_TESTS = [
    [
      "25g each-x-pcs",
      "Muthoot Pappachan 24K 999 Purity 25g Lakshmi Gold Coin Pendant- 25 Gm (5gm each x 5 Pcs)",
      { total: 25, unit: 5, quantity: 5 }
    ],
    [
      "4g components",
      "Muthoot Pappachan Gold Coin 24K 4gm (2gm + 2gm)",
      { total: 4, unit: 2, quantity: 2 }
    ],
    [
      "3pcs each",
      "Muthoot Pappachan 3Pcs 24K 999 Gold Oval Lakshmi Pendant 5gm each",
      { total: 15, unit: 5, quantity: 3 }
    ],
    [
      "8gm single",
      "Kalyan Jewellers Women 24KT 999 Purity Om Gold Coin 8gm",
      { total: 8, unit: 8, quantity: 1 }
    ]
    ,
    [
      "set stated total",
      "C KRISHNIAH CHETTY JEWELLERS PVT LTD Set Of 5 24Kt Gold Coin-0.5g",
      { total: 0.5, unit: null, quantity: 5, ambiguous: false }
    ]
  ].map(([test, text, expected]) => {
    const got = parseWeight(text);
    const pass =
      (
        expected.total == null
          ? got.total == null
          : nearly(got.total, expected.total)
      ) &&
      (
        expected.unit == null
          ? got.unit == null
          : nearly(got.unit, expected.unit)
      ) &&
      got.quantity === expected.quantity &&
      (
        expected.ambiguous == null ||
        got.ambiguous === expected.ambiguous
      );

    return {
      test,
      pass,
      total: got.total,
      unit: got.unit,
      quantity: got.quantity,
      source: got.source,
      expected: `${expected.total ?? "?"}g / ${expected.unit ?? "?"}g x ${expected.quantity}${expected.ambiguous ? " (ambiguous)" : ""}`
    };
  });

  window.myntraWeightSelfTests = WEIGHT_SELF_TESTS;

  if (WEIGHT_SELF_TESTS.some(x => !x.pass)) {
    console.error("❌ WEIGHT PARSER SELF-TEST FAILED");
    console.table(WEIGHT_SELF_TESTS);
  } else {
    console.log("✅ WEIGHT PARSER SELF-TESTS PASSED");
    console.table(WEIGHT_SELF_TESTS);
  }

  /******************************************************************
   * TEST WEIGHT PARSER
   *
   * Useful for checking exact strings without rerunning scraper.
   ******************************************************************/

  window.myntraParseWeight =
    text => {
      const result =
        parseWeight(text);

      console.log(
        text
      );

      console.log(
        result
      );

      console.table(
        result.evidence
      );

      return result;
    };

  /******************************************************************
   * TABLE COMMANDS
   ******************************************************************/

  window.myntraTable =
    () => {
      console.table(
        rows(FINAL)
      );

      return FINAL;
    };

  window.myntraIncompleteTable =
    () => {
      console.table(
        rows(INCOMPLETE)
      );

      return INCOMPLETE;
    };

  window.myntraReviewTable =
    () => {
      console.table(
        rows(REVIEW)
      );

      return REVIEW;
    };

  window.myntraWeightReviewTable =
    () => {
      console.table(
        rows(
          uniqById([
            ...AMBIGUOUS,
            ...WEIGHT_CONFLICTS
          ])
        )
      );
    };

  window.myntraConflictTable =
    () => {
      const data =
        uniqById([
          ...WEIGHT_CONFLICTS,
          ...PURITY_CONFLICTS
        ]);

      console.table(
        rows(data)
      );

      return data;
    };

  window.myntraNonGoldTable =
    () => {
      console.table(
        rows(NON_GOLD)
      );

      return NON_GOLD;
    };

  window.myntraFinenessTable =
    () => {
      console.table(
        rows(
          MISSING_FINENESS
        )
      );

      return MISSING_FINENESS;
    };

  window.myntraSearchTable =
    () => {
      console.table(
        SEARCH_LOG.map(
          x => ({
            phase:
              x.phase,

            stream:
              x.stream,

            offset:
              x.offset,

            http:
              x.http,

            organic:
              x.organic,

            pla:
              x.pla,

            gained:
              x.gained,

            unique:
              x.unique,

            responseTotal:
              x.responseTotal,

            hasNext:
              x.hasNext,

            ms:
              x.ms
          })
        )
      );

      return SEARCH_LOG;
    };

  window.myntraApiTable =
    () => {
      console.table(
        API_LOG
      );

      return API_LOG;
    };

  window.myntraStreamTable =
    () => {
      console.table(
        streamStats
      );

      return streamStats;
    };

  const bridgeResult = await fetch("http://localhost:8788/api/browser-bridge/products", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      store: "myntra.com",
      records: GOLD.map(p => ({
        bridgeSnapshot: true,
        productId: p.id,
        landingPageUrl: p.link,
        productName: p.name,
        brand: p.brand,
        price: p.price,
        metal: p.metal,
        grams: p.totalWeightGrams,
        karat: p.karat,
        purity: p.fineness
      }))
    })
  }).then(r => r.json()).catch(error => ({ error: String(error) }));
  console.log("Aurum Myntra bridge:", bridgeResult);

  function uniqById(array) {
    return [
      ...new Map(
        array.map(
          p => [
            String(p.id),
            p
          ]
        )
      ).values()
    ];
  }

  /******************************************************************
   * DIAGNOSTIC
   ******************************************************************/

  window.myntraDiagnostic =
    () => {
      console.log(
        "================================"
      );

      console.log(
        "MYNTRA DIAGNOSTIC"
      );

      console.log(
        "================================"
      );

      console.log(
        stats
      );

      console.log(
        "\nSTREAMS"
      );

      console.table(
        streamStats
      );

      console.log(
        "\nINCOMPLETE"
      );

      console.table(
        rows(INCOMPLETE)
      );

      console.log(
        "\nREVIEW"
      );

      console.table(
        rows(REVIEW)
      );

      console.log(
        "\nNON-GOLD"
      );

      console.table(
        rows(NON_GOLD)
      );

      /*
       * Weight distribution.
       */
      const distribution =
        new Map();

      for (
        const p
        of GOLD
      ) {
        const key =
          p.totalWeightGrams ==
            null
            ? "MISSING"
            : String(
                p.totalWeightGrams
              );

        distribution.set(
          key,
          (
            distribution.get(
              key
            ) || 0
          ) + 1
        );
      }

      console.log(
        "\nWEIGHT DISTRIBUTION"
      );

      console.table(
        [...distribution]
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
              )
                return 1;

              if (
                b.weight ===
                "MISSING"
              )
                return -1;

              return (
                Number(a.weight) -
                Number(b.weight)
              );
            }
          )
      );

      return {
        stats,
        incomplete:
          INCOMPLETE,
        review:
          REVIEW,
        nonGold:
          NON_GOLD
      };
    };

  /******************************************************************
   * CSV
   ******************************************************************/

  const CSV_FIELDS = [
    "id",
    "brand",
    "name",

    "metal",

    "karat",
    "fineness",
    "puritySource",

    "weight",

    "unitWeightGrams",
    "quantity",
    "totalWeightGrams",

    "statedWeightGrams",
    "possibleTotalWeightGrams",
    "calculatedWeightGrams",

    "components",

    "weightSource",
    "weightConfidence",

    "price",
    "mrp",
    "discountAmount",

    "rating",
    "ratingCount",

    "sellerId",

    "titleWeightLocked",
    "weightVerifiedByAPI",

    "apiWeightConflict",
    "purityConflict",
    "weightConflict",
    "ambiguousWeight",

    "issues",

    "discoverySources",

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
      rows(FINAL);

    return [
      CSV_FIELDS
        .map(
          csvEscape
        )
        .join(","),

      ...data.map(
        row =>
          CSV_FIELDS
            .map(
              key =>
                csvEscape(
                  row[key]
                )
            )
            .join(",")
      )
    ].join("\n");
  }

  /******************************************************************
   * DOWNLOAD
   ******************************************************************/

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

    a.href =
      url;

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

  window.myntraDownloadCSV =
    () =>
      download(
        "\uFEFF" +
        makeCSV(),

        `myntra-gold-${FINAL.length}.csv`,

        "text/csv;charset=utf-8"
      );

  window.myntraDownloadJSON =
    () =>
      download(
        JSON.stringify(
          FINAL,
          null,
          2
        ),

        `myntra-gold-${FINAL.length}.json`,

        "application/json;charset=utf-8"
      );

  window.myntraCopyCSV =
    async () => {
      await navigator
        .clipboard
        .writeText(
          makeCSV()
        );

      console.log(
        "📋 CSV copied"
      );
    };

  window.myntraCopyJSON =
    async () => {
      await navigator
        .clipboard
        .writeText(
          JSON.stringify(
            FINAL,
            null,
            2
          )
        );

      console.log(
        "📋 JSON copied"
      );
    };

  /******************************************************************
   * FINAL OUTPUT
   ******************************************************************/

  console.log("");
  console.log(
    "================================================"
  );

  console.log(
    "✅ MYNTRA GOLD MASTER V7 FINAL COMPLETE"
  );

  console.log(
    "================================================"
  );

  console.log(
    stats
  );

  console.log("");

  console.log(
    `FULL TABLE READY: ${FINAL.length} (run myntraTable() only if needed)`
  );

  console.log("");

  if (
    INCOMPLETE.length === 0
  ) {
    console.log(
      "🎯 ALL REQUIRED GOLD FIELDS FILLED"
    );
  } else {
    console.warn(
      `⚠️ REQUIRED INCOMPLETE: ${INCOMPLETE.length}`
    );
  }

  if (
    REVIEW.length === 0
  ) {
    console.log(
      "✅ NO WEIGHT/PURITY REVIEW ITEMS"
    );
  } else {
    console.warn(
      `⚠️ REVIEW: ${REVIEW.length}`
    );
  }

  if (
    NON_GOLD.length
  ) {
    console.log(
      `🚫 NON-GOLD EXCLUDED: ${NON_GOLD.length}`
    );
  }

  console.log("");

  console.log(
    `⏱️ ${stats.elapsedSeconds}s`
  );

  console.log("");

  console.log(
    "COMMANDS:"
  );

  console.log(
    "window.myntraStats"
  );

  console.log(
    "window.myntraProducts"
  );

  console.log("");

  console.log(
    "myntraTable()"
  );

  console.log(
    "myntraIncompleteTable()"
  );

  console.log(
    "myntraReviewTable()"
  );

  console.log(
    "myntraWeightReviewTable()"
  );

  console.log(
    "myntraConflictTable()"
  );

  console.log(
    "myntraNonGoldTable()"
  );

  console.log(
    "myntraFinenessTable()"
  );

  console.log("");

  console.log(
    "myntraStreamTable()"
  );

  console.log(
    "myntraSearchTable()"
  );

  console.log(
    "myntraApiTable()"
  );

  console.log(
    "myntraDiagnostic()"
  );

  console.log("");

  console.log(
    'myntraParseWeight("Muthoot Pappachan 24K 999 Purity 25g Lakshmi Gold Coin Pendant- 25 Gm (5gm each x 5 Pcs)")'
  );

  console.log("");

  console.log(
    "myntraDownloadCSV()"
  );

  console.log(
    "myntraDownloadJSON()"
  );

  console.log(
    "myntraCopyCSV()"
  );

  console.log(
    "myntraCopyJSON()"
  );

  console.log(
    "================================================"
  );

  return {
    stats,
    products:
      FINAL,
    gold:
      GOLD,
    incomplete:
      INCOMPLETE,
    review:
      REVIEW
  };
})();