import { acquirePooledPage, releasePooledPage } from '../page-pool.js';
import { extractGrams, isNonGoldProductText, tokenToGrams, parseWeightValue, normalizeGoldWeight } from '../weight-parser.js';

export async function open(page, url, timeoutMs) {
  let lastError;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: timeoutMs });
      if (response?.status() >= 400 && response.status() !== 407) throw new Error(`Myntra returned HTTP ${response.status()}.`);
      return;
    } catch (error) {
      lastError = error;
      if (!/ERR_HTTP2_PROTOCOL_ERROR|ERR_CONNECTION_RESET|ERR_NETWORK_CHANGED|NS_ERROR_PROXY_CONNECTION_REFUSED/i.test(error?.message || '') || attempt === 2) throw error;
      await page.waitForTimeout(1000 * (attempt + 1));
    }
  }
  throw lastError;
}

export async function waitForData(page, timeoutMs) {
  await page.waitForFunction(
    () => {
      const text = document.body?.innerText || '';
      const html = document.documentElement?.innerHTML || '';
      return /Selling\s*Price|₹\s*[\d,]+/i.test(text)
        || /"(?:price|discountedPrice|sellingPrice)"\s*:\s*"?\d+/i.test(html);
    },
    undefined,
    { timeout: timeoutMs }
  ).catch(() => {});
}

function gramsFromSlug(pathname) {
  const slug = decodeURIComponent(pathname).toLowerCase();
  const weights = [...slug.matchAll(/(?:^|[-_/])(\d+(?:\.\d+)?)\s*-?\s*(mg|gms|gm|grams|gram|g)(?=[-_/\d]|$)/g)]
    .map(([, value, unit]) => tokenToGrams(value, unit))
    .filter((value) => value > 0);
  if (!weights.length) return null;

  const pieces = Number(slug.match(/(?:^|[-_])(?:set|pack)-of-(\d+)(?=[-_]|$)/)?.[1] || slug.match(/(?:^|[-_])(\d+)\s*-?pcs?(?=[-_]|$)/)?.[1] || 0);
  const weightIsPerPiece = /(?:^|[-_])(?:each|per[-_]?(?:coin|piece))(?:[-_/]|$)/.test(slug);
  const [first, ...rest] = weights;
  // Combo slugs list the pack total first, then every component (35-gm-05-gm--1-gm--2-gm).
  if (rest.length > 1) return rest.reduce((sum, value) => sum + value, 0);
  if (pieces > 1 && weights.length === 1 && weightIsPerPiece) return first * pieces;
  return weights.reduce((sum, value) => sum + value, 0);
}

