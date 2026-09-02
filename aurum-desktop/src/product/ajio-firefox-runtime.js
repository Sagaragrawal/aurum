import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { firefox } from 'playwright';
import {
  refreshProductPageFromExistingPage
} from './stores/ajio/store.js';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const unifiedScriptPath = join(root, 'manual_js', 'ajio_gold_master.js');
const MASTER_BINDINGS = Object.freeze({
  products: 'ajioGold',
  catalogue: 'ajioAllSearchResults',
  missing: 'ajioIncomplete'
});

const GOLD_PURITY_FACETS = Object.freeze([
  'verticalmetalpurity:24 Karat',
  'verticalmetalpurity:24 Karat (995)',
  'verticalmetalpurity:24 Karat (999)',
  'verticalmetalpurity:24 Kt',
  'verticalmetalpurity:24 Kt (995)',
  'verticalmetalpurity:24 Kt (999)',
  'verticalmetalpurity:24 Kt (999.9)',
  'verticalmetalpurity:999'
]);

const GOLD_22K_FACET = 'verticalmetalpurity:22 Kt';

const buildMasterQuery = (include22K) => [
  ':relevance',
  ...GOLD_PURITY_FACETS,
  ...(include22K ? [GOLD_22K_FACET] : [])
].join(':');

const buildMasterUrl = (pathname, include22K) => {
  const url = new URL(pathname, 'https://www.ajio.com');
  url.searchParams.set('query', buildMasterQuery(include22K));
  return url.href;
};

const masterPages = [
  { url: buildMasterUrl('/s/boys-169373', true), scriptPath: unifiedScriptPath, bindings: MASTER_BINDINGS, deriveRequest: true },
  { url: buildMasterUrl('/s/girls-169379', true), scriptPath: unifiedScriptPath, bindings: MASTER_BINDINGS, deriveRequest: true },
  { url: buildMasterUrl('/s/jewellery-176606', true), scriptPath: unifiedScriptPath, bindings: MASTER_BINDINGS, deriveRequest: true },
  { url: buildMasterUrl('/women/c/8303', false), scriptPath: unifiedScriptPath, bindings: MASTER_BINDINGS, deriveRequest: true }
];
const masterExecutionDelayMs = Math.max(0, Number(process.env.PRODUCT_AJIO_MASTER_EXECUTION_DELAY_MS || 60000));
const childPageSettleMs = Math.max(0, Number(process.env.PRODUCT_AJIO_CHILD_PAGE_SETTLE_MS || 2500));
let context = null;
let pageVisible = null;
const pages = new Map();
let profileDir = null;
let running = false;

const send = (requestId, payload) => {
  if (!process.connected || typeof process.send !== 'function') return false;

  try {
    process.send(
      { requestId, ...payload },
      (error) => {
        if (
          error &&
          error.code !== 'ERR_IPC_CHANNEL_CLOSED'
        ) {
          console.error('[ajio-runtime] IPC send failed:', error);
        }
      }
    );

    return true;
  } catch (error) {
    if (error?.code !== 'ERR_IPC_CHANNEL_CLOSED') {
      console.error('[ajio-runtime] IPC send failed:', error);
    }

    return false;
  }
};

const ajioExportDir = join(root, 'exports', 'ajio');

