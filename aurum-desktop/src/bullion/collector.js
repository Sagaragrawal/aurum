/**
 * Bullion collector module
 * Fetches 24K gold prices from various Indian jewelry sources
 * Keeps bullion logic completely isolated from product logic
 */

import { wait, withTimeout, getHostDelay, recordHostRequest } from '../common/utils.js';
import { launchBrowser, suppressDialogs, dismissCommonPopups, isAccessBlockedText, createBrowserContext } from '../common/browser.js';
import { parseBullionPrice, parseBullionRates } from './parser.js';
import { BULLION_SOURCE_IDS } from './sources.js';
import { getEnvironmentConfig } from '../common/environment.js';

const env = getEnvironmentConfig();
const persistentBullionRuntime = { browserPromises: new Map() };

const defaultBullionBrowserName = process.env.BULLION_BROWSER || 'firefox';

const ensurePersistentBullionBrowser = (headless, browserName = defaultBullionBrowserName) => {
  const key = `${browserName}-${headless ? 'headless' : 'headed'}`;
  if (!persistentBullionRuntime.browserPromises.has(key)) {
    persistentBullionRuntime.browserPromises.set(key,
      import('playwright').then((playwright) => launchBrowser(playwright, headless, browserName))
    );
  }
  return persistentBullionRuntime.browserPromises.get(key);
};

export async function disposeBullionRuntime() {
  await Promise.allSettled(
    [...persistentBullionRuntime.browserPromises.values()].map(async (browserPromise) => (await browserPromise).close())
  );
  persistentBullionRuntime.browserPromises.clear();
}

// Source-specific timeout configurations
const getSourceRenderTimeout = (sourceId) => {
  if (sourceId === 'tan') return env.bullionRenderTimeoutTanMs;
  if (sourceId === 'mmtc') return env.bullionRenderTimeoutMmtcMs;
  return env.bullionRenderTimeoutMs;
};

const getSourceFetchTimeout = (sourceId) => 
  sourceId === 'mmtc' ? env.bullionFetchTimeoutMmtcMs : env.bullionFetchTimeoutMs;

/**
 * Dismiss Tanishq-specific modal overlays
 */
async function dismissTanishqOverlay(page) {
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const closed = await page.evaluate(() => {
      const close = document.querySelector('#pge-close-x');
      if (close) {
        close.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
        close.click?.();
      }
      const overlay = document.querySelector('#pge-modal-overlay');
      if (!overlay) return true;
      const element = overlay;
      const hidden = element.style.display === 'none' || element.getAttribute('aria-hidden') === 'true';
      if (!hidden) {
        element.style.display = 'none';
        element.setAttribute('aria-hidden', 'true');
      }
      return true;
    }).catch(() => false);
    if (closed) return;
    await page.waitForTimeout(250);
  }
}

/**
 * Fill pincode field on bullion websites (for location-based pricing)
 */
async function fillPincodeField(page, settings = {}) {
  const candidates = page.locator('input[placeholder*="pin" i], input[placeholder*="postal" i], input[name*="pin" i], input[name*="postal" i], input[aria-label*="pin" i], input[aria-label*="postal" i]');
  if (await candidates.count()) {
    await candidates.first().fill(settings.pincode || process.env.PINCODE || '560048').catch(() => {});
    const check = page.getByRole('button', { name: /check|submit|apply/i }).first();
    if (await check.count()) await check.click({ force: true }).catch(() => {});
  }
}

/**
 * Fetch bullion price from rendered HTML (with browser automation)
 * Handles complex dynamic websites that require JavaScript execution
 */
