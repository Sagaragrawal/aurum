import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import { fork } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { refreshProductPageFromContext as refreshAmazonFromContext } from './stores/amazon/store.js';
import { refreshProductPageFromContext as refreshFlipkartFromContext } from './stores/flipkart/store.js';
import { refreshProductPageFromContext as refreshMyntraFromContext } from './stores/myntra/store.js';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const scripts = join(root, 'manual_js');
const storeRuntimes = new Map();
const ajioRuntimePath = fileURLToPath(new URL('./ajio-firefox-runtime.js', import.meta.url));
let ajioRuntime = null;
let ajioRequestId = 0;
let runProfileRoot = null;
let runProfileRootPromise = null;
const masterConfigs = {
  'ajio.com': {
    profile: 'ajio'
  },
  'amazon.in': {
    script: 'amazon_gold_master_v14_3_final.js',
    browser: 'firefox',
    profile: 'amazon',
    urls: ['https://www.amazon.in/s?i=jewelry&rh=n%3A2908910031%2Cp_n_material_two_browse-bin%3A2160347031&s=popularity-rank&dc&fs=true&rnid=2160329031&xpid=ZrCUqOwcv7FyR&ref=sr_pg_1'],
    readySelector: '[data-component-type="s-search-result"]',
    exportBinding: { products: 'amazonGold', catalogue: 'amazonCatalogue', missing: 'amazonIncomplete' }
  },
  'flipkart.com': {
    script: 'flipkart_gold_master_final.js',
    browser: 'firefox',
    profile: 'flipkart',
    urls: ['https://www.flipkart.com/gold-silver-coins/pr?sid=mcr%2C73x%2Cydh&marketplace=FLIPKART&p%5B%5D=facets.material%255B%255D%3DYellow%2BGold&p%5B%5D=facets.material%255B%255D%3DGold'],
    exportBinding: { products: 'flipkartGold', catalogue: 'flipkartProducts', missing: 'flipkartIncomplete' }
  },
  'myntra.com': {
    script: 'myntra_gold_master_v7_final.js',
    browser: 'firefox',
    profile: 'myntra',
    urls: ['https://www.myntra.com/gold-coin'],
    exportBinding: { products: 'myntraGold', catalogue: 'myntraProducts', missing: 'myntraIncomplete' }
  },
};

const isObject = (value) => Boolean(value) && typeof value === 'object';

const identityFor = (store, item = {}) => {
  if (!isObject(item)) return null;
  if (store === 'ajio.com') return String(item.code || item.id || item.productCode || '').trim() || String(item.url || item.link || '').trim() || null;
  if (store === 'amazon.in') return String(item.asin || item.id || '').trim() || String(item.url || item.link || '').trim() || null;
  if (store === 'flipkart.com') return String(item.pid || item.productId || item.id || '').trim() || String(item.link || item.url || '').trim() || null;
  if (store === 'myntra.com') return String(item.id || item.productId || '').trim() || String(item.link || item.url || item.landingPageUrl || '').trim() || null;
  return String(item.id || item.url || item.link || '').trim() || null;
};

const dedupeByIdentity = (store, list = []) => {
  const out = new Map();
  for (const raw of Array.isArray(list) ? list : []) {
    const key = identityFor(store, raw) || JSON.stringify(raw);
    if (!out.has(key)) out.set(key, raw);
  }
  return [...out.values()];
};

const isRuntimeReusable = (runtime, visible) => {
  if (!runtime?.context) return false;
  if (runtime.pages.size && [...runtime.pages.values()].every((page) => page.isClosed())) return false;
  // Keep the existing store session even if a later request asks for a different visibility mode.
  // A Playwright context cannot change headless/visible in place; replacing it would lose cookies/state.
  return true;
};

const ensureRunProfileRoot = async () => {
  if (runProfileRoot) return runProfileRoot;
  if (!runProfileRootPromise) {
    runProfileRootPromise = (async () => {
      const directory = await mkdtemp(join(tmpdir(), 'aurum-run-'));
      await Promise.all(Object.values(masterConfigs).map(({ profile }) => mkdir(join(directory, profile), { recursive: true })));
      runProfileRoot = directory;
      console.log(`[direct-master] using ephemeral browser profile root ${directory}`);
      return directory;
    })();
  }
  try {
    return await runProfileRootPromise;
  } catch (error) {
    runProfileRootPromise = null;
    throw error;
  }
};

const profileDirectoryFor = async (store) => join(await ensureRunProfileRoot(), masterConfigs[store].profile);

