/**
 * Flipkart product collector
 * Runs batch refresh for Flipkart products only.
 */

import * as store from './store.js';
import { tryHttpFastPath } from '../http-fast-path.js';
import { flipkartProductId, mergeListingProduct, normalizeListingProduct, qualificationReasons, toPersistedProduct } from './listing.js';

const itemTimeoutMs = Number(process.env.PRODUCT_ITEM_TIMEOUT_MS || 75000);
const launchTimeoutMs = Number(process.env.PRODUCT_BROWSER_LAUNCH_TIMEOUT_MS || 30000);
const pageReadyTimeoutMs = Number(process.env.PRODUCT_PAGE_READY_TIMEOUT_MS || 45000);
const productConcurrency = Math.max(1, Math.min(24, Number(process.env.PRODUCT_FLIPKART_CONCURRENCY || process.env.PRODUCT_CONCURRENCY || 6)));
const plpEnabled = process.env.PRODUCT_FLIPKART_PLP_FLOW !== '0';
const plpMaxPages = Math.max(1, Math.min(30, Number(process.env.PRODUCT_FLIPKART_PLP_MAX_PAGES || 30)));
const plpDelayMs = Math.max(0, Number(process.env.PRODUCT_FLIPKART_PLP_REQUEST_DELAY_MS || 150));
const listingUrl = 'https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DGold&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold';
const isMinutesProduct = (product) => {
  try {
    return new URL(product?.url || '').searchParams.get('marketplace')?.toUpperCase() === 'HYPERLOCAL';
  } catch {
    return false;
  }
};

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

const listingPages = new WeakMap();
const getListingPage = async (browser) => {
  let page = listingPages.get(browser);
  if (!page || page.isClosed()) {
    const context = await browser.newContext({
      locale: 'en-IN',
      timezoneId: 'Asia/Kolkata',
      viewport: { width: 1366, height: 900 }
    });
    page = await context.newPage();
    listingPages.set(browser, page);
  }
  return page;
};

const extractListingCards = (page, url, fetchPage = false) => page.evaluate(async ({ requestUrl, fetchPage }) => {
  const source = fetchPage
    ? new DOMParser().parseFromString(await (await fetch(requestUrl, { credentials: 'include', cache: 'no-store', headers: { accept: 'text/html' } })).text(), 'text/html')
    : document;
  const text = (source.body?.innerText || source.body?.textContent || '').replace(/\s+/g, ' ').trim();
  const expected = Number(text.match(/(?:Showing\s+[\d,]+\s*[--]\s*[\d,]+\s+(?:products?\s+)?of\s+|\bof\s+)([\d,]+)\s*products?/i)?.[1]?.replaceAll(',', '')) || null;
  const records = new Map();
  for (const candidate of source.querySelectorAll('[data-id^="CON"], a[href*="/p/"][href*="pid=CON"]')) {
    const anchor = candidate.matches('a') ? candidate : candidate.querySelector('a[href*="/p/"][href*="pid="]');
    const productId = candidate.getAttribute('data-id') || new URL(anchor?.href || '', location.origin).searchParams.get('pid');
    if (!productId || records.has(productId)) continue;
    const card = candidate.matches('[data-id]') ? candidate : candidate.closest('[data-id]') || anchor?.parentElement;
    const cardText = (card?.innerText || card?.textContent || '').replace(/\s+/g, ' ').trim();
    const titles = [...(card?.querySelectorAll('a[title]') || [])].map((item) => item.getAttribute('title')?.trim()).filter((title) => title?.length > 5);
    const name = titles.sort((left, right) => right.length - left.length)[0] || cardText.split(/(?=₹)/)[0]?.trim() || '';
    const values = [...(card?.querySelectorAll('*') || [])].filter((item) => !item.children.length && /^₹\s*[\d,]+(?:\.\d+)?$/.test(item.textContent?.trim() || '')).map((item) => item.textContent.trim());
    const price = values[0] || cardText.match(/₹\s*[\d,]+(?:\.\d+)?/)?.[0] || null;
    records.set(productId, { productId, name, brand: card?.querySelector('.Fo1I0b')?.textContent?.trim() || '', price, url: anchor?.href || null });
  }
  return { expected, records: [...records.values()] };
}, { requestUrl: url, fetchPage });

