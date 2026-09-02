import { extractGrams, isNonGoldProductText, normalizeGoldWeight } from '../weight-parser.js';

const STORE_DOMAIN = 'ajio.com';
const PRODUCT_PATH_PATTERN = /\/p\/\d+/i;

const unitToGrams = { mg: 0.001, g: 1, gm: 1, gms: 1, gram: 1, grams: 1 };
const purityToKarat = { '9999': 24, '999.9': 24, '999': 24, '995': 24, '916': 22, '750': 18, '585': 14 };

const normalizeTokenValue = (value) => {
  const token = String(value || '').trim();
  if (!token) return NaN;
  if (token.includes('.')) return Number(token);
  if (token.startsWith('0') && token.length > 1) return Number(`0.${token.slice(1)}`);
  return Number(token);
};

const toGrams = (value, unit) => {
  const amount = normalizeTokenValue(value);
  const factor = unitToGrams[String(unit || '').toLowerCase()];
  if (!Number.isFinite(amount) || !factor) return null;
  return amount * factor;
};

const parseGramsFromPath = (pathname = '') => {
  const decoded = decodeURIComponent(String(pathname || '')).toLowerCase();
  const match = decoded.match(/(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/i);
  if (!match) return null;
  return toGrams(match[1], match[2]);
};

function gramsFromWeightTokens(value) {
  const text = String(value || '').toLowerCase();
  if (!text) return null;

  const setOfPattern = text.match(/set\s*of\s*(\d+)[^\d]{0,20}(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/i);
  if (setOfPattern) {
    const count = Number(setOfPattern[1]);
    const each = toGrams(setOfPattern[2], setOfPattern[3]);
    if (Number.isFinite(count) && count > 0 && Number.isFinite(each) && each > 0) return count * each;
  }

  const tokens = [...text.matchAll(/(\d+(?:\.\d+)?)\s*(mg|gms|gm|grams|gram|g)\b/gi)]
    .map(([, amount, unit]) => toGrams(amount, unit))
    .filter((weight) => Number.isFinite(weight) && weight > 0 && weight <= 1000);
  if (!tokens.length) return null;

  const mgOnly = tokens.filter((weight) => weight < 1);
  return mgOnly.length ? Math.max(...mgOnly) : Math.max(...tokens);
}

function inferPurity(value) {
  const text = String(value || '');
  const fineness = text.match(/(?<!\d)(9999|999\.9|999|995|916|750|585)(?!\d)/i)?.[1];
  if (fineness) return fineness;
  const karat = text.match(/(?<!\d)(24|22|18|14)\s*-?\s*(?:k|kt|karat)(?![a-z])/i)?.[1];
  if (karat === '24') return '999';
  if (karat === '22') return '916';
  if (karat === '18') return '750';
  if (karat === '14') return '585';
  return null;
}

const purityToDisplayKarat = (purity) => purityToKarat[String(purity || '')] || 24;
const numberFromMoney = (value) => Number(String(value || '').replace(/[^\d.]/g, ''));
const lowerPositivePrice = (...values) => values
  .filter((value) => Number.isFinite(value) && value > 0)
  .sort((left, right) => left - right)[0] || null;

const isAjioProductPath = (pathname = '') => PRODUCT_PATH_PATTERN.test(String(pathname || ''));

const normalizeAjioProductUrl = (requestedUrl) => {
  const requested = new URL(requestedUrl);
  return `https://www.${STORE_DOMAIN}${requested.pathname}${requested.search}`;
};

const currentPageUrl = (page) => {
  try { return new URL(page.url()); } catch { return null; }
};

const isRedirectedToAjioHome = (page) => {
  const current = currentPageUrl(page);
  return Boolean(current && current.hostname.endsWith(STORE_DOMAIN) && !isAjioProductPath(current.pathname));
};

const getPageDiagnostics = async (page) => {
  const bodyText = await page.locator('body').innerText().catch(() => '');
  const title = await page.title().catch(() => '');
  return {
    url: page.url(),
    title,
    status: page.__ajioLastStatus ?? null,
    blocked: isAccessBlockedText(`${title}\n${bodyText}`),
    bodySample: bodyText.replace(/\s+/g, ' ').slice(0, 500)
  };
};

const navigateProductOnPage = async (page, requestedUrl, timeoutMs) => {
  suppressDialogs(page);
  page.__ajioLastStatus = null;
  const target = normalizeAjioProductUrl(requestedUrl);

  // Navigate from inside the already-open AJIO tab. This behaves like a normal
  // same-tab browser navigation and preserves the warmed AJIO session/referrer.
  // Direct page.goto() calls were causing some AJIO product routes to bounce home.
  const navigation = page.waitForNavigation({
    waitUntil: 'domcontentloaded',
    timeout: timeoutMs
  }).catch(() => null);
  await page.evaluate((url) => { window.location.assign(url); }, target).catch(async () => {
    // Only use goto if the page execution context is unavailable.
    const response = await page.goto(target, { waitUntil: 'domcontentloaded', timeout: timeoutMs });
    if (response) page.__ajioLastStatus = response.status();
  });
  const response = await navigation;
  if (response) page.__ajioLastStatus = response.status();

  const current = currentPageUrl(page);
  if (response?.status() === 403) throw new Error(`AJIO HTTP 403 (url=${current?.href || 'unknown'})`);
  if (response?.status() >= 400 && response.status() !== 407) {
    throw new Error(`AJIO HTTP ${response.status()} (url=${current?.href || 'unknown'})`);
  }
  return page;
};

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

export const createBrowserContext = async (browser, options = {}) => {
  const { useDefaultUserAgent = true, ...contextOptions } = options;
  const context = await browser.newContext({
    permissions: [],
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
    viewport: { width: 1366, height: 900 },
    ...(useDefaultUserAgent ? {} : {}),
    ...contextOptions
  });
  await context.route(/\.(png|jpg|jpeg|gif|webp|svg|woff|woff2|ttf|eot|mp4|mp3|avi)$/i, (route) => route.abort()).catch(() => {});
  await context.route(/google-analytics|googletagmanager|facebook|doubleclick|criteo|hotjar|clarity|branch\.io|segment/i, (route) => route.abort()).catch(() => {});
  return context;
};

// AJIO uses one long-lived browser context and exactly one reusable page.
// The page is warmed on AJIO home once, then navigated through products sequentially.
// This deliberately does not spoof browser APIs or use anti-bot bypasses.
const persistentContexts = new WeakMap();
const persistentContextPromises = new WeakMap();
const ajioRuntimes = new WeakMap();
const bootstrappedContexts = new WeakSet();

export async function getPersistentContext(browser, settings = {}) {
  let byKey = persistentContexts.get(browser);
  if (!byKey) {
    byKey = new Map();
    persistentContexts.set(browser, byKey);
  }
  const key = 'direct';
  let context = byKey.get(key);
  if (context) return context;

  let promises = persistentContextPromises.get(browser);
  if (!promises) {
    promises = new Map();
    persistentContextPromises.set(browser, promises);
  }
  let pending = promises.get(key);
  if (!pending) {
    pending = createBrowserContext(browser, {
      useDefaultUserAgent: true
    });
    promises.set(key, pending);
  }
  try {
    context = await pending;
    byKey.set(key, context);
    return context;
  } finally {
    if (promises.get(key) === pending) promises.delete(key);
  }
}

const ajioWarmingPromises = new WeakMap();

async function getAjioRuntime(browser, settings, timeoutMs) {
  const context = await getPersistentContext(browser, settings);
  let runtime = ajioRuntimes.get(context);
  if (!runtime) {
    runtime = { context, page: null, homeReady: false };
    ajioRuntimes.set(context, runtime);
  }

  if (!runtime.page || runtime.page.isClosed()) {
    runtime.page = await context.newPage();
    suppressDialogs(runtime.page);
    runtime.homeReady = false;
  }

  if (!runtime.homeReady) {
    let warming = ajioWarmingPromises.get(context);
    if (!warming) {
      warming = warmAjioHome(runtime.page, timeoutMs).finally(() => {
        ajioWarmingPromises.delete(context);
      });
      ajioWarmingPromises.set(context, warming);
    }
    await warming;
    runtime.homeReady = true;
  }
  bootstrappedContexts.add(context);
  return runtime;
}

const isAjioHomePage = (page) => {
  try {
    const u = new URL(page.url());
    return u.hostname.endsWith(STORE_DOMAIN) && !isAjioProductPath(u.pathname);
  } catch {
    return false;
  }
};

const warmAjioHome = async (page, timeoutMs) => {
  await page.goto('https://www.ajio.com/', {
    waitUntil: 'domcontentloaded',
    timeout: Math.min(timeoutMs, 30000)
  }).catch(() => {});
  await page.waitForTimeout(1200);
  await dismissCommonPopups(page).catch(() => {});
};

export async function bootstrapContext(context, timeoutMs = 30000) {
  if (bootstrappedContexts.has(context)) return;
  const page = await context.newPage();
  try {
    suppressDialogs(page);
    await warmAjioHome(page, timeoutMs);
    bootstrappedContexts.add(context);
  } finally {
    // Do not retain this temporary page. The runtime creates a dedicated warm tab.
    await page.close().catch(() => {});
  }
}

const ensureAjioProductRoute = async (page, requestedUrl, timeoutMs) => {
  const normalized = normalizeAjioProductUrl(requestedUrl);
  const current = currentPageUrl(page);
  if (!current || !isAjioProductPath(current.pathname)) {
    await navigateProductOnPage(page, normalized, timeoutMs);
  }
  if (isRedirectedToAjioHome(page)) throw new Error('AJIO product route returned to homepage.');
};

const recoverToProductUrl = async (page, requestedUrl, timeoutMs) => {
  await dismissCommonPopups(page).catch(() => {});
  try {
    await navigateProductOnPage(page, normalizeAjioProductUrl(requestedUrl), timeoutMs);
  } catch {
    await warmAjioHome(page, Math.min(timeoutMs, 15000));
    await navigateProductOnPage(page, normalizeAjioProductUrl(requestedUrl), timeoutMs);
  }
};

const waitForDomReady = async (page, requestedUrl, timeoutMs) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const state = await page.evaluate(() => {
      const text = document.body?.innerText || '';
      const title = document.querySelector('h1.prod-name')?.textContent?.trim() ||
        document.querySelector('h1')?.textContent?.trim() || '';
      const selling = document.querySelector('.prod-sp')?.textContent || '';
      const mrp = document.querySelector('.prod-cp')?.textContent || '';
      const jsonLd = [...document.querySelectorAll('script[type="application/ld+json"]')]
        .some((node) => /"(?:price|lowPrice|highPrice)"\s*:/i.test(node.textContent || ''));
      return {
        ready: Boolean(title) && (/[0-9]/.test(selling) || /[0-9]/.test(mrp) || jsonLd || /(?:₹|Rs\.?)\s*[\d,]+/.test(text)),
        textLength: text.length
      };
    }).catch(() => ({ ready: false, textLength: 0 }));
    if (state.ready) return;

    // Give AJIO a short chance to finish its client-side product route. If it has
    // settled on home with no product data, fail instead of warming home again and
    // creating a redirect loop.
    if (isRedirectedToAjioHome(page) && state.textLength > 500) {
      await page.waitForTimeout(1200);
      const stillHome = isRedirectedToAjioHome(page);
      if (stillHome) {
        const diagnostics = await getPageDiagnostics(page);
        throw new Error(`AJIO product route settled on homepage; ${JSON.stringify(diagnostics)}`);
      }
    }
    await page.waitForTimeout(250);
  }
  throw new Error(`Ajio product DOM did not become ready within ${timeoutMs}ms.`);
};