const closeStoreRuntime = async (runtime) => {
  if (!runtime) return;
  const pages = [...runtime.pages.values()];
  runtime.pages.clear();
  await Promise.all(pages.map((page) => page.close().catch(() => {})));
  await runtime.context?.close().catch(() => {});
};

const cleanAjioEnvironment = () => {
  const environment = { ...process.env };
  for (const key of ['HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy', 'ALL_PROXY', 'all_proxy', 'NO_PROXY', 'no_proxy']) delete environment[key];
  return environment;
};

const closeAjioRuntime = async () => {
  const runtime = ajioRuntime;
  ajioRuntime = null;
  if (!runtime?.child?.connected) return;
  await new Promise((resolve) => {
    const timeout = setTimeout(resolve, 3000);
    runtime.child.once('exit', () => { clearTimeout(timeout); resolve(); });
    runtime.child.send({ action: 'shutdown' }, () => {});
  });
  if (!runtime.child.killed) runtime.child.kill('SIGTERM');
};

const getAjioRuntime = async () => {
  if (ajioRuntime?.child?.connected) return ajioRuntime;
  const environment = cleanAjioEnvironment();
  environment.AURUM_AJIO_PROFILE_DIR = await profileDirectoryFor('ajio.com');
  const child = fork(ajioRuntimePath, [], { env: environment, stdio: ['ignore', 'inherit', 'inherit', 'ipc'] });
  const pending = new Map();
  child.on('message', (message) => {
    const request = pending.get(message?.requestId);
    if (!request) return;
    pending.delete(message.requestId);
    if (message.type === 'error') request.reject(new Error(message.error || 'AJIO Firefox runtime failed'));
    else request.resolve(message.result);
  });
  child.once('exit', () => {
    if (ajioRuntime?.child === child) ajioRuntime = null;
    for (const request of pending.values()) request.reject(new Error('AJIO Firefox runtime exited'));
    pending.clear();
  });
  ajioRuntime = { child, pending };
  console.log('[direct-master:ajio.com] created isolated Firefox child with proxy environment removed');
  return ajioRuntime;
};

const runAjioInChild = async ({ port, visible = false } = {}) => {
  const runtime = await getAjioRuntime();
  const requestId = `ajio-master-${++ajioRequestId}`;
  return new Promise((resolve, reject) => {
    runtime.pending.set(requestId, { resolve, reject });
    runtime.child.send({ action: 'run', requestId, port, visible, manualWarmup: process.env.PRODUCT_AJIO_MANUAL_DIAGNOSTIC === '1' }, (error) => {
      if (!error) return;
      runtime.pending.delete(requestId);
      reject(error);
    });
  });
};

export const runAjioTargetedProducts = async (products, { visible = false } = {}) => {
  const runtime = await getAjioRuntime();
  const requestId = `ajio-pdp-${++ajioRequestId}`;
  return new Promise((resolve, reject) => {
    runtime.pending.set(requestId, { resolve, reject });
    runtime.child.send({ action: 'refreshProducts', requestId, products: Array.isArray(products) ? products : [], visible }, (error) => {
      if (!error) return;
      runtime.pending.delete(requestId);
      reject(error);
    });
  });
};

async function getStoreRuntime(store, { visible = false } = {}) {
  const config = masterConfigs[store];
  const existing = storeRuntimes.get(store);
  if (isRuntimeReusable(existing, visible)) {
    console.log(`[direct-master:${store}] reusing persistent browser runtime`);
    return existing;
  }
  if (existing) {
    console.log(`[direct-master:${store}] replacing persistent browser runtime`);
    await closeStoreRuntime(existing);
    storeRuntimes.delete(store);
  }

  const playwright = await import('playwright');
  const browser = playwright[config.browser];
  const profileDir = await profileDirectoryFor(store);
  const context = await browser.launchPersistentContext(profileDir, {
    headless: !visible,
    viewport: null,
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata'
  });
  const runtime = {
    store,
    visible,
    context,
    pages: new Map(),
    pdpPage: null
  };
  storeRuntimes.set(store, runtime);
  console.log(`[direct-master:${store}] created ${visible ? 'visible' : 'headless'} persistent browser runtime (${profileDir})`);
  console.log(`[direct-master:${store}] using direct network; proxy settings and credentials are not injected`);
  return runtime;
}

async function getRuntimePage(runtime, url) {
  const existing = runtime.pages.get(url);
  if (existing && !existing.isClosed()) {
    console.log(`[direct-master:${runtime.store}] reusing persistent page ${url}`);
    return existing;
  }
  const page = runtime.pages.size === 0 ? runtime.context.pages()[0] || await runtime.context.newPage() : await runtime.context.newPage();
  runtime.pages.set(url, page);
  console.log(`[direct-master:${runtime.store}] created persistent page ${url}`);
  return page;
}