const listingPageUrl = (sort, pageNumber) => {
  const url = new URL(listingUrl);
  if (sort) url.searchParams.set('sort', sort);
  if (pageNumber > 1) url.searchParams.set('page', String(pageNumber));
  return url.href;
};

async function refreshProductBatchFromListings(products, settings, onProgress) {
  const startedAt = Date.now();
  const playwright = await import('playwright');
  const headless = settings.productBulkHeadless ? true : !settings.productDebugVisibleBrowser && !settings.productForceVisibleBrowser;
  const browser = await getPersistentBrowser(playwright, headless ? 'headless' : 'visible', headless);
  const page = await getListingPage(browser);
  await page.goto(listingPageUrl(null, 1), { waitUntil: 'domcontentloaded', timeout: pageReadyTimeoutMs });
  await page.reload({ waitUntil: 'domcontentloaded', timeout: pageReadyTimeoutMs });
  const candidates = new Map();
  let expected = 0;
  let requests = 0;
  for (const stream of [{ name: 'default', sort: null }, { name: 'price_low', sort: 'price_asc' }, { name: 'price_high', sort: 'price_desc' }]) {
    let emptyPages = 0;
    for (let pageNumber = 1; pageNumber <= plpMaxPages; pageNumber += 1) {
      const result = await extractListingCards(page, listingPageUrl(stream.sort, pageNumber), !(stream.sort === null && pageNumber === 1));
      requests += 1;
      expected = expected || result.expected || 0;
      const before = candidates.size;
      for (const raw of result.records) {
        const candidate = normalizeListingProduct(raw, stream.name);
        if (candidate.productId && candidate.url && candidate.price) candidates.set(candidate.productId, mergeListingProduct(candidates.get(candidate.productId), candidate));
      }
      const gained = candidates.size - before;
      emptyPages = result.records.length ? 0 : emptyPages + 1;
      onProgress({ total: expected || products.length, checked: Math.min(candidates.size, products.length), live: Math.min(candidates.size, products.length), failed: 0, current: `catalogue ${stream.name} page ${pageNumber} (+${gained})`, event: { id: `${Date.now()}-flipkart-plp-${stream.name}-${pageNumber}`, store: 'flipkart.com', phase: 'plp-page', method: 'flipkart-plp', page: pageNumber, gained, unique: candidates.size } });
      if (emptyPages >= 2 || (expected && candidates.size >= expected)) break;
      if (plpDelayMs) await page.waitForTimeout(plpDelayMs);
    }
    if (expected && candidates.size >= expected) break;
  }
  const tracked = new Map(products.map((product) => [flipkartProductId(product), product]));
  const output = [];
  let live = 0;
  let discovered = 0;
  for (const candidate of candidates.values()) {
    const current = tracked.get(candidate.productId);
    const qualified = { ...candidate, grams: current?.manuallyEditedAt && current.grams ? current.grams : candidate.grams || current?.grams || null, karat: candidate.karat || current?.karat || null, purity: current?.manuallyEditedAt && current.purity ? current.purity : candidate.purity || current?.purity || null };
    if (qualificationReasons(qualified).length) continue;
    const next = current || toPersistedProduct(qualified);
    Object.assign(next, { name: qualified.name || next.name, brand: qualified.brand || next.brand, grams: qualified.grams, karat: qualified.karat, purity: qualified.purity || next.purity, price: qualified.price, url: qualified.url, canonicalUrl: qualified.url, checkedAt: new Date().toISOString(), lastLiveAt: new Date().toISOString(), status: 'live', refreshMethod: 'flipkart-plp' });
    delete next.error;
    output.push(next);
    live += 1;
    if (current) tracked.delete(candidate.productId);
    else discovered += 1;
  }
  for (const product of tracked.values()) {
    product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'unverified';
    product.checkedAt = new Date().toISOString();
    product.error = 'Not observed in current Flipkart listing scan; last-known data preserved.';
    output.push(product);
  }
  onProgress({ total: output.length, checked: output.length, live, failed: 0, current: null, products: output });
  return { products: output, summary: { checked: output.length, live, stale: output.length - live, failed: 0, discovered, observed: candidates.size, expected, requests, durationMs: Date.now() - startedAt, method: 'flipkart-plp' } };
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
    if (!isMinutesProduct(product) && !settings.productDebugVisibleBrowser && !settings.productForceVisibleBrowser) {
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
    const failedProduct = setFailure(product, error);
    if (settings.productFinalFallback && failedProduct.status === 'stale') failedProduct.status = 'failed';
    return failedProduct;
  } finally {
    if (!options.browser && !options.getBrowser) await browser?.close().catch(() => {});
  }
}