async function fetchRenderedPrice(source, settings = {}, options = {}) {
  let playwright;
  if (!options.browserPromise) {
    try { playwright = await import('playwright'); } catch { return null; }
  }

  const headless = options.headless ?? true;
  const browser = options.browserPromise
    ? await options.browserPromise
    : await launchBrowser(playwright, headless);
  const ownsBrowser = !options.browserPromise;

  try {
    const context = await createBrowserContext(browser);
    const page = await context.newPage();
    suppressDialogs(page);
    if (!headless) await page.bringToFront();

    const targetUrl = new URL(source.url).href;
    let navigationResponse;

    try {
      navigationResponse = await page.goto(targetUrl, { waitUntil: 'commit', timeout: 30000 });
      if (!headless) await page.bringToFront();
    } catch (error) {
      const message = String(error?.message || '');
      if (/proxy|407|auth/i.test(message)) {
        // Surface to the UI credential popup rather than opening a separate visible browser.
        throw new Error('Proxy authentication required. Enter ID/password in the prompt to continue.');
      }
      throw error;
    }

    // Tanishq: extract price from initial response or rendered DOM if available
    if (source.id === 'tan') {
      const responseHtml = await navigationResponse?.text().catch(() => '');
      let initialPrice = parseBullionPrice(responseHtml, 'tan');
      if (!initialPrice) {
        await page.waitForTimeout(2000);
        const pageHtml = await page.content().catch(() => '');
        initialPrice = parseBullionPrice(pageHtml, 'tan');
      }
      if (initialPrice) return initialPrice;
    }

    // Wait for page to stabilize before interaction
    await page.waitForTimeout(source.id === 'malabar' ? 500 : 3000);

    // Malabar: wait for rate text to appear
    if (source.id === 'malabar') {
      await page.waitForFunction(
        () => /INR\s*\/\s*gms?/i.test(document.body?.innerText || ''),
        undefined,
        { timeout: 6000 }
      ).catch(() => {});
    }

    // Dismiss popups and fill pincode
    for (let attempt = 0; attempt < 2; attempt += 1) {
      await dismissCommonPopups(page);
      if (source.id === 'tan') await dismissTanishqOverlay(page);
      await fillPincodeField(page, settings);
      await dismissCommonPopups(page);
      if (source.id === 'tan') await dismissTanishqOverlay(page);
      await page.waitForTimeout(500);
    }

    // Click on 24 Karat option if available
    const karat24Labels = ['24 Karat', '24K', '24 K', '24 Kt', '24Kt'];
    await page.evaluate((labels) => {
      const elements = [...document.querySelectorAll('button,a,[role="tab"],div,span')];
      for (const label of labels) {
        const target = elements.find((element) => element.textContent.trim().toLowerCase() === label.toLowerCase());
        if (target) {
          target.click();
          return;
        }
      }
    }, karat24Labels);

    // Wait for MMTC to load rate display
    if (source.id === 'mmtc') {
      await page.waitForFunction(
        () => /24k\s*Gold\s*Rate\s*\(Exc\.\s*GST\)[\s\S]{0,500}(?:₹|Rs\.?|INR)\s*[\d,]+(?:\.\d+)?/i.test(document.body?.innerText || ''),
        undefined,
        { timeout: 10000 }
      ).catch(() => {});
    } else {
      await page.waitForTimeout(900);
    }

    // Get page content and check for access blocks
    const bodyText = await page.evaluate(() => document.body?.innerText || '').catch(() => '');
    await dismissCommonPopups(page);

    if (isAccessBlockedText(bodyText)) return null;

    const html = await page.content();
    if (source.id !== 'malabar' && isAccessBlockedText(html)) return null;

    // Tanishq: complex DOM interaction for rate extraction
    if (source.id === 'tan') {
      await dismissTanishqOverlay(page);

      const ensureTanishq24K = async () => {
        for (let attempt = 0; attempt < 6; attempt += 1) {
          await dismissTanishqOverlay(page);
          await page.locator('.select-gold-purity-menu .select-btn').first().click({ force: true, timeout: 1500 }).catch(() => {});
          await page.locator('.select-gold-purity-menu .option[data-value="24 Karat"]').first().click({ force: true, timeout: 1500 }).catch(() => {});
          await page.waitForTimeout(350);
          const selected = await page.evaluate(() => document.querySelector('.select-gold-purity-menu .sBtn-text')?.textContent?.trim() || '').catch(() => '');
          if (/24\s*karat/i.test(selected)) return true;
          await page.evaluate(() => {
            const option24 = document.querySelector('.select-gold-purity-menu .option[data-value="24 Karat"]');
            if (option24) {
              option24.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
              option24.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
              option24.dispatchEvent(new MouseEvent('click', { bubbles: true }));
            }
          }).catch(() => {});
          await page.waitForTimeout(250);
        }
        return false;
      };

      await ensureTanishq24K();

      const tanRate = await page.evaluate(async () => {
        const clickByText = (texts) => {
          const nodes = [...document.querySelectorAll('button,a,[role="button"],span,div')];
          for (const text of texts) {
            const target = nodes.find((element) => (element.textContent || '').trim().toLowerCase() === text.toLowerCase());
            if (target) {
              target.click();
              return true;
            }
          }
          return false;
        };

        for (let attempt = 0; attempt < 8; attempt += 1) {
          clickByText(['cancel', 'not now', 'skip', 'later', 'deny']);
          document.querySelector('#pge-close-x')?.click();
          const overlay = document.querySelector('#pge-modal-overlay');
          if (overlay) {
            overlay.style.display = 'none';
            overlay.setAttribute('aria-hidden', 'true');
          }

          const selectedText = document.querySelector('.select-gold-purity-menu .sBtn-text')?.textContent?.trim() || '';
          if (!/24\s*karat/i.test(selectedText)) {
            const purityMenu = document.querySelector('.select-gold-purity-menu .select-btn');
            purityMenu?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
            const option24 = document.querySelector('.select-gold-purity-menu .option[data-value="24 Karat"]');
            option24?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
          }

          const todayRate = document.querySelector('.goldrate-history-table tbody tr:first-child span.goldpurity-rate');
          const fromDataAttr = todayRate?.getAttribute('data-goldrate24kt');
          const fromText = todayRate?.textContent?.trim();
          const value = fromDataAttr || fromText || null;
          if (value && /^\d[\d,]*$/.test(String(value).trim())) return value;

          await new Promise((resolve) => setTimeout(resolve, 350));
        }

        return null;
      }).catch(() => null);

      if (tanRate) return Number(String(tanRate).replaceAll(',', '').replace(/[^\d.]/g, ''));

      const embedded = await page.evaluate(() => document.querySelector('[data-goldrate24kt]')?.getAttribute('data-goldrate24kt') || null).catch(() => null);
      if (embedded) return Number(String(embedded).replaceAll(',', '').replace(/[^\d.]/g, ''));

      const attrPrice = html.match(/data-goldrate24kt\s*=\s*"([^"]+)"/i)?.[1];
      if (attrPrice) return Number(attrPrice.replaceAll(',', '').replace(/[^\d.]/g, ''));
    }

    // MMTC: extract from DOM
    if (source.id === 'mmtc') {
      const byDom = await page.evaluate(() => {
        const text = document.body?.innerText || '';
        const match = text.match(/24k\s*Gold\s*Rate\s*\(Exc\.\s*GST\)[\s\S]*?(?:1\s*gm|1gm)?\s*(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i);
        return match ? match[1] : null;
      }).catch(() => null);
      if (byDom) return Number(String(byDom).replaceAll(',', ''));
    }

    // Malabar: extract rate for 24K (999) specifically
    if (source.id === 'malabar') {
      const malabarRate = await page.evaluate(() => {
        const text = document.body?.innerText || '';
        const ratePattern = /([\d,]+(?:\.\d+)?)\s*INR\s*\/\s*gms?/gi;
        const rateMatches = [...text.matchAll(ratePattern)];
        const rate24k = rateMatches.find((match, index) => {
          const nextRateIndex = rateMatches[index + 1]?.index ?? text.length;
          return /24\s*k\s*\(\s*999\s*\)/i.test(text.slice(match.index + match[0].length, nextRateIndex));
        });
        if (rate24k) return Number(rate24k[1].replaceAll(',', ''));
        const rates = rateMatches
          .map((match) => Number(match[1].replaceAll(',', '')))
          .filter((value) => Number.isFinite(value) && value > 1000);
        return rates.length ? Math.max(...rates) : null;
      }).catch(() => null);
      if (malabarRate) return Number(malabarRate);
    }

    // Try parsing from body text and HTML
    let parsed = parseBullionPrice(bodyText, source.id) || parseBullionPrice(html, source.id);

    // Kalyan: reload and retry if first attempt failed
    if (!parsed && source.id === 'kalyan') {
      await page.reload({ waitUntil: 'commit', timeout: 18000 }).catch(() => {});
      await page.waitForTimeout(1800);
      await dismissCommonPopups(page);
      const retryBodyText = await page.evaluate(() => document.body?.innerText || '').catch(() => '');
      const retryHtml = await page.content().catch(() => '');
      parsed = parseBullionPrice(retryBodyText, source.id) || parseBullionPrice(retryHtml, source.id);
    }

    return parsed;
  } finally {
    if (ownsBrowser) await browser.close();
  }
}

