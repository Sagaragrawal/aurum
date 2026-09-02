import { createServer } from 'node:http';
import { spawn, fork } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { loadState, saveState as persistState } from '../storage/state-store.js';
import { recordRefreshRun, getBullionHistory, getProductHistory } from '../storage/history-db.js';
import { extractGrams, isNonGoldProductText, normalizeGoldWeight } from '../product/stores/weight-parser.js';
import * as ajioListing from '../product/stores/ajio/listing.js';
import * as amazonListing from '../product/stores/amazon/listing.js';
import * as flipkartListing from '../product/stores/flipkart/listing.js';
import * as myntraListing from '../product/stores/myntra/listing.js';
import { disposeMasterRuntimes, runAjioMaster, runAmazonMaster, runFlipkartMaster, runMyntraMaster, runSharedTargetedProducts } from '../product/master-listing-runner.js';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const browserBridgeArchiveDirectory = join(root, 'data', 'browser-bridge');
const port = Number(process.env.PORT || 8787);
const bullionWorkerPath = fileURLToPath(new URL('../bullion/worker.js', import.meta.url));
const ajioProductWorkerPath = fileURLToPath(new URL('../product/stores/ajio/worker.js', import.meta.url));
const amazonProductWorkerPath = fileURLToPath(new URL('../product/stores/amazon/worker.js', import.meta.url));
const flipkartProductWorkerPath = fileURLToPath(new URL('../product/stores/flipkart/worker.js', import.meta.url));
const myntraProductWorkerPath = fileURLToPath(new URL('../product/stores/myntra/worker.js', import.meta.url));
const productWorkerPathByStore = new Map([
  ['ajio.com', ajioProductWorkerPath],
  ['amazon.in', amazonProductWorkerPath],
  ['flipkart.com', flipkartProductWorkerPath],
  ['myntra.com', myntraProductWorkerPath]
]);

const cleanupStaleRunProfiles = async () => {
  const cutoff = Date.now() - 24 * 60 * 60 * 1000;
  const entries = await readdir(tmpdir(), { withFileTypes: true }).catch(() => []);
  const staleDirectories = [];
  for (const entry of entries) {
    if (!entry.isDirectory() || !entry.name.startsWith('aurum-run-')) continue;
    const directory = join(tmpdir(), entry.name);
    const info = await stat(directory).catch(() => null);
    if (info && info.mtimeMs < cutoff) staleDirectories.push(directory);
  }
  await Promise.all(staleDirectories.map((directory) => rm(directory, { recursive: true, force: true })));
  if (staleDirectories.length) console.log(`Removed ${staleDirectories.length} stale temporary browser profile run(s).`);
};

const isMobileUserAgent = (userAgent = '') => /android|iphone|ipod|ipad|mobile|windows phone/i.test(userAgent);

const normalizeStoreHostname = (inputHostname = '') => {
  const hostname = String(inputHostname || '').toLowerCase().trim();
  if (!hostname) return '';
  for (const domain of productWorkerPathByStore.keys()) {
    if (hostname === domain || hostname.endsWith(`.${domain}`)) return domain;
  }
  return '';
};

const productStoreFor = (product) => {
  const host = product?.source || (() => {
    try { return new URL(product?.url || '').hostname; } catch { return ''; }
  })();
  return normalizeStoreHostname(host);
};

await cleanupStaleRunProfiles();
const state = await loadState();
const { bullion, products, preciousMetalProducts } = state;
const uniqueProducts = dedupeProducts(products);
if (uniqueProducts.length !== products.length) {
  console.log(`Removed ${products.length - uniqueProducts.length} duplicate product link(s).`);
  products.splice(0, products.length, ...uniqueProducts);
}
for (const source of bullion) {
  if (source.status === 'checking') {
    source.status = Number.isFinite(source.price) && source.price > 0 ? 'stale' : 'unavailable';
    source.error = source.error || 'Interrupted by restart';
  }
}
for (const product of products) {
  const canonicalStore = productStoreFor(product);
  if (canonicalStore) product.source = canonicalStore;
  if (product.status === 'checking') {
    product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'unavailable';
    product.error = product.error || 'Interrupted by restart';
  }
  if (!product.name || product.name === 'Reading product page...') {
    const metadata = productMetadataFromUrl(product.url);
    product.name = metadata.name;
    product.grams ||= metadata.grams;
    product.purity ||= metadata.purity;
  }
  const accurateGrams = normalizeGoldWeight(extractGrams(product.name, '', product.url), product.price);
  if (accurateGrams && accurateGrams > 0 && (!product.manuallyEditedAt || product.grams < accurateGrams)) {
    product.grams = accurateGrams;
  }
  if (isClearlyNonGoldProduct(product)) {
    product.status = 'unavailable';
    product.price = null;
    product.couponPrice = null;
    product.error = 'Filtered: Silver/Platinum product (not gold).';
  }
}
const normalizedUniqueProducts = dedupeProducts(products);
if (normalizedUniqueProducts.length !== products.length) {
  console.log(`Removed ${products.length - normalizedUniqueProducts.length} duplicate normalized product link(s).`);
  products.splice(0, products.length, ...normalizedUniqueProducts);
}
await persistState(state);
let runtimeProxyAuth = null;
const productRefresh = { running: false, total: 0, checked: 0, live: 0, failed: 0, current: null };
const bullionRefresh = { running: false, total: 0, checked: 0, live: 0, current: null, scope: 'all' };
const background = { bullionRunning: false, productsRunning: false, shuttingDown: false, restarting: false };
const timers = [];
let lastScheduledProductRefreshAt = 0;
let lastScheduledSub24RefreshAt = 0;
const eventClients = new Set();
let deferredSaveTimer = null;
let browserBridgeRefresh = null;

const productKarat = (product) => {
  const explicit = Number(product?.karat);
  if (explicit > 0) return explicit;
  const purity = String(product?.purity || '').toLowerCase();
  if (/\b24\s*k/.test(purity) || /^(9999|999\.9|999)$/.test(purity)) return 24;
  if (/\b22\s*k/.test(purity) || purity === '916') return 22;
  if (/\b18\s*k/.test(purity) || purity === '750') return 18;
  if (/\b14\s*k/.test(purity) || purity === '585') return 14;
  const text = `${product?.name || ''} ${product?.url || ''}`;
  return Number(text.match(/(?:^|[^0-9])(24|22|18|14)\s*-?\s*k(?:t|arat)?\b/i)?.[1] || 24);
};
const isSub24K = (product) => productKarat(product) < 24;

const stateSnapshot = () => ({
  settings: state.settings,
  bullion,
  products,
  preciousMetalProducts,
  productRefresh,
  bullionRefresh,
  updatedAt: new Date().toISOString()
});

const broadcast = (type = 'state', payload = stateSnapshot()) => {
  const message = `event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`;
  for (const client of [...eventClients]) {
    try { client.write(message); } catch { eventClients.delete(client); }
  }
};

const saveState = async (nextState = state) => {
  await persistState(nextState);
  broadcast('state');
};

const archiveBrowserBridgePayload = async (store, body, result) => {
  const runId = browserBridgeRefresh?.runId || 'manual';
  const archiveDirectory = join(browserBridgeArchiveDirectory, runId);
  await mkdir(archiveDirectory, { recursive: true });
  const receivedAt = new Date().toISOString();
  const filename = `${receivedAt.replace(/[:.]/g, '-')}-${store.replace(/[^a-z0-9]+/gi, '-')}.json`;
  const file = join(archiveDirectory, filename);
  const payload = { runId, receivedAt, store, records: body.records, details: body.details || {}, merge: result };
  await writeFile(`${file}.tmp`, JSON.stringify(payload, null, 2) + '\n', 'utf8');
  await rename(`${file}.tmp`, file);
  return `${runId}/${filename}`;
};

const readBrowserBridgeArchives = async () => {
  try {
    const files = (await readdir(browserBridgeArchiveDirectory, { recursive: true })).filter((file) => file.endsWith('.json')).sort();
    return Promise.all(files.map(async (file) => JSON.parse(await readFile(join(browserBridgeArchiveDirectory, file), 'utf8'))));
  } catch {
    return [];
  }
};

const writeBrowserBridgeRunResult = async (runId, results) => {
  const file = join(browserBridgeArchiveDirectory, runId, 'runner-results.json');
  await writeFile(`${file}.tmp`, JSON.stringify({ completedAt: new Date().toISOString(), results }, null, 2) + '\n', 'utf8');
  await rename(`${file}.tmp`, file);
};

const compactRunnerResult = (result = {}) => ({
  store: result.store,
  complete: Boolean(result.complete),
  openedLinks: Array.isArray(result.openedLinks) ? result.openedLinks : [],
  counts: {
    products: Array.isArray(result.products) ? result.products.length : 0,
    catalogue: Array.isArray(result.catalogue) ? result.catalogue.length : 0,
    missing: Array.isArray(result.missing) ? result.missing.length : 0
  },
  runs: Array.isArray(result.runs)
    ? result.runs.map((run) => ({
      ok: Boolean(run.ok),
      url: run.url || null,
      error: run.error || null,
      bridge: run.bridge || null,
      retailerErrors: Array.isArray(run.retailerErrors) ? run.retailerErrors.slice(-3) : [],
      pageErrors: Array.isArray(run.pageErrors) ? run.pageErrors.slice(-3) : []
    }))
    : []
});

const scheduleStateSave = (delayMs = productRefresh.running ? 1000 : 350) => {
  if (deferredSaveTimer) return;
  deferredSaveTimer = setTimeout(() => {
    deferredSaveTimer = null;
    void saveState(state).catch((error) => console.error('Deferred state save failed:', error.message));
  }, delayMs);
  deferredSaveTimer.unref?.();
};

const runtimeSettings = () => ({ ...state.settings, ...(runtimeProxyAuth ? { runtimeProxyAuth } : {}) });

let persistentBullionWorker = null;
const pendingBullionWorkerTasks = new Map();
const persistentProductWorkers = new Map();
const pendingProductWorkerTasks = new Map();
const productWorkerQueues = new Map();

const resetPersistentBullionWorker = () => {
  persistentBullionWorker = null;
};

