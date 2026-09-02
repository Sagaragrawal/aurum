/**
 * Myntra product collector
 * Runs batch refresh for Myntra products only.
 */

import * as store from './store.js';
import { tryHttpFastPath } from '../http-fast-path.js';
import { applyProductDetails, mergeListingProduct, myntraProductId, normalizeListingProduct, qualificationReasons, toPersistedProduct } from './listing.js';

const itemTimeoutMs = Number(process.env.PRODUCT_ITEM_TIMEOUT_MS || 75000);
const launchTimeoutMs = Number(process.env.PRODUCT_BROWSER_LAUNCH_TIMEOUT_MS || 30000);
const pageReadyTimeoutMs = Number(process.env.PRODUCT_PAGE_READY_TIMEOUT_MS || 45000);
const productConcurrency = Math.max(1, Math.min(24, Number(process.env.PRODUCT_MYNTRA_CONCURRENCY || process.env.PRODUCT_CONCURRENCY || 6)));
const plpEnabled = process.env.PRODUCT_MYNTRA_PLP_FLOW !== '0';
const plpRows = Math.max(10, Math.min(50, Number(process.env.PRODUCT_MYNTRA_PLP_ROWS || 50)));
const plpOffsetStep = Math.max(1, Number(process.env.PRODUCT_MYNTRA_PLP_OFFSET_STEP || 10));
const plpMaxRequests = Math.max(1, Number(process.env.PRODUCT_MYNTRA_PLP_MAX_REQUESTS || 95));
const plpEmptyStop = Math.max(1, Number(process.env.PRODUCT_MYNTRA_PLP_EMPTY_STOP || 4));
const plpNoNewStop = Math.max(1, Number(process.env.PRODUCT_MYNTRA_PLP_NO_NEW_STOP || 8));
const plpDetailLimit = Math.max(0, Number(process.env.PRODUCT_MYNTRA_PLP_DETAIL_LIMIT || 500));
const plpDetailConcurrency = Math.max(1, Math.min(16, Number(process.env.PRODUCT_MYNTRA_PLP_DETAIL_CONCURRENCY || 8)));
const plpRequestDelayMs = Math.max(0, Number(process.env.PRODUCT_MYNTRA_PLP_REQUEST_DELAY_MS || 150));
const plpBootstrapTimeoutMs = Math.max(1000, Number(process.env.PRODUCT_MYNTRA_PLP_BOOTSTRAP_TIMEOUT_MS || 8000));
const plpSearchConcurrency = Math.max(1, Math.min(16, Number(process.env.PRODUCT_MYNTRA_PLP_SEARCH_CONCURRENCY || 12)));
const plpStreams = [null, 'price_asc', 'price_desc', 'popularity', 'new'];
const plpOffsets = [0, 97, 194, 196, 198, 200, 250, 294, 300, 193, 195, 197, 199, 245, 249, 291, 299, 343, 349];

const withTimeout = (promise, milliseconds, label = 'operation') => {
  const timeout = new Promise((_, reject) => {
    const id = setTimeout(() => reject(new Error(`${label} timed out after ${milliseconds}ms`)), milliseconds);
    id.unref?.();
  });
  return Promise.race([promise, timeout]);
};

const launchProductBrowser = (playwright, headless, browserName = 'firefox') => playwright[browserName].launch({ headless });

const persistentBrowsers = new Map();
const getPersistentBrowser = async (playwright, mode, headless, browserName = 'firefox') => {
  const browserKey = `${browserName}:${mode}`;
  const existing = persistentBrowsers.get(browserKey);
  if (existing) {
    const browser = await existing.catch(() => null);
    if (browser?.isConnected()) return browser;
    persistentBrowsers.delete(browserKey);
  }
  const promise = withTimeout(launchProductBrowser(playwright, headless, browserName), launchTimeoutMs, `product ${browserName} ${mode} browser launch`);
  persistentBrowsers.set(browserKey, promise);
  try { return await promise; } catch (error) { persistentBrowsers.delete(browserKey); throw error; }
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
  product.status = unavailable ? 'unavailable' : hasLastKnownValue ? 'stale' : 'unverified';
  if (unavailable) {
    product.price = null;
    product.couponPrice = null;
  }
  product.checkedAt = new Date().toISOString();
  product.error = error?.message || 'product details not found';
  return product;
};