async function getRuntimePdpPage(runtime) {
  if (runtime.pdpPage && !runtime.pdpPage.isClosed()) {
    await runtime.pdpPage.bringToFront().catch(() => {});
    return runtime.pdpPage;
  }

  // Reuse an already-open master/category page. This is deliberate: context.newPage()
  // can surface as a new Firefox window. Reusing the master Page guarantees the PDP
  // stays in the same persistent browser window/session.
  const page = [...runtime.pages.values()].find((candidate) => candidate && !candidate.isClosed())
    || runtime.context.pages().find((candidate) => candidate && !candidate.isClosed());
  if (!page) throw new Error(`${runtime.store} persistent master browser has no reusable page.`);
  runtime.pdpPage = page;
  await page.bringToFront().catch(() => {});
  console.log(`[direct-master:${runtime.store}] reusing master page for targeted PDP navigation`);
  return page;
}

export async function disposeMasterRuntimes() {
  const runtimes = [...storeRuntimes.values()];
  storeRuntimes.clear();
  await Promise.all([closeAjioRuntime(), ...runtimes.map(async (runtime) => closeStoreRuntime(runtime))]);
  if (runProfileRoot) await rm(runProfileRoot, { recursive: true, force: true }).catch(() => {});
  runProfileRoot = null;
  runProfileRootPromise = null;
}

const extractPageResult = async (page, bindings) => page.evaluate(({ productsKey, catalogueKey, missingKey }) => {
  const arrayOrEmpty = (value) => Array.isArray(value) ? value : [];
  return {
    products: arrayOrEmpty(globalThis[productsKey]),
    catalogue: arrayOrEmpty(globalThis[catalogueKey]),
    missing: arrayOrEmpty(globalThis[missingKey])
  };
}, {
  productsKey: bindings.products,
  catalogueKey: bindings.catalogue,
  missingKey: bindings.missing
});

async function runStoreMaster(store, { port, visible = false } = {}) {
  const config = masterConfigs[store];
  if (!config) throw new Error(`Unsupported store master: ${store}`);
  if (store === 'ajio.com') return runAjioInChild({ port, visible });
  const runtime = await getStoreRuntime(store, { visible });
  const { urls } = config;

  const source = (await readFile(join(scripts, config.script), 'utf8')).replaceAll('http://localhost:8788', `http://localhost:${port}`);
  const pageRuns = [];
  const mergedProducts = [];
  const mergedCatalogue = [];
  const mergedMissing = [];
  const startedAt = Date.now();

  console.log(`[direct-master:${store}] starting (${visible ? 'visible' : 'headless'} ${config.browser}, persistent runtime)`);
  console.log(`[direct-master:${store}] links: ${urls.join(' | ')}`);
  for (const url of urls) {
    const page = await getRuntimePage(runtime, url);
    const linkStartedAt = Date.now();
    const diagnostics = { url, bridge: null, retailerErrors: [], pageErrors: [] };
    const onConsole = (message) => {
      const text = message.text();
      if (/Aurum (?:AJIO|Amazon|Flipkart|Myntra) bridge:/i.test(text)) diagnostics.bridge = text;
      if (message.type() === 'error' && /AJIO|Amazon|Flipkart|Myntra|HTTP|fetch|search/i.test(text)) diagnostics.retailerErrors.push(text);
    };
    const onPageError = (error) => diagnostics.pageErrors.push(error.message);
    page.on('console', onConsole);
    page.on('pageerror', onPageError);

    try {
      await page.evaluate(({ productsKey, catalogueKey, missingKey }) => {
        globalThis[productsKey] = undefined;
        globalThis[catalogueKey] = undefined;
        globalThis[missingKey] = undefined;
      }, {
        productsKey: config.exportBinding.products,
        catalogueKey: config.exportBinding.catalogue,
        missingKey: config.exportBinding.missing
      }).catch(() => {});

      console.log(`[direct-master:${store}] opening ${url}`);
      await page.goto(url, { waitUntil: 'commit', timeout: 45000 });
      await page.waitForLoadState('domcontentloaded', { timeout: 45000 }).catch(() => {});
      if (visible) await page.bringToFront().catch(() => {});
      if (config.readySelector) {
        console.log(`[direct-master:${store}] waiting for listing results before script execution`);
        await page.waitForSelector(config.readySelector, { timeout: 20000 });
      }
      if (config.ajioCategoryReady) {
        console.log(`[direct-master:${store}] waiting for AJIO category API before script execution`);
        const ready = await waitForAjioCategoryApi(page);
        await page.evaluate(({ pageZero, request }) => {
          globalThis.__AURUM_AJIO_PAGE0__ = pageZero;
          globalThis.__AURUM_AJIO_REQUEST__ = request;
        }, ready);
      }
      await page.evaluate(() => { globalThis.__aurumMasterRunner = true; });
      console.log(`[direct-master:${store}] executing ${config.script} ${url}`);
      await page.evaluate(async (code) => await (0, eval)(code), source);
      const pulled = await extractPageResult(page, config.exportBinding);
      mergedProducts.push(...pulled.products);
      mergedCatalogue.push(...pulled.catalogue);
      mergedMissing.push(...pulled.missing);
      pageRuns.push({ ok: true, ...diagnostics, counts: { products: pulled.products.length, catalogue: pulled.catalogue.length, missing: pulled.missing.length } });
      console.log(`[direct-master:${store}] completed ${url} in ${Date.now() - linkStartedAt}ms (products=${pulled.products.length}, catalogue=${pulled.catalogue.length}, missing=${pulled.missing.length})`);
    } catch (error) {
      pageRuns.push({ ok: false, ...diagnostics, error: error?.message || String(error), counts: { products: 0, catalogue: 0, missing: 0 } });
      console.log(`[direct-master:${store}] failed ${url} in ${Date.now() - linkStartedAt}ms (${error?.message || String(error)})`);
    } finally {
      page.off('console', onConsole);
      page.off('pageerror', onPageError);
    }
  }

  const products = dedupeByIdentity(store, mergedProducts);
  const catalogue = dedupeByIdentity(store, mergedCatalogue);
  const missing = dedupeByIdentity(store, mergedMissing);
  const hasOutput = products.length > 0 || catalogue.length > 0;

  console.log(`[direct-master:${store}] finished in ${Date.now() - startedAt}ms (complete=${hasOutput && pageRuns.length > 0 && pageRuns.every((run) => run.ok)}, products=${products.length}, catalogue=${catalogue.length}, missing=${missing.length})`);

  return {
    store,
    complete: pageRuns.length > 0 && pageRuns.every((run) => run.ok) && hasOutput,
    products,
    catalogue,
    missing,
    openedLinks: [...urls],
    runs: pageRuns
  };
}

