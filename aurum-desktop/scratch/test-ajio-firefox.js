import playwright from 'playwright';

async function testFirefoxAjio() {
  console.log('Launching Firefox...');
  const browser = await playwright.firefox.launch({ headless: true });
  const context = await browser.newContext({
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0',
    viewport: { width: 1280, height: 800 }
  });
  const page = await context.newPage();
  
  console.log('Navigating to ajio home in Firefox...');
  await page.goto('https://www.ajio.com/', { waitUntil: 'domcontentloaded', timeout: 30000 });
  console.log('Home loaded, title:', await page.title());

  const testCodes = ['6007569640_multi', '6007569640', '6007569550_multi'];
  for (const code of testCodes) {
    console.log(`\nTesting in-browser fetch for code: ${code}`);
    const res = await page.evaluate(async (c) => {
      try {
        const r = await fetch(`/api/p/${encodeURIComponent(c)}`, {
          headers: { 'Accept': 'application/json, text/plain, */*' },
          credentials: 'same-origin'
        });
        if (!r.ok) return { ok: false, status: r.status };
        const data = await r.json();
        return {
          ok: true,
          status: r.status,
          name: data.name,
          price: data.price?.value,
          wasPrice: data.wasPriceData?.value,
          promoDiscountedPrice: data.promoDiscountedPrice,
          inStock: data.stock?.stockLevelStatus !== 'outOfStock'
        };
      } catch (e) {
        return { ok: false, error: e.message };
      }
    }, code);
    console.log('Result:', JSON.stringify(res, null, 2));
  }

  await browser.close();
}

testFirefoxAjio().catch(console.error);

