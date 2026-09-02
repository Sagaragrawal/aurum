/**
 * Amazon product collector
 * Runs batch refresh for Amazon products only.
 */

import * as store from './store.js';
import { tryHttpFastPath } from '../http-fast-path.js';

const itemTimeoutMs = Number(process.env.PRODUCT_ITEM_TIMEOUT_MS || 75000);
const launchTimeoutMs = Number(process.env.PRODUCT_BROWSER_LAUNCH_TIMEOUT_MS || 30000);
const pageReadyTimeoutMs = Number(process.env.PRODUCT_PAGE_READY_TIMEOUT_MS || 45000);
const productConcurrency = Math.max(1, Math.min(24, Number(process.env.PRODUCT_AMAZON_CONCURRENCY || process.env.PRODUCT_CONCURRENCY || 4)));

const withTimeout = (promise, milliseconds, label = 'operation') => {
  const timeout = new Promise((_, reject) => {
    const id = setTimeout(() => reject(new Error(`${label} timed out after ${milliseconds}ms`)), milliseconds);
    id.unref?.();
  });
  return Promise.race([promise, timeout]);
};

const launchProductBrowser = (playwright, headless) => playwright.firefox.launch({ headless });

const persistentBrowsers = new Map();
const getPersistentBrowser = async (playwright, mode, headless) => {
  const existing = persistentBrowsers.get(mode);
  if (existing) {
    const browser = await existing.catch(() => null);
    if (browser?.isConnected()) return browser;
    persistentBrowsers.delete(mode);
  }
  const promise = withTimeout(launchProductBrowser(playwright, headless), launchTimeoutMs, `product ${mode} browser launch`);
  persistentBrowsers.set(mode, promise);
  try { return await promise; } catch (error) { persistentBrowsers.delete(mode); throw error; }
};

export async function closePersistentBrowsers() {
  const entries = [...persistentBrowsers.values()];
  persistentBrowsers.clear();
  await Promise.all(entries.map(async (promise) => {
    const browser = await promise.catch(() => null);
    await browser?.close().catch(() => {});
  }));
}

const setFailure = (product, error) => {
  const unavailable = /out of stock|sold out|not found|no longer available/i.test(error?.message || '');
  const hasLastKnownValue = Number.isFinite(product.price) && product.price > 0 && Number(product.grams) > 0;
  product.status = unavailable ? 'unavailable' : hasLastKnownValue ? 'stale' : 'unavailable';
  if (unavailable) {
    product.price = null;
    product.couponPrice = null;
  }
  product.checkedAt = new Date().toISOString();
  product.error = error?.message || 'product details not found';
  return product;
};

async function refreshProduct(product, settings = {}, options = {}) {
  let browser = options.browser;
  const headless = options.headless ?? (store.supportsHeadless && !settings.productDebugVisibleBrowser && !settings.productForceVisibleBrowser);
  try {
    let fastPathReason = null;
    if (!settings.productDebugVisibleBrowser && !settings.productForceVisibleBrowser) {
      const fast = await tryHttpFastPath(product, store.parse);
      if (fast.result) {
        Object.assign(product, fast.result, { checkedAt: new Date().toISOString(), lastLiveAt: new Date().toISOString(), status: 'live', refreshMethod: 'http' });
        delete product.error;
        return product;
      }
      fastPathReason = fast.reason;
    }
    if (!browser && options.getBrowser) browser = await options.getBrowser();
    if (!browser) {
      const playwright = await import('playwright');
      browser = await withTimeout(launchProductBrowser(playwright, headless), launchTimeoutMs, 'product browser launch');
    }
    let extracted;
    try {
      extracted = await withTimeout(
        store.refreshProductPage(product, browser, settings, { itemTimeoutMs, pageReadyTimeoutMs }),
        itemTimeoutMs,
        `${product.source || 'store'} product refresh`
      );
    } catch (error) {
      if (fastPathReason) error.message = `Browser: ${error.message}; HTTP fast path: ${fastPathReason}`;
      throw error;
    }
    Object.assign(product, extracted, {
      checkedAt: new Date().toISOString(),
      lastLiveAt: new Date().toISOString(),
      status: 'live',
      refreshMethod: 'browser'
    });
    delete product.error;
    return product;
  } catch (error) {
    return setFailure(product, error);
  } finally {
    if (!options.browser && !options.getBrowser) await browser?.close().catch(() => {});
  }
}

export async function refreshProductBatch(products, settings = {}, onProgress = () => {}) {
  if (!products.length) return { checked: 0, live: 0 };

  const startedAt = Date.now();
  const browsers = new Map();
  let checked = 0;
  let live = 0;
  let failed = 0;
  onProgress({ total: products.length, checked, live, failed, current: null });

  try {
    const playwright = await import('playwright');
    const ensureBrowser = async (mode, headless) => {
      if (!browsers.has(mode)) browsers.set(mode, getPersistentBrowser(playwright, mode, headless));
      return browsers.get(mode);
    };

    let nextIndex = 0;
    let workerSequence = 0;
    const worker = async () => {
      const workerId = ++workerSequence;
      while (nextIndex < products.length) {
        const product = products[nextIndex++];
        product.status = 'checking';
        delete product.error;
        const headless = settings.productBulkHeadless
          ? true
          : Boolean(store.supportsHeadless) && !settings.productDebugVisibleBrowser && !settings.productForceVisibleBrowser;
        const mode = headless ? 'headless' : 'visible';
        onProgress({ total: products.length, checked, live, current: product.name, event: { id: `${Date.now()}-${workerId}-${nextIndex}-start`, store: product.source, workerId, phase: 'start', name: product.name } });
        const itemStartedAt = Date.now();
        let result = await refreshProduct(product, settings, { getBrowser: () => ensureBrowser(mode, headless), headless });
        if (result.status !== 'live' && settings.productFallbackVisibleOnFailure && headless) {
          result = await refreshProduct(product, settings, { getBrowser: () => ensureBrowser('visible', false), headless: false });
        }
        checked += 1;
        if (result.status === 'live') live += 1;
        else if (result.status !== 'unavailable') failed += 1;
        onProgress({ total: products.length, checked, live, failed, current: product.name, updatedProduct: result, event: { id: `${Date.now()}-${workerId}-${nextIndex}-done`, store: product.source, workerId, phase: result.status === 'live' ? 'done' : 'failed', name: product.name, status: result.status, method: result.refreshMethod || 'browser', ms: Date.now() - itemStartedAt, error: result.error || null, price: result.price || null, grams: result.grams || null, purity: result.purity || null, karat: result.karat || null } });
      }
    };

    const visibleMode = Boolean(settings.productDebugVisibleBrowser || settings.productForceVisibleBrowser);
    // Headed mode is for inspection/debugging: keep exactly one reusable page so Firefox
    // does not spawn a visible window/tab storm. Headless mode keeps normal parallelism.
    const workerCount = visibleMode ? 1 : Math.min(productConcurrency, products.length);
    await Promise.all(Array.from({ length: workerCount }, () => worker()));
  } catch (error) {
    for (const product of products) {
      if (product.status === 'checking') setFailure(product, error);
    }
    checked = products.length;
    onProgress({ total: products.length, checked, live, current: null });
  }

  const durationMs = Date.now() - startedAt;
  return { checked, live, durationMs, productsPerSecond: durationMs > 0 ? checked / (durationMs / 1000) : 0, concurrency: productConcurrency };
}
