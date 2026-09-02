(async () => {
  const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
  const bridgeUrls = ['http://localhost:8787/api/browser-bridge/products', 'http://localhost:8788/api/browser-bridge/products'];
  const sendProducts = async (store, records, details = {}) => {
    for (const url of bridgeUrls) {
      try {
        const response = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ store, records, details }) });
        if (response.ok) return response.json();
      } catch {}
    }
    return null;
  };
  const fetchJson = async (path) => {
    const response = await fetch(path, { credentials: 'include', cache: 'no-store', headers: { accept: 'application/json', 'x-myntraweb': 'Yes', 'x-requested-with': 'browser', 'x-meta-app': 'channel=web' } });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  };
  const collectDetails = async (records, keyOf, needsDetail, pathOf) => {
    const details = {};
    const targets = records.filter(needsDetail);
    let index = 0;
    await Promise.all(Array.from({ length: Math.min(8, targets.length) }, async () => {
      while (index < targets.length) {
        const record = targets[index++];
        const key = keyOf(record);
        try { details[key] = await fetchJson(pathOf(record, key)); } catch {}
        await wait(120);
      }
    }));
    return details;
  };
  const ajioCode = (product) => String(product?.url || '').match(/\/p\/([^/?#]+)/i)?.[1] || product?.fnlColorVariantData?.colorGroup || product?.code;
  const collectAjio = async () => {
    const records = new Map();
    const searchPath = (page) => `/api/search?fields=SITE&currentPage=${page}&pageSize=45&format=json&query=gold%20coin&text=gold%20coin`;
    const addProducts = (payload) => {
      for (const product of payload?.products || []) {
        const code = ajioCode(product);
        if (code) records.set(String(code), product);
      }
    };
    const first = await fetchJson(searchPath(0));
    addProducts(first);
    const pages = Math.max(1, Number(first?.pagination?.totalPages) || Math.ceil(Number(first?.pagination?.totalResults || 0) / 45));
    let nextPage = 1;
    await Promise.all(Array.from({ length: Math.min(8, Math.max(0, pages - 1)) }, async () => {
      while (nextPage < pages) {
        const page = nextPage++;
        try { addProducts(await fetchJson(searchPath(page))); } catch {}
        await wait(120);
      }
    }));
    const values = [...records.values()];
    const details = await collectDetails(
      values,
      (product) => String(ajioCode(product)),
      (product) => !/\b\d+(?:\.\d+)?\s*(?:mg|g|gm|gms|gram|grams)\b/i.test(`${product?.name || ''} ${product?.url || ''}`) || !/\b(?:24|23|22|21|20|18|14|10|9)\s*(?:k|kt|karat)\b|\b(?:9999|999\.9|999|995|916|750|585)\b/i.test(`${product?.name || ''} ${product?.url || ''}`),
      (_, code) => `/api/p/${encodeURIComponent(code)}`
    );
    return sendProducts('ajio.com', values, details);
  };
  const collectMyntra = async () => {
    const records = new Map();
    const locationCookie = document.cookie.split('; ').find((entry) => entry.startsWith('mynt-ulc='))
      || document.cookie.split('; ').find((entry) => entry.startsWith('mynt-ulc-api='));
    const pincode = decodeURIComponent(locationCookie || '').match(/pincode:(\d{6})/)?.[1] || '560048';
    const streams = [[null, 'default'], ['price_asc', 'price_low'], ['price_desc', 'price_high'], ['popularity', 'popularity'], ['new', 'newest']];
    const offsets = [0, 97, 194, 196, 198, 200, 250, 294, 300, 193, 195, 197, 199, 245, 249, 291, 299, 343, 349];
    const searchPath = (offset, sort) => {
      const search = new URL('/gateway/v4/search/gold-coin', location.origin);
      search.searchParams.set('rows', '50');
      search.searchParams.set('o', String(offset));
      search.searchParams.set('p', String(Math.max(1, Math.floor(offset / 50) + 1)));
      search.searchParams.set('plaEnabled', 'true');
      search.searchParams.set('xdEnabled', 'false');
      search.searchParams.set('isFacet', 'true');
      if (sort) search.searchParams.set('sort', sort);
      if (pincode) search.searchParams.set('pincode', pincode);
      return `${search.pathname}${search.search}`;
    };
    const jobs = streams.flatMap(([sort, stream]) => offsets.map((offset) => ({ sort, stream, offset })));
    let nextJob = 0;
    await Promise.all(Array.from({ length: Math.min(12, jobs.length) }, async () => {
      while (nextJob < jobs.length) {
        const { offset, sort } = jobs[nextJob++];
        try {
          const payload = await fetchJson(searchPath(offset, sort));
          for (const product of [...(payload.products || []), ...(payload.plaProducts || [])]) {
            if (product?.productId != null) records.set(String(product.productId), product);
          }
        } catch {}
        await wait(100);
      }
    }));
    const values = [...records.values()];
    const details = await collectDetails(
      values,
      (product) => String(product.productId),
      (product) => !/\b\d+(?:\.\d+)?\s*(?:mg|g|gm|gms|gram|grams)\b/i.test(`${product?.productName || product?.product || ''} ${product?.landingPageUrl || ''}`) || !/\b(?:24|23|22|21|20|18|14|10|9)\s*(?:kt|k|karat)\b/i.test(`${product?.productName || product?.product || ''}`),
      (_, id) => `/gateway/v2/product/${encodeURIComponent(id)}`
    );
    return sendProducts('myntra.com', values, details);
  };

  if (location.hostname.endsWith('ajio.com') && /^\/c\/8303060(?:12|09)/.test(location.pathname)) {
    if (performance.getEntriesByType('navigation')[0]?.type !== 'reload') {
      location.reload();
      return;
    }
    if (!window.__aurumProductBridgeRunning) {
      window.__aurumProductBridgeRunning = true;
      try { console.log('Aurum: sending live AJIO listing API data.', await collectAjio()); } catch (error) { console.warn('Aurum AJIO bridge failed:', error.message); }
    }
    return;
  }
  if (location.hostname.endsWith('myntra.com') && location.pathname === '/gold-coin') {
    if (performance.getEntriesByType('navigation')[0]?.type !== 'reload') {
      location.reload();
      return;
    }
    if (!window.__aurumProductBridgeRunning) {
      window.__aurumProductBridgeRunning = true;
      try { console.log('Aurum: sending live Myntra listing API data.', await collectMyntra()); } catch (error) { console.warn('Aurum Myntra bridge failed:', error.message); }
    }
    return;
  }

  const source = location.hostname.includes('joyalukkas') ? 'joy' : 'tan';
  const clickExact = (label) => [...document.querySelectorAll('button,a,[role="tab"],div,span')].find((element) => element.textContent.trim() === label)?.click();
  if (source === 'joy') clickExact('Online Store Rate');
  if (source === 'tan') clickExact('24 Karat');
  await wait(1200);
  const text = document.body.innerText;
  const pattern = source === 'joy'
    ? /GOLD\s*24KT\s*RATE\s*₹\s*([\d,]+)/i
    : /Gold\s*Rate\s*History\s*24\s*Karat[\s\S]*?Date\s+Rate\s+\d{1,2}-\d{1,2}-\d{4}\s*₹\s*([\d,]+)/i;
  const match = text.match(pattern);
  if (!match) return;
  const price = Number(match[1].replaceAll(',', ''));
  await fetch('http://localhost:8787/api/rates', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ source, price }) });
})();