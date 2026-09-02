import { acquirePooledPage, releasePooledPage } from '../page-pool.js';
import { extractGrams, isNonGoldProductText, normalizeGoldWeight } from '../weight-parser.js';
export const supportsHeadless = true;

const isMinutesUrl = (url) => new URL(url).searchParams.get('marketplace')?.toUpperCase() === 'HYPERLOCAL';

const visibleText = (element) => {
  const style = window.getComputedStyle(element);
  return style.display !== 'none' && style.visibility !== 'hidden' && element.getBoundingClientRect().width > 0;
};

async function confirmLocation(page) {
  await page.waitForFunction(() => /place pin on the exact location/i.test(document.body?.innerText || ''), undefined, { timeout: 10000 }).catch(() => {});
  let clicked = false;
  const control = page.getByText(/^(confirm|confirm location|deliver here|save|done|continue)$/i).last();
  clicked = await control.waitFor({ state: 'visible', timeout: 7000 }).then(async () => {
    await control.click();
    return true;
  }).catch(async () => {
    return page.evaluate(() => {
      const candidates = [...document.querySelectorAll('button,[role="button"]')]
        .filter((element) => {
          const text = (element.textContent || '').trim().toLowerCase();
          return /confirm|deliver here|save|done|continue/.test(text);
        });
      if (!candidates.length) return false;
      candidates[candidates.length - 1].click();
      return true;
    }).catch(() => false);
  });
  if (!clicked) {
    const hasPrice = await page.waitForFunction(() => /₹\s*[\d,]+/.test(document.body?.innerText || ''), undefined, { timeout: 4000 }).then(() => true).catch(() => false);
    if (!hasPrice) throw new Error('Flipkart location confirmation did not complete.');
  }
  await page.waitForTimeout(1000);
  await page.waitForFunction(() => !location.pathname.includes('hyperlocal-preview-page')
    || /₹\s*[\d,]+/.test(document.body?.innerText || ''), undefined, { timeout: 30000 });
}

async function selectLocation(page, query, resultPattern) {
  const locationInput = page.locator('input[placeholder*="Search by area"]');
  await locationInput.fill(query);
  await locationInput.press('Enter');
  await page.waitForTimeout(1500);
  const result = page.getByText(resultPattern, { exact: false }).first();
  try {
    await result.waitFor({ state: 'visible', timeout: 10000 });
  } catch {
    return false;
  }
  await result.click();
  await page.waitForTimeout(1000);
  return true;
}