const ensurePersistentBullionWorker = async () => {
  if (persistentBullionWorker?.connected) return persistentBullionWorker;
  const child = fork(bullionWorkerPath, [], {
    cwd: process.cwd(),
    env: process.env,
    silent: true
  });
  child.on('message', (message) => {
    const requestId = message?.requestId;
    if (!requestId || !pendingBullionWorkerTasks.has(requestId)) return;
    const task = pendingBullionWorkerTasks.get(requestId);
    if (message.type === 'progress') {
      task.onProgress(message.progress || {}, message.bullion);
      return;
    }
    clearTimeout(task.timeout);
    pendingBullionWorkerTasks.delete(requestId);
    if (message.type === 'result') task.resolve(message.result || {});
    else task.reject(new Error(message.error || 'bullion worker task failed'));
  });

  child.on('exit', (code, signal) => {
    for (const [requestId, task] of pendingBullionWorkerTasks) {
      clearTimeout(task.timeout);
      task.reject(new Error(`Persistent bullion worker exited (code=${code}, signal=${signal || 'none'})`));
      pendingBullionWorkerTasks.delete(requestId);
    }
    resetPersistentBullionWorker();
  });

  child.on('error', (error) => {
    for (const [requestId, task] of pendingBullionWorkerTasks) {
      clearTimeout(task.timeout);
      task.reject(error);
      pendingBullionWorkerTasks.delete(requestId);
    }
    resetPersistentBullionWorker();
  });

  persistentBullionWorker = child;
  return child;
};

const runPersistentBullionWorkerTask = async (payload, onProgress = () => { }, timeoutMs = 180000) => {
  const child = await ensurePersistentBullionWorker();
  return new Promise((resolve, reject) => {
    const requestId = randomUUID();
    const timeout = setTimeout(() => {
      pendingBullionWorkerTasks.delete(requestId);
      reject(new Error(`Persistent bullion worker timed out after ${timeoutMs}ms`));
      try { child.kill('SIGTERM'); } catch { }
    }, timeoutMs);
    timeout.unref?.();

    pendingBullionWorkerTasks.set(requestId, { resolve, reject, onProgress, timeout });
    child.send({ ...payload, requestId }, (error) => {
      if (!error) return;
      clearTimeout(timeout);
      pendingBullionWorkerTasks.delete(requestId);
      reject(error);
      try { child.kill('SIGTERM'); } catch { }
    });
  });
};

const disposePersistentBullionWorker = async () => {
  const child = persistentBullionWorker;
  if (!child?.connected) return;
  try {
    await runPersistentBullionWorkerTask({ action: 'disposeRuntime' }, () => { }, 15000).catch(() => { });
  } finally {
    try { child.kill('SIGTERM'); } catch { }
    resetPersistentBullionWorker();
  }
};

const resetPersistentProductWorker = (store) => {
  persistentProductWorkers.delete(store);
  pendingProductWorkerTasks.delete(store);
};

const ensurePersistentProductWorker = async (store) => {
  const existing = persistentProductWorkers.get(store);
  if (existing?.connected) return existing;

  const workerPath = productWorkerPathByStore.get(store);
  if (!workerPath) throw new Error(`Unsupported product store: ${store}`);

  const child = fork(workerPath, [], {
    cwd: process.cwd(),
    env: { ...process.env, PRODUCT_STORE_DOMAIN: store },
    silent: true
  });

  pendingProductWorkerTasks.set(store, new Map());
  child.on('message', (message) => {
    const requestId = message?.requestId;
    const pending = pendingProductWorkerTasks.get(store);
    if (!requestId || !pending?.has(requestId)) return;
    const task = pending.get(requestId);
    if (message.type === 'progress') {
      task.onProgress(message.progress || {}, message.products);
      return;
    }
    clearTimeout(task.timeout);
    pending.delete(requestId);
    if (message.type === 'result') task.resolve(message.result || {});
    else task.reject(new Error(message.error || `${store} product worker task failed`));
  });

  child.on('exit', (code, signal) => {
    const pending = pendingProductWorkerTasks.get(store) || new Map();
    for (const [requestId, task] of pending) {
      clearTimeout(task.timeout);
      task.reject(new Error(`Persistent ${store} product worker exited (code=${code}, signal=${signal || 'none'})`));
      pending.delete(requestId);
    }
    resetPersistentProductWorker(store);
  });

  child.on('error', (error) => {
    const pending = pendingProductWorkerTasks.get(store) || new Map();
    for (const [requestId, task] of pending) {
      clearTimeout(task.timeout);
      task.reject(error);
      pending.delete(requestId);
    }
    resetPersistentProductWorker(store);
  });

  persistentProductWorkers.set(store, child);
  return child;
};

const runPersistentProductWorkerTaskNow = async (store, payload, onProgress = () => { }, timeoutMs = 180000) => {
  const child = await ensurePersistentProductWorker(store);
  return new Promise((resolve, reject) => {
    const requestId = randomUUID();
    const pending = pendingProductWorkerTasks.get(store);
    const timeout = setTimeout(() => {
      pending?.delete(requestId);
      reject(new Error(`Persistent ${store} product worker timed out after ${timeoutMs}ms`));
      try { child.kill('SIGTERM'); } catch { }
    }, timeoutMs);
    timeout.unref?.();

    pending.set(requestId, { resolve, reject, onProgress, timeout });
    child.send({ ...payload, requestId }, (error) => {
      if (!error) return;
      clearTimeout(timeout);
      pending.delete(requestId);
      reject(error);
      try { child.kill('SIGTERM'); } catch { }
    });
  });
};

const runPersistentProductWorkerTask = (store, payload, onProgress = () => { }, timeoutMs = 180000) => {
  const previous = productWorkerQueues.get(store) || Promise.resolve();
  const task = previous.catch(() => { }).then(() => runPersistentProductWorkerTaskNow(store, payload, onProgress, timeoutMs));
  const completion = task.catch(() => { });
  productWorkerQueues.set(store, completion);
  completion.finally(() => {
    if (productWorkerQueues.get(store) === completion) productWorkerQueues.delete(store);
  });
  return task;
};

const disposePersistentProductWorkers = async () => {
  const entries = [...persistentProductWorkers.entries()];
  await Promise.all(entries.map(async ([store, child]) => {
    if (!child?.connected) return;
    try {
      await runPersistentProductWorkerTask(store, { action: 'disposeRuntime' }, () => { }, 15000).catch(() => { });
    } finally {
      try { child.kill('SIGTERM'); } catch { }
      resetPersistentProductWorker(store);
    }
  }));
};

const mergeById = (targetList, updatedList) => {
  const byId = new Map((updatedList || []).map((item) => [item.id, item]));
  for (const item of targetList) {
    const next = byId.get(item.id);
    if (next) {
      Object.assign(item, next);
      if (isClearlyNonGoldProduct(item)) {
        item.status = 'unavailable';
        item.price = null;
        item.couponPrice = null;
        item.error = 'Filtered: Silver/Platinum product (not gold).';
      }
    }
  }
};

const mergeStoreDiscoveries = (store, updatedList) => {
  const knownUrls = new Set(products.map((product) => {
    try { return canonicalProductUrl(product.url); } catch { return product.url; }
  }));
  for (const product of updatedList || []) {
    if (products.some((current) => current.id === product.id)) continue;
    let canonicalUrl;
    try { canonicalUrl = canonicalProductUrl(product.url); } catch { continue; }
    if (knownUrls.has(canonicalUrl) || productStoreFor(product) !== store) continue;
    knownUrls.add(canonicalUrl);
    products.push(product);
  }
};

const runWorkerTask = (workerPath, payload, onProgress = () => { }, timeoutMs = 180000, workerEnv = {}) => new Promise((resolve, reject) => {
  const child = fork(workerPath, [], {
    cwd: process.cwd(),
    env: { ...process.env, ...workerEnv },
    silent: true
  });
  let settled = false;
  let timeout;
  // Inactivity window: restarted on every progress message from the worker.
  const arm = () => {
    timeout = setTimeout(() => {
      if (settled) return;
      settled = true;
      child.kill('SIGTERM');
      reject(new Error(`Worker stalled for ${timeoutMs}ms`));
    }, timeoutMs);
    timeout.unref();
  };
  arm();
  child.on('message', (message) => {
    if (!message || settled) return;
    if (message.type === 'progress') {
      arm();
      onProgress(message.progress || {}, message.bullion || message.products);
      return;
    }
    if (message.type === 'result') {
      settled = true;
      clearTimeout(timeout);
      resolve(message.result || {});
      child.disconnect();
      child.kill('SIGTERM');
      return;
    }
    if (message.type === 'error') {
      settled = true;
      clearTimeout(timeout);
      reject(new Error(message.error || 'Worker task failed'));
      child.disconnect();
      child.kill('SIGTERM');
    }
  });
  child.on('error', (error) => {
    if (settled) return;
    settled = true;
    clearTimeout(timeout);
    reject(error);
  });
  child.on('exit', (code, signal) => {
    if (settled) return;
    settled = true;
    clearTimeout(timeout);
    reject(new Error(`Worker exited before completing (code=${code}, signal=${signal || 'none'})`));
  });
  child.send(payload);
});

const finalizeBullionFailure = (sourceIds = null, errorMessage = 'refresh failed') => {
  const targetIds = Array.isArray(sourceIds) && sourceIds.length ? new Set(sourceIds) : null;
  for (const source of bullion) {
    if (targetIds && !targetIds.has(source.id)) continue;
    if (source.status !== 'checking') continue;
    if (Number.isFinite(source.price) && source.price > 0) source.status = 'stale';
    else source.status = 'unavailable';
    source.error = errorMessage;
  }
};

const finalizeProductFailure = (errorMessage = 'product refresh failed', productIds = null) => {
  const targetIds = productIds instanceof Set ? productIds : null;
  for (const product of products) {
    if (targetIds && !targetIds.has(product.id)) continue;
    if (product.status !== 'checking') continue;
    product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'failed';
    product.checkedAt = new Date().toISOString();
    product.error = errorMessage;
  }
};

const bullionWorkerTimeoutMs = (requestedSourceIds = null) => {
  if (Array.isArray(requestedSourceIds) && requestedSourceIds.length === 1) {
    const sourceId = requestedSourceIds[0];
    if (sourceId === 'tan') return Number(process.env.BULLION_WORKER_TIMEOUT_TAN_MS || 70000);
    return Number(process.env.BULLION_WORKER_TIMEOUT_SINGLE_MS || 35000);
  }
  return Number(process.env.BULLION_WORKER_TIMEOUT_ALL_MS || 120000);
};

const runBullionRefreshJob = async (requestedSourceIds = null, onProgress = () => { }) => {
  const result = await runPersistentBullionWorkerTask({
    action: 'refreshSources',
    bullion,
    settings: { ...runtimeSettings(), bullionPersistentBrowser: true },
    requestedSourceIds
  }, (progress, partialBullion) => {
    if (Array.isArray(partialBullion)) mergeById(bullion, partialBullion);
    onProgress(progress);
    broadcast('progress', { productRefresh, bullionRefresh });
  }, bullionWorkerTimeoutMs(requestedSourceIds));
  mergeById(bullion, result.bullion || []);
  return result.summary || { checked: 0, live: 0, note: 'No refresh result.' };
};

