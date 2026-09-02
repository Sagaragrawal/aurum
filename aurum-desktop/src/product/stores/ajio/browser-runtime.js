const RUNTIME_NAME = '__aurumAjioListing';
const RUNTIME_VERSION = 2;

const installRuntime = () => {
  const existing = globalThis.__aurumAjioListing;
  if (existing?.version === 2 && typeof existing.fetchJson === 'function' && typeof existing.collectCards === 'function') return;

  globalThis.__aurumAjioListing = {
    version: 2,
    state: {
      status: 'ready',
      phase: 'idle',
      categoryId: null,
      page: null,
      requests: 0,
      lastUrl: null,
      lastStatus: null,
      lastError: null,
      updatedAt: new Date().toISOString()
    },
    update(patch = {}) {
      Object.assign(this.state, patch, { updatedAt: new Date().toISOString() });
      return this.state;
    },
    async fetchJson(requestUrl, timeoutMs) {
      this.update({ status: 'running', requests: this.state.requests + 1, lastUrl: requestUrl, lastStatus: null, lastError: null });
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetch(requestUrl, {
          credentials: 'include',
          cache: 'no-store',
          headers: { accept: 'application/json, text/plain, */*' },
          signal: controller.signal
        });
        const body = await response.text();
        this.update({ status: response.ok ? 'ready' : 'error', lastStatus: response.status });
        return { ok: response.ok, status: response.status, body };
      } catch (error) {
        const message = `${error?.name || 'Error'}: ${error?.message || error}`;
        this.update({ status: 'error', lastStatus: 0, lastError: message });
        return { ok: false, status: 0, error: message };
      } finally {
        clearTimeout(timeout);
      }
    },
    async collectCards(options = {}) {
      const maxBatches = Number(options.maxBatches) || 150;
      const noGrowthLimit = Number(options.noGrowthLimit) || 18;
      const delayMs = Number(options.delayMs) || 600;
      const products = new Map();
      let noGrowth = 0;

      const clean = (value) => String(value ?? '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
      const money = (value) => {
        const match = String(value || '').match(/[\d,]+(?:\.\d+)?/);
        const number = match ? Number(match[0].replaceAll(',', '')) : null;
        return Number.isFinite(number) && number > 0 ? number : null;
      };
      const capture = () => {
        let added = 0;
        for (const anchor of document.querySelectorAll('a[href*="/p/"]')) {
          let url;
          try { url = new URL(anchor.href, location.origin === 'null' ? 'https://www.ajio.com' : location.origin); } catch { continue; }
          url.search = '';
          url.hash = '';
          const id = url.pathname.match(/\/p\/([^/?#]+)/i)?.[1];
          if (!id) continue;
          const card = anchor.closest('.rilrtl-products-list__item') || anchor;
          const brand = clean(card.querySelector('.brand')?.textContent);
          const itemName = clean(card.querySelector('.nameCls')?.textContent);
          const imageAlt = clean(card.querySelector('img[alt*="Product image"]')?.getAttribute('alt')).replace(/^Product image of\s*/i, '');
          const name = clean(brand && itemName ? `${brand} ${itemName}` : itemName || imageAlt);
          const text = clean(card.textContent);
          const price = money(card.querySelector('.price')?.textContent)
            || money(text.match(/₹\s*[\d,]+(?:\.\d+)?/)?.[0]);
          const wasPrice = money(card.querySelector('.orginal-price')?.textContent);
          const offerPrice = money(card.querySelector('.offer-pricess-new')?.textContent)
            || money(text.match(/(?:Offer|Best)\s*Price\s*:?\s*₹\s*[\d,]+/i)?.[0]);
          const incoming = { id, url: url.href, name, brand, text, imageAlt, price, wasPrice, offerPrice };
          const previous = products.get(id);
          products.set(id, previous ? { ...previous, ...Object.fromEntries(Object.entries(incoming).filter(([, value]) => value !== null && value !== '')) } : incoming);
          if (!previous) added += 1;
        }
        return added;
      };

      capture();
      for (let batch = 0; batch < maxBatches; batch += 1) {
        const before = products.size;
        const oldHeight = document.documentElement.scrollHeight;
        const grid = document.querySelector('.ReactVirtualized__Grid.items, .ReactVirtualized__Grid');
        if (grid) {
          const gridMax = Math.max(0, grid.scrollHeight - grid.clientHeight);
          grid.scrollTop = Math.min(gridMax, grid.scrollTop + Math.max(300, grid.clientHeight * 0.8));
          grid.dispatchEvent(new Event('scroll', { bubbles: true }));
        }
        scrollTo(0, Math.max(0, oldHeight - innerHeight * 1.5));
        await new Promise((resolve) => setTimeout(resolve, delayMs));
        capture();
        if (products.size === before) {
          if (grid) {
            grid.scrollTop = Math.max(0, grid.scrollTop - Math.max(500, grid.clientHeight));
            grid.dispatchEvent(new Event('scroll', { bubbles: true }));
          }
          scrollBy(0, -Math.max(500, innerHeight));
          await new Promise((resolve) => setTimeout(resolve, Math.max(150, delayMs / 2)));
          scrollTo(0, document.documentElement.scrollHeight);
          await new Promise((resolve) => setTimeout(resolve, delayMs));
          capture();
        }
        const grew = products.size > before || document.documentElement.scrollHeight > oldHeight;
        noGrowth = grew ? 0 : noGrowth + 1;
        this.update({ phase: 'dom-scroll', page: batch + 1, observed: products.size });
        await globalThis.__aurumAjioProgress?.({ batch: batch + 1, observed: products.size, added: products.size - before, noGrowth });
        if (noGrowth >= noGrowthLimit) break;
      }
      return [...products.values()];
    }
  };
};

export async function ensureAjioBrowserRuntime(page) {
  await page.addInitScript(installRuntime);
  await page.evaluate(installRuntime);
  return RUNTIME_NAME;
}

export async function updateAjioBrowserRuntime(page, patch) {
  await page.evaluate(({ runtimeName, update }) => {
    globalThis[runtimeName]?.update(update);
  }, { runtimeName: RUNTIME_NAME, update: patch });
}

export async function fetchAjioJson(page, url, timeoutMs) {
  return page.evaluate(async ({ runtimeName, requestUrl, requestTimeoutMs }) => {
    const runtime = globalThis[runtimeName];
    if (!runtime?.fetchJson) throw new Error('AJIO browser runtime is not installed');
    return runtime.fetchJson(requestUrl, requestTimeoutMs);
  }, { runtimeName: RUNTIME_NAME, requestUrl: url, requestTimeoutMs: timeoutMs });
}

const progressCallbacks = new WeakMap();
const progressBindings = new WeakSet();

export async function collectAjioCards(page, options, onProgress = () => {}) {
  progressCallbacks.set(page, onProgress);
  if (!progressBindings.has(page)) {
    await page.exposeFunction('__aurumAjioProgress', (progress) => progressCallbacks.get(page)?.(progress));
    progressBindings.add(page);
  }
  return page.evaluate(async ({ runtimeName, runtimeOptions }) => {
    const runtime = globalThis[runtimeName];
    if (!runtime?.collectCards) throw new Error('AJIO browser runtime is not installed');
    return runtime.collectCards(runtimeOptions);
  }, { runtimeName: RUNTIME_NAME, runtimeOptions: options });
}

export { RUNTIME_NAME, RUNTIME_VERSION };
