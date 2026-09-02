import { randomUUID } from 'node:crypto';
import { extractGrams, isNonGoldProductText, normalizeGoldWeight } from '../weight-parser.js';

const STORE_DOMAIN = 'flipkart.com';

const clean = (value) => String(value ?? '').replace(/\s+/g, ' ').trim();
const amount = (value) => {
  const match = String(value ?? '').replaceAll(',', '').match(/\d+(?:\.\d+)?/);
  const parsed = Number(match?.[0]);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
};

export const flipkartProductId = (value) => {
  try {
    return new URL(typeof value === 'string' ? value : value?.url || '', `https://www.${STORE_DOMAIN}/`).searchParams.get('pid') || value?.productId || null;
  } catch {
    return value?.productId || null;
  }
};

const canonicalUrl = (value, productId) => {
  try {
    const url = new URL(value, `https://www.${STORE_DOMAIN}/`);
    for (const key of ['otracker', 'otracker1', 'lid', 'fm', 'ppt', 'ppn', 'srno', 'spotlightTagId', 'iid', 'ssid', 'ov_redirect', 'store']) url.searchParams.delete(key);
    if (productId) url.searchParams.set('pid', productId);
    url.searchParams.set('marketplace', 'FLIPKART');
    url.hash = '';
    return url.href;
  } catch {
    return null;
  }
};

const purityFromText = (value) => {
  const text = clean(value).replace(/\b9999\b/g, '999.9');
  const karat = Number(text.match(/\b(24|23|22|21|20|19|18|14)\s*(?:\(\s*\d{3,4}(?:\.\d+)?\s*\)\s*)?(?:kt|k|karat|carat)\b/i)?.[1]) || null;
  const purity = text.match(/\b(999\.9|999|995|990|958|950|925|916|875|833|750|585)\b/)?.[1] || null;
  return { karat: karat || (Number(purity) >= 990 ? 24 : Number(purity) >= 915 ? 22 : Number(purity) >= 749 ? 18 : Number(purity) >= 584 ? 14 : null), purity };
};

export function normalizeListingProduct(raw, stream = 'default') {
  const productId = flipkartProductId(raw);
  const url = canonicalUrl(raw?.url || raw?.link, productId);
  const name = clean(raw?.name);
  const brand = clean(raw?.brand);
  const text = `${name} ${brand} ${url || ''}`;
  const price = amount(raw?.price);
  const { karat, purity } = purityFromText(text);
  const grams = normalizeGoldWeight(extractGrams(name, text, url || ''), price);
  const metal = isNonGoldProductText(text) ? 'non-gold' : /\bgold\b/i.test(text) || karat ? 'gold' : null;
  return { productId, url, name, brand, price, couponPrice: null, grams, karat, purity, metal, streams: [stream] };
}

export function mergeListingProduct(current, incoming) {
  if (!current) return incoming;
  return {
    ...current,
    ...Object.fromEntries(Object.entries(incoming).filter(([, value]) => value !== null && value !== '' && value !== undefined)),
    streams: [...new Set([...(current.streams || []), ...(incoming.streams || [])])]
  };
}

export const qualificationReasons = (product) => {
  const reasons = [];
  if (product.metal !== 'gold') reasons.push('missing-gold-evidence');
  if (!product.grams) reasons.push('missing-weight');
  if (!product.karat) reasons.push('missing-karat');
  if (!product.price) reasons.push('missing-price');
  return reasons;
};

export const toPersistedProduct = (product) => {
  const now = new Date().toISOString();
  return {
    id: randomUUID(), name: product.name, brand: product.brand, source: STORE_DOMAIN,
    grams: product.grams, price: product.price, purity: product.purity || (product.karat === 24 ? '999' : product.karat === 22 ? '916' : null), karat: product.karat,
    url: product.url, canonicalUrl: product.url, couponPrice: null, checkedAt: now, lastLiveAt: now, status: 'live', refreshMethod: 'flipkart-plp'
  };
};