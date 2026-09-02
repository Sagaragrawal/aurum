/**
 * Ajio product collector
 * Runs batch refresh for Ajio products only.
 */

import * as store from './store.js';
import {
  ajioProductCode,
  applyDetailQualifiers,
  applyPurityFacet,
  detailQualifiers,
  mergeListingProduct,
  normalizeListingProduct,
  qualificationReasons,
  toPersistedProduct
} from './listing.js';
import { collectAjioCards, ensureAjioBrowserRuntime, fetchAjioJson, updateAjioBrowserRuntime } from './browser-runtime.js';

const STORE_DOMAIN = 'ajio.com';

const itemTimeoutMs = Number(process.env.PRODUCT_AJIO_ITEM_TIMEOUT_MS || 25000);
const launchTimeoutMs = Number(process.env.PRODUCT_BROWSER_LAUNCH_TIMEOUT_MS || 30000);
const pageReadyTimeoutMs = Number(process.env.PRODUCT_AJIO_PAGE_READY_TIMEOUT_MS || 12000);
const browserSuccessLimit = Math.max(1, Number(process.env.PRODUCT_AJIO_BROWSER_SUCCESS_LIMIT || 5000));
const browserFailureLimit = Math.max(1, Number(process.env.PRODUCT_AJIO_BROWSER_FAILURE_LIMIT || 10));
const blockedCooldownMs = Math.max(30000, Number(process.env.PRODUCT_AJIO_403_COOLDOWN_MS || 120000));
let blockedUntil = 0;
const productConcurrency = Math.max(1, Math.min(24, Number(process.env.PRODUCT_AJIO_CONCURRENCY || process.env.PRODUCT_CONCURRENCY || 6)));
const plpEnabled = process.env.PRODUCT_AJIO_PLP_FLOW !== '0';
const plpCategoryIds = String(process.env.PRODUCT_AJIO_PLP_CATEGORIES || '830306012,830306009').split(',').map((value) => value.trim()).filter(Boolean);
const plpRequestTimeoutMs = Math.max(1000, Number(process.env.PRODUCT_AJIO_PLP_REQUEST_TIMEOUT_MS || 12000));
const plpRequestAttempts = Math.max(1, Number(process.env.PRODUCT_AJIO_PLP_REQUEST_ATTEMPTS || 3));
const plpRequestDelayMs = Math.max(0, Number(process.env.PRODUCT_AJIO_PLP_REQUEST_DELAY_MS || 300));
const plpDetailLimit = Math.max(0, Number(process.env.PRODUCT_AJIO_PLP_DETAIL_LIMIT || 50));
const plpMinimumCoverage = Math.max(0.1, Math.min(1, Number(process.env.PRODUCT_AJIO_PLP_MINIMUM_COVERAGE || 0.8)));
const withTimeout = (promise, milliseconds, label = 'operation') => {
  const timeout = new Promise((_, reject) => {
    const id = setTimeout(() => reject(new Error(`${label} timed out after ${milliseconds}ms`)), milliseconds);
    id.unref?.();
  });
  return Promise.race([promise, timeout]);
};

const launchProductBrowser = (playwright, headless, browserName = 'firefox') => playwright[browserName].launch({ headless });

const fetchJsonInPage = async (page, url) => {
  const result = await fetchAjioJson(page, url, plpRequestTimeoutMs);
  if (!result.ok) {
    const error = new Error(result.status ? `AJIO listing HTTP ${result.status}` : result.error || 'AJIO listing request failed');
    error.status = result.status;
    throw error;
  }
  return JSON.parse(result.body);
};

const fetchJsonWithRetry = async (page, url) => {
  let lastError;
  for (let attempt = 1; attempt <= plpRequestAttempts; attempt += 1) {
    try {
      return await fetchJsonInPage(page, url);
    } catch (error) {
      lastError = error;
      if ([403, 404, 429].includes(error.status) || attempt === plpRequestAttempts) break;
      await page.waitForTimeout(500 * attempt);
    }
  }
  throw lastError;
};