const runProductsRefreshJob = async (productsToRefresh = products, onProgress = () => { }, extraSettings = {}) => {
  const groupedProducts = new Map();
  const unsupportedProducts = [];
  for (const product of productsToRefresh) {
    const store = productStoreFor(product);
    if (!store || !productWorkerPathByStore.has(store)) {
      unsupportedProducts.push(product);
      continue;
    }
    if (!groupedProducts.has(store)) groupedProducts.set(store, []);
    groupedProducts.get(store).push(product);
  }

  const perStoreProgress = new Map();
  const onStoreProgress = (store, progress, partialProducts) => {
    if (Array.isArray(partialProducts)) {
      mergeById(products, partialProducts);
      scheduleStateSave();
    }
    const updatedProduct = progress?.updatedProduct;
    if (updatedProduct?.id) {
      const target = products.find((item) => item.id === updatedProduct.id);
      if (target) {
        Object.assign(target, updatedProduct);
        if (isClearlyNonGoldProduct(target)) {
          target.status = 'unavailable';
          target.price = null;
          target.couponPrice = null;
          target.error = 'Filtered: Silver/Platinum product (not gold).';
        }
      }
      scheduleStateSave();
    }
    const previousProgress = perStoreProgress.get(store) || {};
    const storeTargetCount = groupedProducts.get(store)?.length || 0;
    const normalizedProgress = {
      ...(progress || {}),
      checked: Math.min(storeTargetCount, Math.max(Number(previousProgress.checked || 0), Number(progress?.checked || 0))),
      live: Math.min(storeTargetCount, Math.max(Number(previousProgress.live || 0), Number(progress?.live || 0))),
      failed: Math.min(storeTargetCount, Math.max(Number(previousProgress.failed || 0), Number(progress?.failed || 0)))
    };
    perStoreProgress.set(store, normalizedProgress);
    const checked = [...perStoreProgress.values()].reduce((sum, value) => sum + Number(value.checked || 0), 0);
    const live = [...perStoreProgress.values()].reduce((sum, value) => sum + Number(value.live || 0), 0);
    const failed = [...perStoreProgress.values()].reduce((sum, value) => sum + Number(value.failed || 0), 0);
    const current = progress?.current ? `${store}: ${progress.current}` : null;
    onProgress({ total: productsToRefresh.length, checked, live, failed, current, ...(progress?.blocked ? { blocked: true, note: progress.note || `${store} refresh blocked` } : {}), ...(progress?.event ? { event: progress.event } : {}), ...(updatedProduct ? { updatedProduct } : {}) });
    broadcast('progress', { productRefresh, bullionRefresh });
  };

  // Running every retailer at full page-pool concurrency at once can overwhelm laptop CPUs/RAM.
  // Keep stores parallel, but cap how many store jobs are active. Each store still has its own
  // internal page concurrency and its Firefox process remains warm between batches.
  const storeEntries = [...groupedProducts];
  const storeResults = new Array(storeEntries.length);
  const storeParallelism = Math.max(1, Math.min(storeEntries.length || 1, Number(process.env.PRODUCT_STORE_PARALLELISM || 4)));
  let nextStoreIndex = 0;
  const runStore = async (index) => {
    const [store, storeProducts] = storeEntries[index];
    const storeStartedAt = new Date();
    // AJIO is intentionally sequential. Give the batch worker enough lifetime for
    // many short per-item attempts; the individual AJIO item timeout is only 30s.
    const ajioBatchTimeoutMs = Math.min(3600000, Math.max(180000, storeProducts.length * 35000 + 60000));
    const timeoutMs = Number(store === 'ajio.com'
      ? (state.settings.productDebugVisibleBrowser
        ? process.env.AJIO_DEBUG_WORKER_TIMEOUT_MS || ajioBatchTimeoutMs
        : process.env.PRODUCT_AJIO_BULK_WORKER_TIMEOUT_MS || ajioBatchTimeoutMs)
      : storeProducts.length > 1
        ? process.env.PRODUCT_BULK_WORKER_STALL_MS || 300000
        : process.env.PRODUCT_WORKER_TIMEOUT_MS || 90000);
    let result;
    try {
      result = await runPersistentProductWorkerTask(store, {
        action: 'refreshProducts',
        products: storeProducts,
        settings: {
          ...runtimeSettings(),
          productBulkMode: storeProducts.length > 1,
          productPersistentBrowser: true,
          ...extraSettings,
        }
      }, (progress, partialProducts) => onStoreProgress(store, progress, partialProducts), timeoutMs);
      mergeById(products, result.products || []);
      if (store === 'ajio.com' || store === 'myntra.com' || store === 'flipkart.com') mergeStoreDiscoveries(store, result.products || []);
    } catch (error) {
      const message = error?.message || `${store} product refresh failed`;
      const transientFailure = /timed out|timeout|network|connection|worker exited/i.test(message);
      for (const product of storeProducts) {
        if (product.status === 'checking') product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : transientFailure ? 'unverified' : 'failed';
        product.error = message;
      }
      const live = storeProducts.filter((product) => product.status === 'live').length;
      const failed = storeProducts.filter((product) => product.status === 'failed').length;
      result = { products: storeProducts, summary: { checked: storeProducts.length, live, failed, error: message } };
      onStoreProgress(store, { checked: storeProducts.length, total: storeProducts.length, live, failed, current: null, note: message }, storeProducts);
    }
    const completedAt = new Date();
    const summary = result?.summary || {};
    try { recordRefreshRun({ store, startedAt: storeStartedAt.toISOString(), completedAt: completedAt.toISOString(), total: Number(summary.checked || storeProducts.length), live: Number(summary.live || 0), durationMs: completedAt - storeStartedAt }); } catch (error) { console.error('Refresh metric write failed:', error.message); }
    storeResults[index] = result || {};
  };
  const storeWorker = async () => {
    while (nextStoreIndex < storeEntries.length) {
      const index = nextStoreIndex++;
      await runStore(index);
    }
  };
  await Promise.all(Array.from({ length: storeParallelism }, () => storeWorker()));

  if (unsupportedProducts.length) {
    const unsupportedError = 'Unsupported product store for store-specific worker.';
    for (const product of unsupportedProducts) {
      if (product.status === 'checking') {
        product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'failed';
      }
      product.checkedAt = new Date().toISOString();
      product.error = unsupportedError;
    }
  }

  const checked = storeResults.reduce((sum, result) => sum + Number(result?.summary?.checked || 0), 0) + unsupportedProducts.length;
  const live = storeResults.reduce((sum, result) => sum + Number(result?.summary?.live || 0), 0);
  const failed = storeResults.reduce((sum, result) => sum + Number(result?.summary?.failed || 0), 0) + unsupportedProducts.length;
  const discovered = storeResults.reduce((sum, result) => sum + Number(result?.summary?.discovered || 0), 0);
  const observed = storeResults.reduce((sum, result) => sum + Number(result?.summary?.observed || 0), 0);
  const requests = storeResults.reduce((sum, result) => sum + Number(result?.summary?.requests || 0), 0);
  const stale = productsToRefresh.filter((product) => product.status === 'stale').length;
  const unverified = productsToRefresh.filter((product) => product.status === 'unverified').length;
  const unavailable = productsToRefresh.filter((product) => product.status === 'unavailable').length;
  const blockedStores = storeEntries.filter((_, index) => Boolean(storeResults[index]?.summary?.blocked)).map(([store]) => store);
  const blocked = blockedStores.length > 0 && blockedStores.length === storeEntries.length;
  const partial = blockedStores.length > 0 && !blocked;
  const note = blockedStores.length ? `${blockedStores.join(', ')} blocked; remaining stores completed.` : null;
  return { checked, live, stale, unverified, unavailable, failed, discovered, observed, requests, blocked, partial, note };
};

const runSharedRuntimeProductRefresh = async (productsToRefresh = products, onProgress = () => {}, options = {}) => {
  const grouped = new Map();
  const unsupported = [];
  for (const product of productsToRefresh) {
    const store = productStoreFor(product);
    if (!['ajio.com', 'amazon.in', 'flipkart.com', 'myntra.com'].includes(store)) { unsupported.push(product); continue; }
    if (!grouped.has(store)) grouped.set(store, []);
    grouped.get(store).push(product);
  }
  const perStore = new Map();
  const results = [];
  for (const [store, storeProducts] of grouped) {
    let result;
    try {
      result = await runSharedTargetedProducts(store, storeProducts, {
        visible: options.visible ?? Boolean(state.settings.productDebugVisibleBrowser),
        settings: runtimeSettings(),
        onProgress: (progress) => {
          perStore.set(store, progress);
          const checked = [...perStore.values()].reduce((sum, value) => sum + Number(value.checked || 0), 0);
          const live = [...perStore.values()].reduce((sum, value) => sum + Number(value.live || 0), 0);
          const failed = [...perStore.values()].reduce((sum, value) => sum + Number(value.failed || 0), 0);
          onProgress({ total: productsToRefresh.length, checked, live, failed, current: progress.current ? `${store}: ${progress.current}` : null });
        }
      });
    } catch (error) {
      const message = error?.message || `${store} shared browser refresh failed`;
      result = {
        store,
        checked: storeProducts.length,
        succeeded: 0,
        failed: storeProducts.length,
        products: storeProducts.map((product) => ({ id: product.id, product, ok: false, error: message }))
      };
      perStore.set(store, { checked: storeProducts.length, live: 0, failed: storeProducts.length });
      onProgress({ total: productsToRefresh.length, checked: [...perStore.values()].reduce((sum, value) => sum + Number(value.checked || 0), 0), live: [...perStore.values()].reduce((sum, value) => sum + Number(value.live || 0), 0), failed: [...perStore.values()].reduce((sum, value) => sum + Number(value.failed || 0), 0), current: null, note: message });
    }
    results.push(result);
    for (const row of result.products || []) {
      const target = products.find((item) => item.id === row.product?.id || item.id === row.id);
      if (!target) continue;
      const now = new Date().toISOString();
      if (row.ok && row.extracted) {
        Object.assign(target, row.extracted, { checkedAt: now, lastLiveAt: now, status: 'live', refreshMethod: 'shared-master-browser' });
        delete target.error;
        delete target.lastAttemptError;
        if (isClearlyNonGoldProduct(target)) {
          target.status = 'unavailable'; target.price = null; target.couponPrice = null;
          target.error = 'Filtered: Silver/Platinum product (not gold).';
        }
      } else {
        target.checkedAt = now;
        target.status = Number.isFinite(target.price) && target.price > 0 ? 'stale' : 'failed';
        target.error = row.error || 'Product refresh failed in shared store browser.';
      }
    }
    scheduleStateSave();
  }
  for (const product of unsupported) {
    product.checkedAt = new Date().toISOString();
    product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'failed';
    product.error = 'Unsupported product store for shared browser refresh.';
  }
  const checked = results.reduce((sum, result) => sum + Number(result.checked || 0), 0) + unsupported.length;
  return {
    checked,
    live: productsToRefresh.filter((product) => product.status === 'live').length,
    failed: productsToRefresh.filter((product) => product.status === 'failed').length,
    stale: productsToRefresh.filter((product) => product.status === 'stale').length,
    unverified: productsToRefresh.filter((product) => product.status === 'unverified').length,
    unavailable: productsToRefresh.filter((product) => product.status === 'unavailable').length,
    discovered: 0, observed: 0, requests: checked, method: 'shared-master-browser'
  };
};

