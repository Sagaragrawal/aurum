// Reusable Playwright context/page pool for long-lived store workers.
// One direct-network context is kept per browser; pages are recycled between products.
const pools = new WeakMap();
const maxIdlePages = Math.max(0, Number(process.env.PRODUCT_POOL_IDLE_PAGES || 1));
const blankIdlePages = process.env.PRODUCT_POOL_BLANK_IDLE !== '0';

export async function acquirePooledPage(browser, settings, createContext, options = {}) {
  let byKey = pools.get(browser);
  if (!byKey) { byKey = new Map(); pools.set(browser, byKey); }
  const key = 'direct';
  let pool = byKey.get(key);
  if (!pool) {
    const context = await createContext(browser, {
      useDefaultUserAgent: true
    });
    pool = { context, idle: [], all: new Set() };
    byKey.set(key, pool);
  }

  let page = pool.idle.pop();
  while (page?.isClosed()) page = pool.idle.pop();
  if (!page) {
    page = await pool.context.newPage();
    pool.all.add(page);
    if (options.blockHeavyAssets !== false) {
      await page.route('**/*', async (route) => {
        const type = route.request().resourceType();
        if (type === 'image' || type === 'media' || type === 'font') return route.abort().catch(() => {});
        return route.continue().catch(() => {});
      }).catch(() => {});
    }
  }
  return { page, pool };
}

export async function releasePooledPage(handle, healthy = true) {
  const { page, pool } = handle || {};
  if (!page || !pool) return;
  if (!healthy || page.isClosed()) {
    pool.all.delete(page);
    await page.close().catch(() => {});
    return;
  }
  // Keep the context/cookies warm, but release the heavy product DOM once the page is idle.
  // about:blank dramatically reduces retained layout/JS memory without destroying the session.
  if (blankIdlePages) await page.goto('about:blank', { waitUntil: 'commit', timeout: 2500 }).catch(() => {});
  if (pool.idle.length >= maxIdlePages) {
    pool.all.delete(page);
    await page.close().catch(() => {});
    return;
  }
  pool.idle.push(page);
}