const categoryUrl = (categoryId, pageNumber, query = ':relevance') => {
  const url = new URL(`/api/category/${categoryId}`, `https://www.${STORE_DOMAIN}`);
  url.searchParams.set('fields', 'SITE');
  url.searchParams.set('currentPage', String(pageNumber));
  url.searchParams.set('pageSize', '45');
  url.searchParams.set('format', 'json');
  url.searchParams.set('query', query);
  url.searchParams.set('gridColumns', '3');
  return url.href;
};

const persistentListingPages = new WeakMap();
const getPersistentListingPage = async (context) => {
  let page = persistentListingPages.get(context);
  if (!page || page.isClosed()) {
    page = await context.newPage();
    persistentListingPages.set(context, page);
  }
  return page;
};

const loadCategoryBootstrap = async (page, categoryId) => {
  await page.goto(`https://www.${STORE_DOMAIN}/c/${categoryId}`, { waitUntil: 'commit', timeout: launchTimeoutMs });
  await page.reload({ waitUntil: 'commit', timeout: launchTimeoutMs });
  await page.waitForFunction(
    () => Object.keys(globalThis.__PRELOADED_STATE__?.grid?.entities || {}).length > 0
      || /access denied|captcha|request blocked|blocked due to security reasons/i.test(document.body?.innerText || ''),
    undefined,
    { timeout: pageReadyTimeoutMs }
  ).catch(() => {});
  const bootstrap = await page.evaluate(() => {
    const state = globalThis.__PRELOADED_STATE__;
    const request = state?.request;
    const products = Object.values(state?.grid?.entities || {});
    return {
      products,
      totalResults: Number(request?.totalResults) || products.length,
      pageSize: Number(request?.query?.pageSize) || 45,
      query: String(request?.query?.query || ':relevance'),
      bodyText: String(document.body?.innerText || '').slice(0, 2000)
    };
  });
  if (/access denied|captcha|request blocked|blocked due to security reasons/i.test(bootstrap.bodyText)) {
    throw new Error('AJIO listing access blocked');
  }
  if (!bootstrap.products.length) throw new Error(`AJIO category ${categoryId} did not expose preloaded products`);
  return {
    products: bootstrap.products,
    facets: [],
    pagination: {
      currentPage: 0,
      pageSize: bootstrap.pageSize,
      totalResults: bootstrap.totalResults,
      totalPages: Math.max(1, Math.ceil(bootstrap.totalResults / bootstrap.pageSize))
    },
    query: bootstrap.query
  };
};

const mergeCandidateWithTracked = (candidate, tracked) => ({
  ...candidate,
  grams: tracked?.manuallyEditedAt && tracked?.grams ? tracked.grams : candidate.grams || tracked?.grams || null,
  purity: tracked?.manuallyEditedAt && tracked?.purity ? tracked.purity : candidate.purity || tracked?.purity || null,
  karat: candidate.karat || tracked?.karat || null,
  metal: candidate.metal || (/gold/i.test(`${tracked?.name || ''} ${tracked?.purity || ''}`) ? 'gold' : null)
});