function gramsFromWeightRange(text) {
  const range = text.match(/weight\s*range[^\d]{0,20}(\d+(?:\.\d+)?)\s*(?:-|to)\s*(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/i);
  if (!range) return null;
  const [, low, high, unit] = range;
  const lowGrams = tokenToGrams(low, unit);
  const highGrams = tokenToGrams(high, unit);
  if (!Number.isFinite(lowGrams) || !Number.isFinite(highGrams)) return null;
  return Math.max(lowGrams, highGrams);
}

function gramsFromFreeText(text) {
  const matches = [...text.matchAll(/(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/gi)]
    .map(([, value, unit]) => ({ grams: tokenToGrams(value, unit), unit: unit.toLowerCase() }))
    .filter(({ grams }) => Number.isFinite(grams) && grams > 0 && grams <= 200);
  if (!matches.length) return null;
  const mgMatches = matches.filter(({ unit }) => unit === 'mg');
  const source = mgMatches.length ? mgMatches : matches;
  return Math.max(...source.map(({ grams }) => grams));
}

function extractPdpFromJson(html) {
  try {
    const scriptMatch = html.match(/<script>window\.__myx\s*=\s*([\s\S]*?)<\/script>/i);
    if (!scriptMatch) return null;
    const scriptContent = scriptMatch[1];
    const pdpIndex = scriptContent.indexOf('"pdpData":');
    if (pdpIndex === -1) return null;
    const raw = scriptContent.slice(pdpIndex + 10);
    let depth = 0;
    let end = 0;
    for (let i = 0; i < raw.length; i++) {
      if (raw[i] === '{') depth++;
      else if (raw[i] === '}') {
        depth--;
        if (depth === 0) { end = i + 1; break; }
      }
    }
    if (end > 0) return JSON.parse(raw.slice(0, end));
  } catch {}
  return null;
}

export const supportsHeadless = true;

export function parseProductApi(json, url) {
  const sourceUrl = new URL(url);
  const pdp = json?.style;
  if (!pdp || pdp.flags?.outOfStock) return null;

  const title = String(pdp.name || pdp.title || '').trim() || sourceUrl.hostname;
  const brand = String(pdp.brand?.name || '').trim();
  const attrs = { ...(pdp.articleAttributes || {}) };
  delete attrs['Weight Range'];
  const descText = [
    ...(pdp.descriptors || []).map((detail) => detail?.description),
    ...(pdp.productDetails || []).map((detail) => detail?.description),
    ...(pdp.productContentGroupEntries || []).flatMap((group) => (group?.attributes || []).map((attribute) => attribute?.value))
  ].filter(Boolean).join(' ').replace(/<[^>]+>/g, ' ');
  const attrText = Object.entries(attrs).map(([key, value]) => `${key} ${value}`).join(' ');
  const combinedText = `${title} ${brand} ${descText} ${attrText} ${decodeURIComponent(sourceUrl.pathname)}`.replace(/<[^>]+>/g, ' ');

  if (isNonGoldProductText(combinedText)) {
    throw new Error('Filtered: Silver/Platinum product (not gold).');
  }

  const price = Number(
    pdp.sizes?.[0]?.sizeSellerData?.[0]?.discountedPrice ||
    pdp.price?.discounted ||
    pdp.sizes?.[0]?.sizeSellerData?.[0]?.mrp ||
    pdp.price?.mrp
  );
  const netWeightSpec = parseWeightValue(attrs['Metal Net Weight'] || attrs['Net Weight'] || attrs.Weight || attrs['Gross Weight']) || 0;
  const rawGrams = extractGrams(title, combinedText, sourceUrl.pathname)
    || netWeightSpec
    || gramsFromSlug(sourceUrl.pathname);
  const grams = normalizeGoldWeight(rawGrams, price);
  if (!grams || !price) return null;

  const puritySpec = String(attrs['Gold Purity'] || attrs['Metal Purity'] || attrs.Purity || '').trim();
  const label = `${decodeURIComponent(sourceUrl.pathname)} ${title} ${descText} ${puritySpec}`;
  const karat = label.match(/(?<![0-9a-z])(24|22|18)\s*-?\s*kt?(?![a-z])/i)?.[1]
    || puritySpec.match(/(\d{2})\s*K/i)?.[1]
    || null;
  const fineness = label.match(/(?<![0-9])(9999|999\.9|999|995|916)(?![0-9])/)?.[1]
    || puritySpec.match(/(9\d{2,3})\b/)?.[1]
    || null;
  const karatPurity = karat === '24' ? '999' : karat === '22' ? '916' : karat === '18' ? '750' : null;
  const purity = fineness || karatPurity || '999';
  const resolvedKarat = Number(karat || (purity === '916' ? 22 : purity === '750' ? 18 : 24));

  return {
    name: title.replace(/\s+/g, ' '),
    brand: brand || title.split(/\s+24K/i)[0].trim(),
    price,
    couponPrice: null,
    grams,
    purity,
    karat: resolvedKarat,
    source: 'myntra.com',
    url,
    refreshMethod: 'api'
  };
}

export function parse(html, url) {
  const sourceUrl = new URL(url);
  const pdp = extractPdpFromJson(html);

  if (pdp) {
    if (pdp.flags?.outOfStock) return null;
    const title = String(pdp.name || pdp.title || '').trim() || sourceUrl.hostname;
    const brand = String(pdp.brand?.name || '').trim();
    const descText = [
      ...(pdp.descriptors || []).map((d) => d.description),
      ...(pdp.productDetails || []).map((d) => d.description)
    ].join(' ').replace(/<[^>]+>/g, ' ');

    const attrs = { ...(pdp.articleAttributes || {}) };
    delete attrs['Weight Range'];
    const attrText = Object.entries(attrs).map(([k, v]) => `${k} ${v}`).join(' ');
    const combinedText = `${title} ${brand} ${descText} ${attrText} ${decodeURIComponent(sourceUrl.pathname)}`.replace(/<[^>]+>/g, ' ');

    if (isNonGoldProductText(combinedText)) {
      throw new Error('Filtered: Silver/Platinum product (not gold).');
    }

    const price = Number(
      pdp.sizes?.[0]?.sizeSellerData?.[0]?.discountedPrice ||
      pdp.price?.discounted ||
      pdp.sizes?.[0]?.sizeSellerData?.[0]?.mrp ||
      pdp.price?.mrp
    );

    const couponCandidate = Number(html.match(/Best\s*Price:\s*(?:Rs\.?|₹)\s*([\d,]+)/i)?.[1]?.replaceAll(',', '')) || null;
    const couponPrice = couponCandidate && price && couponCandidate < price ? couponCandidate : null;

    const netWeightSpec = parseWeightValue(attrs['Metal Net Weight'] || attrs['Net Weight'] || attrs['Weight'] || attrs['Gross Weight']) || 0;
    const rawGrams = extractGrams(title, combinedText, sourceUrl.pathname)
      || netWeightSpec
      || gramsFromSlug(sourceUrl.pathname);
    const grams = normalizeGoldWeight(rawGrams, price);

    if (!grams || !price) return null;

    const puritySpec = String(attrs['Gold Purity'] || attrs['Metal Purity'] || attrs['Purity'] || '').trim();
    const label = `${decodeURIComponent(sourceUrl.pathname)} ${title} ${descText} ${puritySpec}`;
    const karat = label.match(/(?<![0-9a-z])(24|22|18)\s*-?\s*kt?(?![a-z])/i)?.[1]
      || puritySpec.match(/(\d{2})\s*K/i)?.[1]
      || null;
    const fineness = label.match(/(?<![0-9])(9999|999\.9|999|995|916)(?![0-9])/)?.[1]
      || puritySpec.match(/(9\d{2,3})\b/)?.[1]
      || null;
    const karatPurity = karat === '24' ? '999' : karat === '22' ? '916' : karat === '18' ? '750' : null;
    const purity = fineness || karatPurity || '999';
    const resolvedKarat = Number(karat || (purity === '916' ? 22 : purity === '750' ? 18 : 24));

    return {
      name: title.replace(/\s+/g, ' '),
      brand: brand || title.split(/\s+24K/i)[0].trim(),
      price,
      couponPrice,
      grams,
      purity,
      karat: resolvedKarat,
      source: 'myntra.com',
      url
    };
  }

  const title = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]?.replace(/\s*\|\s*Myntra.*$/i, '').trim() || sourceUrl.hostname;

  const stripTags = (value) => String(value || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
  const readIndexRowValue = (keyPattern) => {
    const rowRegex = new RegExp(
      `<div[^>]*class=["'][^"']*index-rowKey[^"']*["'][^>]*>\\s*${keyPattern}\\s*<\\/div>\\s*<div[^>]*class=["'][^"']*index-rowValue[^"']*["'][^>]*>([\\s\\S]*?)<\\/div>`,
      'i'
    );
    return stripTags(html.match(rowRegex)?.[1] || '');
  };

  const text = html.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/gi, ' ').replace(/\s+/g, ' ');

  if (/currently\s+sold\s+out|out\s+of\s+stock|sold\s+out/i.test(text)) return null;

  const metalSpec = readIndexRowValue('Metal') || readIndexRowValue('Base\\s*Metal') || readIndexRowValue('Material') || '';
  if (isNonGoldProductText(`${title} ${decodeURIComponent(sourceUrl.pathname)} ${metalSpec} ${text.slice(0, 500)}`)) {
    throw new Error('Filtered: Silver/Platinum product (not gold).');
  }

  const sellingPrice = text.match(/Selling\s*Price[\s\S]{0,400}?(?:₹|Rs\.?)\s*([\d,]+(?:\.\d+)?)/i)?.[1]?.replaceAll(',', '');
  const priceFromJson = html.match(/"(?:sellingPrice|discountedPrice|finalPrice|price)"\s*:\s*"?(\d+(?:\.\d+)?)"?/i)?.[1];
  const firstCurrencyPrice = text.match(/(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i)?.[1]?.replaceAll(',', '');
  const price = Number(sellingPrice || priceFromJson || firstCurrencyPrice);
  const couponPrice = Number(text.match(/Best\s*Price:\s*(?:Rs\.?|₹)\s*([\d,]+)/i)?.[1]?.replaceAll(',', '')) || null;

  const netWeightSpec = parseWeightValue(readIndexRowValue('Metal\\s*Net\\s*Weight')) || 0;
  let grams = extractGrams(title, text.slice(0, 4000), sourceUrl.pathname)
    || netWeightSpec
    || gramsFromSlug(sourceUrl.pathname);

  if (!grams) return null;

  const label = `${decodeURIComponent(sourceUrl.pathname)} ${title}`;
  const puritySpec = [
    readIndexRowValue('Gold\\s*Purity'),
    readIndexRowValue('Metal\\s*Purity')
  ].filter(Boolean).join(' ');
  const purityText = `${text} ${puritySpec}`;
  const karat = label.match(/(?<![0-9a-z])(24|22|18)\s*-?\s*kt?(?![a-z])/i)?.[1]
    || purityText.match(/(?:Metal|Gold)\s*Purity[^0-9]{0,60}(\d{2})\s*K/i)?.[1]
    || purityText.match(/(?:Metal|Gold)\s*Purity[^0-9]{0,60}(\d{2})\s*Kt/i)?.[1]
    || null;
  const fineness = label.match(/(?<![0-9])(9999|999\.9|999|995|916)(?![0-9])/)?.[1]
    || purityText.match(/(?<![0-9])(9999|999\.9|999|995|916|750|585)(?![0-9])/i)?.[1]
    || purityText.match(/(?:Metal|Gold)\s*Purity[^0-9]{0,60}(9\d{2,3})\b/i)?.[1]
    || null;
  const karatPurity = karat === '24' ? '999' : karat === '22' ? '916' : karat === '18' ? '750' : karat === '14' ? '585' : null;
  const purity = karatPurity || fineness;
  const brand = html.match(/"brand"\s*:\s*"([^"]+)"/i)?.[1] || title.split(/\s+24K/i)[0].split(/\s+Floral/i)[0];

  if (!price || !grams || !purity) return null;
  const resolvedKarat = Number(karat || (purity === '916' ? 22 : purity === '750' ? 18 : purity === '585' ? 14 : 24));
  return { name: title.replace(/\s+/g, ' '), brand, price, couponPrice: couponPrice && couponPrice < price ? couponPrice : null, grams, purity, karat: resolvedKarat, source: 'myntra.com', url };
}

const browserUserAgent = process.env.BROWSER_USER_AGENT || 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';
let productApiTransportAvailable = true;

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
    const productId = new URL(product.url).pathname.match(/\/(\d+)(?:\/buy)?\/?$/)?.[1];
    if (productId && productApiTransportAvailable) {
      try {
        const apiResponse = await page.goto(`https://www.myntra.com/gateway/v2/product/${productId}`, { waitUntil: 'commit', timeout: itemTimeoutMs });
        if (apiResponse?.ok()) {
          const apiResult = parseProductApi(await apiResponse.json(), product.url);
          if (apiResult) return apiResult;
        }
      } catch (error) {
        productApiTransportAvailable = false;
        if (/Filtered: Silver\/Platinum product/i.test(error?.message || '')) throw error;
      }
    }
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