export async function open(page, url, timeoutMs, settings = {}) {
  const isHyperlocal = isMinutesUrl(url);
  const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: timeoutMs });
  if (response?.status() >= 400 && response.status() !== 407) throw new Error(`Flipkart returned HTTP ${response.status()}.`);
  if (!isHyperlocal) return;

  const hasPriceAlready = await page.waitForFunction(() => {
    const text = document.body?.innerText || '';
    return /₹\s*[\d,]+/.test(text) && !location.pathname.includes('hyperlocal-preview-page');
  }, undefined, { timeout: 3000 }).then(() => true).catch(() => false);
  if (hasPriceAlready) return;

  const locationInput = page.locator('input[placeholder*="Search by area"]');
  const needsLocation = await locationInput.waitFor({ state: 'visible', timeout: 6000 })
    .then(() => true)
    .catch(() => false);
  if (needsLocation) {
    const preciseAddress = String(settings.preciseAddress || '').trim();
    const pincode = String(settings.pincode || '').trim();
    if (!preciseAddress && !pincode) {
      const cleanUrl = url.replace(/([?&])marketplace=HYPERLOCAL(&|$)/i, '$1').replace(/[?&]$/, '');
      await page.goto(cleanUrl, { waitUntil: 'domcontentloaded', timeout: timeoutMs });
      return;
    }
    let selected = false;
    if (preciseAddress) {
      selected = await selectLocation(page, preciseAddress, new RegExp(preciseAddress.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i'));
      if (!selected && pincode) {
        selected = await selectLocation(page, pincode, new RegExp(`${pincode}.*India`, 'i'));
      }
    } else {
      selected = await selectLocation(page, pincode, new RegExp(`${pincode}.*India`, 'i'));
    }
    if (selected) {
      await confirmLocation(page).catch(() => {});
      await page.waitForLoadState('domcontentloaded', { timeout: 15000 }).catch(() => {});
    } else {
      const cleanUrl = url.replace(/([?&])marketplace=HYPERLOCAL(&|$)/i, '$1').replace(/[?&]$/, '');
      await page.goto(cleanUrl, { waitUntil: 'domcontentloaded', timeout: timeoutMs });
    }
  }
}

export async function waitForData(page, timeoutMs) {
  await page.waitForFunction(
    () => /₹\s*[\d,]+/i.test(document.body?.innerText || '') || /"price"\s*:\s*"?\d+/i.test(document.documentElement?.innerHTML || ''),
    undefined,
    { timeout: timeoutMs }
  ).catch(() => {});
}

export function parse(html, url) {
  const sourceUrl = new URL(url);
  const text = html.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
  const mainProductHtml = html.match(/<h1[\s\S]*?<\/h1>[\s\S]*?(?=Delivery details)/i)?.[0] || '';
  const mainProductText = mainProductHtml.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');
  const title = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]?.replace(/\s*\|.*$/, '').trim() || '';

  if (isNonGoldProductText(`${title} ${mainProductText} ${sourceUrl.pathname} ${text.slice(0, 500)}`)) {
    throw new Error('Filtered: Silver/Platinum product (not gold).');
  }

  const price = Number((
    mainProductText.match(/(?:₹|Rs\.?)\s*([\d,]+)/i)
    || text.match(/(?:₹|Rs\.?)\s*([\d,]+)/i)
    || html.match(/"sellingPrice"\s*:\s*(\d+(?:\.\d+)?)/i)
  )?.[1]?.replaceAll(',', '') || 0);

  const grams = normalizeGoldWeight(extractGrams(title || mainProductText, `${mainProductText} ${text}`, sourceUrl.pathname), price);
  const karat = `${title} ${mainProductText} ${text}`.match(/\b(24|22|18|14)\s*(?:\(\s*\d{3,4}(?:\.\d+)?\s*\)\s*)?-?\s*k(?:t|arat)?\b/i)?.[1];
  const purity = text.match(/\b(9999|999\.9|999|916|750|585)\b/)?.[1]
    || (karat === '24' ? '999' : karat === '22' ? '916' : karat === '18' ? '750' : karat === '14' ? '585' : '')
    || sourceUrl.pathname.match(/\b(9999|999\.9|999|916|750|585)\b/i)?.[1]
    || '';
  const name = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i)?.[1]?.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()
    || title
    || sourceUrl.hostname;

  if (!price || !grams) return null;
  return { name, brand: '', price, couponPrice: null, grams, purity, source: 'flipkart.com', url };
}

const browserUserAgent = process.env.BROWSER_USER_AGENT || 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

const suppressDialogs = (page) => {
  page.on('dialog', async (dialog) => {
    try { await dialog.dismiss(); } catch {}
  });
};

const dismissCommonPopups = async (page) => {
  await page.keyboard.press('Escape').catch(() => {});
  await page.evaluate(() => {
    const closeMatchers = [/close/i, /no thanks/i, /maybe later/i, /not now/i, /skip/i, /got it/i, /dismiss/i, /continue without/i, /reject all/i, /accept all/i];
    const elements = [...document.querySelectorAll('button,[role="button"],.close,.modal-close,[aria-label*="close" i],[class*="close" i],[id*="close" i]')];
    elements.forEach((element) => {
      const label = `${element.textContent || ''} ${element.getAttribute('aria-label') || ''}`.trim();
      if (closeMatchers.some((matcher) => matcher.test(label))) element.click();
    });
  }).catch(() => {});
};