const sharedPdpRunners = new Map([
  ['amazon.in', refreshAmazonFromContext],
  ['flipkart.com', refreshFlipkartFromContext],
  ['myntra.com', refreshMyntraFromContext]
]);

export async function runSharedTargetedProducts(store, products, { visible = false, settings = {}, onProgress = () => {} } = {}) {
  const input = Array.isArray(products) ? products : [];
  if (store === 'ajio.com') {
    const result = await runAjioTargetedProducts(input, { visible });
    let checked = 0, live = 0, failed = 0;
    for (const row of result.products || []) {
      checked += 1;
      if (row.ok) live += 1; else failed += 1;
      onProgress({ store, total: input.length, checked, live, failed, current: row.product?.name || row.product?.id || null });
    }
    return result;
  }
  const runner = sharedPdpRunners.get(store);
  if (!runner) throw new Error(`No shared PDP runner for ${store}`);
  const runtime = await getStoreRuntime(store, { visible });
  const pdpPage = await getRuntimePdpPage(runtime);
  const results = [];
  let live = 0, failed = 0;
  for (let index = 0; index < input.length; index += 1) {
    const product = input[index];
    const startedAt = Date.now();
    try {
      const extracted = await runner(product, pdpPage, { ...settings, productPersistentBrowser: true, sharedMasterRuntime: true });
      results.push({ id: product.id, product, ok: true, extracted, durationMs: Date.now() - startedAt });
      live += 1;
    } catch (error) {
      results.push({ id: product.id, product, ok: false, error: error?.message || String(error), durationMs: Date.now() - startedAt });
      failed += 1;
    }
    onProgress({ store, total: input.length, checked: index + 1, live, failed, current: product.name || product.id || null });
  }
  return { store, products: results, checked: results.length, succeeded: live, failed };
}

export const runAjioMaster = (options = {}) => runStoreMaster('ajio.com', options);
export const runAmazonMaster = (options = {}) => runStoreMaster('amazon.in', options);
export const runFlipkartMaster = (options = {}) => runStoreMaster('flipkart.com', options);
export const runMyntraMaster = (options = {}) => runStoreMaster('myntra.com', options);