import { mirrorStateToDatabase } from './history-db.js';
import { mkdir, readFile, readdir, rename, unlink, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isNonGoldProductText } from '../product/stores/weight-parser.js';

const dataDirectory = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..', 'data');
const bullionFile = join(dataDirectory, 'bullion.json');
const productsDirectory = join(dataDirectory, 'products');
const preciousMetalsFile = join(dataDirectory, 'precious-metals.json');
const legacyFile = join(dataDirectory, 'state.json');
const emptyState = {
  settings: { pincode: '560048', preciseAddress: 'Dhruvika Mogra Apartment', debugVisibleBrowser: false, productDebugVisibleBrowser: false, productAutoRefresh: false, productRefreshIntervalMin: 5, refreshProductsOnStart: false, refreshBullionOnStart: false },
  bullion: [
    { id: 'tan', source: 'Tanishq', label: 'Tanishq gold rate', price: null, price24: null, price22: null, price22Derived: false, fetchedAt: null, status: 'unavailable' },
    { id: 'malabar', source: 'Malabar Gold & Diamonds', label: 'Malabar Gold & Diamonds', price: null, price24: null, price22: null, price22Derived: false, fetchedAt: null, status: 'unavailable' },
    { id: 'mmtc', source: 'MMTC-PAMP', label: 'MMTC-PAMP', price: null, price24: null, price22: null, price22Derived: false, fetchedAt: null, status: 'unavailable' },
    { id: 'kalyan', source: 'Kalyan Jewellers', label: 'Kalyan Jewellers', price: null, price24: null, price22: null, price22Derived: false, fetchedAt: null, status: 'unavailable' }
  ],
  products: [],
  preciousMetalProducts: []
};

const hasUsableProductPrice = (product) => Number.isFinite(product.price) && product.price > 0 && Number(product.grams) > 0;
const isTransientProductError = (error) => /access denied|request blocked|blocked due to security reasons|captcha|permission|403|timed out|timeout|price or gold weight was not found|product details not found|parser unavailable|disabled/i.test(String(error || ''));

export async function loadState() {
  try {
    let state;
    try {
      const bullion = JSON.parse(await readFile(bullionFile, 'utf8'));
      const products = [];
      for (const file of await readdir(productsDirectory)) if (file.endsWith('.json')) products.push(...JSON.parse(await readFile(join(productsDirectory, file), 'utf8')));
      let preciousMetalProducts = [];
      try { preciousMetalProducts = JSON.parse(await readFile(preciousMetalsFile, 'utf8')); } catch {}
      state = { bullion: bullion.sources || bullion, products, preciousMetalProducts, settings: bullion.settings };
    } catch {
      state = JSON.parse(await readFile(legacyFile, 'utf8'));
    }
    const bullion = (state.bullion || emptyState.bullion).filter((item) => item.id !== 'joy');
    for (const source of emptyState.bullion) {
      const existing = bullion.find((item) => item.id === source.id);
      if (!existing) bullion.push(structuredClone(source));
      else {
        existing.url ||= source.url;
        existing.label ||= source.label;
        existing.source ||= source.source;
        existing.price24 ??= Number.isFinite(existing.price) ? existing.price : null;
        existing.price22 ??= null;
        existing.price22Derived ??= false;
      }
    }
    bullion.forEach((item) => {
      if (Number.isFinite(item.price) && item.price > 0) {
        item.status = 'live';
      }
    });
    const products = state.products || [];
    const preciousMetalProducts = state.preciousMetalProducts || [];
    const retainedProducts = [];
    products.forEach((product) => {
      product.url = product.url?.replace(/[?#]$/, '') || product.url;
      product.source = String(product.source || '').replace(/^www\./i, '') || product.source;
      if (product.status === 'live') delete product.error;
      if (product.status === 'checking') product.status = hasUsableProductPrice(product) ? 'stale' : 'unverified';
      if (product.status === 'live' && !hasUsableProductPrice(product)) product.status = 'unverified';
      if (product.status === 'unavailable' && isTransientProductError(product.error)) product.status = hasUsableProductPrice(product) ? 'stale' : 'unverified';
      if (product.lastLiveAt && Date.now() - new Date(product.lastLiveAt).getTime() > 30 * 60 * 1000 && product.status === 'live') product.status = 'stale';
      if (isNonGoldProductText(`${product.name || ''} ${product.url || ''} ${product.purity || ''}`)) {
        product.metal = /platinum|\bpt\s*\d+/i.test(`${product.name || ''} ${product.url || ''} ${product.purity || ''}`) ? 'platinum' : 'silver';
        preciousMetalProducts.push(product);
      } else {
        retainedProducts.push(product);
      }
    });
    return { settings: { ...emptyState.settings, ...(state.settings || {}) }, bullion, products: retainedProducts, preciousMetalProducts };
  } catch {
    return structuredClone(emptyState);
  }
}

let pendingWrite = Promise.resolve();
export function saveState(state) {
  pendingWrite = pendingWrite.then(async () => {
    await mkdir(productsDirectory, { recursive: true });
    const sources = state.bullion;
    const bullionPayload = { settings: state.settings, sources };
    const bullionTemporary = `${bullionFile}.tmp`;
    await writeFile(bullionTemporary, JSON.stringify(bullionPayload, null, 2) + '\n', 'utf8');
    await rename(bullionTemporary, bullionFile);
    const preciousMetalsTemporary = `${preciousMetalsFile}.tmp`;
    await writeFile(preciousMetalsTemporary, JSON.stringify(state.preciousMetalProducts || [], null, 2) + '\n', 'utf8');
    await rename(preciousMetalsTemporary, preciousMetalsFile);
    const byStore = new Map();
    for (const product of state.products) { const key = (product.source || 'unknown').replace(/^www\./, '').replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') || 'unknown'; if (!byStore.has(key)) byStore.set(key, []); byStore.get(key).push(product); }
    await Promise.all([...byStore].map(async ([store, products]) => {
      const file = join(productsDirectory, `${store}.json`);
      const temporaryFile = `${file}.tmp`;
      await writeFile(temporaryFile, JSON.stringify(products, null, 2) + '\n', 'utf8');
      await rename(temporaryFile, file);
    }));
    const activeFiles = new Set([...byStore.keys()].map((store) => `${store}.json`));
    const storedFiles = await readdir(productsDirectory);
    await Promise.all(storedFiles
      .filter((file) => file.endsWith('.json') && !activeFiles.has(file))
      .map((file) => unlink(join(productsDirectory, file))));
    // SQLite/WAL is an additive mirror/history store. JSON remains authoritative for compatibility.
    mirrorStateToDatabase(state);
  });
  return pendingWrite;
}