const isAccessBlockedText = (text = '') => /access denied|request blocked|blocked due to security reasons|captcha|you don't have permission/i.test(String(text || ''));

const createBrowserContext = async (browser, options = {}) => {
  const { useDefaultUserAgent = false, ...contextOptions } = options;
  return browser.newContext({
    permissions: [],
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
    viewport: { width: 1366, height: 900 },
    ...(useDefaultUserAgent ? {} : { userAgent: browserUserAgent }),
    ...contextOptions
  });
};

const readStablePageHtml = async (page) => {
  let lastError;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      await page.waitForLoadState('domcontentloaded', { timeout: 5000 }).catch(() => {});
      return await page.content();
    } catch (error) {
      lastError = error;
      await page.waitForTimeout(750);
    }
  }
  throw lastError;
};

async function refreshProductPageOnPage(product, page, settings = {}, timeouts = {}) {
  const itemTimeoutMs = Number(timeouts.itemTimeoutMs || 75000);
  const pageReadyTimeoutMs = Number(timeouts.pageReadyTimeoutMs || 45000);
  try {
    suppressDialogs(page);
    await open(page, product.url, itemTimeoutMs, settings);
    await waitForData(page, pageReadyTimeoutMs);
    await dismissCommonPopups(page);

    const html = await readStablePageHtml(page);
    const visibleText = await page.locator('body').innerText().catch(() => '');
    const title = await page.title().catch(() => '');
    if (page.url().startsWith('chrome-error://')) throw new Error('Browser could not load the product page.');
    if (/access denied|request blocked/i.test(title)) throw new Error('Access denied by the store.');
    if (isAccessBlockedText(visibleText) && !/Selling\s*Price|₹\s*[\d,]+/i.test(visibleText)) {
      throw new Error('Access blocked by the store.');
    }
    if (/currently\s+sold\s+out|out\s+of\s+stock|sold\s+out/i.test(`${title} ${visibleText}`)) {
      throw new Error('Product is out of stock.');
    }

    const extracted = parse(html, product.url);
    if (!extracted) throw new Error('Price or gold weight was not found on the loaded product page.');
    return extracted;
  } catch (error) {
    throw error;
  }
}

export async function refreshProductPageFromContext(product, sharedRuntimeTarget, settings = {}, timeouts = {}) {
  if (!sharedRuntimeTarget) throw new Error('Shared master browser page/context is unavailable.');

  // The master runner passes its already-open master Page. Reusing that Page keeps
  // targeted PDP navigation in the same visible browser window/session instead of
  // asking Playwright/Firefox to create a separate top-level window for each item.
  const isSharedPage = typeof sharedRuntimeTarget.goto === 'function' && typeof sharedRuntimeTarget.isClosed === 'function';
  const page = isSharedPage ? sharedRuntimeTarget : await sharedRuntimeTarget.newPage();
  try {
    if (page.isClosed()) throw new Error('Shared master browser page is closed.');
    await page.bringToFront().catch(() => {});
    return await refreshProductPageOnPage(product, page, settings, timeouts);
  } finally {
    // Never close a Page owned by the persistent master runtime.
    if (!isSharedPage) await page.close().catch(() => {});
  }
}

export async function refreshProductPage(product, browser, settings = {}, timeouts = {}) {
  const pooled = await acquirePooledPage(browser, settings, createBrowserContext);
  const context = pooled ? pooled.pool.context : await createBrowserContext(browser, { useDefaultUserAgent: true });
  const page = pooled ? pooled.page : await context.newPage();
  let healthy = true;
  try {
    return await refreshProductPageOnPage(product, page, settings, timeouts);
  } catch (error) {
    healthy = !/Target closed|Browser has been closed|crash|NS_ERROR/i.test(error?.message || '');
    throw error;
  } finally {
    if (pooled) await releasePooledPage(pooled, healthy);
    else await context.close().catch(() => {});
  }
}