const listingPages = new WeakMap();
const getListingPage = async (browser) => {
  let page = listingPages.get(browser);
  if (!page || page.isClosed()) {
    const context = await browser.newContext({ locale: 'en-IN', timezoneId: 'Asia/Kolkata', viewport: { width: 1366, height: 900 } });
    page = await context.newPage();
    listingPages.set(browser, page);
  }
  if (!page.url().startsWith('https://www.myntra.com/')) {
    await page.goto('https://www.myntra.com/gold-coin', { waitUntil: 'commit', timeout: plpBootstrapTimeoutMs });
  }
  await page.reload({ waitUntil: 'commit', timeout: plpBootstrapTimeoutMs });
  return page;
};

const fetchJsonInPage = async (page, path) => {
  const result = await page.evaluate(async (requestPath) => {
    const response = await fetch(requestPath, {
      credentials: 'include',
      cache: 'no-store',
      headers: { accept: 'application/json', 'x-myntraweb': 'Yes', 'x-requested-with': 'browser', 'x-meta-app': 'channel=web' }
    });
    return { status: response.status, body: await response.text() };
  }, path);
  if (result.status < 200 || result.status >= 300) {
    const error = new Error(`Myntra listing HTTP ${result.status}`);
    error.status = result.status;
    throw error;
  }
  return JSON.parse(result.body);
};

const getPincodeInPage = (page) => page.evaluate(() => {
  try {
    const value = document.cookie.split('; ').find((entry) => entry.startsWith('mynt-ulc='))
      || document.cookie.split('; ').find((entry) => entry.startsWith('mynt-ulc-api='));
    return decodeURIComponent(value || '').match(/pincode:(\d{6})/)?.[1] || null;
  } catch {
    return null;
  }
});

const listingResult = (payload) => payload?.products ? payload : Object.values(payload || {}).find((value) => Array.isArray(value?.products));

const runPool = async (items, concurrency, worker) => {
  let nextIndex = 0;
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (nextIndex < items.length) await worker(items[nextIndex++]);
  }));
};

const mergeCandidateWithTracked = (candidate, tracked) => ({
  ...candidate,
  grams: tracked?.manuallyEditedAt && tracked?.grams ? tracked.grams : candidate.grams || tracked?.grams || null,
  karat: candidate.karat || tracked?.karat || null,
  purity: tracked?.manuallyEditedAt && tracked?.purity ? tracked.purity : candidate.purity || tracked?.purity || null,
  metal: candidate.metal || (/gold/i.test(`${tracked?.name || ''} ${tracked?.purity || ''}`) ? 'gold' : null)
});