const safeFilePart = (value) =>
  String(value || 'ajio')
    .replace(/^https?:\/\//i, '')
    .replace(/[^\w.-]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 120);

const saveAjioFullJson = async (page, pageUrl) => {
  const fullData = await page.evaluate(() => {
    if (typeof window.ajioAllData !== 'function') {
      return null;
    }

    return window.ajioAllData();
  });

  if (!fullData) {
    console.log(
      `[direct-master:ajio.com] full JSON unavailable ${pageUrl}`
    );

    return null;
  }

  await mkdir(ajioExportDir, { recursive: true });

  const url = new URL(pageUrl);

  const pageName =
    safeFilePart(url.pathname) || 'ajio';

  const timestamp =
    new Date()
      .toISOString()
      .replace(/[:.]/g, '-');

  const filename =
    `${pageName}_${timestamp}.json`;

  const filepath =
    join(ajioExportDir, filename);

  await writeFile(
    filepath,
    JSON.stringify(fullData, null, 2),
    'utf8'
  );

  const counts =
    fullData?.counts || {};

  console.log(
    `[direct-master:ajio.com] FULL JSON SAVED · ` +
    `${filename} · ` +
    `captured=${counts.discoveredUnique ?? '?'} · ` +
    `gold=${counts.includedGold ?? '?'} · ` +
    `excluded=${counts.excluded ?? '?'} · ` +
    `gap=${counts.missingBeforeClassification ?? '?'}`
  );

  return filepath;
};

const waitForPlpRequest = async (targetPage) => {
  await targetPage.waitForFunction(() => {
    try {
      const raw = localStorage.getItem('plpRequestMobile');
      if (!raw) return false;
      const value = JSON.parse(raw);
      return Boolean(value?.request?.pathname && value?.request?.query);
    } catch {
      return false;
    }
  }, { timeout: 30000 });

  return targetPage.evaluate(() => {
    const value = JSON.parse(localStorage.getItem('plpRequestMobile'));
    return value.request;
  });
};

const ensureRuntime = async ({ visible = false } = {}) => {
  // A live AJIO context is authoritative for the app session. Do not replace it just because
  // a later request asks for a different visibility mode; replacing it loses the warmed session.
  if (context) return;
  profileDir = process.env.AURUM_AJIO_PROFILE_DIR;
  if (!profileDir) throw new Error('Missing AJIO temporary profile directory.');
  await mkdir(profileDir, { recursive: true });
  context = await firefox.launchPersistentContext(profileDir, {
    headless: !visible,
    viewport: null,
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
    firefoxUserPrefs: {
      'browser.link.open_newwindow': 3,
      'browser.link.open_newwindow.restriction': 0
    }
  });
  pageVisible = visible;
  console.log(`[direct-master:ajio.com] isolated Firefox profile ${profileDir}`);
};

const getCategoryPage = async (url) => {
  const existing = pages.get(url);
  if (existing && !existing.isClosed()) return existing;
  let page;
  if (pages.size === 0) {
    page = context.pages()[0] || await context.newPage();
  } else {
    const opener = [...pages.values()][0];
    const popup = context.waitForEvent('page', { timeout: 10000 });
    await opener.evaluate((target) => window.open(target, '_blank'), url);
    page = await popup;
  }
  pages.set(url, page);
  console.log(`[direct-master:ajio.com] created persistent category tab ${url}`);
  return page;
};

const extractResult = async (page, bindings) => page.evaluate((keys) => ({
  products: Array.isArray(globalThis[keys.products]) ? globalThis[keys.products] : [],
  catalogue: Array.isArray(globalThis[keys.catalogue]) ? globalThis[keys.catalogue] : [],
  missing: Array.isArray(globalThis[keys.missing]) ? globalThis[keys.missing] : []
}), bindings);

const derivedCategoryRequest = async (page) => page.evaluate(() => {
  const current = new URL(location.href);
  const categoryId = current.pathname.match(/\/c\/(\d+)/)?.[1];
  const curatedId = current.pathname.match(/^\/s\/([^/?#]+)/)?.[1];
  const pageQuery = current.searchParams.get('query') || ':relevance';

  if (curatedId) {
    const tail = pageQuery
      .replace(/^:relevance:?/i, '')
      .replace(/^relevance:?/i, '')
      .replace(/^:+/, '')
      .trim();
    const slug = curatedId.replace(/-\d+$/, '');
    const head = slug ? slug.charAt(0).toUpperCase() + slug.slice(1) : '';
    return {
      pathname: '/api/category/83',
      query: {
        currentPage: 0, pageSize: 45, format: 'json',
        query: [':relevance', 'curated:true', `curatedId:${curatedId}`, `head:${head}`, 'relevance:undefined', tail].filter(Boolean).join(':'),
        curated: 'true', curatedid: curatedId,
        facets: tail ? `relevance:undefined:${tail}` : 'relevance',
        gridColumns: '3',
        advfilter: 'true', platform: 'Desktop',
        sort: 'relevance'
      }
    };
  }

  if (!categoryId) return null;
  return {
    pathname: `/api/category/${categoryId}`,
    query: {
      fields: 'SITE', currentPage: 0, pageSize: 45, format: 'json',
      query: pageQuery,
      gridColumns: '3'
    }
  };
});

const run = async ({ port, visible = false, manualWarmup }) => {
  await ensureRuntime({ visible });
  const runPages = masterPages;
  const runs = [];
  const products = [];
  const catalogue = [];
  const missing = [];
  const categoryPages = [];
  for (const [index, master] of runPages.entries()) {
    const { url } = master;
    try {
      console.log(`[direct-master:ajio.com] opening category ${url}`);
      const page = await getCategoryPage(url);
      if (page.url() !== url) {
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
      }
      if (index > 0) {
        await page.waitForTimeout(childPageSettleMs);
        console.log(`[direct-master:ajio.com] refreshing child category after ${childPageSettleMs}ms settle ${url}`);
        await page.reload({ waitUntil: 'domcontentloaded', timeout: 45000 });
      }
      if (visible) await page.bringToFront().catch(() => { });
      const request = master.deriveRequest
        ? await derivedCategoryRequest(page)
        : await waitForPlpRequest(page);
      if (!request) throw new Error('Unable to derive AJIO category request.');
      console.log(`[direct-master:ajio.com] category page ready ${url}`);
      categoryPages.push({ ...master, page, request });
    } catch (error) {
      console.log(`[direct-master:ajio.com] category page failed ${url}: ${error?.message || String(error)}`);
      runs.push({ ok: false, url, error: error?.message || String(error), counts: { products: 0, catalogue: 0, missing: 0 } });
    }
  }
  if (manualWarmup) {
    for (const { url } of categoryPages) runs.push({ ok: true, manual: true, url, counts: { products: 0, catalogue: 0, missing: 0 } });
    console.log('[direct-master:ajio.com] ALL AJIO MASTER TABS OPEN - MASTER NOT EXECUTED');
  } else {
    if (categoryPages.length && masterExecutionDelayMs > 0) {
      console.log(`[direct-master:ajio.com] tabs ready; waiting ${Math.round(masterExecutionDelayMs / 1000)}s before master execution`);
      await categoryPages[0].page.waitForTimeout(masterExecutionDelayMs);
    }
    for (const { url, page, request, scriptPath, bindings } of categoryPages) {
      try {
        // Do not pre-probe AJIO's API here. The master performs its own page-0
        // request after the browser tabs have had the full warm-up period.
        await page.evaluate((pageRequest) => {
          delete globalThis.__AURUM_AJIO_PAGE0__;
          globalThis.__AURUM_AJIO_REQUEST__ = pageRequest;
        }, request);
        const source = (await readFile(scriptPath, 'utf8')).replaceAll('http://localhost:8788', `http://localhost:${port}`);
        await page.evaluate(() => { globalThis.__aurumMasterRunner = true; });
        console.log(`[direct-master:ajio.com] executing ${scriptPath.split('/').at(-1)} ${url}`);
        await page.evaluate(
          async (code) => await (0, eval)(code),
          source
        );

        const result =
          await extractResult(page, bindings);


        // ------------------------------------------------------------
        // SAVE COMPLETE AJIO MASTER JSON FROM NODE
        // ------------------------------------------------------------

        let fullJsonPath = null;

        try {
          fullJsonPath =
            await saveAjioFullJson(page, url);
        } catch (error) {
          console.log(
            `[direct-master:ajio.com] FULL JSON SAVE FAILED ${url}: ` +
            `${error?.message || String(error)}`
          );
        }


        // ------------------------------------------------------------
        // EXISTING FLOW — UNCHANGED
        // ------------------------------------------------------------

        products.push(...result.products);
        catalogue.push(...result.catalogue);
        missing.push(...result.missing);

        console.log(
          `[direct-master:ajio.com] master completed ${url} ` +
          `(products=${result.products.length}, ` +
          `catalogue=${result.catalogue.length}, ` +
          `missing=${result.missing.length})`
        );

        runs.push({
          ok: true,
          url,

          counts: {
            products:
              result.products.length,

            catalogue:
              result.catalogue.length,

            missing:
              result.missing.length
          },

          fullJsonPath
        });
      } catch (error) {
        console.log(`[direct-master:ajio.com] master skipped ${url}: ${error?.message || String(error)}`);
        runs.push({ ok: false, url, error: `AJIO browser/API initialization failed: ${error?.message || String(error)}`, counts: { products: 0, catalogue: 0, missing: 0 } });
      }
    }
  }
  const hasOutput = products.length > 0 || catalogue.length > 0;
  return { store: 'ajio.com', complete: !manualWarmup && runs.length === runPages.length && runs.every((entry) => entry.ok) && hasOutput, manual: manualWarmup, products, catalogue, missing, openedLinks: runPages.map(({ url }) => url), runs };
};

const refreshProducts = async ({
  products = [],
  visible = false
} = {}) => {

  await ensureRuntime({ visible });

  const input =
    Array.isArray(products)
      ? products
      : [];

  const results = [];

  // Targeted refresh may be the first AJIO action after app startup. Establish an AJIO
  // origin in the SAME persistent context before using the in-browser product API.
  if (![...pages.values()].some((candidate) => candidate && !candidate.isClosed() && /ajio\.com$/i.test(new URL(candidate.url()).hostname))) {
    const warmUrl = masterPages[0].url;
    const warmPage = await getCategoryPage(warmUrl);
    if (!/ajio\.com$/i.test(new URL(warmPage.url()).hostname)) {
      await warmPage.goto(warmUrl, { waitUntil: 'domcontentloaded', timeout: 45000 });
    }
    await warmPage.waitForTimeout(5000);
    console.log('[direct-master:ajio.com] targeted PDP session warmed in shared Firefox context');
  }

  /*
   * IMPORTANT:
   *
   * Use an existing AJIO category page.
   *
   * Do NOT:
   *   - launch Firefox
   *   - create another BrowserContext
   *   - create another AJIO profile
   *
   * This page belongs to the SAME context that successfully
   * executed the AJIO catalogue master.
   */
  let sharedPage =
    [...pages.values()]
      .find(
        (candidate) =>
          candidate &&
          !candidate.isClosed()
      );

  if (!sharedPage) {
    sharedPage =
      context.pages()
        .find((candidate) => !candidate.isClosed());
  }

  if (!sharedPage) {
    throw new Error(
      'AJIO master browser has no reusable page'
    );
  }

  console.log(
    `[direct-master:ajio.com] targeted PDP using shared master context ` +
    `(${input.length} products)`
  );

  for (let index = 0; index < input.length; index += 1) {

    const product =
      input[index];

    const startedAt =
      Date.now();

    try {

      const extracted =
        await refreshProductPageFromExistingPage(
          product,
          sharedPage,
          {
            productPersistentBrowser: true,
            ajioTargetedRefresh: true,
            sharedMasterRuntime: true
          }
        );

      results.push({
        id: product.id,
        product,
        ok: true,
        extracted,
        durationMs:
          Date.now() - startedAt
      });

    } catch (error) {

      results.push({
        id: product.id,
        product,
        ok: false,
        error:
          error?.message ||
          String(error),
        durationMs:
          Date.now() - startedAt
      });
    }
  }

  return {
    store: 'ajio.com',
    products: results,
    checked: results.length,
    succeeded:
      results.filter((row) => row.ok).length,
    failed:
      results.filter((row) => !row.ok).length
  };
};

const closeRuntime = async () => {
  await context?.close().catch(() => { });
  context = null;
  pageVisible = null;
  pages.clear();
  profileDir = null;
};

process.on('message', async (message) => {

  if (message?.action === 'shutdown') {

    await closeRuntime();

    process.exit(0);

    return;
  }


  if (running) {
    send(
      message.requestId,
      {
        type: 'error',
        error: 'AJIO Firefox runtime is busy'
      }
    );

    return;
  }


  if (
    message?.action !== 'run' &&
    message?.action !== 'refreshProducts'
  ) {
    return;
  }


  running = true;

  try {

    let result;


    if (message.action === 'run') {

      result =
        await run(message);

    } else {

      result =
        await refreshProducts(message);

    }


    send(
      message.requestId,
      {
        type: 'result',
        result
      }
    );

  } catch (error) {

    send(
      message.requestId,
      {
        type: 'error',
        error:
          error?.message ||
          String(error)
      }
    );

  } finally {

    running = false;
  }
});

process.once('disconnect', async () => { await closeRuntime(); process.exit(0); });