const runSingleProductRefreshJob = async (product, options = {}) => {
  const store = productStoreFor(product);
  if (!['ajio.com', 'amazon.in', 'flipkart.com', 'myntra.com'].includes(store)) {
    product.status = Number.isFinite(product.price) && product.price > 0 ? 'stale' : 'failed';
    product.checkedAt = new Date().toISOString();
    product.error = 'Unsupported product store for shared browser refresh.';
    return product;
  }

  await runSharedRuntimeProductRefresh([product], () => {}, {
    visible: options.forceVisible ? true : Boolean(state.settings.productDebugVisibleBrowser)
  });
  return product;
};

const schedule = (handler, intervalMs) => {
  const timer = setInterval(handler, intervalMs);
  timer.unref();
  timers.push(timer);
  return timer;
};

const sendJson = (response, status, payload) => { response.writeHead(status, { 'content-type': 'application/json', 'access-control-allow-origin': '*' }); response.end(JSON.stringify(payload)); };
const readBody = async (request) => { let body = ''; for await (const chunk of request) body += chunk; return JSON.parse(body || '{}'); };
const normalizeProductUrl = (value) => {
  const parsed = new URL(value);
  if (!parsed.search) parsed.search = '';
  parsed.hash = '';
  return parsed.href;
};
function isClearlyNonGoldProduct(product = {}) {
  return isNonGoldProductText(`${product?.name || ''} ${product?.url || ''} ${product?.purity || ''}`);
}
const purityCodes = new Set(['9999', '999', '995', '958', '916', '875', '750', '585', '417', '375', '333']);

