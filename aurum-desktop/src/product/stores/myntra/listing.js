import { randomUUID } from 'node:crypto';
import { extractGrams, isNonGoldProductText, normalizeGoldWeight, parseWeightValue } from '../weight-parser.js';

const STORE_DOMAIN = 'myntra.com';

const clean = (value) => String(value ?? '').replace(/<br\s*\/?>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
const numberFrom = (value) => {
  const number = Number(value?.value ?? value);
  return Number.isFinite(number) && number > 0 ? number : null;
};

export const myntraProductId = (value) => {
  const source = typeof value === 'string' ? value : value?.url || value?.landingPageUrl || value?.productId;
  return String(source || '').match(/\/(\d+)(?:\/buy)?\/?(?:[?#].*)?$/)?.[1]
    || (value?.productId != null ? String(value.productId) : null);
};

const canonicalUrl = (value) => {
  try {
    const url = new URL(value, `https://www.${STORE_DOMAIN}/`);
    url.search = '';
    url.hash = '';
    return url.href;
  } catch {
    return null;
  }
};

const purityFromText = (value) => {
  const text = clean(value).replace(/\b9999(?=[\s_-]*(?:purity|fineness|gold|coin|bar))/gi, '999.9');
  const karatMatch = text.match(/\b(24|23|22|21|20|19|18|15|14|12|10|9|8)\s*(?:kt|k|karat|carat)\b/i);
  const finenessMatch = text.match(/\b(999\.99|999\.9\+?|999\+?|995|990|958|950|925|916|875|833|792|750|625|585|417|375|333)\s*(?:purity|fineness|gold)\b/i)
    || text.match(/\b(?:24|23|22|21|20|19|18|14|10|9)\s*(?:kt|k)\s*(?:\(|[-:/])?\s*(999\.99|999\.9\+?|999\+?|995|990|958|950|925|916|875|833|792|750|625|585|417|375|333)\b/i);
  const fineness = finenessMatch?.[1] || null;
  let karat = karatMatch ? Number(karatMatch[1]) : null;
  if (!karat && fineness) {
    const value = Number.parseFloat(fineness);
    if (value >= 990) karat = 24;
    else if (value >= 957 && value <= 959) karat = 23;
    else if (value >= 915 && value <= 917) karat = 22;
    else if (value >= 749 && value <= 751) karat = 18;
    else if (value >= 584 && value <= 586) karat = 14;
  }
  return { karat, purity: fineness };
};

const listingText = (raw) => [raw?.brand, raw?.productName, raw?.product, raw?.additionalInfo, raw?.landingPageUrl, raw?.productUrl, raw?.url].filter(Boolean).map(clean).join(' ');

export function normalizeListingProduct(raw, source) {
  const productId = myntraProductId(raw);
  const url = canonicalUrl(raw?.landingPageUrl || raw?.productUrl || raw?.url);
  const name = clean(raw?.productName || raw?.product || raw?.name);
  const brand = clean(raw?.brand);
  const price = numberFrom(raw?.price) || numberFrom(raw?.discountedPrice);
  const text = listingText(raw);
  const { karat, purity } = purityFromText(text);
  const grams = normalizeGoldWeight(extractGrams(name, text, url || ''), price);
  const metal = isNonGoldProductText(text) ? 'non-gold' : /\bgold\b/i.test(text) || karat ? 'gold' : null;
  const couponPrice = numberFrom(raw?.couponData?.couponDiscount?.bestPrice || raw?.couponData?.couponDescription?.bestPrice);

  return {
    productId,
    url,
    name,
    brand,
    source: STORE_DOMAIN,
    grams,
    karat,
    purity,
    metal,
    price,
    couponPrice: couponPrice && price && couponPrice < price ? couponPrice : null,
    sources: [source],
    evidence: {
      grams: grams ? 'listing-text' : null,
      karat: karat ? 'listing-text' : null,
      purity: purity ? 'listing-text' : null,
      metal: metal ? 'listing-text' : null
    }
  };
}

export function mergeListingProduct(current, incoming) {
  if (!current) return incoming;
  return {
    ...current,
    ...Object.fromEntries(Object.entries(incoming).filter(([, value]) => value !== null && value !== '' && value !== undefined)),
    sources: [...new Set([...(current.sources || []), ...(incoming.sources || [])])],
    evidence: { ...current.evidence, ...Object.fromEntries(Object.entries(incoming.evidence || {}).filter(([, value]) => value)) }
  };
}

export function applyProductDetails(product, json) {
  const style = json?.style;
  if (!style) return product;
  const attrs = { ...(style.articleAttributes || {}) };
  const description = [
    ...(style.productDetails || []).map((detail) => detail?.description),
    ...(style.descriptors || []).map((detail) => detail?.description),
    ...(style.productContentGroupEntries || []).flatMap((group) => (group?.attributes || []).map((attribute) => attribute?.value))
  ].filter(Boolean).map(clean).join(' ');
  const details = [style.name, attrs['Gold Purity'], ...Object.values(attrs), description].filter(Boolean).map(clean).join(' ');
  const purityLabel = clean(attrs['Gold Purity'] || attrs['Metal Purity'] || attrs.Purity);
  const structuredPurity = purityFromText(purityLabel);
  const describedPurity = purityFromText(description);
  const { karat, purity } = structuredPurity.karat ? structuredPurity : describedPurity;
  const structuredGrams = parseWeightValue(attrs['Metal Net Weight'] || attrs['Net Weight'] || attrs.Weight || attrs['Gross Weight']);
  const grams = normalizeGoldWeight(extractGrams(style.name || product.name, details, product.url || ''), product.price) || structuredGrams;
  if (!product.karat && karat) {
    product.karat = karat;
    product.evidence.karat = 'product-api';
  }
  if (!product.purity && purity) {
    product.purity = purity;
    product.evidence.purity = 'product-api';
  }
  if (!product.grams && grams) {
    product.grams = grams;
    product.evidence.grams = 'product-api';
  }
  if (product.metal !== 'non-gold' && (/\bgold\b/i.test(details) || product.karat)) {
    product.metal = 'gold';
    product.evidence.metal = 'product-api';
  }
  product.purityLabel = purityLabel || product.purityLabel || null;
  product.descriptionKarat = describedPurity.karat || null;
  product.descriptionFineness = describedPurity.purity || null;
  product.purityConflict = Boolean(structuredPurity.karat && describedPurity.karat && structuredPurity.karat !== describedPurity.karat);
  return product;
}

export function qualificationReasons(product) {
  const reasons = [];
  if (product.metal !== 'gold' || isNonGoldProductText(`${product.name} ${product.url}`)) reasons.push(product.metal ? `metal:${product.metal}` : 'missing-metal');
  if (!product.grams) reasons.push('missing-weight');
  if (!product.karat) reasons.push('missing-karat');
  if (!product.price) reasons.push('missing-price');
  return reasons;
}

export function toPersistedProduct(product) {
  const now = new Date().toISOString();
  return {
    id: randomUUID(), name: product.name, brand: product.brand, source: STORE_DOMAIN,
    grams: product.grams, price: product.price, purity: product.purity || (product.karat === 24 ? '999' : product.karat === 22 ? '916' : null), karat: product.karat,
    url: product.url, canonicalUrl: product.url, couponPrice: product.couponPrice,
    checkedAt: now, lastLiveAt: now, status: 'live', refreshMethod: 'myntra-plp'
  };
}