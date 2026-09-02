import { acquirePooledPage, releasePooledPage } from '../page-pool.js';
import { extractGrams, isNonGoldProductText } from '../weight-parser.js';

export const supportsHeadless = true;

export async function open(page, url, timeoutMs) {
  const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: timeoutMs });
  if (response?.status() >= 400 && response.status() !== 407) throw new Error(`Amazon returned HTTP ${response.status()}.`);
}

export async function waitForData(page, timeoutMs) {
  await page.waitForFunction(
    () => Boolean(document.querySelector('#corePriceDisplay_desktop_feature_div .a-price-whole, #priceblock_ourprice, #priceblock_dealprice, #priceblock_saleprice')),
    undefined,
    { timeout: timeoutMs }
  ).catch(() => {});
}

export function parse(html, url) {
  const sourceUrl = new URL(url);
  const title = html.match(/<span[^>]+id=["']productTitle["'][^>]*>([\s\S]*?)<\/span>/i)?.[1]?.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()
    || html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]?.replace(/\s*[:|].*$/, '').trim()
    || sourceUrl.hostname;
  const text = html.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ');

  if (isNonGoldProductText(`${title} ${sourceUrl.pathname} ${text.slice(0, 500)}`)) {
    throw new Error('Filtered: Silver/Platinum product (not gold).');
  }

  const priceText = html.match(/id=["']corePriceDisplay_desktop_feature_div["'][\s\S]{0,3000}?class=["'][^"']*a-price-whole[^"']*["'][^>]*>([\d,]+)/i)?.[1]
    || html.match(/id=["']corePriceDisplay_desktop_feature_div["'][\s\S]{0,5000}?₹\s*([\d,]+(?:\.\d+)?)/i)?.[1]
    || html.match(/id=["']priceblock_(?:ourprice|dealprice|saleprice)["'][^>]*>\s*[₹\s]*([\d,]+)/i)?.[1]
    || html.match(/["']priceToPay["'][\s\S]{0,800}?["']priceAmount["']\s*:\s*([\d.]+)/i)?.[1];
  const price = Number(String(priceText || '').replaceAll(',', ''));

  const grams = extractGrams(title, '', sourceUrl.pathname) || extractGrams('', text, sourceUrl.pathname);
  const purity = title.match(/\b(9999|999\.9|999)\b/i)?.[1] || text.match(/(?:purity|fineness)[^\d]{0,30}(9999|999\.9|999)\b/i)?.[1] || (/(?:24\s*(?:k|kt|karat))/i.test(`${title} ${text}`) ? '999' : null);
  const brand = title.split(/\s+(?:24\s*(?:k|kt|karat)|gold)\b/i)[0].trim();

  if (!price || !grams || !purity) return null;
  return { name: title, brand, price, grams, purity, source: 'amazon.in', url };
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