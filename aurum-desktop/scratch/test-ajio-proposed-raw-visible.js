import { mkdir, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { firefox } from 'playwright';

const url = 'https://www.ajio.com/s/girls-169379?query=:relevance:verticalmetalpurity:24%20Kt%20%28995%29:verticalmetalpurity:24%20Kt:verticalmetalpurity:999:verticalmetalpurity:24%20Kt%20%28999.9%29:verticalmetalpurity:24%20Kt%20%28999%29:verticalmetalpurity:22%20Kt';
const profileDirectory = join(tmpdir(), 'aurum-ajio-proposed-raw-visible');
const masterSource = (await readFile('manual_js/ajio_gold_master.js', 'utf8'))
  .replaceAll('http://localhost:8788', 'http://127.0.0.1:9');

await mkdir(profileDirectory, { recursive: true });
const context = await firefox.launchPersistentContext(profileDirectory, {
  headless: false,
  viewport: null,
  locale: 'en-IN',
  timezoneId: 'Asia/Kolkata',
  firefoxUserPrefs: {
    'browser.link.open_newwindow': 3,
    'browser.link.open_newwindow.restriction': 0
  }
});
const page = context.pages()[0] || await context.newPage();
let captured = null;
const capture = async (response) => {
  if (captured || !/\/api\/(?:search|category)\//.test(response.url()) || !response.ok()) return;
  try {
    const body = await response.json();
    if (!Array.isArray(body?.products)) return;
    captured = { url: response.url(), status: response.status(), body };
  } catch {}
};
page.on('response', capture);

console.log(`[AJIO RAW TEST] visible persistent profile: ${profileDirectory}`);
console.log(`[AJIO RAW TEST] opening: ${url}`);
await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
await page.bringToFront();

for (let attempt = 0; attempt < 30 && !captured; attempt += 1) {
  await page.waitForTimeout(1000);
}

if (!captured) {
  throw new Error('No successful AJIO listing API response was observed within 30 seconds.');
}

const request = await page.evaluate(() => {
  const raw = localStorage.getItem('plpRequestMobile');
  if (!raw) return null;
  try { return JSON.parse(raw)?.request || null; } catch { return null; }
});
const rawRows = captured.body.products.map((product) => ({
  code: product.code,
  name: product.name,
  price: product.price?.value ?? product.price,
  url: product.url
}));

console.log(`[AJIO RAW TEST] page-zero HTTP ${captured.status}: ${rawRows.length} raw rows`);
console.log(`[AJIO RAW TEST] endpoint: ${captured.url}`);
console.table(rawRows);

if (!request?.pathname || !request?.query) {
  console.log('[AJIO RAW TEST] plpRequestMobile is absent; raw capture was successful but the current master cannot reuse this route yet.');
} else {
  await page.evaluate(({ pageZero, pageRequest }) => {
    globalThis.__AURUM_AJIO_PAGE0__ = pageZero;
    globalThis.__AURUM_AJIO_REQUEST__ = pageRequest;
    globalThis.__aurumMasterRunner = true;
  }, { pageZero: captured.body, pageRequest: request });
  await page.evaluate(async (code) => await (0, eval)(code), masterSource);
  const result = await page.evaluate(() => ({
    products: globalThis.ajioGold?.length || 0,
    catalogue: globalThis.ajioAllSearchResults?.length || 0,
    excluded: globalThis.ajioExcluded?.length || 0,
    incomplete: globalThis.ajioIncomplete?.length || 0,
    stats: globalThis.ajioStats || null
  }));
  console.log('[AJIO RAW TEST] master result with bridge posting disabled:', JSON.stringify(result, null, 2));
}

console.log('[AJIO RAW TEST] browser remains open. Press Ctrl+C in this terminal to close it.');
process.stdin.resume();