/**
 * Polite HTTP fetch with rate limiting and proper headers
 */
async function politeFetch(url, options = {}) {
  const host = new URL(url).hostname;
  const delay = getHostDelay(host, env.minDelayMs);
  if (delay) await wait(delay);
  recordHostRequest(host);

  const response = await fetch(url, {
    method: options.method || 'GET',
    headers: {
      accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      'accept-language': 'en-IN,en;q=0.9',
      'user-agent': env.browserUserAgent,
      ...(options.headers || {})
    },
    body: options.body,
    signal: AbortSignal.timeout(12000)
  });

  if (response.status === 407) throw new Error('Proxy authentication required. Enter ID/password in the prompt to continue.');
  if (response.status === 403 || response.status === 429) throw new Error(`bot challenge (${response.status})`);
  if (!response.ok) throw new Error(`HTTP ${response.status}`);

  return response.text();
}

/**
 * Refresh all bullion source prices
 * Runs concurrently with fallback retry logic
 */
export async function refreshBullionSources(bullionData, settings = {}, requestedSourceIds = null, onProgress = () => {}) {
  const checkedAt = new Date().toISOString();
  const retentionMs = 30 * 60 * 1000;
  const idsToUse = Array.isArray(requestedSourceIds) && requestedSourceIds.length 
    ? requestedSourceIds 
    : BULLION_SOURCE_IDS;

  const activeSources = idsToUse
    .filter((id) => BULLION_SOURCE_IDS.includes(id))
    .map((id) => bullionData.find((item) => item.id === id))
    .filter(Boolean)
    .map((item) => ({ id: item.id, url: item.url }));

  let checked = 0;
  let live = 0;
  onProgress({ total: activeSources.length, checked, live, current: null });

  let authRequiredSource = null;
  const browserPromises = new Map();
  const usePersistentRuntime = settings.bullionPersistentBrowser !== false;

  const getBrowser = (headless, browserName = defaultBullionBrowserName) => {
    if (usePersistentRuntime) {
      return ensurePersistentBullionBrowser(headless, browserName);
    }
    const key = `${browserName}-${headless ? 'headless' : 'headed'}`;
    if (!browserPromises.has(key)) {
      browserPromises.set(key, 
        import('playwright').then((playwright) => 
          launchBrowser(playwright, headless, browserName)
        )
      );
    }
    return browserPromises.get(key);
  };

  const refreshOneSource = async (source) => {
    const target = bullionData.find((item) => item.id === source.id);
    const configuredSource = target?.url ? { ...source, url: target.url } : source;
    if (!configuredSource.url) throw new Error(`missing source url for ${source.id}`);

    const maxAttempts = source.id === 'tan' ? 1 : Number(target?.maxAttempts || process.env.BULLION_RETRY_ATTEMPTS || 1);
    let price24 = null;
    let price22 = null;
    let lastError = null;

    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      try {
        // Try direct fast fetch first for non-tan sources
        if (source.id === 'malabar') {
          const graphqlUrl = 'https://www.malabargoldanddiamonds.com/graphql-magento?query=' + encodeURIComponent('query getMetalRate($filter: MetalRateFilterInput) { getMetalRate(filter: $filter) { items { entry_date entry_time purity unit rate country state } } }') + '&variables=' + encodeURIComponent(JSON.stringify({ filter: { metal_type: 'gold', country: 'India' } }));
          const fastData = await withTimeout(
            politeFetch(graphqlUrl, { headers: { 'accept': 'application/json' } }),
            getSourceFetchTimeout(source.id),
            'malabar direct api fetch'
          ).catch(() => null);
          if (fastData) {
            const rates = parseBullionRates(fastData, 'malabar');
            price24 = rates.price24;
            price22 = rates.price22;
          }
        } else if (source.id === 'mmtc') {
          const quoteUrl = 'https://www.mmtcpamp.com/api/getQuote';
          const fastData = await withTimeout(
            politeFetch(quoteUrl, {
              method: 'POST',
              headers: {
                'content-type': 'application/json',
                'accept': 'application/json',
                'referer': 'https://www.mmtcpamp.com/gold-silver-rate-today'
              },
              body: JSON.stringify({ currencyPair: 'XAU/INR', type: 'BUY' })
            }),
            getSourceFetchTimeout(source.id),
            'mmtc direct api fetch'
          ).catch(() => null);
          if (fastData) {
            const rates = parseBullionRates(fastData, 'mmtc');
            price24 = rates.price24;
            price22 = rates.price22;
          }
        } else if (source.id !== 'tan') {
          const fastHtml = await withTimeout(
            politeFetch(configuredSource.url), 
            getSourceFetchTimeout(source.id), 
            `${source.id} direct fetch`
          ).catch(() => null);
          
          if (fastHtml) {
            const rates = parseBullionRates(fastHtml, source.id);
            price24 = rates.price24;
            price22 = rates.price22;
          }
        }

        // Visible-browser mode deliberately renders every source for inspection.
        if (!price24 || settings.debugVisibleBrowser) {
          // Determine if browser should be headless:
          // Sources in bullionHeadlessIncompatibleSources ALWAYS need visible browser (never headless)
          // Other sources: use headless UNLESS debugVisibleBrowser is enabled
          const requiresVisibleBrowser = env.bullionHeadlessIncompatibleSources.has(source.id);
          const headless = requiresVisibleBrowser ? false : !Boolean(settings.debugVisibleBrowser);
          
          const renderedPrice24 = await withTimeout(
            fetchRenderedPrice(configuredSource, settings, { headless, browserPromise: getBrowser(headless, defaultBullionBrowserName) }),
            getSourceRenderTimeout(source.id),
            `${source.id} rendered fetch`
          );
          if (!price24) price24 = renderedPrice24;
          if (!price22) {
            const fallbackHtml = await withTimeout(
              politeFetch(configuredSource.url),
              getSourceFetchTimeout(source.id),
              `${source.id} direct fetch for 22K`
            ).catch(() => null);
            if (fallbackHtml) price22 = parseBullionRates(fallbackHtml, source.id).price22;
          }
        }

        if (price24) break;
        lastError = new Error('24K rate not found in rendered page');
      } catch (error) {
        lastError = error;
      }

      if (!price24 && attempt < maxAttempts) {
        await wait(1200 * attempt);
      }
    }

    if (!price24) throw (lastError || new Error('24K rate not found'));

    const derivedPrice22 = !price22 && Number.isFinite(price24) ? Number((price24 * (22 / 24)).toFixed(2)) : null;
    const resolvedPrice22 = Number.isFinite(price22) && price22 > 0 ? price22 : derivedPrice22;

    if (target) {
      target.status = 'live';
      target.price = price24;
      target.price24 = price24;
      target.price22 = resolvedPrice22;
      target.price22Derived = Boolean(derivedPrice22 && resolvedPrice22 === derivedPrice22);
      target.fetchedAt = checkedAt;
      target.lastLiveAt = checkedAt;
      delete target.error;
    }

    return price24;
  };

  const runWithConcurrency = async (items, worker, limit) => {
    const results = new Array(items.length);
    let index = 0;
    const threads = Array.from({ length: Math.max(1, Math.min(limit, items.length)) }, async () => {
      while (index < items.length) {
        const current = index;
        index += 1;
        onProgress({ total: items.length, checked, live, current: items[current].id });
        try {
          const value = await worker(items[current]);
          results[current] = { status: 'fulfilled', value };
          live += 1;
        } catch (reason) {
          results[current] = { status: 'rejected', reason };
          if (!authRequiredSource && /Proxy authentication required/i.test(String(reason?.message || reason || ''))) {
            authRequiredSource = items[current].id;
          }
        } finally {
          checked += 1;
          onProgress({ total: items.length, checked, live, current: checked < items.length ? null : items[current].id });
        }
      }
    });
    await Promise.all(threads);
    return results;
  };

  const sequentialMode = Boolean(settings.debugVisibleBrowser);
  const concurrency = sequentialMode ? 1 : env.bullionConcurrency;
  const results = await runWithConcurrency(activeSources, refreshOneSource, concurrency);

  if (settings.debugVisibleBrowser) await wait(5000);

  // Clean up browser resources
  if (!usePersistentRuntime) {
    await Promise.allSettled(
      [...browserPromises.values()].map(async (browserPromise) => (await browserPromise).close())
    );
  }

  // Update bullion data with results
  results.forEach((result, index) => {
    if (result.status === 'rejected') {
      const target = bullionData.find((item) => item.id === activeSources[index].id);
      if (target) {
        const hasPrice = Number.isFinite(target.price) && target.price > 0;
        target.status = hasPrice ? 'stale' : 'unavailable';
        target.error = result.reason?.message || 'rate fetch failed';
      }
    }
  });

  return {
    checked: activeSources.length,
    live,
    note: live === activeSources.length ? 'Live rates updated.' : live ? 'One live rate updated.' : 'No fresh rates received.',
    authRequired: Boolean(authRequiredSource),
    authSource: authRequiredSource
  };
}