async function refreshProductBatchFromListings(products, settings, onProgress) {
  const startedAt = Date.now();
  const headless = settings.productBulkHeadless
    ? true
    : !settings.productDebugVisibleBrowser && !settings.productForceVisibleBrowser;
  const mode = headless ? 'headless' : 'visible';
  const playwright = await import('playwright');
  const browser = await getPersistentBrowser(playwright, mode, headless, 'firefox');
  const context = await store.getPersistentContext(browser, settings);
  const page = await getPersistentListingPage(context);
  await ensureAjioBrowserRuntime(page);
  const candidates = new Map();
  const categoryRoots = new Map();
  let requestCount = 0;
  let checkedPages = 0;
  let totalPages = 0;
  let expectedRecords = 0;
  let usedDomFallback = false;

  const request = async (url) => {
    requestCount += 1;
    const result = await fetchJsonWithRetry(page, url);
    if (plpRequestDelayMs) await page.waitForTimeout(plpRequestDelayMs);
    return result;
  };

  try {
    for (const categoryId of plpCategoryIds) {
      await updateAjioBrowserRuntime(page, { status: 'running', phase: 'category-bootstrap', categoryId, page: 0 });
      const firstPage = await loadCategoryBootstrap(page, categoryId);
      categoryRoots.set(categoryId, firstPage);
      const pageCount = Number(firstPage.pagination?.totalPages) || 1;
      expectedRecords += Number(firstPage.pagination?.totalResults) || firstPage.products.length;
      totalPages += pageCount;
      for (let pageNumber = 0; pageNumber < pageCount; pageNumber += 1) {
        await updateAjioBrowserRuntime(page, { status: 'running', phase: 'listing', categoryId, page: pageNumber });
        let pageData;
        try {
          pageData = pageNumber === 0 ? firstPage : await request(categoryUrl(categoryId, pageNumber, firstPage.query));
        } catch (error) {
          if (!isHttp403(error?.message)) throw error;
          usedDomFallback = true;
          const cards = await collectAjioCards(page, { maxBatches: 150, noGrowthLimit: 18, delayMs: 600 }, (domProgress) => {
            onProgress({
              total: Number(firstPage.pagination?.totalResults) || products.length,
              checked: domProgress.observed,
              live: domProgress.observed,
              failed: 0,
              current: `${categoryId} DOM batch ${domProgress.batch} (${domProgress.noGrowth} quiet)`,
              blocked: true,
              note: 'Direct AJIO pagination was blocked; collecting the loaded page with visible JavaScript.',
              event: { id: `${Date.now()}-ajio-dom-${categoryId}-${domProgress.batch}`, store: STORE_DOMAIN, phase: 'plp-dom-progress', method: 'ajio-plp-dom', categoryId, batch: domProgress.batch, unique: domProgress.observed }
            });
          });
          for (const raw of cards) {
            const candidate = normalizeListingProduct(raw, `category:${categoryId}:dom-scroll`);
            if (!candidate.ajioCode || !candidate.url || !candidate.price) continue;
            candidates.set(candidate.ajioCode, mergeListingProduct(candidates.get(candidate.ajioCode), candidate));
          }
          onProgress({
            total: totalPages,
            checked: checkedPages + 1,
            live: candidates.size,
            failed: 0,
            current: `${categoryId} DOM fallback (${cards.length} cards)`,
            blocked: true,
            note: 'Direct AJIO pagination was blocked; continued with the loaded page scroll extractor.',
            event: { id: `${Date.now()}-ajio-dom-${categoryId}`, store: STORE_DOMAIN, phase: 'plp-dom-fallback', method: 'ajio-plp-dom', categoryId, unique: candidates.size }
          });
          break;
        }
        for (const raw of pageData.products || []) {
          const candidate = normalizeListingProduct(raw, `category:${categoryId}:page:${pageNumber}`);
          if (!candidate.ajioCode || !candidate.url || !candidate.price) continue;
          candidates.set(candidate.ajioCode, mergeListingProduct(candidates.get(candidate.ajioCode), candidate));
        }
        checkedPages += 1;
        onProgress({
          total: totalPages,
          checked: checkedPages,
          live: candidates.size,
          failed: 0,
          current: `${categoryId} page ${pageNumber + 1}/${pageCount}`,
          event: { id: `${Date.now()}-ajio-plp-${categoryId}-${pageNumber}`, store: STORE_DOMAIN, phase: 'plp-page', method: 'ajio-plp', categoryId, page: pageNumber, unique: candidates.size }
        });
      }
    }

    const trackedByCode = new Map(products.map((product) => [ajioProductCode(product), product]));
    for (const candidate of candidates.values()) {
      const tracked = trackedByCode.get(candidate.ajioCode);
      if (!tracked) continue;
      if (!candidate.grams && Number(tracked.grams) > 0) candidate.grams = Number(tracked.grams);
      if (!candidate.karat && Number(tracked.karat) > 0) candidate.karat = Number(tracked.karat);
      if (!candidate.purity && tracked.purity) candidate.purity = tracked.purity;
      if (!candidate.metal && !/silver|platinum|gold[- ]?plated|brass|copper/i.test(`${tracked.name || ''} ${tracked.url || ''}`)) candidate.metal = 'gold';
    }

    if (usedDomFallback && candidates.size < expectedRecords * plpMinimumCoverage) {
      const message = `AJIO DOM fallback coverage too low (${candidates.size}/${expectedRecords}); preserving all last-known products.`;
      for (const product of products) {
        if (product.status === 'checking') product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'unverified';
        product.lastAttemptAt = new Date().toISOString();
        product.error = message;
      }
      await updateAjioBrowserRuntime(page, { status: 'blocked', phase: 'stopped', observed: candidates.size, expected: expectedRecords, lastError: message });
      onProgress({ total: products.length, checked: products.length, live: 0, stale: products.filter((product) => product.status === 'stale').length, unavailable: products.filter((product) => product.status === 'unavailable').length, failed: 0, current: null, blocked: true, note: message });
      return { products, summary: { checked: products.length, live: 0, stale: products.filter((product) => product.status === 'stale').length, unavailable: products.filter((product) => product.status === 'unavailable').length, failed: 0, discovered: 0, observed: candidates.size, expected: expectedRecords, requests: requestCount, durationMs: Date.now() - startedAt, method: 'ajio-plp-dom', partial: true, blocked: true } };
    }

    for (const [categoryId, root] of categoryRoots) {
      await updateAjioBrowserRuntime(page, { status: 'running', phase: 'purity-facets', categoryId, page: 0 });
      const unresolved = new Set([...candidates.values()]
        .filter((candidate) => candidate.sources.some((source) => source.startsWith(`category:${categoryId}:`)))
        .filter((candidate) => !candidate.karat || !candidate.purity)
        .map((candidate) => candidate.ajioCode));
      const purityFacet = (root.facets || []).find((facet) => facet.code === 'verticalmetalpurity');
      if (!unresolved.size || !purityFacet) continue;
      for (const facetValue of purityFacet.values || []) {
        const query = new URL(facetValue.query?.url || '', `https://www.${STORE_DOMAIN}`).searchParams.get('q');
        if (!query) continue;
        const firstPage = await request(categoryUrl(categoryId, 0, query));
        const pageCount = Number(firstPage.pagination?.totalPages) || 1;
        for (let pageNumber = 0; pageNumber < pageCount; pageNumber += 1) {
          const pageData = pageNumber === 0 ? firstPage : await request(categoryUrl(categoryId, pageNumber, query));
          for (const raw of pageData.products || []) {
            const code = ajioProductCode(raw);
            if (!code || !unresolved.has(code) || !candidates.has(code)) continue;
            applyPurityFacet(candidates.get(code), facetValue.name || facetValue.code);
            unresolved.delete(code);
          }
        }
        if (!unresolved.size) break;
      }
    }

    const unresolvedDetails = [...candidates.values()]
      .filter((candidate) => !candidate.grams || !candidate.karat || !candidate.purity || !candidate.metal)
      .slice(0, plpDetailLimit);
    for (const candidate of unresolvedDetails) {
      await updateAjioBrowserRuntime(page, { status: 'running', phase: 'detail-json', categoryId: null, page: null, productCode: candidate.ajioCode });
      try {
        const payload = await request(new URL(`/api/p/${encodeURIComponent(candidate.ajioCode)}`, `https://www.${STORE_DOMAIN}`).href);
        applyDetailQualifiers(candidate, detailQualifiers(payload, candidate.ajioCode));
      } catch (error) {
        if ([403, 429].includes(error.status)) {
          if (usedDomFallback) break;
          throw error;
        }
      }
    }

    const output = [];
    let live = 0;
    let discovered = 0;
    for (const candidate of candidates.values()) {
      const tracked = trackedByCode.get(candidate.ajioCode);
      const qualified = mergeCandidateWithTracked(candidate, tracked);
      if (qualificationReasons(qualified).length) continue;
      const next = tracked || toPersistedProduct(qualified);
      Object.assign(next, {
        name: qualified.name || next.name,
        brand: qualified.brand || next.brand,
        grams: qualified.grams,
        karat: qualified.karat,
        purity: qualified.purity || next.purity,
        price: qualified.price,
        couponPrice: qualified.couponPrice,
        url: qualified.url,
        canonicalUrl: qualified.url,
        checkedAt: new Date().toISOString(),
        lastLiveAt: new Date().toISOString(),
        status: 'live',
        refreshMethod: 'ajio-plp'
      });
      delete next.error;
      output.push(next);
      live += 1;
      if (!tracked) discovered += 1;
      else trackedByCode.delete(candidate.ajioCode);
    }

    for (const product of trackedByCode.values()) {
      const explicitlyUnavailable = /out of stock|sold out|no longer available|filtered: silver\/platinum/i.test(product.error || '');
      product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : explicitlyUnavailable ? 'unavailable' : 'unverified';
      product.checkedAt = new Date().toISOString();
      product.error = 'Not observed in current AJIO listing scan; last-known data preserved.';
      output.push(product);
    }

    const durationMs = Date.now() - startedAt;
    const stale = output.filter((product) => product.status === 'stale').length;
    const unavailable = output.filter((product) => product.status === 'unavailable').length;
    const failed = output.filter((product) => product.status === 'failed').length;
    await updateAjioBrowserRuntime(page, { status: usedDomFallback ? 'partial' : 'complete', phase: 'complete', categoryId: null, page: null, productCode: null, observed: candidates.size, live, discovered });
    onProgress({ total: output.length, checked: output.length, live, stale, unavailable, failed, current: null, partial: usedDomFallback, note: usedDomFallback ? 'AJIO completed with visible DOM fallback after direct pagination was blocked.' : null, products: output });
    return { products: output, summary: { checked: output.length, live, stale, unavailable, failed, discovered, observed: candidates.size, requests: requestCount, durationMs, productsPerSecond: durationMs > 0 ? output.length / (durationMs / 1000) : 0, method: usedDomFallback ? 'ajio-plp-dom' : 'ajio-plp', partial: usedDomFallback } };
  } catch (error) {
    await updateAjioBrowserRuntime(page, {
      status: isHttp403(error?.message) ? 'blocked' : 'error',
      phase: 'stopped',
      lastError: error?.message || String(error)
    }).catch(() => {});
    throw error;
  } finally {
    if (page.isClosed()) persistentListingPages.delete(context);
  }
}

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

