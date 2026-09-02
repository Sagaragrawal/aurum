const enabled = process.env.PRODUCT_HTTP_FAST_PATH !== '0';
const timeoutMs = Math.max(1000, Number(process.env.PRODUCT_HTTP_FAST_TIMEOUT_MS || 8000));
const concurrency = Math.max(1, Number(process.env.PRODUCT_HTTP_FAST_CONCURRENCY || 24));
const ua = process.env.BROWSER_USER_AGENT || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

let active = 0;
const waiters = [];
const acquire = async () => {
  if (active < concurrency) { active++; return; }
  await new Promise((r) => waiters.push(r));
  active++;
};
const release = () => {
  active--;
  waiters.shift()?.();
};

function normalizeFetchUrl(rawUrl) {
  try {
    const u = new URL(rawUrl);
    if (u.hostname.endsWith('flipkart.com')) {
      u.searchParams.delete('marketplace');
      return u.href;
    }
    if (u.hostname.endsWith('amazon.in') || u.hostname.endsWith('amazon.com')) {
      const dpMatch = u.pathname.match(/\/dp\/([A-Z0-9]+)/i) || u.pathname.match(/\/gp\/product\/([A-Z0-9]+)/i);
      if (dpMatch) {
        return `https://${u.hostname}/dp/${dpMatch[1]}`;
      }
    }
    return rawUrl;
  } catch {
    return rawUrl;
  }
}

export async function tryHttpFastPath(product, parse) {
  if (!enabled || typeof parse !== 'function') return { result: null, reason: enabled ? 'parser unavailable' : 'disabled' };

  await acquire();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  timer.unref?.();

  const fetchUrl = normalizeFetchUrl(product.url);

  try {
    const response = await fetch(fetchUrl, {
      redirect: 'follow',
      signal: controller.signal,
      headers: {
        'user-agent': ua,
        'accept-language': 'en-IN,en;q=0.9',
        accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
      }
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const html = await response.text();
    const result = parse(html, product.url);
    if (result) {
      return { result, reason: null };
    }
    throw new Error('HTML did not contain usable product data');
  } catch (error) {
    return {
      result: null,
      reason: error?.name === 'AbortError' ? `timeout ${timeoutMs}ms` : (error?.message || 'HTTP request failed')
    };
  } finally {
    clearTimeout(timer);
    release();
  }
}