function gramsFromSlug(slug) {
  const normalized = String(slug || '').toLowerCase();
  const unitToGrams = { mg: 0.001, g: 1, gm: 1, gms: 1, gram: 1, grams: 1 };

  const toWeight = (value, unit) => {
    const token = String(value || '');
    const normalizedValue = (!token.includes('.') && token.startsWith('0') && token.length > 1) ? Number(`0.${token.slice(1)}`) : Number(token);
    const factor = unitToGrams[String(unit || '').toLowerCase()];
    if (!Number.isFinite(normalizedValue) || !factor) return null;
    return normalizedValue * factor;
  };

  const comboTrailingUnit = normalized.match(/(\d+(?:\.\d+)?)\s*(?:\+|x|×)\s*(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/i);
  if (comboTrailingUnit) {
    const left = toWeight(comboTrailingUnit[1], comboTrailingUnit[3]);
    const right = toWeight(comboTrailingUnit[2], comboTrailingUnit[3]);
    if (Number.isFinite(left) && Number.isFinite(right) && left + right > 0) return left + right;
  }

  const comboPerPart = normalized.match(/(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\s*(?:\+|x|×)\s*(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/i);
  if (comboPerPart) {
    const left = toWeight(comboPerPart[1], comboPerPart[2]);
    const right = toWeight(comboPerPart[3], comboPerPart[4]);
    if (Number.isFinite(left) && Number.isFinite(right) && left + right > 0) return left + right;
  }

  const decimalSlug = normalized.match(/(\d+)[-_](\d+)\s*[-_]*(mg|g|gm|gms|gram|grams)\b/i);
  if (decimalSlug && !purityCodes.has(decimalSlug[1])) {
    const grams = toWeight(`${decimalSlug[1]}.${decimalSlug[2]}`, decimalSlug[3]);
    if (Number.isFinite(grams) && grams > 0) return grams;
  }

  const allTokens = [...normalized.matchAll(/(\d+(?:\.\d+)?)\s*[-_]*(mg|g|gm|gms|gram|grams)\b/gi)]
    .map(([, value, unit]) => toWeight(value, unit))
    .filter((value) => Number.isFinite(value) && value > 0 && value <= 1000);
  if (!allTokens.length) return 0;
  const mgOnly = allTokens.filter((value) => value < 1);
  return mgOnly.length ? Math.max(...mgOnly) : Math.max(...allTokens);
}

function productMetadataFromUrl(value) {
  const parsed = new URL(value);
  const segments = decodeURIComponent(parsed.pathname).split('/').filter(Boolean).filter((s) => !/^(p|buy|dp|gp|product|gold-coin|silver-coin|coin)$/i.test(s));
  const fullSlug = segments.join(' ');
  const mainSegment = segments.at(-1) || segments.at(0) || parsed.hostname;
  const title = mainSegment.replace(/[-_+]+/g, ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
  const grams = extractGrams(title, fullSlug, parsed.pathname) || gramsFromSlug(fullSlug) || null;
  const purity = fullSlug.match(/(?<!\d)(9999|999\.9|999|995|916|750|585)(?!\d)/i)?.[1]
    || (/(?:^|\b|[-_])(24)\s*-?\s*(?:k|kt|karat)\b/i.test(fullSlug) ? '999' : '')
    || '';
  const isLikelyNonGold = isNonGoldProductText(`${title} ${fullSlug} ${parsed.pathname}`);
  return { name: title, grams, purity, isLikelyNonGold };
}
// Identity key for dedupe: full normalized URL (host + path + query, no hash).
function canonicalProductUrl(value) {
  const url = new URL(normalizeProductUrl(value));
  if (normalizeStoreHostname(url.hostname) === 'flipkart.com') {
    const marketplace = url.searchParams.get('marketplace')?.toUpperCase();
    const productId = url.searchParams.get('pid');
    if (productId && marketplace !== 'HYPERLOCAL') {
      url.search = '';
      url.searchParams.set('pid', productId);
      url.searchParams.set('marketplace', 'FLIPKART');
    } else if (productId) {
      const shopId = url.searchParams.get('shopId');
      url.search = '';
      url.searchParams.set('pid', productId);
      url.searchParams.set('marketplace', 'HYPERLOCAL');
      if (shopId) url.searchParams.set('shopId', shopId);
    }
  }
  return url.href;
}

function dedupeProducts(list) {
  const rank = (product) => (product.status === 'live' ? 3 : Number(product.price) > 0 ? 2 : product.checkedAt ? 1 : 0);
  const byKey = new Map();
  for (const product of list) {
    let key;
    try { key = canonicalProductUrl(product.url); } catch { key = product.url; }
    const existing = byKey.get(key);
    if (!existing || rank(product) > rank(existing)) byKey.set(key, product);
  }
  for (const [key, product] of byKey) product.canonicalUrl = key;
  return [...byKey.values()];
}

const mergeBrowserBridgeProducts = (store, records, details = {}) => {
  const listing = store === 'ajio.com' ? ajioListing : store === 'amazon.in' ? amazonListing : store === 'flipkart.com' ? flipkartListing : store === 'myntra.com' ? myntraListing : null;
  if (!listing) return { received: 0, updated: 0, discovered: 0, skipped: 0 };
  const knownByUrl = new Map(products.map((product) => {
    try { return [canonicalProductUrl(product.url), product]; } catch { return [product.url, product]; }
  }));
  const knownByRetailerId = new Map();
  for (const product of products.filter((item) => productStoreFor(item) === store)) {
    const id = store === 'ajio.com' ? ajioListing.ajioProductCode(product) : store === 'amazon.in' ? amazonListing.amazonProductAsin(product) : store === 'flipkart.com' ? flipkartListing.flipkartProductId(product) : myntraListing.myntraProductId(product);
    if (!id) continue;
    const matches = knownByRetailerId.get(id) || [];
    matches.push(product);
    knownByRetailerId.set(id, matches);
  }
  let updated = 0;
  let discovered = 0;
  let skipped = 0;
  for (const raw of records) {
    let candidate;

    // AJIO catalogue master already returns normalized records.
    // Do not pass these back through normalizeListingProduct(), which expects
    // the raw AJIO listing API shape (raw.url, fnlColorVariantData, etc.).
    if (store === 'ajio.com' && (raw?.link || raw?.weightGrams != null)) {
      candidate = {
        ajioCode: String(raw.code || raw.id || '').trim() || null,
        url: raw.link || raw.url || null,
        name: raw.name || null,
        brand: raw.brand || null,
        source: 'ajio.com',
        grams: Number(raw.weightGrams) > 0 ? Number(raw.weightGrams) : null,
        karat: Number(raw.karat) > 0 ? Number(raw.karat) : null,
        purity: raw.purity != null ? String(raw.purity) : null,
        metal: raw.metal || null,
        price: Number(raw.price) > 0 ? Number(raw.price) : null,
        wasPrice: Number(raw.wasPrice) > 0 ? Number(raw.wasPrice) : null,
        couponPrice: Number(raw.offerPrice) > 0 ? Number(raw.offerPrice) : null,
        sources: ['browser-bridge'],
        evidence: {
          grams: raw.weightSource || null,
          karat: raw.puritySource || null,
          purity: raw.puritySource || null,
          metal: raw.metal ? 'ajio-master' : null
        }
      };
    } else {
      candidate = listing.normalizeListingProduct(raw, 'browser-bridge');
    }

    if (raw.bridgeSnapshot) {
      if (raw.metal) candidate.metal = raw.metal;
      if (Number(raw.grams) > 0) candidate.grams = Number(raw.grams);
      if (Number(raw.karat) > 0) candidate.karat = Number(raw.karat);
      if (raw.purity) candidate.purity = String(raw.purity);
    }
    const detailKey = store === 'ajio.com' ? candidate.ajioCode : store === 'amazon.in' ? candidate.asin : candidate.productId;
    if (details[detailKey]) {
      if (store === 'ajio.com') listing.applyDetailQualifiers(candidate, listing.detailQualifiers(details[detailKey], detailKey));
      else if (store === 'myntra.com') listing.applyProductDetails(candidate, details[detailKey]);
    }
    if (!candidate.url || !candidate.price || candidate.metal !== 'gold') { skipped += 1; continue; }
    let key;
    try { key = canonicalProductUrl(candidate.url); } catch { skipped += 1; continue; }
    let existing = knownByRetailerId.get(detailKey) || [];
    if (!existing.length && knownByUrl.get(key)) existing = [knownByUrl.get(key)];

    // AJIO can expose the same style with variant IDs such as 469608182003,
    // 4696081820030_multi, or a public URL ending in 469608182_yellow.
    // If exact code/URL matching misses, fall back to the 9-digit base style code.
    if (!existing.length && store === 'ajio.com' && detailKey) {
      const baseCode = String(detailKey).match(/^\d{9}/)?.[0];
      if (baseCode) {
        existing = products.filter((product) => {
          if (productStoreFor(product) !== 'ajio.com') return false;
          const productCode = String(ajioListing.ajioProductCode(product) || '');
          return productCode.startsWith(baseCode);
        });
      }
    }

    if (existing.length) {
      for (const product of existing) {
        const now = new Date().toISOString();
        Object.assign(product, {
          name: candidate.name || product.name,
          brand: candidate.brand || product.brand,
          grams: candidate.grams ?? product.grams,
          karat: candidate.karat ?? product.karat,
          purity: candidate.purity ?? product.purity,
          price: candidate.price ?? product.price,
          couponPrice: candidate.couponPrice ?? product.couponPrice,
          checkedAt: now,
          lastLiveAt: now,
          status: 'live',
          refreshMethod: `${store}-browser-bridge`
        });
        delete product.error;
      }
      updated += existing.length;
      continue;
    }
    if (listing.qualificationReasons(candidate).length) { skipped += 1; continue; }
    const next = listing.toPersistedProduct(candidate);
    next.refreshMethod = `${store}-browser-bridge`;
    products.push(next);
    knownByUrl.set(key, next);
    knownByRetailerId.set(detailKey, [next]);
    discovered += 1;
  }
  return { received: records.length, updated, discovered, skipped };
};

const productNeedsPostMasterRefresh = (product, masterStartedAt) => {
  if (!product) return false;

  // Master successfully refreshed this product during this run.
  const checkedAt =
    product.checkedAt
      ? new Date(product.checkedAt).getTime()
      : 0;

  const lastLiveAt =
    product.lastLiveAt
      ? new Date(product.lastLiveAt).getTime()
      : 0;

  const refreshedThisRun =
    checkedAt >= masterStartedAt ||
    lastLiveAt >= masterStartedAt;

  if (refreshedThisRun && product.status === 'live') {
    return false;
  }

  // Anything not live after master needs PDP fallback.
  if (product.status !== 'live') {
    return true;
  }

  // Existing record wasn't touched by this master run.
  return !refreshedThisRun;
};

const runDirectMasterRefresh = async (targets, { includeDiscoveryMasters = false } = {}) => {
  const startedAt = Date.now();
  const runId = `refresh-${new Date().toISOString().replace(/[:.]/g, '-')}`;
  let directMasterEventNumber = 0;
  const masterRunners = new Map([
    ['ajio.com', runAjioMaster],
    ['myntra.com', runMyntraMaster],
    ['flipkart.com', runFlipkartMaster],
    ['amazon.in', runAmazonMaster]
  ]);
  const stores = includeDiscoveryMasters
    ? [...masterRunners.keys()]
    : [...new Set(targets.map(productStoreFor))].filter((store) => masterRunners.has(store));
  await mkdir(join(browserBridgeArchiveDirectory, runId), { recursive: true });
  await writeFile(join(browserBridgeArchiveDirectory, runId, 'run.json'), JSON.stringify({ runId, startedAt: new Date().toISOString(), mode: 'direct-masters', stores }, null, 2) + '\n', 'utf8');

  const reportMaster = (phase, store, details = {}) => {
    Object.assign(productRefresh, {
      current: details.current || `Direct master ${phase}: ${store}`,
      event: {
        id: `${runId}-master-${++directMasterEventNumber}`,
        type: 'direct-master',
        phase,
        store,
        ...details
      }
    });
    broadcast('progress', { productRefresh, bullionRefresh });
  };

  Object.assign(productRefresh, { running: true, total: targets.length, checked: 0, live: 0, failed: 0, current: 'Running direct catalogue masters', scope: 'direct-masters' });
  broadcast('progress', { productRefresh, bullionRefresh });

  const masterOptions = { port, visible: Boolean(state.settings.productDebugVisibleBrowser) };
  const settled = await Promise.allSettled(stores.map(async (store) => {
    reportMaster('start', store, { current: `Opening ${store} catalogue master` });
    try {
      const result = await masterRunners.get(store)(masterOptions);
      reportMaster('complete', store, {
        current: `${store} catalogue master completed`,
        openedLinks: result.openedLinks || [],
        runs: result.runs || [],
        counts: {
          products: Array.isArray(result.products) ? result.products.length : 0,
          catalogue: Array.isArray(result.catalogue) ? result.catalogue.length : 0,
          missing: Array.isArray(result.missing) ? result.missing.length : 0
        },
        complete: Boolean(result.complete)
      });
      return result;
    } catch (error) {
      reportMaster('failed', store, {
        current: `${store} catalogue master failed`,
        error: error?.message || String(error)
      });
      throw error;
    }
  }));

  const runnerResults = settled.map((result, index) => {
    if (result.status === 'fulfilled') return result.value;
    return {
      store: stores[index],
      complete: false,
      products: [],
      catalogue: [],
      missing: [],
      runs: [{ ok: false, error: result.reason?.message || String(result.reason || 'unknown master failure') }]
    };
  });

  try {
    await writeBrowserBridgeRunResult(runId, runnerResults.map(compactRunnerResult));
  } catch (error) {
    console.error('Unable to write direct master runner-results:', error?.message || error);
  }

  const mergeByStore = [];
  for (const result of runnerResults) {
    const merged = mergeBrowserBridgeProducts(result.store, result.products || [], {});
    mergeByStore.push({ store: result.store, complete: Boolean(result.complete), merged, catalogue: Array.isArray(result.catalogue) ? result.catalogue.length : 0, missing: Array.isArray(result.missing) ? result.missing.length : 0 });
    reportMaster('merged', result.store, {
      current: `${result.store} catalogue results merged`,
      complete: Boolean(result.complete),
      received: Number(merged.received || 0),
      updated: Number(merged.updated || 0),
      discovered: Number(merged.discovered || 0),
      skipped: Number(merged.skipped || 0)
    });
  }

  const manualStores = new Set(runnerResults.filter((result) => result.manual).map((result) => result.store));
  const failedStores = mergeByStore.filter((entry) => !entry.complete && !manualStores.has(entry.store)).map((entry) => entry.store);

const masterErrors = runnerResults.flatMap((result) => (result.runs || [])
  .filter((run) => !run.ok)
  .map((run) =>
    `${result.store}: ${
      run.error ||
      run.retailerError ||
      run.pageError ||
      'master run failed'
    }`
  )
);
  // ------------------------------------------------------------
  // POST-MASTER PRODUCT FALLBACK
  //
  // Full refresh:
  //   every existing target not successfully refreshed by master
  //
  // Selected-store refresh:
  //   same behaviour, but only selected store targets
  //
  // Explicit targeted IDs:
  //   should never enter this master flow
  // ------------------------------------------------------------

  const fallbackTargets =
    targets.filter((product) =>
      productNeedsPostMasterRefresh(
        product,
        startedAt
      )
    );

  const fallbackIdSet = new Set(fallbackTargets.map((product) => product.id));
  const masterCoveredTargets = targets.filter((product) => !fallbackIdSet.has(product.id));
  const masterCoveredLive = masterCoveredTargets.filter((product) => product.status === 'live').length;
  const masterCoveredFailed = masterCoveredTargets.filter((product) => product.status === 'failed').length;

  Object.assign(productRefresh, {
    checked: masterCoveredTargets.length,
    live: masterCoveredLive,
    failed: masterCoveredFailed,
    current: fallbackTargets.length
      ? `Refreshing ${fallbackTargets.length} residual products by product page`
      : null,
    note:
      failedStores.length
        ? `${stores.length - failedStores.length}/${stores.length} direct catalogue masters completed; ` +
        `${failedStores.join(', ')} failed.` +
        `${masterErrors.length ? ` ${masterErrors.join(' | ')}` : ''}` +
        `${fallbackTargets.length
          ? ` ${fallbackTargets.length} residual product(s) were refreshed directly by product page.`
          : ' No residual product-page refresh was required.'}`
        : manualStores.size
          ? `${stores.length - manualStores.size}/${stores.length} direct catalogue masters completed; ` +
          `${[...manualStores].join(', ')} requires manual master execution.` +
          `${fallbackTargets.length
            ? ` ${fallbackTargets.length} residual product(s) were refreshed directly by product page.`
            : ''}`
          : `All ${stores.length} direct catalogue masters completed.` +
          `${fallbackTargets.length
            ? ` ${fallbackTargets.length} residual product(s) were refreshed directly by product page.`
            : ' No residual product-page refresh was required.'}`,
  });
  broadcast('progress', { productRefresh, bullionRefresh });

  let fallbackResult = { checked: 0, live: 0, failed: 0, stale: 0, unverified: 0, unavailable: 0, discovered: 0, observed: 0, requests: 0 };
  if (fallbackTargets.length) {
    fallbackResult = await runSharedRuntimeProductRefresh(fallbackTargets, (progress) => {
      Object.assign(productRefresh, progress, {
        running: true,
        total: targets.length,
        checked: Math.min(
          targets.length,
          masterCoveredTargets.length + Number(progress.checked || 0)
        ),
        live: masterCoveredLive + Number(progress.live || 0),
        failed: masterCoveredFailed + Number(progress.failed || 0),
        scope: 'direct-masters'
      });
      broadcast('progress', { productRefresh, bullionRefresh });
    });
  }

  return {
    checked: targets.length,
    live: targets.filter((product) => product.status === 'live').length,
    stale: targets.filter((product) => product.status === 'stale').length,
    unverified: targets.filter((product) => product.status === 'unverified').length,
    failed: targets.filter((product) => product.status === 'failed').length,
    unavailable: targets.filter((product) => product.status === 'unavailable').length,
    discovered: mergeByStore.reduce((sum, entry) => sum + Number(entry.merged.discovered || 0), 0) + Number(fallbackResult.discovered || 0),
    observed: mergeByStore.reduce((sum, entry) => sum + Number(entry.merged.received || 0), 0) + Number(fallbackResult.observed || 0),
    requests: Number(fallbackResult.requests || 0),
    partial: failedStores.length > 0 || manualStores.size > 0,
    blocked: false,
    note: failedStores.length
      ? `${stores.length - failedStores.length}/${stores.length} direct catalogue masters completed; ${failedStores.join(', ')} failed.${masterErrors.length ? ` ${masterErrors.join(' | ')}` : ''}${fallbackTargets.length ? ` ${fallbackTargets.length} explicitly selected products entered targeted refresh after the direct-master failure.` : ' Existing catalogue records were preserved; no product pages were opened.'}`
      : manualStores.size
        ? `${stores.length - manualStores.size}/${stores.length} direct catalogue masters completed; ${[...manualStores].join(', ')} is open for manual master execution and no targeted refresh was started.`
        : `All ${stores.length} direct catalogue masters completed.${fallbackTargets.length ? ` ${fallbackTargets.length} residual product(s) were refreshed in the shared store browser.` : ' No residual product refresh was required.'}`,
    durationMs: Date.now() - startedAt,
    method: 'direct-masters'
  };
};

const bullionRefreshIntervalMs = Math.max(1, Number(process.env.BULLION_INTERVAL_MIN || 30)) * 60 * 1000;
const bullionRefreshDue = () => bullion.some((source) => {
  const refreshedAt = new Date(source.fetchedAt || source.lastLiveAt || 0).getTime();
  return !Number.isFinite(refreshedAt) || Date.now() - refreshedAt >= bullionRefreshIntervalMs;
});

const runScheduledBullionRefresh = async (force = false) => {
  if (background.shuttingDown || background.bullionRunning || (!force && !bullionRefreshDue())) return;
  background.bullionRunning = true;
  for (const source of bullion) {
    source.status = 'checking';
    delete source.error;
  }
  Object.assign(bullionRefresh, { running: true, total: bullion.length, checked: 0, live: 0, current: null, scope: 'scheduled', authRequired: false, authSource: null });
  broadcast('progress', { productRefresh, bullionRefresh });
  try {
    const result = await runBullionRefreshJob(null, (progress) => Object.assign(bullionRefresh, progress, { running: true, scope: 'scheduled' }));
    Object.assign(bullionRefresh, result, { running: false, current: null, scope: 'scheduled' });
    await saveState(state);
  } catch (error) {
    finalizeBullionFailure(null, error?.message || 'Scheduled bullion refresh failed');
    Object.assign(bullionRefresh, { running: false, current: null, scope: 'scheduled', note: error?.message || 'Scheduled bullion refresh failed' });
    await saveState(state).catch(() => { });
    console.error('Scheduled bullion refresh failed:', error.message);
  } finally {
    background.bullionRunning = false;
    broadcast('progress', { productRefresh, bullionRefresh });
  }
};

const runScheduledProductRefresh = async () => {
  if (!state.settings.productAutoRefresh || background.shuttingDown || background.productsRunning || productRefresh.running) return;
  const intervalMs = Math.max(1, Number(state.settings.productRefreshIntervalMin || 5)) * 60 * 1000;
  if (Date.now() - lastScheduledProductRefreshAt < intervalMs) return;
  lastScheduledProductRefreshAt = Date.now();
  background.productsRunning = true;
  try {
    const primaryProducts = products.filter((product) => !isSub24K(product));
    const sub24Multiplier = Math.max(2, Number(process.env.SUB24K_REFRESH_MULTIPLIER || 6));
    const sub24IntervalMs = intervalMs * sub24Multiplier;
    const includeSub24 = Date.now() - lastScheduledSub24RefreshAt >= sub24IntervalMs;
    const sub24Products = includeSub24 ? products.filter(isSub24K) : [];
    const refreshTargets = [...primaryProducts, ...sub24Products];
    if (includeSub24) lastScheduledSub24RefreshAt = Date.now();
    if (refreshTargets.length) await runProductsRefreshJob(refreshTargets);
    await saveState(state);
  } catch (error) {
    console.error('Scheduled product refresh failed:', error.message);
  } finally {
    background.productsRunning = false;
  }
};

const runStartupProductRefresh = async () => {
  if (background.shuttingDown || background.productsRunning || productRefresh.running || !products.length) return;
  background.productsRunning = true;
  const targets = [...products];
  Object.assign(productRefresh, { running: true, total: targets.length, checked: 0, live: 0, failed: 0, current: null, scope: 'startup' });
  for (const product of targets) {
    if (!['ajio.com', 'myntra.com'].includes(productStoreFor(product))) product.status = 'checking';
    delete product.error;
  }
  broadcast('progress', { productRefresh, bullionRefresh });
  await saveState(state);
  try {
    const result = await runDirectMasterRefresh(targets, { includeDiscoveryMasters: true });
    Object.assign(productRefresh, result, { running: false, total: result.checked || targets.length, checked: result.checked || targets.length, current: null, scope: 'startup' });
    await saveState(state);
  } catch (error) {
    finalizeProductFailure(error?.message || 'Startup product refresh failed', new Set(targets.map((product) => product.id)));
    Object.assign(productRefresh, { running: false, checked: targets.length, current: null, scope: 'startup', note: error?.message || 'Startup product refresh failed' });
    await saveState(state).catch(() => { });
    console.error('Startup product refresh failed:', error.message);
  } finally {
    background.productsRunning = false;
    broadcast('progress', { productRefresh, bullionRefresh });
  }
};

const terminateChildren = () => new Promise((resolve) => {
  if (process.platform === 'win32') {
    const killer = spawn('taskkill', ['/F', '/T', '/PID', String(process.pid)], { windowsHide: true });
    killer.on('error', () => resolve());
    killer.on('close', () => resolve());
  } else {
    const killer = spawn('pkill', ['-TERM', '-P', String(process.pid)], { windowsHide: true });
    killer.on('error', () => resolve());
    killer.on('close', () => resolve());
  }
});

const restartProcess = async () => {
  if (background.restarting || background.shuttingDown) return;
  background.restarting = true;
  background.shuttingDown = true;
  for (const timer of timers) clearInterval(timer);
  if (deferredSaveTimer) clearTimeout(deferredSaveTimer);
  await disposePersistentBullionWorker().catch(() => { });
  await disposePersistentProductWorkers().catch(() => { });
  await disposeMasterRuntimes().catch(() => { });
  try { await saveState(state); } catch { }
  await terminateChildren();
  const child = spawn(process.execPath, process.argv.slice(1), {
    cwd: process.cwd(),
    env: process.env,
    detached: true,
    stdio: 'ignore',
    windowsHide: true
  });
  child.unref();
  const forceExit = setTimeout(() => process.exit(1), 3000);
  forceExit.unref();
  server.close(() => {
    clearTimeout(forceExit);
    process.exit(0);
  });
};

let server;
const requestShutdown = async (reason = 'shutdown') => {
  if (background.shuttingDown) return;
  background.shuttingDown = true;
  for (const timer of timers) clearInterval(timer);
  if (deferredSaveTimer) clearTimeout(deferredSaveTimer);
  await Promise.allSettled([
    disposePersistentBullionWorker(),
    disposePersistentProductWorkers(),
    disposeMasterRuntimes()
  ]);
  const forceExit = setTimeout(() => process.exit(1), 5000);
  forceExit.unref();
  saveState(state).catch(() => { }).finally(() => {
    server.close(() => {
      clearTimeout(forceExit);
      process.exit(0);
    });
  });
  console.log(`Stopping Aurum (${reason})...`);
};

server = createServer(async (request, response) => {
  const url = new URL(request.url, `http://${request.headers.host || 'localhost'}`);
  if (request.method === 'OPTIONS') {
    response.writeHead(204, {
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'GET, POST, PATCH, DELETE, OPTIONS',
      'access-control-allow-headers': 'content-type'
    });
    response.end();
    return;
  }
  if (url.pathname === '/api/events' && request.method === 'GET') {
    response.writeHead(200, {
      'content-type': 'text/event-stream',
      'cache-control': 'no-cache, no-transform',
      'connection': 'keep-alive',
      'access-control-allow-origin': '*'
    });
    response.write(`retry: 3000\nevent: state\ndata: ${JSON.stringify(stateSnapshot())}\n\n`);
    eventClients.add(response);
    const heartbeat = setInterval(() => { try { response.write(': keepalive\n\n'); } catch { } }, 25000);
    heartbeat.unref();
    request.on('close', () => { clearInterval(heartbeat); eventClients.delete(response); });
    return;
  }
  if (url.pathname === '/api/health' && request.method === 'GET') {
    const liveBullion = bullion.filter((item) => item.status === 'live').length;
    const liveProducts = products.filter((item) => item.status === 'live').length;
    return sendJson(response, 200, {
      ok: true,
      uptimeSec: Math.round(process.uptime()),
      bullion: { total: bullion.length, live: liveBullion, refreshing: bullionRefresh.running },
      products: { total: products.length, live: liveProducts, refreshing: productRefresh.running },
      eventClients: eventClients.size
    });
  }
  if (url.pathname === '/api/state' && request.method === 'GET') return sendJson(response, 200, { settings: state.settings, bullion, products, preciousMetalProducts, updatedAt: new Date().toISOString() });
  if (url.pathname === '/api/history/bullion' && request.method === 'GET') {
    const karat = Number(url.searchParams.get('karat')) || 24;
    const limit = Math.min(500, Math.max(10, Number(url.searchParams.get('limit')) || 200));
    try {
      const history = getBullionHistory(karat, limit);
      return sendJson(response, 200, { karat, history });
    } catch (e) {
      return sendJson(response, 500, { error: e.message });
    }
  }
  if (url.pathname.startsWith('/api/history/products/') && request.method === 'GET') {
    const productId = url.pathname.split('/').pop();
    const limit = Math.min(200, Math.max(5, Number(url.searchParams.get('limit')) || 50));
    try {
      const history = getProductHistory(productId, limit);
      return sendJson(response, 200, { productId, history });
    } catch (e) {
      return sendJson(response, 500, { error: e.message });
    }
  }
  if (url.pathname === '/api/restart' && request.method === 'POST') {
    if (background.restarting) return sendJson(response, 202, { restarting: true, note: 'Restart already in progress.' });
    sendJson(response, 202, { restarting: true, note: 'Server restarting now.' });
    setTimeout(() => { void restartProcess(); }, 100);
    return;
  }
  if (url.pathname === '/api/proxy-auth' && request.method === 'GET') return sendJson(response, 200, { configured: Boolean(runtimeProxyAuth?.username), persisted: false });
  if (url.pathname === '/api/proxy-auth' && request.method === 'POST') {
    let body;
    try { body = await readBody(request); } catch { return sendJson(response, 400, { error: 'invalid JSON' }); }
    const username = String(body.username || '').trim();
    const password = String(body.password || '');
    if (!username) return sendJson(response, 400, { error: 'username is required' });
    runtimeProxyAuth = { username, password, updatedAt: new Date().toISOString() };
    bullionRefresh.authRequired = false;
    delete bullionRefresh.authSource;
    return sendJson(response, 200, { configured: true, persisted: false });
  }
  if (url.pathname === '/api/proxy-auth' && request.method === 'DELETE') {
    runtimeProxyAuth = null;
    return sendJson(response, 200, { configured: false, persisted: false });
  }
  if (url.pathname === '/api/settings' && request.method === 'POST') {
    let body;
    try { body = await readBody(request); } catch { return sendJson(response, 400, { error: 'invalid JSON' }); }
    if (body.pincode !== undefined) state.settings.pincode = String(body.pincode).trim();
    if (body.preciseAddress !== undefined) state.settings.preciseAddress = String(body.preciseAddress).trim();
    if (body.debugVisibleBrowser !== undefined) state.settings.debugVisibleBrowser = Boolean(body.debugVisibleBrowser);
    if (body.productDebugVisibleBrowser !== undefined) state.settings.productDebugVisibleBrowser = Boolean(body.productDebugVisibleBrowser);
    if (body.productAutoRefresh !== undefined) state.settings.productAutoRefresh = Boolean(body.productAutoRefresh);
    if (body.productRefreshIntervalMin !== undefined) state.settings.productRefreshIntervalMin = Math.max(1, Math.min(1440, Number(body.productRefreshIntervalMin) || 5));
    if (body.refreshProductsOnStart !== undefined) state.settings.refreshProductsOnStart = Boolean(body.refreshProductsOnStart);
    if (body.refreshBullionOnStart !== undefined) state.settings.refreshBullionOnStart = Boolean(body.refreshBullionOnStart);
    await saveState(state);
    return sendJson(response, 200, state.settings);
  }
  if (url.pathname === '/api/rates' && request.method === 'POST') {
    let body;
    try { body = await readBody(request); } catch { return sendJson(response, 400, { error: 'invalid JSON' }); }
    const target = bullion.find((item) => item.id === body.source);
    if (!target || !Number.isFinite(Number(body.price))) return sendJson(response, 400, { error: 'source and numeric price are required' });
    target.price = Number(body.price); target.price24 = Number(body.price); target.fetchedAt = new Date().toISOString(); target.status = 'live'; delete target.error; await saveState(state);
    return sendJson(response, 200, target);
  }
  if (url.pathname === '/api/browser-bridge/products' && request.method === 'POST') {
    let body;
    try { body = await readBody(request); } catch { return sendJson(response, 400, { error: 'invalid JSON' }); }
    const store = normalizeStoreHostname(body.store);
    const records = Array.isArray(body.records) ? body.records : [];
    const details = body.details && typeof body.details === 'object' ? body.details : {};
    if (!['ajio.com', 'amazon.in', 'flipkart.com', 'myntra.com'].includes(store) || !records.length) return sendJson(response, 400, { error: 'supported store and non-empty records are required' });
    const result = mergeBrowserBridgeProducts(store, records, details);
    const archive = await archiveBrowserBridgePayload(store, body, result);
    await saveState(state);
    if (browserBridgeRefresh?.expectedStores.has(store)) {
      browserBridgeRefresh.receivedStores.add(store);
      const fresh = products.filter((product) => browserBridgeRefresh.expectedStores.has(productStoreFor(product)) && new Date(product.lastLiveAt || 0).getTime() >= browserBridgeRefresh.startedAt).length;
      Object.assign(productRefresh, { checked: fresh, live: fresh, current: `Browser ${store} snapshot received (${browserBridgeRefresh.receivedStores.size}/${browserBridgeRefresh.expectedStores.size})` });
      if (browserBridgeRefresh.receivedStores.size === browserBridgeRefresh.expectedStores.size) browserBridgeRefresh.complete();
    }
    broadcast();
    return sendJson(response, 200, { ok: true, store, archive, ...result });
  }
  if (url.pathname === '/api/browser-bridge/raw' && request.method === 'GET') {
    const archives = await readBrowserBridgeArchives();
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    response.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'content-disposition': `attachment; filename="aurum-browser-bridge-raw-${stamp}.json"` });
    response.end(JSON.stringify({ exportedAt: new Date().toISOString(), archives }, null, 2));
    return;
  }
  if (url.pathname === '/api/products' && request.method === 'POST') {
    let body;
    try { body = await readBody(request); } catch { return sendJson(response, 400, { error: 'invalid JSON' }); }
    if (!body.url) return sendJson(response, 400, { error: 'url is required' });
    try { new URL(body.url); } catch { return sendJson(response, 400, { error: 'url must be valid' }); }
    const normalizedUrl = normalizeProductUrl(body.url);
    const canonicalUrl = canonicalProductUrl(normalizedUrl);
    const existing = products.find((product) => canonicalProductUrl(product.url) === canonicalUrl);
    if (existing) return sendJson(response, 409, { error: 'product already tracked', product: existing });
    const supplied = body.name && Number(body.grams) > 0 && Number(body.price) > 0 && body.purity;
    const metadata = productMetadataFromUrl(normalizedUrl);
    if (metadata.isLikelyNonGold) return sendJson(response, 400, { error: 'Only gold products are supported. This URL appears to be non-gold.' });
    const product = { id: randomUUID(), name: supplied ? body.name : metadata.name, brand: body.brand || '', source: body.source || new URL(normalizedUrl).hostname, grams: supplied ? Number(body.grams) : metadata.grams, price: supplied ? Number(body.price) : null, purity: supplied ? body.purity : metadata.purity, url: normalizedUrl, canonicalUrl, checkedAt: supplied ? new Date().toISOString() : null, status: supplied ? 'live' : 'checking' };
    products.push(product);
    if (!supplied) runSingleProductRefreshJob(product, { forceVisible: true }).then(() => saveState(state)).catch(() => saveState(state));
    else await saveState(state);
    return sendJson(response, 202, product);
  }
  if (url.pathname === '/api/products/bulk' && request.method === 'POST') {
    let body;
    try { body = await readBody(request); } catch { return sendJson(response, 400, { error: 'invalid JSON' }); }
    const inputUrls = Array.isArray(body.urls) ? body.urls : String(body.urls || '').split(/\r?\n/);
    const seen = new Set(products.map((product) => { try { return canonicalProductUrl(product.url); } catch { return product.url; } }));
    const targets = [];
    let skippedTracked = 0;
    let skippedRepeated = 0;
    let skippedInvalid = 0;
    let skippedNon24K = 0;
    const invalidSamples = [];
    const batchSeen = new Set();
    for (const value of inputUrls) {
      // Pasted lists often carry quotes, bullets or trailing commas around the link.
      const rawUrl = String(value || '').trim().match(/https?:\/\/[^\s"'<>]+/i)?.[0]?.replace(/[),.;'"]+$/, '') || '';
      if (!rawUrl) {
        if (String(value || '').trim()) { skippedInvalid += 1; if (invalidSamples.length < 5) invalidSamples.push(String(value).trim().slice(0, 120)); }
        continue;
      }
      let normalizedUrl;
      try { normalizedUrl = normalizeProductUrl(rawUrl); } catch { skippedInvalid += 1; if (invalidSamples.length < 5) invalidSamples.push(rawUrl.slice(0, 120)); continue; }
      const canonicalUrl = canonicalProductUrl(normalizedUrl);
      if (batchSeen.has(canonicalUrl)) { skippedRepeated += 1; continue; }
      if (seen.has(canonicalUrl)) { skippedTracked += 1; batchSeen.add(canonicalUrl); continue; }
      batchSeen.add(canonicalUrl);
      seen.add(canonicalUrl);
      const metadata = productMetadataFromUrl(normalizedUrl);
      if (metadata.isLikelyNonGold) { skippedNon24K += 1; continue; }
      const product = { id: randomUUID(), name: metadata.name, brand: '', source: new URL(normalizedUrl).hostname, grams: metadata.grams, price: null, purity: metadata.purity, url: normalizedUrl, canonicalUrl, checkedAt: null, status: 'checking' };
      products.push(product);
      targets.push(product);
    }
    const skipReport = { skipped: skippedTracked + skippedRepeated + skippedInvalid + skippedNon24K, skippedTracked, skippedRepeated, skippedInvalid, skippedNon24K, invalidSamples, received: inputUrls.length };
    if (!targets.length) return sendJson(response, 200, { added: 0, ...skipReport });
    const affectedSources = new Set(targets.map((product) => product.source));
    const refreshTargets = products.filter((product) => affectedSources.has(product.source));
    Object.assign(productRefresh, { running: true, total: refreshTargets.length, checked: 0, live: 0, failed: 0, current: null });
    await saveState(state);
    runProductsRefreshJob(refreshTargets, (progress) => Object.assign(productRefresh, progress, { running: true }), { productBulkHeadless: !state.settings.productDebugVisibleBrowser, productFallbackVisibleOnFailure: false }).then((result) => { Object.assign(productRefresh, result, { running: false, total: result.checked || refreshTargets.length, checked: result.checked || refreshTargets.length, current: null }); return saveState(state); }).catch((error) => { finalizeProductFailure(error?.message || 'bulk product refresh failed', new Set(refreshTargets.map((item) => item.id))); Object.assign(productRefresh, { running: false, checked: refreshTargets.length, current: null }); return saveState(state); });
    return sendJson(response, 202, { added: targets.length, ...skipReport, refreshing: refreshTargets.length });
  }
  if (url.pathname.startsWith('/api/products/') && request.method === 'PATCH') {
    let body;
    try { body = await readBody(request); } catch { return sendJson(response, 400, { error: 'invalid JSON' }); }
    const product = products.find((item) => item.id === url.pathname.split('/').pop());
    if (!product) return sendJson(response, 404, { error: 'product not found' });
    const grams = Number(body.grams);
    const price = Number(body.price);
    const couponPrice = body.couponPrice === '' || body.couponPrice === null || body.couponPrice === undefined ? null : Number(body.couponPrice);
    if (!String(body.name || '').trim() || !Number.isFinite(grams) || grams <= 0 || !Number.isFinite(price) || price <= 0 || (couponPrice !== null && (!Number.isFinite(couponPrice) || couponPrice <= 0))) {
      return sendJson(response, 400, { error: 'name, weight, and original price are required; values must be positive' });
    }
    const editedAt = new Date().toISOString();
    Object.assign(product, { name: String(body.name).trim(), brand: String(body.brand || '').trim(), grams, purity: String(body.purity || '').trim(), price, couponPrice, status: 'live', checkedAt: editedAt, lastLiveAt: editedAt, manuallyEditedAt: editedAt });
    delete product.error;
    await saveState(state);
    return sendJson(response, 200, product);
  }
  if (url.pathname.startsWith('/api/products/') && request.method === 'DELETE') {
    const id = url.pathname.split('/').pop();
    const index = products.findIndex((product) => product.id === id);
    if (index < 0) return sendJson(response, 404, { error: 'product not found' });
    const [removed] = products.splice(index, 1);
    await saveState(state);
    return sendJson(response, 200, removed);
  }
  if (url.pathname.startsWith('/api/products/') && url.pathname.endsWith('/retry') && request.method === 'POST') {
    const product = products.find((item) => item.id === url.pathname.split('/').at(-2));
    if (!product) return sendJson(response, 404, { error: 'product not found' });
    product.status = 'checking'; product.checkedAt = null; delete product.error; await saveState(state);
    runSingleProductRefreshJob(product).then(() => saveState(state)).catch((error) => {
      finalizeProductFailure(error?.message || 'single product refresh failed', new Set([product.id]));
      return saveState(state);
    });
    return sendJson(response, 202, product);
  }
  if (url.pathname === '/api/refresh' && request.method === 'POST') {
    if (background.bullionRunning) return sendJson(response, 202, bullionRefresh);
    background.bullionRunning = true;
    Object.assign(bullionRefresh, { running: true, total: bullion.length, checked: 0, live: 0, current: null, scope: 'all', authRequired: false, authSource: null });
    bullion.forEach((item) => {
      item.status = 'checking';
      delete item.error;
    });
    await saveState(state);
    runBullionRefreshJob(null, (progress) => Object.assign(bullionRefresh, progress, { running: true, scope: 'all' }))
      .then((result) => {
        Object.assign(bullionRefresh, result, { running: false, current: null, scope: 'all' });
        background.bullionRunning = false;
        return saveState(state);
      })
      .catch((error) => {
        finalizeBullionFailure(null, error?.message || 'Bullion refresh failed');
        Object.assign(bullionRefresh, { running: false, current: null });
        background.bullionRunning = false;
        console.error('Bullion refresh failed:', error?.message || error);
        void saveState(state);
      });
    return sendJson(response, 202, bullionRefresh);
  }
  if (url.pathname.startsWith('/api/refresh/') && request.method === 'POST') {
    if (background.bullionRunning) return sendJson(response, 202, bullionRefresh);
    background.bullionRunning = true;
    const sourceId = url.pathname.split('/').pop();
    const target = bullion.find((item) => item.id === sourceId);
    if (!target) {
      background.bullionRunning = false;
      return sendJson(response, 404, { error: 'source not found' });
    }
    Object.assign(bullionRefresh, { running: true, total: 1, checked: 0, live: 0, current: sourceId, scope: sourceId, authRequired: false, authSource: null });
    target.status = 'checking';
    delete target.error;
    await saveState(state);
    runBullionRefreshJob([sourceId], (progress) => Object.assign(bullionRefresh, progress, { running: true, scope: sourceId }))
      .then((result) => {
        Object.assign(bullionRefresh, result, { running: false, current: null, scope: sourceId });
        background.bullionRunning = false;
        return saveState(state);
      })
      .catch((error) => {
        finalizeBullionFailure([sourceId], error?.message || 'Bullion source refresh failed');
        Object.assign(bullionRefresh, { running: false, current: null, scope: sourceId });
        background.bullionRunning = false;
        console.error('Bullion source refresh failed:', error?.message || error);
        void saveState(state);
      });
    return sendJson(response, 202, bullionRefresh);
  }
  if (url.pathname === '/api/products/refresh' && request.method === 'POST') {
    if (productRefresh.running) return sendJson(response, 202, productRefresh);
    let body;
    try { body = await readBody(request); } catch { return sendJson(response, 400, { error: 'invalid JSON' }); }
    const requestedIds = Array.isArray(body.productIds) ? new Set(body.productIds.filter(Boolean)) : null;
    const requestedStores = Array.isArray(body.stores) ? new Set(body.stores.map((store) => normalizeStoreHostname(store)).filter(Boolean)) : null;
    const requestedKarats = Array.isArray(body.karats) ? new Set(body.karats.map((karat) => Number(karat)).filter((karat) => Number.isFinite(karat))) : null;
    const gramsMin = Number.isFinite(Number(body.gramsMin)) && body.gramsMin !== '' && body.gramsMin != null ? Number(body.gramsMin) : null;
    const gramsMax = Number.isFinite(Number(body.gramsMax)) && body.gramsMax !== '' && body.gramsMax != null ? Number(body.gramsMax) : null;
    const staleOnly = Boolean(body.staleOnly);
    const refreshMode =
      ['full', 'selected-stores', 'targeted-products'].includes(body.refreshMode)
        ? body.refreshMode
        : requestedIds
          ? 'targeted-products'
          : requestedStores
            ? 'selected-stores'
            : staleOnly
              ? 'targeted-products'
              : 'full';
    const includeDiscoveryMasters =
      refreshMode === 'full'; const targets = products
        .filter((product) => !requestedIds || requestedIds.has(product.id))
        .filter((product) => !requestedStores || requestedStores.has(productStoreFor(product)))
        .filter((product) => !requestedKarats || requestedKarats.has(productKarat(product)))
        .filter((product) => gramsMin === null || (Number(product.grams) || 0) >= gramsMin)
        .filter((product) => gramsMax === null || (Number(product.grams) || 0) <= gramsMax)
        .filter((product) => !staleOnly || product.status === 'stale' || product.status === 'unverified' || product.status === 'failed' || product.status === 'unavailable');
    if (!targets.length) return sendJson(response, 400, { error: staleOnly ? 'No stale products to refresh.' : 'No tracked products match the selected filters.' });
    const useDirectMasters =
      refreshMode === 'full' ||
      refreshMode === 'selected-stores';
    if (useDirectMasters) {
      Object.assign(productRefresh, { running: true, total: targets.length, checked: 0, live: 0, failed: 0, current: 'Running direct catalogue masters', authRequired: false, authSource: null, note: null, event: null, method: null, scope: 'direct-masters', partial: false, blocked: false });
      await saveState(state);
      void runDirectMasterRefresh(targets, { includeDiscoveryMasters }).then((result) => {
        Object.assign(productRefresh, result, { running: false, total: products.length, checked: products.length, current: null });
        return saveState(state);
      }).catch((error) => {
        finalizeProductFailure(error?.message || 'direct master refresh failed', new Set(targets.map((item) => item.id)));
        Object.assign(productRefresh, { running: false, checked: targets.length, current: null, note: error?.message || 'direct master refresh failed' });
        return saveState(state);
      });
      return sendJson(response, 202, productRefresh);
    }
    Object.assign(productRefresh, { running: true, total: targets.length, checked: 0, live: 0, failed: 0, current: null, authRequired: false, authSource: null });
    targets.forEach((item) => {
      if (!['ajio.com', 'flipkart.com', 'myntra.com'].includes(productStoreFor(item))) item.status = 'checking';
      delete item.error;
    });
    await saveState(state);
    runSharedRuntimeProductRefresh(targets, (progress) => Object.assign(productRefresh, progress)).then((result) => { Object.assign(productRefresh, result, { running: false, total: result.checked || targets.length, checked: result.checked || targets.length, current: null }); return saveState(state); }).catch((error) => {
      finalizeProductFailure(error?.message || 'product refresh failed', new Set(targets.map((item) => item.id)));
      Object.assign(productRefresh, { running: false, checked: targets.length, current: null });
      console.error('Product refresh failed:', error?.message || error);
      void saveState(state);
    });
    return sendJson(response, 202, productRefresh);
  }
  if (url.pathname === '/api/products/progress' && request.method === 'GET') return sendJson(response, 200, productRefresh);
  if (url.pathname === '/api/bullion/progress' && request.method === 'GET') return sendJson(response, 200, bullionRefresh);
  const filePath = join(root, 'public', url.pathname === '/' || url.pathname === '/mobile' ? 'index.html' : url.pathname);
  try {
    const content = await readFile(filePath);
    const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.webmanifest': 'application/manifest+json' };
    response.writeHead(200, { 'content-type': types[extname(filePath)] || 'application/octet-stream' });
    response.end(content);
  } catch { sendJson(response, 404, { error: 'not found' }); }
});

server.listen(port, process.env.HOST || '0.0.0.0', () => {
  console.log(`Aurum running at http://localhost:${port}`);
  const checkingProducts = products.filter((p) => p.status === 'checking');
  if (checkingProducts.length > 0) {
    console.log(`Resuming previous in-flight refresh for ${checkingProducts.length} products...`);
    Object.assign(productRefresh, { running: true, total: checkingProducts.length, checked: 0, live: 0, failed: 0, current: null });
    runProductsRefreshJob(checkingProducts, (progress) => Object.assign(productRefresh, progress))
      .then((result) => {
        Object.assign(productRefresh, result, { running: false, total: result.checked || checkingProducts.length, current: null });
        return saveState(state);
      })
      .catch((error) => {
        finalizeProductFailure(error?.message || 'resumed refresh failed', new Set(checkingProducts.map((i) => i.id)));
        Object.assign(productRefresh, { running: false, checked: checkingProducts.length, current: null });
        void saveState(state);
      });
  }
  const refreshBullionOnStart = state.settings.refreshBullionOnStart || process.env.AUTO_REFRESH_ON_START === '1';
  void (async () => {
    if (refreshBullionOnStart) {
      console.log('Startup bullion refresh enabled.');
      await runScheduledBullionRefresh(true);
    }
    else await runScheduledBullionRefresh();
    if (state.settings.refreshProductsOnStart && checkingProducts.length === 0) {
      console.log('Startup product refresh enabled.');
      await runStartupProductRefresh();
    }
  })();
});
schedule(() => { void runScheduledBullionRefresh(); }, 60 * 1000);
schedule(() => { void runScheduledProductRefresh(); }, 60 * 1000);

process.on('SIGINT', () => { void requestShutdown('SIGINT'); });
process.on('SIGTERM', () => { void requestShutdown('SIGTERM'); });
