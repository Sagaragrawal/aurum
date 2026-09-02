import { randomUUID } from 'node:crypto';
import { extractGrams, isNonGoldProductText, normalizeGoldWeight } from '../weight-parser.js';

const STORE_DOMAIN = 'ajio.com';
const minimumKarat = 22;
const minimumGrams = 0.5;

const clean = (value) => String(value ?? '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
const numberFrom = (value) => {
  const numeric = Number(value?.value ?? value);
  return Number.isFinite(numeric) && numeric > 0 ? numeric : null;
};

export const ajioProductCode = (value) => {
  const source = typeof value === 'string' ? value : value?.url;
  return String(source || '').match(/\/p\/([^/?#]+)/i)?.[1]
    || String(value?.fnlColorVariantData?.colorGroup || value?.code || '')
    || null;
};

const canonicalUrl = (value) => {
  try {
    const url = new URL(value, `https://www.${STORE_DOMAIN}`);
    url.search = '';
    url.hash = '';
    return url.href;
  } catch {
    return null;
  }
};

const purityFromText = (value) => {
  const text = clean(value);
  const karatMatch = text.match(/(?<![0-9a-z])(24|23|22|21|20|18|14|10|9)\s*-?\s*(?:k|kt|karat|carat)(?![a-z])/i);
  const purityMatch = text.match(/(?<!\d)(999\.99\+?|999\.9\+?|9999|999\+?|995|990|958|950|916|875|833|750|585|417|375)(?!\d)/i);
  const purity = purityMatch?.[1] || null;
  let karat = karatMatch ? Number(karatMatch[1]) : null;
  if (!karat && purity) {
    const numeric = Number.parseFloat(purity);
    if (numeric >= 990) karat = 24;
    else if (numeric >= 915 && numeric <= 917) karat = 22;
    else if (numeric >= 874 && numeric <= 876) karat = 21;
    else if (numeric >= 749 && numeric <= 751) karat = 18;
    else if (numeric >= 584 && numeric <= 586) karat = 14;
    else if (numeric >= 416 && numeric <= 418) karat = 10;
    else if (numeric >= 374 && numeric <= 376) karat = 9;
  }
  return { karat, purity };
};

const metalFromText = (value, karat = null) => {
  const text = clean(value).toLowerCase();
  const plated = /\b(?:gold[- ]?plated|gold tone|imitation|brass|copper)\b/.test(text);
  const silver = /\b(?:silver|sterling|925)\b/.test(text);
  const platinum = /\bplatinum\b/.test(text);
  const gold = Boolean(karat || /\bgold\b/.test(text));
  if (plated) return 'non-gold';
  if (platinum) return gold ? 'conflict' : 'platinum';
  if (silver) return gold ? 'conflict' : 'silver';
  if (gold) return 'gold';
  return null;
};

const listingEvidenceText = (raw) => [
  raw?.fnlColorVariantData?.brandName,
  raw?.brandName,
  raw?.name,
  raw?.text,
  raw?.imageAlt,
  raw?.url,
  ...(Array.isArray(raw?.images) ? raw.images : []).map((image) => image?.altText),
  ...(Array.isArray(raw?.extraImages) ? raw.extraImages : []).map((image) => image?.altText),
  ...(Array.isArray(raw?.tags) ? raw.tags : raw?.tags ? [raw.tags] : [])
    .map((tag) => typeof tag === 'string' ? tag : JSON.stringify(tag))
].filter(Boolean).map(clean).join(' ');

const normalizeObservedWeight = (text, price) => {
  const normalizedText = clean(text).replace(/\b(\d+(?:\.\d+)?)\s*gg\b/gi, '$1 g');
  return normalizeGoldWeight(extractGrams(normalizedText, normalizedText, normalizedText), price);
};

// AJIO PDP/detail payloads expose jewellery weights as labelled qualifiers.
// Prefer Metal Weight, then Net Weight, and only then Gross Weight. This avoids
// treating dimensions (for example Diameter: 20.7 mm) or an old listing/title
// weight as the gold weight.
const weightFromLabelledText = (value) => {
  const text = clean(value);
  if (!text) return null;
  for (const label of ['metal weight', 'net weight', 'gross weight']) {
    const match = text.match(new RegExp(`${label}\\s*:?\\s*(\\d+(?:\\.\\d+)?)\\s*(mg|gms|gm|grams|gram|g)\\b`, 'i'));
    if (!match) continue;
    const amount = Number(match[1]);
    if (!Number.isFinite(amount) || amount <= 0) continue;
    return match[2].toLowerCase() === 'mg' ? amount / 1000 : amount;
  }
  return null;
};

export function normalizeListingProduct(raw, source) {
  const ajioCode = ajioProductCode(raw);
  const url = canonicalUrl(raw?.url);
  const brand = clean(raw?.brandName || raw?.fnlColorVariantData?.brandName);
  const rawName = clean(raw?.name);
  const name = clean(brand && rawName ? `${brand} ${rawName}` : rawName);
  const price = numberFrom(raw?.price);
  const wasPrice = numberFrom(raw?.wasPriceData);
  const offerPrices = [raw?.offerPrice, raw?.promoDiscountedPrice, raw?.discountedPrice, raw?.cartOfferPrice]
    .map(numberFrom)
    .filter((offer) => offer && price && offer < price);
  const evidenceText = listingEvidenceText(raw);
  const { karat, purity } = purityFromText(evidenceText);
  const grams = normalizeObservedWeight(evidenceText, price);
  const metal = metalFromText(evidenceText, karat);

  return {
    ajioCode,
    url,
    name,
    brand,
    source: STORE_DOMAIN,
    grams,
    karat,
    purity,
    metal,
    price,
    wasPrice,
    couponPrice: offerPrices.length ? Math.min(...offerPrices) : null,
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
    evidence: {
      ...current.evidence,
      ...Object.fromEntries(Object.entries(incoming.evidence || {}).filter(([, value]) => value))
    }
  };
}

export function applyPurityFacet(product, facetLabel) {
  const parsed = purityFromText(facetLabel);
  if (!product.karat && parsed.karat) {
    product.karat = parsed.karat;
    product.evidence.karat = `facet:${facetLabel}`;
  }
  if (!product.purity && parsed.purity) {
    product.purity = parsed.purity;
    product.evidence.purity = `facet:${facetLabel}`;
  }
  return product;
}

const qualifierMap = (option) => new Map((option?.variantOptionQualifiers || [])
  .map((item) => [String(item.qualifier || item.name || '').toLowerCase(), item.value]));

export function detailQualifiers(payload, productCode) {
  const baseOptions = (payload?.baseOptions || []).flatMap((group) => [
    ...(group.options || []),
    ...(group.selected ? [group.selected] : [])
  ]);
  const exactBase = baseOptions.find((option) => option.code === productCode) || baseOptions[0];
  const rootCode = String(payload?.code || productCode).replace(/_[a-z0-9]+$/i, '');
  const variant = (payload?.variantOptions || []).find((option) => rootCode.startsWith(String(option.code || '')))
    || payload?.variantOptions?.[0];
  const baseValues = qualifierMap(exactBase);
  const variantValues = qualifierMap(variant);
  const rawWeight = variantValues.get('metalweight')
    ?? variantValues.get('netweight')
    ?? variantValues.get('grossweight');
  const unit = String(variantValues.get('uom') || 'g').toLowerCase();
  const featureText = (payload?.featureData || []).flatMap((feature) => [
    feature?.name,
    feature?.value,
    ...(feature?.featureValues || []).map((item) => item?.value)
  ]).filter(Boolean).join(' ');
  let grams = Number(rawWeight);
  if (unit === 'mg') grams /= 1000;
  if (unit === 'kg') grams *= 1000;
  const labelledWeight = weightFromLabelledText(featureText);
  if ((!Number.isFinite(grams) || grams <= 0) && Number.isFinite(labelledWeight) && labelledWeight > 0) grams = labelledWeight;
  if (!Number.isFinite(grams) || grams <= 0) grams = normalizeObservedWeight(payload?.name, numberFrom(payload?.price));
  const purity = purityFromText(baseValues.get('metalpurity') || payload?.name || '');
  return {
    name: clean(payload?.name),
    grams: Number.isFinite(grams) && grams > 0 ? grams : null,
    ...purity,
    metal: metalFromText(`${payload?.name || ''} ${featureText}`, purity.karat)
  };
}

export function applyDetailQualifiers(product, details) {
  if (!product.grams && details.grams) {
    product.grams = details.grams;
    product.evidence.grams = 'detail-api:variant-qualifier';
  }
  if (!product.karat && details.karat) {
    product.karat = details.karat;
    product.evidence.karat = 'detail-api:base-option';
  }
  if (!product.purity && details.purity) {
    product.purity = details.purity;
    product.evidence.purity = 'detail-api:base-option';
  }
  if (details.metal) {
    product.metal = details.metal;
    product.evidence.metal = 'detail-api:feature-data';
  }
  return product;
}

export function qualificationReasons(product) {
  const reasons = [];
  if (product.metal !== 'gold' || isNonGoldProductText(`${product.name} ${product.url}`)) {
    reasons.push(product.metal ? `metal:${product.metal}` : 'missing-metal');
  }
  if (!product.grams) reasons.push('missing-weight');
  else if (product.grams < minimumGrams) reasons.push('under-minimum-weight');
  if (!product.karat) reasons.push('missing-karat');
  else if (product.karat < minimumKarat) reasons.push('under-minimum-karat');
  if (!product.price) reasons.push('missing-price');
  return reasons;
}

export function toPersistedProduct(product) {
  const now = new Date().toISOString();
  return {
    id: randomUUID(),
    name: product.name,
    brand: product.brand,
    source: STORE_DOMAIN,
    grams: product.grams,
    price: product.price,
    purity: product.purity || (product.karat === 24 ? '999' : product.karat === 22 ? '916' : null),
    karat: product.karat,
    url: product.url,
    canonicalUrl: product.url,
    couponPrice: product.couponPrice,
    checkedAt: now,
    lastLiveAt: now,
    status: 'live',
    refreshMethod: 'ajio-plp'
  };
}