export async function refreshProductBatch(products, settings = {}, onProgress = () => {}) {
  if (!products.length) return { checked: 0, live: 0 };
  if (plpEnabled && !settings.flipkartTargetedRefresh) {
    const mainWebsiteProducts = products.filter((product) => !isMinutesProduct(product));
    const minutesProducts = products.filter(isMinutesProduct);
    let listingSummary = { checked: 0, live: 0, stale: 0, failed: 0, discovered: 0, observed: 0, expected: 0, requests: 0, method: 'flipkart-plp' };
    if (mainWebsiteProducts.length) {
      try {
        const result = await refreshProductBatchFromListings(mainWebsiteProducts, settings, onProgress);
        mainWebsiteProducts.splice(0, mainWebsiteProducts.length, ...result.products);
        const fallbackTargets = mainWebsiteProducts.filter((product) => ['stale', 'unverified'].includes(product.status));
        if (fallbackTargets.length) {
          await refreshProductBatch(fallbackTargets, { ...settings, flipkartTargetedRefresh: true, productFinalFallback: true }, onProgress);
        }
        listingSummary = result.summary;
      } catch (error) {
        const message = `Flipkart catalogue scan unavailable (${error.message}); existing website values preserved without PDP fallback.`;
        for (const product of mainWebsiteProducts) {
          if (product.status === 'checking') product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'unverified';
          product.lastAttemptAt = new Date().toISOString();
          if (product.status === 'live') product.lastAttemptError = message;
          else product.error = message;
        }
        onProgress({ total: mainWebsiteProducts.length, checked: mainWebsiteProducts.length, live: 0, failed: 0, current: null, blocked: true, note: message });
        listingSummary = { checked: mainWebsiteProducts.length, live: 0, failed: 0, stale: mainWebsiteProducts.filter((product) => product.status === 'stale').length, unverified: mainWebsiteProducts.filter((product) => product.status === 'unverified').length, blocked: true, partial: true, method: 'flipkart-plp' };
        await refreshProductBatch(mainWebsiteProducts, { ...settings, flipkartTargetedRefresh: true, productFinalFallback: true }, onProgress);
      }
    }
    const minutesSummary = minutesProducts.length
      ? await refreshProductBatch(minutesProducts, { ...settings, flipkartTargetedRefresh: true }, onProgress)
      : { checked: 0, live: 0, failed: 0 };
    products.splice(0, products.length, ...mainWebsiteProducts, ...minutesProducts);
    return {
      checked: Number(listingSummary.checked || 0) + Number(minutesSummary.checked || 0),
      live: Number(listingSummary.live || 0) + Number(minutesSummary.live || 0),
      stale: Number(listingSummary.stale || 0) + Number(minutesSummary.stale || 0),
      failed: Number(listingSummary.failed || 0) + Number(minutesSummary.failed || 0),
      discovered: Number(listingSummary.discovered || 0),
      observed: Number(listingSummary.observed || 0),
      expected: Number(listingSummary.expected || 0),
      requests: Number(listingSummary.requests || 0),
      durationMs: Number(listingSummary.durationMs || 0) + Number(minutesSummary.durationMs || 0),
      method: minutesProducts.length ? 'flipkart-plp+minutes' : 'flipkart-plp',
      partial: Boolean(listingSummary.partial || minutesSummary.partial),
      blocked: Boolean(listingSummary.blocked || minutesSummary.blocked)
    };
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
