import { loadState, saveState } from '../src/storage/state-store.js';
import { isNonGoldProductText } from '../src/product/stores/weight-parser.js';
import { DatabaseSync } from 'node:sqlite';
import { join } from 'node:path';

const state = await loadState();
let restored = 0;
let reclassified = 0;
let historyRestored = 0;
const db = new DatabaseSync(join(process.cwd(), 'data', 'aurum.sqlite'));
const lastHistoricalPrice = db.prepare('SELECT price, coupon_price, checked_at FROM price_history WHERE product_id = ? AND price > 0 ORDER BY id DESC LIMIT 1');

for (const product of state.products) {
  if (product.source === 'myntra.com' && ['failed', 'unavailable'].includes(product.status) && /timed out|timeout|worker exited/i.test(product.error || '')) {
    product.status = 'unverified';
    product.error = 'Myntra refresh timed out; product availability is unverified.';
    reclassified += 1;
  }
  if (product.source === 'ajio.com' && product.status === 'unavailable') {
    const explicitUnavailable = /out of stock|sold out|no longer available|filtered: silver\/platinum/i.test(product.error || '');
    if (!explicitUnavailable) {
      product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'unverified';
      product.error = 'AJIO listing data is currently unverified; product availability is unknown.';
      reclassified += 1;
    }
  }
  if (product.source === 'ajio.com' && product.status === 'failed' && /availability is unknown|listing data is currently unverified/i.test(product.error || '')) {
    product.status = 'unverified';
    reclassified += 1;
  }
  if (product.source === 'ajio.com' && product.status === 'unverified' && (!Number.isFinite(product.price) || product.price <= 0)) {
    const historical = lastHistoricalPrice.get(product.id);
    if (historical && Number.isFinite(historical.price) && historical.price > 0) {
      product.price = historical.price;
      product.couponPrice = Number.isFinite(historical.coupon_price) && historical.coupon_price > 0 ? historical.coupon_price : null;
      product.status = 'stale';
      product.checkedAt = historical.checked_at || product.checkedAt;
      product.error = 'Last known AJIO price restored from local price history; current listing access is unavailable.';
      historyRestored += 1;
    }
  }
  if (product.source !== 'ajio.com' || product.refreshMethod !== 'ajio-plp') continue;
  if (!Number.isFinite(product.price) || product.price <= 0) continue;
  if (!Number.isFinite(Number(product.grams)) || Number(product.grams) < 0.5) continue;
  if (!Number.isFinite(Number(product.karat)) || Number(product.karat) < 22) continue;
  if (isNonGoldProductText(`${product.name || ''} ${product.url || ''} ${product.purity || ''}`)) continue;

  const lastSuccessfulAt = new Date(product.lastLiveAt || product.checkedAt || 0).getTime();
  product.status = Number.isFinite(lastSuccessfulAt) && Date.now() - lastSuccessfulAt <= 30 * 60 * 1000 ? 'live' : 'stale';
  product.checkedAt = product.lastLiveAt || product.checkedAt;
  delete product.error;
  delete product.lastAttemptAt;
  restored += 1;
}

await saveState(state);
console.log(`Restored ${restored} previously successful AJIO PLP products to live.`);
console.log(`Reclassified ${reclassified} ambiguous product records as unverified.`);
console.log(`Restored ${historyRestored} AJIO last-known prices from local history as stale.`);
