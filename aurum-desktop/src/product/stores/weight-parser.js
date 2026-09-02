const unitToGrams = { mg: 0.001, g: 1, gm: 1, gms: 1, gram: 1, grams: 1 };

export function tokenToGrams(value, unit) {
  const token = String(value || '').trim();
  if (!token) return 0;
  const num = (!token.includes('.') && /^0\d+$/.test(token)) ? Number(`0.${token}`) : Number(token);
  const factor = unitToGrams[String(unit || '').toLowerCase()] || 1;
  return Number.isFinite(num) ? num * factor : 0;
}

export function parseWeightValue(value) {
  const match = String(value || '').match(/(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/i);
  if (!match) return null;
  return tokenToGrams(match[1], match[2]);
}

export function isNonGoldProductText(text = '') {
  const str = String(text || '').toLowerCase();
  // 1. Explicit silver keywords
  if (/\b(silver\s*coin|silver\s*bar|silver\s*pendant|fine\s*silver|sterling\s*silver|999\s*silver|999\.9\s*silver|silver\s*999|9999\s*silver|chandi|silver\s*biscuit|silver\s*round)\b/i.test(str)) {
    if (!/\bgold\s*coin\b|\bgold\s*bar\b|\b24\s*k\s*gold\b|\b22\s*k\s*gold\b/i.test(str)) return true;
  }
  // 2. Explicit platinum keywords
  if (/\b(platinum\s*coin|platinum\s*bar|platinum\s*pendant|pt\s*950|pt950|pt\s*999|pt999|950\s*platinum|999\s*platinum)\b/i.test(str)) {
    if (!/\bgold\s*coin\b|\bgold\s*bar\b|\b24\s*k\s*gold\b|\b22\s*k\s*gold\b/i.test(str)) return true;
  }
  // 3. Spec metal type
  if (/\bmetal\s*(?:type)?\s*:\s*(?:silver|platinum|brass|copper|steel)\b/i.test(str)) return true;
  // 4. If title has "silver" or "platinum" but no mention of "gold" at all:
  if (!/\bgold\b/i.test(str) && (/\bsilver\b/i.test(str) || /\bplatinum\b/i.test(str))) return true;
  return false;
}

export function extractGrams(title = '', text = '', urlOrSlug = '') {
  const combined = `${title} ${urlOrSlug} ${text}`
    .replace(/<[^>]+>/g, ' ')
    .replace(/weight\s*range[^\d]{0,20}\d+[\s\w.-]+?(?:mg|gms|gm|grams|gram|g)\b/gi, ' ')
    .replace(/\b\d+(?:\.\d+)?\s*[xX*]\s*\d+(?:\.\d+)?\s*(?:mm|cm)\b/gi, ' ')
    .replace(/\b\d+(?:\.\d+)?\s*(?:mm|cm)\b/gi, ' ')
    .replace(/\s+/g, ' ');

  // 1. Explicit multi-part combo with +: "0.5 Gm + 1 Gm + 2 Gm", "10 G + 5 G", "1 G + 2 G", "2 + 2 gm"
  const comboMatch = combined.match(/(?:^|[^a-z0-9])((?:\d+(?:\.\d+)?\s*(?:mg|gms|gm|grams|gram|g)?\s*\+\s*)+\d+(?:\.\d+)?\s*(?:mg|gms|gm|grams|gram|g)\b)/i);
  if (comboMatch) {
    const rawCombo = comboMatch[1];
    const defaultUnit = rawCombo.match(/(mg|gms|gm|grams|gram|g)\b/i)?.[1] || 'g';
    const parts = rawCombo.split('+');
    let total = 0;
    for (const part of parts) {
      const match = part.match(/(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)?/i);
      if (match) {
        const val = tokenToGrams(match[1], match[2] || defaultUnit);
        if (val > 0) total += val;
      }
    }
    if (total > 0) return total;
  }

  // 2. Multi-pack pattern: "3Pcs 24KT Gold Coin 10 G Each", "4pcs 5g each", "3 pcs (10g each)"
  const pcsEach = combined.match(/(\d+)\s*(?:pcs|pc|pieces|items|coins?|bars?)\b[^,\n()]*?(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\s*(?:each|per\s*(?:pc|piece|coin|bar))\b/i)
    || combined.match(/(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\s*(?:each|per\s*(?:pc|piece|coin|bar))\b[^,\n()]*?[xX*]\s*(\d+)\s*(?:pcs|pc|pieces|items|coins?|bars?)?\b/i)
    || combined.match(/\(\s*(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\s*(?:each|per\s*(?:pc|piece|coin|bar))?\s*[xX*]\s*(\d+)\s*(?:pcs|pc|pieces|items)?\s*\)/i);
  if (pcsEach) {
    const num1 = Number(pcsEach[1]);
    const num2 = Number(pcsEach[2]);
    if (pcsEach[3]) {
      const eachGrams = tokenToGrams(num2, pcsEach[3]);
      if (Number.isFinite(num1) && num1 > 0 && eachGrams > 0) return num1 * eachGrams;
    } else {
      const eachGrams = tokenToGrams(num1, pcsEach[2]);
      const count = Number(pcsEach[3]);
      if (Number.isFinite(count) && count > 0 && eachGrams > 0) return count * eachGrams;
    }
  }

  // 3. Title / Spec standard weight extraction:
  const titleMatch = String(title || '').match(/(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/i);
  if (titleMatch) {
    const val = tokenToGrams(titleMatch[1], titleMatch[2]);
    if (val > 0) return val;
  }

  // 6. Net weight in text / specs
  const specMatch = combined.match(/(?:net\s+)?(?:weight|wt|quantity)[^\d]{0,40}(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/i);
  if (specMatch) {
    const val = tokenToGrams(specMatch[1], specMatch[2]);
    if (val > 0) return val;
  }

  // 7. URL slug weight
  const slugMatch = String(urlOrSlug || '').match(/(?:^|[-_/])(\d+(?:\.\d+)?)\s*-?\s*(mg|gms|gm|grams|gram|g)(?=[-_/\d]|$)/i);
  if (slugMatch) {
    const val = tokenToGrams(slugMatch[1], slugMatch[2]);
    if (val > 0) return val;
  }

  // 8. General fallback in text
  const tokens = [...combined.matchAll(/(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/gi)]
    .map(([, amount, unit]) => tokenToGrams(amount, unit))
    .filter((w) => Number.isFinite(w) && w > 0 && w <= 1000);
  if (tokens.length) {
    const mgOnly = tokens.filter((w) => w < 1);
    return mgOnly.length ? Math.max(...mgOnly) : Math.max(...tokens);
  }

  return null;
}

export function normalizeGoldWeight(grams, price) {
  let g = Number(grams);
  if (!Number.isFinite(g) || g <= 0) return null;
  const p = Number(price);
  if (Number.isFinite(p) && p > 0) {
    // If price / grams is < ₹3,000/g for a gold coin (current gold rate is ~₹16,000/g):
    // Check if vendor entered 500g instead of 500mg (or 100g instead of 100mg, 50g instead of 50mg)
    if (g >= 50 && (p / g) < 3000 && p < 100000) {
      g = g / 1000;
    }
  }
  return g;
}