const extractFromVisibleDom = async (page, url) => {
  const sourceUrl = new URL(url);
  const dom = await page.evaluate(() => {
    const pick = (selector) => document.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() || '';
    return {
      brand: pick('h2.brand-name'),
      title: pick('h1.prod-name'),
      sellingPrice: pick('.prod-sp'),
      mrpPrice: pick('.prod-cp'),
      promoPrice: pick('.promo-discounted-price'),
      purityOption: pick('.color-swatch .size-variant-item'),
      sizeOption: pick('.size-swatch [aria-label="Select Size"]'),
      bodyText: (document.body?.innerText || '').replace(/\s+/g, ' ').slice(0, 50000),
      jsonLd: [...document.querySelectorAll('script[type="application/ld+json"]')].map((node) => node.textContent || '').join(' ')
    };
  }).catch(() => null);
  if (!dom) return null;

  const labelText = [dom.title, dom.purityOption, dom.sizeOption, dom.bodyText, dom.jsonLd, sourceUrl.pathname].join(' ');
  if (isNonGoldProductText(labelText)) {
    throw new Error('Filtered: Silver/Platinum product (not gold).');
  }

  const selling = numberFromMoney(dom.sellingPrice);
  const promo = numberFromMoney(dom.promoPrice);
  const jsonLdPrices = [...String(dom.jsonLd || '').matchAll(/"(?:price|lowPrice|highPrice)"\s*:\s*"?([0-9]+(?:\.[0-9]+)?)/gi)]
    .map((match) => Number(match[1])).filter((value) => Number.isFinite(value) && value > 0);
  const price = selling || numberFromMoney(dom.mrpPrice) || lowerPositivePrice(...jsonLdPrices);
  const couponPrice = promo && price && promo < price ? promo : null;

  let grams = extractGrams(dom.title, labelText, sourceUrl.pathname) || gramsFromWeightTokens(labelText) || parseGramsFromPath(sourceUrl.pathname);
  if (!Number.isFinite(grams) || grams <= 0) grams = null;

  const purity = inferPurity(labelText);
  if (!price || !grams || !purity) return null;

  const displayKarat = purityToDisplayKarat(purity);
  const pageWeight = Number.isFinite(grams) ? String(Number(grams.toFixed(3))).replace(/\.0+$/, '') : '';
  const baseName = (dom.title || sourceUrl.hostname).split('|')[0].trim();
  const detailName = `${baseName} | ${displayKarat} Kt (${purity || '-'})${pageWeight ? ` | ${pageWeight} gm` : ''}`;
  const name = dom.brand ? `${dom.brand} ${detailName}` : detailName;
  return { name: name.replace(/\s+/g, ' '), brand: dom.brand || '', price, couponPrice, grams, purity, source: STORE_DOMAIN, url };
};

const parseFromHtmlFallback = (html, url) => {
  const sourceUrl = new URL(url);
  const text = html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ');

  const domTitle = html.match(/<h1[^>]*class=["'][^"']*prod-name[^"']*["'][^>]*>([\s\S]*?)<\/h1>/i)?.[1]?.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim() || '';
  const domBrand = html.match(/<h2[^>]*class=["'][^"']*brand-name[^"']*["'][^>]*>([\s\S]*?)<\/h2>/i)?.[1]?.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim() || '';
  const labelText = `${domTitle} ${domBrand} ${text} ${sourceUrl.pathname}`;
  if (isNonGoldProductText(labelText)) {
    throw new Error('Filtered: Silver/Platinum product (not gold).');
  }

  const selling = numberFromMoney(html.match(/class=["'][^"']*prod-sp[^"']*["'][^>]*>([^<]+)/i)?.[1]);
  const mrp = numberFromMoney(html.match(/class=["'][^"']*prod-cp[^"']*["'][^>]*>([^<]+)/i)?.[1]);
  const promo = lowerPositivePrice(...[...html.matchAll(/class=["'][^"']*promo-discounted-price[^"']*["'][\s\S]*?<span[^>]*>([\s\S]*?)<\/span>/gi)].map((match) => numberFromMoney(match[1])));
  const price = selling || mrp;
  const couponPrice = promo && price && promo < price ? promo : null;
  const grams = extractGrams(domTitle, labelText, sourceUrl.pathname) || gramsFromWeightTokens(labelText) || parseGramsFromPath(sourceUrl.pathname);
  const purity = inferPurity(labelText);

  if (!price || !grams || !purity) return null;
  const displayKarat = purityToDisplayKarat(purity);
  const pageWeight = Number.isFinite(grams) ? String(Number(grams.toFixed(3))).replace(/\.0+$/, '') : '';
  const baseName = (domTitle || sourceUrl.hostname).split('|')[0].trim();
  const detailName = `${baseName} | ${displayKarat} Kt (${purity || '-'})${pageWeight ? ` | ${pageWeight} gm` : ''}`;
  const name = domBrand ? `${domBrand} ${detailName}` : detailName;
  return { name: name.replace(/\s+/g, ' '), brand: domBrand || '', price, couponPrice, grams, purity, source: STORE_DOMAIN, url };
};

export const supportsHeadless = true;

export async function open(page, url, timeoutMs) {
  const response = await page.goto(url, {
    waitUntil: 'domcontentloaded',
    timeout: timeoutMs,
    referer: 'https://www.ajio.com/'
  });
  if (response) page.__ajioLastStatus = response.status();
  if (response?.status() === 403) throw new Error('Ajio denied browser access (HTTP 403).');
  if (response?.status() >= 400 && response.status() !== 407) throw new Error(`Ajio returned HTTP ${response.status()}.`);
  if (isRedirectedToAjioHome(page)) {
    const diagnostics = await getPageDiagnostics(page);
    throw new Error(`Ajio redirected product to homepage; ${JSON.stringify(diagnostics)}`);
  }
}

export async function waitForData(page, timeoutMs) {
  await page.waitForFunction(
    () => {
      const hasSelling = Boolean(document.querySelector('.prod-sp')?.textContent?.match(/\d/));
      const hasMrp = Boolean(document.querySelector('.prod-cp')?.textContent?.match(/\d/));
      const hasTitle = Boolean(document.querySelector('h1.prod-name'));
      return hasTitle && (hasSelling || hasMrp);
    },
    undefined,
    { timeout: timeoutMs }
  );
}

export function parseFromApiPayload(data, url) {
  if (!data || typeof data !== 'object') return null;

  if (data.stock?.stockLevelStatus === 'outOfStock' || data.purchasable === false) {
    throw new Error('product is out of stock');
  }

  const brand = String(data.brandName || data.fnlColorVariantData?.brandName || '').trim();
  const title = String(data.name || '').trim();
  const sourceUrl = new URL(url);
  const featureText = Array.isArray(data.featureData) ? data.featureData.map((f) => `${f?.name || ''} ${f?.value || ''}`).join(' ') : '';
  const labelText = [title, brand, data.description || '', featureText, sourceUrl.pathname].join(' ');

  if (isNonGoldProductText(labelText)) {
    throw new Error('Filtered: Silver/Platinum product (not gold).');
  }

  const selling = numberFromMoney(data.price?.value ?? data.price);
  const mrp = numberFromMoney(data.wasPriceData?.value ?? data.wasPriceData ?? data.price?.wasPrice);
  
  const promoCandidates = [
    data.promoDiscountedPrice,
    data.promoDiscountedPriceFormatted,
    data.offerPrice,
    data.discountedPrice,
    data.cartOfferPrice,
    data.promoData?.promoDiscountedPrice,
    ...(Array.isArray(data.applicablePromotions) ? data.applicablePromotions.map((p) => p?.discountedPrice || p?.promoPrice || p?.offerPrice) : []),
    ...(Array.isArray(data.potentialPromotions) ? data.potentialPromotions.map((p) => p?.discountedPrice || p?.promoPrice || p?.offerPrice) : [])
  ].map((v) => numberFromMoney(v)).filter((v) => Number.isFinite(v) && v > 0);

  const price = Number.isFinite(selling) && selling > 0 ? selling : Number.isFinite(mrp) && mrp > 0 ? mrp : null;
  const promo = promoCandidates.length ? Math.min(...promoCandidates) : null;
  const couponPrice = promo && price && promo < price ? promo : null;

  let grams = normalizeGoldWeight(extractGrams(title, labelText, sourceUrl.pathname) || gramsFromWeightTokens(labelText) || parseGramsFromPath(sourceUrl.pathname), price);
  if (!Number.isFinite(grams) || grams <= 0) grams = null;

  const purity = inferPurity(labelText);
  if (!price || !grams || !purity) return null;

  const displayKarat = purityToDisplayKarat(purity);
  const pageWeight = Number.isFinite(grams) ? String(Number(grams.toFixed(3))).replace(/\.0+$/, '') : '';
  const baseName = (title || sourceUrl.hostname).split('|')[0].trim();
  const detailName = `${baseName} | ${displayKarat} Kt (${purity || '-'})${pageWeight ? ` | ${pageWeight} gm` : ''}`;
  const name = brand ? `${brand} ${detailName}` : detailName;

  return {
    name: name.replace(/\s+/g, ' '),
    brand,
    price,
    couponPrice,
    grams,
    purity,
    source: STORE_DOMAIN,
    url
  };
}

export const fetchProductViaInBrowserApi = async (page, requestedUrl) => {
  const codeMatch = String(requestedUrl || '').match(/\/p\/([a-zA-Z0-9_-]+)/);
  if (!codeMatch) return null;
  const rawCode = codeMatch[1];
  const base = rawCode.replace(/_[a-z0-9]+$/i, '');
  const candidateCodes = [
    rawCode,
    base,
    base + '_multi',
    base.length === 9 ? base + '001' : base + '0001',
    base.length === 9 ? base + '0001' : base + '001',
    base + '_gold',
    base + '_yellow'
  ];
  const uniqueCodes = [...new Set(candidateCodes.filter(Boolean))];

  const result = await page.evaluate(async (codes) => {
    let notFoundCount = 0;
    for (const code of codes) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 3500);
        const res = await fetch('https://www.ajio.com/api/p/' + code, {
          headers: { accept: 'application/json, text/plain, */*' },
          cache: 'no-store',
          signal: controller.signal
        });
        clearTimeout(timeoutId);
        if (res.status === 404) {
          notFoundCount++;
          continue;
        }
        if (!res.ok) continue;
        const json = await res.json();
        if (json && json.name && (json.price?.value || json.wasPriceData?.value || json.promoDiscountedPrice || json.offerPrice)) {
          return { data: json };
        }
      } catch {}
    }
    if (notFoundCount === codes.length) return { notFound: true };
    return null;
  }, uniqueCodes).catch(() => null);

  if (result?.notFound) {
    throw new Error('Product not found (404)');
  }

  const payload = result?.data;
  if (payload?.stock?.stockLevelStatus === 'outOfStock' || payload?.purchasable === false) {
    throw new Error('Product is out of stock');
  }

  if (payload) {
    return parseFromApiPayload(payload, requestedUrl);
  }
  return null;
};

export function parse(html, url) {
  return parseFromHtmlFallback(html, url);
}

const navigateAndCaptureProduct = async (page, requestedUrl, timeoutMs) => {
  const target = normalizeAjioProductUrl(requestedUrl);
  let captured = null;
  let capturedHtml = '';

  const onResponse = async (response) => {
    if (captured) return;
    try {
      const u = new URL(response.url());
      if (!u.hostname.endsWith(STORE_DOMAIN) || !isAjioProductPath(u.pathname)) return;
      if (response.request().resourceType() !== 'document' || response.status() >= 400) return;
      const html = await response.text().catch(() => '');
      if (!html) return;
      const parsed = parseFromHtmlFallback(html, requestedUrl);
      if (parsed) {
        captured = parsed;
        capturedHtml = html;
      }
    } catch {}
  };
  page.on('response', onResponse);

  const navigationPromise = navigateProductOnPage(page, target, timeoutMs).catch((error) => error);
  const deadline = Date.now() + Math.min(timeoutMs, 25000);
  try {
    while (Date.now() < deadline && !captured) {
      const current = currentPageUrl(page);
      if (current && isAjioProductPath(current.pathname)) {
        const domResult = await extractFromVisibleDom(page, requestedUrl).catch(() => null);
        if (domResult) {
          captured = domResult;
          break;
        }
        const html = await page.content().catch(() => '');
        if (html) {
          const parsed = parseFromHtmlFallback(html, requestedUrl);
          if (parsed) {
            captured = parsed;
            capturedHtml = html;
            break;
          }
        }
      }
      const settled = await Promise.race([
        navigationPromise.then(() => true),
        page.waitForTimeout(75).then(() => false)
      ]);
      if (settled && isRedirectedToAjioHome(page)) break;
    }

    const navResult = await navigationPromise;
    if (captured) return captured;
    if (navResult instanceof Error) throw navResult;

    if (!isRedirectedToAjioHome(page)) {
      const finalDom = await extractFromVisibleDom(page, requestedUrl).catch(() => null);
      if (finalDom) return finalDom;
      const html = capturedHtml || await page.content().catch(() => '');
      const finalHtml = html ? parseFromHtmlFallback(html, requestedUrl) : null;
      if (finalHtml) return finalHtml;
    }
    return null;
  } finally {
    page.off('response', onResponse);
  }
};

export async function refreshProductPageFromExistingPage(
  product,
  page,
  settings = {},
  timeouts = {}
) {
  const requestedUrl =
    normalizeAjioProductUrl(product.url);

  if (!page || page.isClosed()) {
    throw new Error(
      'AJIO shared browser page is unavailable'
    );
  }

  try {
    // Use the SAME master Page for visible PDP navigation. The previous shared-runtime
    // path only executed an in-page API fetch, so no AJIO product page ever appeared.
    // Navigating this existing Page preserves the exact Firefox context/cookies while
    // avoiding a second browser/window.
    await page.bringToFront().catch(() => {});
    await open(
      page,
      requestedUrl,
      Number(timeouts.itemTimeoutMs || 25000)
    );
    await waitForData(
      page,
      Number(timeouts.pageReadyTimeoutMs || 12000)
    ).catch(() => {});

    // Prefer the normal in-browser API after the PDP route is visibly open. It gives
    // the same structured extraction quality as the master while retaining the page.
    const fastResult =
      await fetchProductViaInBrowserApi(
        page,
        requestedUrl
      );

    if (fastResult) {
      return fastResult;
    }

    const domResult = await extractFromVisibleDom(page, requestedUrl).catch(() => null);
    if (domResult) return domResult;

    const html = await page.content().catch(() => '');
    const htmlResult = html ? parseFromHtmlFallback(html, requestedUrl) : null;
    if (htmlResult) return htmlResult;

  } catch (error) {

    if (
      /out of stock|not found|404|no longer available/i
        .test(error?.message || '')
    ) {
      throw error;
    }

    throw new Error(
      `AJIO shared-session PDP failed: ${
        error?.message || String(error)
      }`
    );
  }

  throw new Error(
    'Product not found (404)'
  );
}

export async function refreshProductPage(product, browser, settings = {}, timeouts = {}) {
  const itemTimeoutMs = Number(timeouts.itemTimeoutMs || 25000);
  const pageReadyTimeoutMs = Number(timeouts.pageReadyTimeoutMs || 12000);
  const context = await getPersistentContext(browser, settings);

  const runtime = await getAjioRuntime(browser, settings, Math.min(itemTimeoutMs, 15000));
  const homePage = runtime.page;
  const requestedUrl = normalizeAjioProductUrl(product.url);

  // Fast-path: Fetch product data via in-browser API directly (<100ms).
  // Concurrent page.evaluate() calls on the warmed page run smoothly in parallel across all workers.
  try {
    const fastResult = await fetchProductViaInBrowserApi(homePage, requestedUrl);
    if (fastResult) return fastResult;
  } catch (apiError) {
    if (/out of stock|not found|404|no longer available/i.test(apiError?.message || '')) {
      throw apiError;
    }
  }

  // If fast in-browser API didn't return a match, the item is either 404 or out of stock on Ajio.
  // We do NOT need to launch heavy DOM navigations that stall Playwright workers!
  throw new Error(`Product not found (404)`);
}
