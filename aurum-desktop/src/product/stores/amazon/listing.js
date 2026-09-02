import { randomUUID } from 'node:crypto';
import { isNonGoldProductText, normalizeGoldWeight } from '../weight-parser.js';

const STORE_DOMAIN = 'amazon.in';
const clean = (value) => String(value ?? '').replace(/\s+/g, ' ').trim();

export const amazonProductAsin = (value) => {
  const source = typeof value === 'string' ? value : value?.url || value?.asin;
  return String(source || '').match(/\/(?:dp|gp\/product)\/([A-Z0-9]{10})\b/i)?.[1] || String(value?.asin || '').match(/^[A-Z0-9]{10}$/i)?.[0] || null;
};

const canonicalUrl = (value, asin) => {
  if (asin) return `https://www.${STORE_DOMAIN}/dp/${asin}`;
  try { return new URL(value).href; } catch { return null; }
};

export function normalizeListingProduct(raw) {
  const asin = amazonProductAsin(raw);
  const url = canonicalUrl(raw?.url || raw?.link, asin);
  const name = clean(raw?.name || raw?.productName);
  const price = Number(raw?.price);
  const grams = normalizeGoldWeight(Number(raw?.grams ?? raw?.weightGrams), price);
  const karat = Number(raw?.karat) || null;
  const purity = clean(raw?.purity || raw?.fineness) || null;
  const metal = raw?.metal || (isNonGoldProductText(`${name} ${url || ''}`) ? 'non-gold' : 'gold');
  return { asin, url, name, brand: clean(raw?.brand), price: Number.isFinite(price) && price > 0 ? price : null, couponPrice: null, grams, karat, purity, metal };
}

export const qualificationReasons = (product) => {
  const reasons = [];
  if (product.metal !== 'gold') reasons.push('missing-gold-evidence');
  if (!product.grams) reasons.push('missing-weight');
  if (!product.price) reasons.push('missing-price');
  return reasons;
};

export const toPersistedProduct = (product) => {
  const now = new Date().toISOString();
  return { id: randomUUID(), name: product.name, brand: product.brand, source: STORE_DOMAIN, grams: product.grams, price: product.price, purity: product.purity, karat: product.karat, url: product.url, canonicalUrl: product.url, couponPrice: null, checkedAt: now, lastLiveAt: now, status: 'live', refreshMethod: 'amazon-browser-bridge' };
};