async function restartPersistentBrowser(mode, browsers, browserName = 'firefox') {
  const local = browsers.get(mode);
  browsers.delete(mode);
  const browserKey = `${browserName}:${mode}`;
  const shared = persistentBrowsers.get(browserKey);
  persistentBrowsers.delete(browserKey);
  const promises = [...new Set([local, shared].filter(Boolean))];
  await Promise.all(promises.map(async (promise) => {
    const browser = await Promise.resolve(promise).catch(() => null);
    await browser?.close().catch(() => {});
  }));
}

const isHttp403 = (value) => /(?:HTTP\s*403|listing access blocked|denied browser access|Akamai\s*403|blocked due to security reasons)/i.test(String(value || ''));

const setFailure = (product, error) => {
  const message = error?.message || '';
  const unavailable = /out of stock|sold out|no longer available/i.test(message);
  const hasLastKnownValue = Number.isFinite(product.price) && product.price > 0 && Number(product.grams) > 0;
  product.status = unavailable ? 'unavailable' : hasLastKnownValue ? 'stale' : 'unverified';
  if (unavailable) {
    product.price = null;
    product.couponPrice = null;
  }
  product.checkedAt = new Date().toISOString();
  product.error = message || 'product details not found';
  return product;
};

const setBlocked = (product, message) => {
  if (product.status === 'checking') product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'unverified';
  product.lastAttemptAt = new Date().toISOString();
  if (product.status === 'live') product.lastAttemptError = message;
  else product.error = message;
  return product;
};