async function refreshProductBatchFromListings(products, settings, onProgress) {
  const startedAt = Date.now();
  const playwright = await import('playwright');
  const headless = settings.productBulkHeadless ? true : !settings.productDebugVisibleBrowser && !settings.productForceVisibleBrowser;
  const browser = await getPersistentBrowser(playwright, headless ? 'headless' : 'visible', headless, 'firefox');
  const page = await getListingPage(browser);
  const candidates = new Map();
  let requests = 0;
  let expectedRecords = 0;
  const cookiePincode = await getPincodeInPage(page);
  const configuredPincode = String(settings.pincode || '').match(/^\d{6}$/)?.[0] || null;
  const pincode = cookiePincode || configuredPincode;

  const jobs = plpStreams.flatMap((sort) => plpOffsets.map((offset) => ({ sort, offset }))).slice(0, plpMaxRequests);
  await runPool(jobs, plpSearchConcurrency, async ({ sort, offset }) => {
    const search = new URL('/gateway/v4/search/gold-coin', 'https://www.myntra.com');
    search.searchParams.set('rows', String(plpRows));
    search.searchParams.set('o', String(offset));
    search.searchParams.set('p', String(Math.max(1, Math.floor(offset / plpRows) + 1)));
    search.searchParams.set('plaEnabled', 'true');
    search.searchParams.set('xdEnabled', 'false');
    search.searchParams.set('isFacet', 'true');
    if (sort) search.searchParams.set('sort', sort);
    if (pincode) search.searchParams.set('pincode', pincode);
    const result = listingResult(await fetchJsonInPage(page, `${search.pathname}${search.search}`));
    requests += 1;
    const organic = result?.products || [];
    const pla = result?.plaProducts || [];
    expectedRecords = Number(result?.totalCount) || expectedRecords;
    const before = candidates.size;
    for (const raw of [...organic, ...pla]) {
      const candidate = normalizeListingProduct(raw, `search:${sort || 'default'}:${offset}`);
      if (!candidate.productId || !candidate.url || !candidate.price) continue;
      candidates.set(candidate.productId, mergeListingProduct(candidates.get(candidate.productId), candidate));
    }
    const gained = candidates.size - before;
    onProgress({ total: expectedRecords || products.length, checked: Math.min(candidates.size, products.length), live: Math.min(candidates.size, products.length), failed: 0, current: `catalogue ${sort || 'default'} offset ${offset} (+${gained})`, event: { id: `${Date.now()}-myntra-plp-${sort || 'default'}-${offset}`, store: 'myntra.com', phase: 'plp-probe', method: 'myntra-plp', sort: sort || 'default', offset, gained, unique: candidates.size } });
    if (plpRequestDelayMs) await page.waitForTimeout(plpRequestDelayMs);
  });

  const trackedById = new Map(products.map((product) => [myntraProductId(product), product]));
  const details = [...candidates.values()]
    .filter((candidate) => candidate.metal === 'gold' && (!candidate.grams || !candidate.karat || (candidate.karat === 24 && !candidate.purity)))
    .slice(0, plpDetailLimit);
  await runPool(details, plpDetailConcurrency, async (candidate) => {
    try {
      applyProductDetails(candidate, await fetchJsonInPage(page, `/gateway/v2/product/${candidate.productId}`));
      requests += 1;
    } catch (error) {
      if ([403, 429].includes(error.status)) throw error;
    }
    if (plpRequestDelayMs) await page.waitForTimeout(plpRequestDelayMs);
  });

  const output = [];
  let live = 0;
  let discovered = 0;
  for (const candidate of candidates.values()) {
    const tracked = trackedById.get(candidate.productId);
    const qualified = mergeCandidateWithTracked(candidate, tracked);
    if (qualificationReasons(qualified).length) continue;
    const next = tracked || toPersistedProduct(qualified);
    Object.assign(next, { name: qualified.name || next.name, brand: qualified.brand || next.brand, grams: qualified.grams, karat: qualified.karat, purity: qualified.purity || next.purity, price: qualified.price, couponPrice: qualified.couponPrice, url: qualified.url, canonicalUrl: qualified.url, checkedAt: new Date().toISOString(), lastLiveAt: new Date().toISOString(), status: 'live', refreshMethod: 'myntra-plp' });
    delete next.error;
    output.push(next);
    live += 1;
    if (tracked) trackedById.delete(candidate.productId);
    else discovered += 1;
  }
  for (const product of trackedById.values()) {
    product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'unverified';
    product.checkedAt = new Date().toISOString();
    product.error = 'Not observed in current Myntra listing scan; last-known data preserved.';
    output.push(product);
  }
  const stale = output.filter((product) => product.status === 'stale').length;
  onProgress({ total: output.length, checked: output.length, live, stale, failed: 0, current: null, products: output });
  return { products: output, summary: { checked: output.length, live, stale, failed: 0, discovered, observed: candidates.size, expected: expectedRecords, requests, durationMs: Date.now() - startedAt, method: 'myntra-plp' } };
}

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
      status: 'live'
    });
    product.refreshMethod = extracted.refreshMethod || 'browser';
    delete product.error;
    return product;
  } catch (error) {
    const failedProduct = setFailure(product, error);
    if (settings.productFinalFallback && failedProduct.status === 'stale') failedProduct.status = 'failed';
    return failedProduct;
  } finally {
    if (!options.browser && !options.getBrowser) await browser?.close().catch(() => {});
  }
}

export async function refreshProductBatch(products, settings = {}, onProgress = () => {}) {
  if (!products.length) return { checked: 0, live: 0 };
  if (plpEnabled && !settings.myntraTargetedRefresh) {
    try {
      const result = await refreshProductBatchFromListings(products, settings, onProgress);
      products.splice(0, products.length, ...result.products);
      const fallbackTargets = products.filter((product) => ['stale', 'unverified'].includes(product.status));
      if (fallbackTargets.length) await refreshProductBatch(fallbackTargets, { ...settings, myntraTargetedRefresh: true, productFinalFallback: true }, onProgress);
      return result.summary;
    } catch (error) {
      const message = `Myntra catalogue scan unavailable (${error.message}); existing values preserved without PDP fallback.`;
      for (const product of products) {
        if (product.status === 'checking') product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'unverified';
        product.lastAttemptAt = new Date().toISOString();
        if (product.status === 'live') product.lastAttemptError = message;
        else product.error = message;
      }
      onProgress({ total: products.length, checked: products.length, live: 0, failed: 0, current: null, blocked: true, note: message });
      return { checked: products.length, live: 0, failed: 0, stale: products.filter((product) => product.status === 'stale').length, unverified: products.filter((product) => product.status === 'unverified').length, durationMs: 0, blocked: true, partial: true, method: 'myntra-plp' };
    }
  }

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