async function refreshProduct(product, settings = {}, options = {}) {
  let browser = options.browser;
  const headless = options.headless ?? (store.supportsHeadless && !settings.productDebugVisibleBrowser && !settings.productForceVisibleBrowser);
  try {
    if (!browser && options.getBrowser) browser = await options.getBrowser();
    if (!browser) {
      const playwright = await import('playwright');
      browser = await withTimeout(launchProductBrowser(playwright, headless), launchTimeoutMs, 'product browser launch');
    }
    const extracted = await withTimeout(
      store.refreshProductPage(product, browser, settings, { itemTimeoutMs, pageReadyTimeoutMs }),
      itemTimeoutMs,
      `${product.source || 'store'} product refresh`
    );
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
  if (plpEnabled && !settings.ajioTargetedRefresh) {
    if (Date.now() < blockedUntil) {
      const seconds = Math.ceil((blockedUntil - Date.now()) / 1000);
      const message = `AJIO listing cooldown after HTTP 403 (${seconds}s remaining).`;
      for (const product of products) setBlocked(product, message);
      onProgress({ total: products.length, checked: products.length, live: 0, failed: 0, current: null, blocked: true, note: message });
      return { checked: products.length, live: 0, failed: 0, durationMs: 0, blocked: true, method: 'ajio-plp' };
    }
    try {
      const result = await refreshProductBatchFromListings(products, settings, onProgress);
      products.splice(0, products.length, ...result.products);
      const fallbackTargets = products.filter((product) => ['stale', 'unverified'].includes(product.status));
      if (fallbackTargets.length) await refreshProductBatch(fallbackTargets, { ...settings, ajioTargetedRefresh: true, productFinalFallback: true }, onProgress);
      return result.summary;
    } catch (error) {
      if (!isHttp403(error?.message)) throw error;
      blockedUntil = Date.now() + blockedCooldownMs;
      const message = `${error.message}; preserving last-known prices and cooling down.`;
      for (const product of products) setBlocked(product, message);
      onProgress({ total: products.length, checked: products.length, live: 0, failed: 0, current: null, blocked: true, note: message });
      return { checked: products.length, live: 0, failed: 0, durationMs: 0, blocked: true, method: 'ajio-plp' };
    }
  }

  const startedAt = Date.now();
  const browsers = new Map();
  const lifecycle = new Map();
  let checked = 0;
  let live = 0;
  let failed = 0;
  let stoppedFor403 = false;
  onProgress({ total: products.length, checked, live, failed, current: null });

  try {
    const playwright = await import('playwright');
    const ensureBrowser = async (mode, headless) => {
      if (!browsers.has(mode)) browsers.set(mode, getPersistentBrowser(playwright, mode, headless));
      return browsers.get(mode);
    };
    const stateFor = (mode) => {
      if (!lifecycle.has(mode)) lifecycle.set(mode, { successes: 0, consecutiveFailures: 0 });
      return lifecycle.get(mode);
    };
    const restart = async (mode, reason) => {
      await restartPersistentBrowser(mode, browsers);
      lifecycle.set(mode, { successes: 0, consecutiveFailures: 0 });
      onProgress({ total: products.length, checked, live, failed, current: null, event: { id: `${Date.now()}-ajio-restart`, store: STORE_DOMAIN, phase: 'browser-restart', reason } });
    };

    // A 403 means AJIO rejected this session/request. Do not rotate browsers to
    // circumvent it. Cool down and preserve all last-known values instead.
    if (Date.now() < blockedUntil) {
      const seconds = Math.ceil((blockedUntil - Date.now()) / 1000);
      for (const product of products) setFailure(product, new Error(`AJIO cooldown after HTTP 403 (${seconds}s remaining).`));
      return { checked: products.length, live: 0, failed: products.length, durationMs: Date.now() - startedAt, concurrency: 1, blocked: true };
    }

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
        const state = stateFor(mode);
        onProgress({ total: products.length, checked, live, failed, current: product.name, event: { id: `${Date.now()}-${workerId}-${nextIndex}-start`, store: product.source || STORE_DOMAIN, workerId, phase: 'start', name: product.name } });
        const itemStartedAt = Date.now();

        let result = await refreshProduct(product, settings, { getBrowser: () => ensureBrowser(mode, headless), headless });
        if (result.status !== 'live' && settings.productFallbackVisibleOnFailure && headless && !isHttp403(result.error)) {
          result = await refreshProduct(product, settings, { getBrowser: () => ensureBrowser('visible', false), headless: false });
        }

        checked += 1;
        if (result.status === 'live') {
          live += 1;
          state.successes += 1;
          state.consecutiveFailures = 0;
        } else {
          if (result.status !== 'unavailable') failed += 1;
          state.consecutiveFailures += 1;
        }

        onProgress({ total: products.length, checked, live, failed, current: product.name, updatedProduct: result, event: { id: `${Date.now()}-${workerId}-${nextIndex}-done`, store: product.source || STORE_DOMAIN, workerId, phase: result.status === 'live' ? 'done' : 'failed', name: product.name, status: result.status, method: result.refreshMethod || 'browser', ms: Date.now() - itemStartedAt, error: result.error || null, price: result.price || null, grams: result.grams || null, purity: result.purity || null, karat: result.karat || null } });

        if (isHttp403(result.error)) {
          if (state.consecutiveFailures >= 3) {
            blockedUntil = Date.now() + blockedCooldownMs;
            stoppedFor403 = true;
            await restartPersistentBrowser(mode, browsers);
            for (; nextIndex < products.length; nextIndex += 1) {
              const pending = products[nextIndex];
              setFailure(pending, new Error(`AJIO refresh paused after repeated 403; cooldown ${Math.ceil(blockedCooldownMs / 60000)} minutes.`));
              checked += 1;
              failed += 1;
              onProgress({ total: products.length, checked, live, failed, current: pending.name, updatedProduct: pending });
            }
            break;
          } else {
            await restartPersistentBrowser(mode, browsers);
          }
        }

        if (result.status === 'live' && state.successes >= browserSuccessLimit) {
          await restart(mode, `${browserSuccessLimit} successful AJIO reads`);
        } else if (result.status !== 'live' && state.consecutiveFailures >= 20) {
          await restart(mode, `20 consecutive AJIO failures`);
        }
      }
    };

    const visibleMode = Boolean(settings.productDebugVisibleBrowser || settings.productForceVisibleBrowser);
    const workerCount = visibleMode ? 1 : Math.min(productConcurrency, products.length);
    await Promise.all(Array.from({ length: workerCount }, () => worker()));
  } catch (error) {
    for (const product of products) {
      if (product.status === 'checking') setFailure(product, error);
    }
    checked = products.length;
    onProgress({ total: products.length, checked, live, failed, current: null });
  }

  const durationMs = Date.now() - startedAt;
  return { checked, live, failed, durationMs, productsPerSecond: durationMs > 0 ? checked / (durationMs / 1000) : 0, concurrency: productConcurrency, blocked: stoppedFor403 };
}
