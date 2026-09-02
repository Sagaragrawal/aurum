const $ = (selector) => document.querySelector(selector);
const explicitMobileRoute = location.pathname.replace(/\/+$/, '') === '/mobile';
const mobileUserAgent = /android|iphone|ipod|ipad|mobile|windows phone/i.test(navigator.userAgent);
const isMobileScreen = window.matchMedia('(max-width: 768px)').matches;
// Auto-detect: /mobile is for testing mobile view on PC.
// For /, mobile mode is only enabled if the device has a mobile screen AND mobile user-agent (so "Request Desktop Site" on mobile and all PCs show full desktop view).
const routeIsMobile = explicitMobileRoute || (mobileUserAgent && isMobileScreen);
document.body.classList.toggle('mobile-mode', routeIsMobile);
if (routeIsMobile) {
  document.body.dataset.mobileView = 'market';
} else {
  delete document.body.dataset.mobileView;
}
const brandLink = document.querySelector('.brand');
if (brandLink) {
  brandLink.href = explicitMobileRoute ? '/mobile' : '/';
  brandLink.addEventListener('click', (event) => {
    const wordmark = event.currentTarget.querySelector('span:last-child');
    if (wordmark && event.clientX > wordmark.getBoundingClientRect().right + 8) event.preventDefault();
  });
}

const themeToggle = $('#themeToggle');
const themeState = $('#themeState');
const failedQuickFilter = document.querySelector('[data-quick="failed"]');
if (failedQuickFilter && !document.querySelector('[data-quick="unverified"]')) {
  const unverifiedFilter = document.createElement('button');
  unverifiedFilter.className = 'quick-chip';
  unverifiedFilter.dataset.quick = 'unverified';
  unverifiedFilter.textContent = 'Unverified';
  failedQuickFilter.before(unverifiedFilter);
}
const failedStatPill = $('#statFailed')?.closest('.stat-pill');
if (failedStatPill && !$('#statUnverified')) {
  const unverifiedStat = document.createElement('span');
  unverifiedStat.className = 'stat-pill unverified';
  unverifiedStat.innerHTML = 'Unverified: <b id="statUnverified">0</b>';
  failedStatPill.before(unverifiedStat);
}
const browserSettingsSection = [...document.querySelectorAll('.settings-section')]
  .find((section) => section.querySelector('h3')?.textContent.trim() === 'Browser behavior');
const startupSettings = [
  ['refreshBullionOnStart', 'Refresh bullion at server start', 'Check all bullion sources whenever Aurum starts'],
  ['refreshProductsOnStart', 'Refresh products at server start', 'Refresh the full product watchlist whenever Aurum starts']
];
for (const [id, label, description] of startupSettings) {
  if (!browserSettingsSection || document.getElementById(id)) continue;
  const row = document.createElement('label');
  row.className = 'switch-row';
  row.innerHTML = `<span><strong>${label}</strong><small>${description}</small></span><input id="${id}" type="checkbox">`;
  browserSettingsSection.appendChild(row);
}
const averageValue = document.querySelector('.average-card .average-value');
if (averageValue && !$('#averageRate22')) {
  averageValue.innerHTML = '<section class="benchmark-value-block benchmark-value-24"><div class="rate-value benchmark-rate-value"><span>₹</span><strong id="averageRate">Unavailable</strong><small>/ gram · 24K</small></div><small>Median-cleaned live source average</small><div class="range benchmark-range benchmark-range-24"><span></span></div><div class="benchmark-range-labels" id="benchmarkRange24"><span>Waiting for source data</span><span>Refresh to check</span></div></section><section class="benchmark-value-block benchmark-value-22"><div class="rate-value benchmark-rate-value"><span>₹</span><strong id="averageRate22">Unavailable</strong><small>/ gram · 22K</small></div><small>Median-cleaned live source average</small><div class="range benchmark-range benchmark-range-22"><span></span></div><div class="benchmark-range-labels" id="benchmarkRange22"><span>Waiting for source data</span><span>Refresh to check</span></div></section>';
  const averageCard = averageValue.closest('.average-card');
  averageCard?.querySelector(':scope > p')?.remove();
  averageCard?.querySelector(':scope > .range')?.remove();
  averageCard?.querySelector(':scope > .range-labels')?.remove();
}
const systemTheme = () => matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
const applyTheme = (preference = 'system') => {
  const resolved = preference === 'system' ? systemTheme() : preference;
  document.documentElement.dataset.theme = resolved;
  document.documentElement.dataset.themePreference = preference;
  localStorage.setItem('aurum-theme', preference);
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.content = resolved === 'light' ? '#f7f6f2' : '#080a09';
  if (themeState) themeState.textContent = preference[0].toUpperCase() + preference.slice(1);
  themeToggle?.setAttribute('aria-label', `Theme: ${preference}. Tap to change.`);
};
applyTheme(localStorage.getItem('aurum-theme') || 'system');
themeToggle?.addEventListener('click', () => {
  const current = document.documentElement.dataset.themePreference || 'system';
  applyTheme(current === 'system' ? 'dark' : current === 'dark' ? 'light' : 'system');
});
matchMedia('(prefers-color-scheme: light)').addEventListener?.('change', () => {
  if ((document.documentElement.dataset.themePreference || 'system') === 'system') applyTheme('system');
});
let state = { bullion: [], products: [] };
let mode = 'percent';
let productFilter = '';
let productSort = 'pricePerGram';
let productSortDescending = false;
let productSortCycle = 0; // 0=default, 1=asc, 2=desc
let quickFilter = 'all';
let trendKarat = 24;
let visibleTrendKarats = new Set([24, 22]);
let purityView = '24';
let gramsMinFilter = null;
let gramsMaxFilter = null;
let settings = { pincode: '560048', preciseAddress: 'Dhruvika Mogra Apartment', debugVisibleBrowser: false, productDebugVisibleBrowser: false, productAutoRefresh: false, productRefreshIntervalMin: 5, refreshProductsOnStart: false, refreshBullionOnStart: false };
let selectedStores = new Set();
let storesInitialized = false;
let allStoresSelected = true;
let restartPending = false;
let pendingDeleteId = null;
let pendingDeleteTimer = null;
let editingProductId = null;
let livePollTimer = null;
let livePollAttempts = 0;
let bullionProgress = { running: false, total: 0, checked: 0, live: 0, current: null, scope: 'all', authRequired: false, authSource: null };
const terminalStorageKey = 'aurum-terminal-logs';
const refreshTerminalLines = (() => {
  try {
    const raw = sessionStorage.getItem(terminalStorageKey);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
})();
const lastPersistedTerminalLine = refreshTerminalLines.at(-1);
let lastRefreshTerminalKey = lastPersistedTerminalLine
  ? `${lastPersistedTerminalLine.kind}:${lastPersistedTerminalLine.message}`
  : '';
let lastRefreshLoggedChecked = -1;
let lastRefreshCompletionKey = '';
const appendRefreshTerminal = (message, kind = 'info') => {
  const terminal = document.querySelector('#refreshTerminal');
  if (!message) return;
  const now = new Date();
  const time = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const key = `${kind}:${message}`;
  if (key === lastRefreshTerminalKey) return;
  lastRefreshTerminalKey = key;
  refreshTerminalLines.push({ time, message, kind });
  if (refreshTerminalLines.length > 1000) refreshTerminalLines.splice(0, refreshTerminalLines.length - 1000);
  try {
    sessionStorage.setItem(terminalStorageKey, JSON.stringify(refreshTerminalLines.slice(-300)));
  } catch { }
  if (terminal) {
    terminal.innerHTML = refreshTerminalLines.map((line) => `<div class="refresh-terminal-line ${line.kind}"><span class="refresh-terminal-time">${attr(line.time)}</span><span class="refresh-terminal-msg">${attr(line.message)}</span></div>`).join('');
    terminal.scrollTop = terminal.scrollHeight;
  }
};
const decodeHtml = (() => {
  const node = document.createElement('textarea');
  return (value) => {
    node.innerHTML = String(value ?? '');
    return node.value;
  };
})();
let lastRefreshEventId = '';
let refreshBatchStartedAt = null;
let activityStatsCleared = false;
const cleanErrorMessage = (raw) => {
  const str = String(raw || '').trim();
  if (!str) return 'failed to extract price';
  if (/Akamai\s*403|HTTP\s*403|security reasons/i.test(str)) return 'Akamai 403 (Rate limited / Security cooldown)';
  if (/out of stock|sold out/i.test(str)) return 'Out of stock';
  if (/not found|404|no longer available/i.test(str)) return 'Product not found (404)';
  if (/timed out after (\d+)ms/i.test(str)) {
    const ms = str.match(/timed out after (\d+)ms/i)?.[1];
    return `Timeout (${Math.round(Number(ms) / 1000)}s)`;
  }
  return str.length > 70 ? `${str.slice(0, 67)}...` : str;
};
const logRefreshProgress = (progress) => {
  if (!progress) return;
  const event = progress.event;
  if (event?.id && event.id !== lastRefreshEventId) {
    lastRefreshEventId = event.id;
    const store = storeLabel(event.store || 'store');
    if (event.type === 'direct-master') {
      if (event.phase === 'start') {
        appendRefreshTerminal(`[${store}] MASTER START · Opening catalogue browser`, 'active');
      } else if (event.phase === 'complete') {
        const links = Array.isArray(event.openedLinks) ? event.openedLinks : [];
        const runs = Array.isArray(event.runs) ? event.runs : [];
        for (const link of links) {
          let displayedLink = link;
          try { displayedLink = `${new URL(link).hostname}${new URL(link).pathname}`; } catch { }
          appendRefreshTerminal(`[${store}] OPENED · ${displayedLink}`, 'active');
        }
        for (const run of runs) {
          if (!run.url) continue;
          let displayedLink = run.url;
          try { displayedLink = `${new URL(run.url).hostname}${new URL(run.url).pathname}`; } catch { }
          const counts = run.counts || {};
          if (run.ok) appendRefreshTerminal(`[${store}] LINK OK · ${displayedLink} · ${counts.products || 0} products · ${counts.catalogue || 0} catalogue · ${counts.missing || 0} incomplete`, 'success');
          else appendRefreshTerminal(`[${store}] LINK FAILED · ${displayedLink} · ${cleanErrorMessage(run.error || 'master run failed')}`, 'error');
        }
        const counts = event.counts || {};
        appendRefreshTerminal(`[${store}] MASTER ${event.complete ? 'COMPLETE' : 'PARTIAL'} · ${counts.products || 0} products · ${counts.catalogue || 0} catalogue · ${counts.missing || 0} incomplete`, event.complete ? 'success' : 'error');
      } else if (event.phase === 'merged') {
        appendRefreshTerminal(`[${store}] MERGED · ${event.received || 0} received · ${event.updated || 0} updated · ${event.discovered || 0} discovered · ${event.skipped || 0} skipped`, 'success');
      } else if (event.phase === 'fallback-skipped') {
        appendRefreshTerminal(`[${store}] PDP FALLBACK SKIPPED · Existing records preserved`, 'info');
      } else if (event.phase === 'failed') {
        appendRefreshTerminal(`[${store}] MASTER FAILED · ${cleanErrorMessage(event.error || 'master failed')}`, 'error');
      }
      return;
    }
    const name = compactProductName(decodeHtml(event.name || 'Product'));
    const shortName = name.length > 50 ? `${name.slice(0, 47)}...` : name;
    if (event.phase === 'start') {
      appendRefreshTerminal(`[${store}] W${event.workerId} START · ${shortName}`, 'active');
    } else if (event.phase === 'done') {
      const parts = [`[${store}] W${event.workerId} OK · ${event.ms || 0}ms`];
      if (event.price) parts.push(`₹${money(event.price)}`);
      if (event.grams) parts.push(`${event.grams}g`);
      if (event.purity) parts.push(event.purity);
      if (event.karat && event.karat !== 24) parts.push(`${event.karat}K`);
      parts.push(shortName);
      appendRefreshTerminal(parts.join(' · '), 'success');
    } else if (event.phase === 'failed') {
      const status = event.status === 'unavailable' ? 'UNAVAIL' : event.status === 'unverified' ? 'UNVERIFIED' : event.status === 'stale' ? 'STALE' : 'FAIL';
      const reason = cleanErrorMessage(event.error);
      appendRefreshTerminal(`[${store}] W${event.workerId} ${status} · ${event.ms || 0}ms · ${shortName} · ${reason}`, 'error');
    }
  }
  if (progress.running) {
    lastRefreshCompletionKey = '';
    if (!refreshBatchStartedAt) refreshBatchStartedAt = Date.now();
    const checked = Number(progress.checked) || 0;
    const total = Number(progress.total) || 0;
    if (checked === lastRefreshLoggedChecked) return;
    lastRefreshLoggedChecked = checked;
    const elapsed = Math.round((Date.now() - refreshBatchStartedAt) / 1000);
    const currentName = compactProductName(decodeHtml(progress.current || ''));
    const target = currentName ? ` · ${currentName.length > 50 ? `${currentName.slice(0, 47)}...` : currentName}` : '';
    const failed = Number(progress.failed) || 0;
    const failedPart = failed ? ` · ${failed} failed` : '';
    appendRefreshTerminal(`PROGRESS ${checked}/${total} · ${progress.live || 0} live${failedPart} · ${elapsed}s${target}`, 'active');
  } else if (progress.total) {
    lastRefreshLoggedChecked = -1;
    const elapsed = refreshBatchStartedAt ? Math.round((Date.now() - refreshBatchStartedAt) / 1000) : 0;
    const completionKey = `${progress.blocked ? 'blocked' : progress.partial ? 'partial' : 'done'}:${progress.total}:${progress.checked}:${progress.live}:${progress.failed}:${progress.note || ''}`;
    if (completionKey === lastRefreshCompletionKey) return;
    lastRefreshCompletionKey = completionKey;
    if (progress.blocked) {
      appendRefreshTerminal(`BLOCKED · ${cleanErrorMessage(progress.note || 'AJIO refresh cooling down')} · ${elapsed}s`, 'error');
      refreshBatchStartedAt = null;
      return;
    }
    if (progress.partial) {
      appendRefreshTerminal(`PARTIAL · ${progress.live || 0} live · ${progress.stale || 0} stale · ${progress.unverified || 0} unverified · ${progress.failed || 0} failed · ${progress.unavailable || 0} unavailable · ${cleanErrorMessage(progress.note || '')} · ${elapsed}s`, 'info');
      refreshBatchStartedAt = null;
      return;
    }
    const failed = Number(progress.failed) || 0;
    const failedPart = failed ? ` · ${failed} failed` : '';
    appendRefreshTerminal(`DONE · ${progress.live || 0}/${progress.total || 0} live${failedPart} · ${elapsed}s`, 'success');
    refreshBatchStartedAt = null;
  }
};
const money = (value) => new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 }).format(Math.round(value));
const attr = (value) => String(value ?? '').replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');

const icon = (name) => {
  const paths = {
    refresh: '<path d="M20 7v5h-5"/><path d="M4 17v-5h5"/><path d="M18.2 9A7 7 0 0 0 6.1 6.1L4 8"/><path d="M5.8 15A7 7 0 0 0 17.9 17.9L20 16"/>',
    edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z"/>',
    trash: '<path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v5M14 11v5"/>'
  };
  return `<svg class="${name === 'refresh' ? 'refresh-spin-svg' : ''}" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || ''}</svg>`;
};
const normalizeStoreSource = (source, url = '') => {
  const host = String(source || '').toLowerCase().trim() || (() => {
    try { return new URL(url || '').hostname.toLowerCase(); } catch { return ''; }
  })();
  if (!host) return '';
  if (host === 'ajio.com' || host.endsWith('.ajio.com')) return 'ajio.com';
  if (host === 'amazon.in' || host.endsWith('.amazon.in')) return 'amazon.in';
  if (host === 'flipkart.com' || host.endsWith('.flipkart.com')) return 'flipkart.com';
  if (host === 'myntra.com' || host.endsWith('.myntra.com')) return 'myntra.com';
  return host.replace(/^www\./, '');
};
const storeLabel = (source) => String(normalizeStoreSource(source) || '').replace(/\.com$|\.in$/i, '').replace(/^./, (letter) => letter.toUpperCase());
const compactProductName = (name) => decodeHtml(String(name || 'Product')).replace(/^Buy\s+/i, '').replace(/\s+-\s+Gold Coin for (?:Unisex|Men|Women)\s+\d+\s*$/i, '').replace(/\s+-\s+(?:Gold Coin|Gold Bar|Pendant)\s+for\s+(?:Unisex|Men|Women)\s+\d+\s*$/i, '').replace(/\s+/g, ' ').trim();
const productKarat = (item) => { const explicit = Number(item?.karat); if (explicit > 0) return explicit; const purity = String(item?.purity || ''); if (/^(9999|999\.9|999)$/.test(purity)) return 24; if (purity === '916') return 22; if (purity === '750') return 18; if (purity === '585') return 14; return Number(`${item?.name || ''} ${item?.url || ''}`.match(/(?:^|[^0-9])(24|22|18|14)\s*-?\s*k(?:t|arat)?\b/i)?.[1] || 24); };
const isSub24K = (item) => productKarat(item) < 24;
const median = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
};
const benchmarkRates = (karat = 24) => {
  const liveRates = state.bullion
    .filter((item) => Number.isFinite(karat === 22 ? item.price22 : (item.price24 ?? item.price)))
    .map((item) => karat === 22 ? Number(item.price22) : Number(item.price24 ?? item.price))
    .filter((value) => Number.isFinite(value) && value > 0);
  if (liveRates.length < 3) return liveRates;
  const center = median(liveRates);
  const maxDeviation = center * 0.06;
  const filtered = liveRates.filter((price) => Math.abs(price - center) <= maxDeviation);
  return filtered.length >= 2 ? filtered : liveRates;
};
const average = (karat = 24) => { const prices = benchmarkRates(karat); return prices.length ? prices.reduce((sum, price) => sum + price, 0) / prices.length : null; };
const relative = (iso) => iso ? new Intl.RelativeTimeFormat('en', { numeric: 'auto' }).format(Math.round((new Date(iso) - Date.now()) / 60000), 'minute') : 'queued';
const uiBusy = () => {
  const progress = window.refreshProgress || { running: false };
  return progress.running || bullionProgress.running || state.bullion.some((item) => item.status === 'checking') || state.products.some((item) => item.status === 'checking');
};
const stopLivePolling = () => {
  if (livePollTimer) clearInterval(livePollTimer);
  livePollTimer = null;
  livePollAttempts = 0;
};
const startLivePolling = () => {
  if (livePollTimer) return;
  livePollAttempts = 0;
  livePollTimer = setInterval(async () => {
    livePollAttempts += 1;
    try {
      await load();
      if (!uiBusy() || livePollAttempts >= 120) stopLivePolling();
    } catch {
      if (livePollAttempts >= 120) stopLivePolling();
    }
  }, 1000);
};
window.addEventListener('online', () => { void load().catch(() => { }); startLivePolling(); });
const setProxyStatus = (message, state = '') => {
  const status = $('#proxyStatus');
  const summaryStatus = $('#proxySummaryStatus');
  status.textContent = message;
  status.className = `proxy-status ${state}`;
  summaryStatus.textContent = state === 'ready' ? 'Ready' : state === 'error' ? 'Rejected' : 'Not set';
  summaryStatus.className = `proxy-summary-status ${state}`;
};
const confirmProxyCredentials = async () => {
  try {
    const response = await fetch('/api/proxy-auth', { signal: AbortSignal.timeout(2000) });
    const result = await response.json();
    if (!result.configured) return false;
    const username = $('#proxyUsername').value.trim();
    const password = $('#proxyPassword').value;
    if (username && password) sessionStorage.setItem(proxyStorageKey, JSON.stringify({ username, password }));
    setProxyStatus(result.persisted ? 'Saved & ready' : 'Ready', 'ready');
    return true;
  } catch {
    return false;
  }
};
const saveProxyCredentials = async () => {
  const username = $('#proxyUsername').value.trim();
  const password = $('#proxyPassword').value;
  if (!username || !password) {
    setProxyStatus('Enter ID/password', 'error');
    return false;
  }
  setProxyStatus('Saved & ready', 'ready');
  let response;
  try {
    response = await fetch('/api/proxy-auth', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username, password }),
      signal: AbortSignal.timeout(8000)
    });
  } catch {
    if (await confirmProxyCredentials()) return true;
    setProxyStatus('Save timed out; reload to check status', 'error');
    return false;
  }
  if (!response.ok) {
    const result = await response.json().catch(() => ({}));
    setProxyStatus(result.error || 'Could not save', 'error');
    return false;
  }
  sessionStorage.setItem(proxyStorageKey, JSON.stringify({ username, password }));
  const result = await response.json().catch(() => ({}));
  setProxyStatus(result.persisted ? 'Saved & ready' : 'Ready', 'ready');
  return true;
};
const ensureProxyCredentials = async () => {
  if ($('#proxyUsername').value.trim() || $('#proxyPassword').value) return saveProxyCredentials();
  const proxy = await fetch('/api/proxy-auth').then((response) => response.json()).catch(() => ({ configured: false }));
  if (proxy.configured) return true;
  setProxyStatus('Enter ID/password', 'error');
  const settingsMenu = document.querySelector('.settings-menu');
  if (settingsMenu) settingsMenu.open = true;
  toast('Enter proxy ID and password in Settings to refresh bullion rates.');
  return false;
};
const pollBullionProgress = () => {
  const timer = setInterval(async () => {
    const progress = await fetch('/api/bullion/progress').then((res) => res.json()).catch(() => ({ running: false }));
    bullionProgress = progress;
    if (progress.authRequired) {
      clearInterval(timer);
      $('#refreshButton').disabled = false;
      setProxyStatus('Credentials rejected', 'error');
      toast('Proxy credentials were rejected. Update them in the top bar and refresh again.');
      return;
    }
    await load();
    if (!progress.running) {
      await load();
      clearInterval(timer);
      $('#refreshButton').disabled = false;
      $('#syncText').textContent = `${progress.live || 0}/${progress.checked || 0} ${progress.total === 1 ? 'source' : 'sources'} live`;
      toast(progress.note || 'Bullion refresh complete.');
    }
  }, 1000);
};
const startBullionRefresh = async (scope = 'all') => {
  startLivePolling();
  const response = scope === 'all'
    ? await fetch('/api/refresh', { method: 'POST' })
    : await fetch(`/api/refresh/${scope}`, { method: 'POST' });
  if (!response || !response.ok) {
    const result = await response.json().catch(() => ({}));
    throw new Error(result.error || 'Could not start bullion refresh');
  }
  pollBullionProgress();
};
function render() {
  document.documentElement.style.setProperty('--refresh-phase', `-${Math.round(performance.now() % 1400)}ms`);
  const benchmark24 = average(24);
  const benchmark22 = average(22);
  const benchmark = benchmark24;
  const benchmarkForProduct = (item) => {
    const karat = productKarat(item);
    if (karat === 24) return benchmark24;
    if (karat === 22) return benchmark22;
    return null;
  };
  const rateSources = $('#rateSources');
  if (rateSources) {
    const signature = state.bullion.map((item) => item.id).join('|');
    if (rateSources.dataset.signature !== signature) {
      rateSources.innerHTML = state.bullion.map((item) => `<article class="rate-card" data-source-card="${attr(item.id)}"><div class="card-top"><span class="source-dot teal"></span><a id="${attr(item.id)}Link" class="source-link" href="${attr(item.url || '#')}" target="_blank" rel="noreferrer">${attr(item.label || item.source || item.id)}</a><button type="button" class="bullion-refresh" data-refresh-source="${attr(item.id)}" title="Refresh source" aria-label="Refresh ${attr(item.label || item.source || item.id)}">${icon('refresh')}</button><span class="live-pill" id="${attr(item.id)}Status">UNAVAILABLE</span></div><div class="rate-value"><span>₹</span><strong id="${attr(item.id)}Rate24">Unavailable</strong><small>/ gram · 24K</small></div><div class="rate-value"><span>₹</span><strong id="${attr(item.id)}Rate22">Unavailable</strong><small>/ gram · 22K</small></div><div class="card-foot"><span id="${attr(item.id)}Meta">24K / 22K</span><span id="${attr(item.id)}Time">Not checked</span></div></article>`).join('');
      rateSources.dataset.signature = signature;
    }
  }
  state.bullion.forEach((item) => {
    const prefix = item.id;
    const hasPrice24 = Number.isFinite(item.price24 ?? item.price) && (item.price24 ?? item.price) > 0;
    const hasPrice22 = Number.isFinite(item.price22) && item.price22 > 0;
    const checking = item.status === 'checking';
    const live = item.status === 'live' && hasPrice24;
    $(`#${prefix}Rate24`).textContent = hasPrice24 ? money(item.price24 ?? item.price) : 'Unavailable';
    $(`#${prefix}Rate22`).textContent = hasPrice22 ? money(item.price22) : 'Unavailable';
    const status = $(`#${prefix}Status`);
    status.textContent = checking ? 'CHECKING' : live ? 'LIVE' : item.status === 'stale' ? 'STALE' : 'UNAVAILABLE';
    status.classList.toggle('live-pill-active', live && !checking);
    status.classList.toggle('live-pill-checking', checking);
    status.title = item.error || (live ? 'Rate fetched successfully' : checking ? 'Fetching latest live rate' : 'Latest fetch did not return a usable rate');
    status.closest('.rate-card')?.classList.toggle('loading-source', checking);
    const sourceLink = $(`#${prefix}Link`);
    if (sourceLink) {
      sourceLink.textContent = item.label || item.source || item.id.toUpperCase();
      sourceLink.href = item.url || '#';
      sourceLink.title = item.url || 'Source URL unavailable';
    }
    const sourceMeta = $(`#${prefix}Meta`);
    if (sourceMeta) {
      sourceMeta.textContent = item.price22Derived ? '24K / 22K (22K derived)' : '24K / 22K';
    }
    $(`#${prefix}Time`).textContent = checking ? 'refreshing now' : relative(item.fetchedAt);
  });
  $('#averageRate').textContent = benchmark24 ? money(benchmark24) : 'Unavailable';
  $('#averageRate22').textContent = benchmark22 ? money(benchmark22) : 'Unavailable';
  renderBullionTrendChart();
  const prices24 = benchmarkRates(24);
  const prices22 = benchmarkRates(22);
  const rangeText = (prices) => prices.length ? `<span>₹${money(Math.min(...prices))} low</span><span>₹${money(Math.max(...prices))} high</span>` : '<span>Waiting for source data</span><span>Refresh to check</span>';
  $('#benchmarkRange24').innerHTML = rangeText(prices24);
  $('#benchmarkRange22').innerHTML = rangeText(prices22);
  const progress = window.refreshProgress || { running: false, checked: 0, total: 0, live: 0, current: null };
  const activeNames = state.products.filter((item) => item.status === 'checking').map((item) => item.name).join(', ');
  const activeCount = state.products.filter((item) => item.status === 'checking').length;
  logRefreshProgress(progress);
  const refreshOverview = $('#refreshOverview');
  const tracked = state.products.length;
  const liveCount = state.products.filter((item) => item.status === 'live').length;
  const staleCount = state.products.filter((item) => item.status === 'stale').length;
  const unverifiedCount = state.products.filter((item) => item.status === 'unverified').length;
  const failedCount = state.products.filter((item) => item.status === 'failed').length;
  const unavailableCount = state.products.filter((item) => item.status === 'unavailable').length;
  const checkingCount = state.products.filter((item) => item.status === 'checking').length;
  const checkedCount = Number(progress.checked) || 0;

  const activityStats = activityStatsCleared
    ? { tracked: 0, live: 0, stale: 0, unverified: 0, failed: 0, unavailable: 0 }
    : { tracked, live: liveCount, stale: staleCount, unverified: unverifiedCount, failed: failedCount, unavailable: unavailableCount };
  const statTotal = $('#statTotal'); if (statTotal) statTotal.textContent = String(activityStats.tracked);
  const statLive = $('#statLive'); if (statLive) statLive.textContent = String(activityStats.live);
  const statStale = $('#statStale'); if (statStale) statStale.textContent = String(activityStats.stale);
  const statUnverified = $('#statUnverified'); if (statUnverified) statUnverified.textContent = String(activityStats.unverified);
  const statFailed = $('#statFailed'); if (statFailed) statFailed.textContent = String(activityStats.failed);
  const statUnavailable = $('#statUnavailable'); if (statUnavailable) statUnavailable.textContent = String(activityStats.unavailable);

  const badge = $('#activityLiveBadge');
  if (badge) {
    if (checkingCount > 0 || progress.running) {
      badge.textContent = progress.running ? `Running (${progress.checked || 0}/${progress.total || tracked})` : `In-flight (${checkingCount})`;
      badge.className = 'activity-badge running';
    } else {
      badge.textContent = `${liveCount}/${tracked} Live`;
      badge.className = 'activity-badge done';
    }
  }

  if (refreshOverview) {
    refreshOverview.textContent = progress.running
      ? `Checked: ${checkedCount}/${progress.total || tracked} | Live: ${progress.live || 0} | Stale: ${staleCount} | Unverified: ${unverifiedCount} | Failed: ${failedCount} | Unavailable: ${unavailableCount} | Checking: ${checkingCount}`
      : `Tracked: ${tracked} | Live: ${liveCount} | Stale: ${staleCount} | Unverified: ${unverifiedCount} | Failed: ${failedCount} | Unavailable: ${unavailableCount} | Checking: ${checkingCount}`;
  }
  $('#refreshProgress').textContent = progress.running
    ? `Refreshing ${progress.checked}/${progress.total} products · ${progress.live} live · ${activeCount} in flight · ${activeNames || progress.current || 'starting next'}`
    : progress.total
      ? `Last refresh: ${progress.live}/${progress.total} live.`
      : 'Ready for a product refresh.';
  const trackedStores = new Set(state.products.map((item) => normalizeStoreSource(item.source, item.url)).filter(Boolean));
  const stores = [...trackedStores].sort();
  if (!storesInitialized) {
    selectedStores = new Set(stores);
    allStoresSelected = true;
    storesInitialized = true;
  } else {
    selectedStores = new Set([...selectedStores].filter((store) => trackedStores.has(store)));
    if (allStoresSelected) selectedStores = new Set(stores);
  }
  const allSelected = stores.length > 0 && selectedStores.size === stores.length;
  allStoresSelected = allSelected;
  const storeCounts = new Map(stores.map((store) => [store, state.products.filter((item) => normalizeStoreSource(item.source, item.url) === store).length]));
  const allTile = `<button type="button" class="store-tile store-all ${allSelected ? 'selected' : 'unselected'}" data-store-all="true" aria-pressed="${allSelected}"><span>All</span><b class="filter-count">${tracked}</b></button>`;
  $('#storeTiles').innerHTML = allTile + stores.map((store) => {
    const selected = selectedStores.has(store);
    return `<button type="button" class="store-tile ${selected ? 'selected' : 'unselected'}" data-store="${store}" aria-pressed="${selected}"><span>${attr(storeLabel(store))}</span><b class="filter-count">${storeCounts.get(store) || 0}</b></button>`;
  }).join('');
  const deltaInput = $('#deltaValue');
  const deltaVal = Number(deltaInput?.value);
  const delta = Number.isFinite(deltaVal) && deltaVal >= 0 ? deltaVal : (mode === 'percent' ? 2 : 200);
  const deals = state.products
    .filter((item) => item.status === 'live' && item.price > 0 && item.grams > 0 && [24, 22].includes(productKarat(item)))
    .map((item) => {
      const effectivePrice = item.couponPrice && item.couponPrice < item.price ? item.couponPrice : item.price;
      const perGram = effectivePrice / item.grams;
      const benchmarkPerGram = benchmarkForProduct(item);
      if (!benchmarkPerGram) return null;
      const rupeesDiff = perGram - benchmarkPerGram;
      const percentDiff = ((perGram - benchmarkPerGram) / benchmarkPerGram) * 100;
      return { ...item, effectivePrice, perGram, benchmarkPerGram, rupeesDiff, percentDiff, diff: percentDiff };
    })
    .filter(Boolean)
    .sort((a, b) => mode === 'percent' ? Math.abs(a.percentDiff) - Math.abs(b.percentDiff) : Math.abs(a.rupeesDiff) - Math.abs(b.rupeesDiff))
    .filter((item) => mode === 'percent' ? Math.abs(item.percentDiff) <= delta : Math.abs(item.rupeesDiff) <= delta);
  $('#dealCount').textContent = `${deals.length} ${deals.length === 1 ? 'deal' : 'deals'}`;
  $('#dealList').innerHTML = deals.slice(0, 6).map((item) => {
    const isBelow = item.rupeesDiff < 0;
    const deltaText = mode === 'percent'
      ? `${isBelow ? '↓' : '↑'} ${Math.abs(item.percentDiff).toFixed(2)}% vs benchmark`
      : `${isBelow ? '↓' : '↑'} ₹${money(Math.abs(item.rupeesDiff))}/g vs benchmark`;
    return `<a class="deal-card ${isBelow ? 'steal' : ''}" href="${attr(item.url)}" target="_blank" rel="noreferrer"><span class="deal-tag">${isBelow ? 'STEAL DEAL' : 'CLOSE MATCH'}</span><h3 class="deal-name">${attr(item.name)}</h3><div class="deal-meta">${attr(storeLabel(item.source))}${item.brand ? ` · ${attr(item.brand)}` : ''} · ${item.grams}g · ${productKarat(item)}K · ${attr(item.purity)}</div><div class="deal-price">₹${money(item.perGram)} <small>/ gram${item.couponPrice && item.couponPrice < item.price ? ' coupon' : ''}</small></div><div class="deal-delta">${deltaText}</div></a>`;
  }).join('') || '<p class="deal-meta">No products match this delta yet.</p>';
  const baseFilteredProducts = state.products.filter((item) => {
    const normalizedStore = normalizeStoreSource(item.source, item.url);
    if (!selectedStores.has(normalizedStore)) return false;
    if (productFilter && !`${item.name} ${item.brand} ${item.source} ${item.grams} ${item.purity}`.toLowerCase().includes(productFilter)) return false;
    if (gramsMinFilter !== null && (Number(item.grams) || 0) < gramsMinFilter) return false;
    if (gramsMaxFilter !== null && (Number(item.grams) || 0) > gramsMaxFilter) return false;
    return true;
  });
  const matchesQuickFilter = (item, filter) => {
    const pg = item.price && item.grams ? (item.couponPrice && item.couponPrice < item.price ? item.couponPrice : item.price) / item.grams : null;
    const itemBenchmark = benchmarkForProduct(item);
    const diff = pg && itemBenchmark ? ((pg - itemBenchmark) / itemBenchmark) * 100 : null;
    if (filter === 'below') return diff !== null && diff < 0;
    if (filter === 'not-live') return item.status !== 'live';
    if (['live', 'checking', 'stale', 'unverified', 'failed', 'unavailable'].includes(filter)) return item.status === filter;
    return true;
  };
  document.querySelectorAll('#quickFilters [data-quick]').forEach((button) => {
    const filter = button.dataset.quick;
    const label = button.dataset.filterLabel || button.textContent.trim();
    const count = baseFilteredProducts.filter((item) => matchesQuickFilter(item, filter)).length;
    button.dataset.filterLabel = label;
    button.innerHTML = `<span>${attr(label)}</span><b class="filter-count">${count}</b>`;
    button.setAttribute('aria-label', `${label}: ${count} products`);
  });
  const visibleProducts = baseFilteredProducts.filter((item) => matchesQuickFilter(item, quickFilter));
  const purityMatches = (item) => { const karat = productKarat(item); return purityView === '24' ? karat === 24 : purityView === '22' ? karat === 22 : karat !== 24 && karat !== 22; };
  $('#purity24Count').textContent = visibleProducts.filter((item) => productKarat(item) === 24).length;
  $('#purity22Count').textContent = visibleProducts.filter((item) => productKarat(item) === 22).length;
  $('#purityOtherCount').textContent = visibleProducts.filter((item) => ![24, 22].includes(productKarat(item))).length;
  const purityFilteredProducts = visibleProducts.filter(purityMatches);
  const primaryVisibleProducts = purityFilteredProducts;
  const sub24VisibleProducts = [];
  const statusOrder = { live: 0, checking: 1, stale: 2, unverified: 3, failed: 4, unavailable: 5 };
  const sortValue = (item) => {
    if (productSort === 'grams') return Number(item.grams) || 0;
    if (productSort === 'price') return Number(item.price) || 0;
    if (productSort === 'couponPrice') return Number(item.couponPrice) || Infinity;
    if (productSort === 'name') return compactProductName(item.name).toLowerCase();
    if (productSort === 'store') return normalizeStoreSource(item.source, item.url);
    if (productSort === 'couponPerGram') return item.couponPrice && item.grams ? item.couponPrice / item.grams : Infinity;
    if (productSort === 'vsBullion') { const pg = item.price && item.grams ? (item.couponPrice && item.couponPrice < item.price ? item.couponPrice : item.price) / item.grams : null; const bm = benchmarkForProduct(item); return pg && bm ? ((pg - bm) / bm) * 100 : Infinity; }
    return item.price && item.grams ? item.price / item.grams : Infinity;
  };
  const sortedProducts = [...primaryVisibleProducts].sort((a, b) => {
    const aLive = a.status === 'live' ? 0 : 1;
    const bLive = b.status === 'live' ? 0 : 1;
    if (aLive !== bLive) return aLive - bLive;
    const va = sortValue(a);
    const vb = sortValue(b);
    const diff = typeof va === 'string' ? va.localeCompare(vb) : va - vb;
    return productSortDescending ? -diff : diff;
  });
  if ($('#mobileProductCount')) $('#mobileProductCount').textContent = state.products.length;
  document.querySelectorAll('th[data-sort]').forEach((th) => {
    const key = th.dataset.sort;
    const arrow = productSortCycle === 0 ? '' : key === productSort ? (productSortDescending ? ' ↓' : ' ↑') : '';
    const base = th.textContent.replace(/ [↑↓]$/, '');
    th.textContent = base + arrow;
    th.classList.toggle('sort-active', productSortCycle !== 0 && key === productSort);
  });
  $('#productTotal').textContent = visibleProducts.length === state.products.length
    ? `${state.products.length} products`
    : `${visibleProducts.length} of ${state.products.length} products`;
  const isMobile = document.body.classList.contains('mobile-mode');
  const maxInitial = 100;
  const renderList = sortedProducts.slice(0, maxInitial);

  if (!isMobile) {
    $('#productRows').innerHTML = renderList.map((item) => {
      const displayName = compactProductName(item.name);
      const perGram = item.price && item.grams ? item.price / item.grams : null;
      const couponPerGram = item.couponPrice && item.grams ? item.couponPrice / item.grams : null;
      const comparisonPerGram = couponPerGram || perGram;
      const itemBenchmark = benchmarkForProduct(item);
      const diff = comparisonPerGram && itemBenchmark ? ((comparisonPerGram - itemBenchmark) / itemBenchmark) * 100 : null;
      const comparison = diff !== null ? `<span class="comparison-caption">vs gold rate</span><strong>${diff < 0 ? '↓' : '↑'} ${Math.abs(diff).toFixed(2)}%</strong><small>₹${money(comparisonPerGram)}/g</small>` : '<span class="comparison-caption">vs gold rate</span><strong>Unavailable</strong>';
      const refreshLabel = item.status === 'unavailable' || item.status === 'stale' || item.status === 'unverified' || item.status === 'failed' ? 'Retry' : 'Refresh';
      const checking = item.status === 'checking';
      const edit = `<button class="edit-button" data-edit-id="${item.id}" title="Edit product details" aria-label="Edit ${attr(displayName)}">${icon('edit')}</button>`;
      const refresh = `<button class="retry-button ${checking ? 'loading' : ''}" data-retry-id="${item.id}" title="${attr(refreshLabel)} product${settings.debugVisibleBrowser ? ' with visible browser' : ' in background'}" aria-label="${attr(refreshLabel)} ${attr(displayName)}" ${checking ? 'disabled aria-busy="true"' : ''}>${icon('refresh')}</button>`;
      const status = `<span class="status-badge ${item.status}" title="${attr(item.error || `Status: ${item.status}`)}">${item.status.toUpperCase()}</span>`;
      const priceBlock = item.price ? `<strong>₹${money(item.price)}</strong>` : 'Unavailable';
      return `<tr class="${checking ? 'loading-row' : ''} ${item.couponPrice ? 'has-coupon' : 'no-coupon'}"><td><a href="${item.url}" target="_blank" rel="noreferrer">${attr(displayName)} ↗</a><br><span class="store">${attr(item.brand || '')}</span> ${status}</td><td>${attr(storeLabel(item.source))}</td><td>${item.grams ? item.grams + 'g' : '—'}</td><td class="product-price">${priceBlock}</td><td>${item.couponPrice ? '₹' + money(item.couponPrice) : ''}</td><td><strong>${perGram ? '₹' + money(perGram) : '—'}</strong></td><td class="coupon-price"><strong>${couponPerGram ? '₹' + money(couponPerGram) : '—'}</strong></td><td class="comparison-cell ${diff !== null && diff < 0 ? 'under' : 'over'}">${comparison}</td><td>${edit}${refresh}<button class="delete-button" data-delete-id="${item.id}" title="Delete product" aria-label="Delete ${attr(displayName)}">${icon('trash')}</button></td></tr>`;
    }).join('');
  }

  if ($('#bottomProductCount')) $('#bottomProductCount').textContent = state.products.length;
  const mobileCards = $('#mobileProductCards');
  if (mobileCards && isMobile) {
    mobileCards.innerHTML = renderList.map((item) => {
      const displayName = compactProductName(item.name);
      const effective = item.couponPrice && item.couponPrice < item.price ? item.couponPrice : item.price;
      const perGram = effective && item.grams ? effective / item.grams : null;
      const itemBenchmark = benchmarkForProduct(item);
      const diff = perGram && itemBenchmark ? ((perGram - itemBenchmark) / itemBenchmark) * 100 : null;
      const checking = item.status === 'checking';
      return `<article class="product-card-mobile ${checking ? 'is-refreshing' : ''}"><div class="pcm-head"><div><a href="${attr(item.url)}" target="_blank" rel="noreferrer">${attr(displayName)}</a><div class="pcm-meta">${attr(item.brand || storeLabel(item.source))} · ${item.grams ? item.grams + 'g' : '—'} · ${attr(item.purity || '24K')}</div></div><span class="status-badge ${item.status}">${item.status.toUpperCase()}</span></div><div class="pcm-value"><span>Effective / gram</span><strong>${perGram ? '₹' + money(perGram) : '—'}</strong><em class="${diff !== null && diff < 0 ? 'under' : 'over'}">${diff !== null ? `${diff < 0 ? '↓' : '↑'} ${Math.abs(diff).toFixed(2)}%` : 'No benchmark'}</em></div><div class="pcm-stats"><span><small>Price</small><b>${item.price ? '₹' + money(item.price) : '—'}</b></span><span><small>Coupon</small><b>${item.couponPrice ? '₹' + money(item.couponPrice) : '—'}</b></span><span><small>Store</small><b>${attr(storeLabel(item.source))}</b></span></div><div class="pcm-actions"><button data-mobile-retry="${attr(item.id)}" ${checking ? 'disabled' : ''}>${icon('refresh')}<span>${checking ? 'Refreshing' : 'Refresh'}</span></button><button data-mobile-edit="${attr(item.id)}">${icon('edit')}<span>Edit</span></button><button class="danger" data-mobile-delete="${attr(item.id)}">${icon('trash')}<span>Delete</span></button></div></article>`;
    }).join('') || '<div class="mobile-empty">No products match these filters.</div>';
  }
  const sub24Section = $('#sub24Section');
  const sub24Sorted = [...sub24VisibleProducts].sort((a, b) => {
    const aLive = a.status === 'live' ? 0 : 1;
    const bLive = b.status === 'live' ? 0 : 1;
    if (aLive !== bLive) return aLive - bLive;
    const va = sortValue(a);
    const vb = sortValue(b);
    const diff = typeof va === 'string' ? va.localeCompare(vb) : va - vb;
    return productSortDescending ? -diff : diff;
  });
  if (sub24Section) sub24Section.hidden = true;
  if ($('#sub24Count')) $('#sub24Count').textContent = `${sub24Sorted.length} ${sub24Sorted.length === 1 ? 'product' : 'products'}`;
  const sub24Rows = $('#sub24ProductRows');
  if (sub24Rows) sub24Rows.innerHTML = sub24Sorted.map((item) => { const displayName = compactProductName(item.name); const effective = item.couponPrice && item.couponPrice < item.price ? item.couponPrice : item.price; const perGram = effective && item.grams ? effective / item.grams : null; const checking = item.status === 'checking'; return `<tr class="${checking ? 'loading-row' : ''}"><td><a href="${attr(item.url)}" target="_blank" rel="noreferrer">${attr(displayName)} ↗</a><br><span class="store">${attr(item.brand || '')}</span></td><td>${attr(storeLabel(item.source))}</td><td><strong>${productKarat(item)}K</strong> · ${attr(item.purity || '—')}</td><td>${item.grams ? item.grams + 'g' : '—'}</td><td>${item.price ? '₹' + money(item.price) : '—'}</td><td><strong>${perGram ? '₹' + money(perGram) : '—'}</strong></td><td><span class="status-badge ${item.status}">${item.status.toUpperCase()}</span></td><td><button class="edit-button" data-edit-id="${item.id}">${icon('edit')}</button><button class="retry-button ${checking ? 'loading' : ''}" data-retry-id="${item.id}" ${checking ? 'disabled' : ''}>${icon('refresh')}</button><button class="delete-button" data-delete-id="${item.id}">${icon('trash')}</button></td></tr>`; }).join('');
  const sub24Cards = $('#sub24MobileProductCards');
  if (sub24Cards) sub24Cards.innerHTML = sub24Sorted.map((item) => { const displayName = compactProductName(item.name); const effective = item.couponPrice && item.couponPrice < item.price ? item.couponPrice : item.price; const perGram = effective && item.grams ? effective / item.grams : null; const checking = item.status === 'checking'; return `<article class="product-card-mobile sub24-card ${checking ? 'is-refreshing' : ''}"><div class="pcm-head"><div><a href="${attr(item.url)}" target="_blank" rel="noreferrer">${attr(displayName)}</a><div class="pcm-meta">${attr(item.brand || storeLabel(item.source))} · ${item.grams ? item.grams + 'g' : '—'} · ${productKarat(item)}K / ${attr(item.purity || '—')}</div></div><span class="status-badge ${item.status}">${item.status.toUpperCase()}</span></div><div class="pcm-value"><span>Effective / gram</span><strong>${perGram ? '₹' + money(perGram) : '—'}</strong><em class="sub24-purity">${productKarat(item)}K gold</em></div><div class="pcm-stats"><span><small>Price</small><b>${item.price ? '₹' + money(item.price) : '—'}</b></span><span><small>Coupon</small><b>${item.couponPrice ? '₹' + money(item.couponPrice) : '—'}</b></span><span><small>Store</small><b>${attr(storeLabel(item.source))}</b></span></div><div class="pcm-actions"><button data-mobile-retry="${attr(item.id)}" ${checking ? 'disabled' : ''}>${icon('refresh')}<span>${checking ? 'Refreshing' : 'Refresh'}</span></button><button data-mobile-edit="${attr(item.id)}">${icon('edit')}<span>Edit</span></button><button class="danger" data-mobile-delete="${attr(item.id)}">${icon('trash')}<span>Delete</span></button></div></article>`; }).join('');
  $('#lastChecked').textContent = new Date().toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}
async function load() {
  const response = await fetch('/api/state');
  state = await response.json();
  settings = state.settings || settings;
  const proxy = await fetch('/api/proxy-auth').then((response) => response.json()).catch(() => ({ configured: false }));
  if (proxy.configured) setProxyStatus(proxy.persisted ? 'Saved & ready' : 'Ready', 'ready');
  else setProxyStatus('Not set');
  window.refreshProgress = state.productRefresh || window.refreshProgress || { running: false };
  bullionProgress = state.bullionRefresh || bullionProgress || { running: false };
  if (window.refreshProgress.authRequired || bullionProgress.authRequired) setProxyStatus('Credentials rejected', 'error');
  $('#preciseAddress').value = settings.preciseAddress || '';
  $('#pincode').value = settings.pincode || '';
  if ($('#desktopPreciseAddress')) $('#desktopPreciseAddress').value = settings.preciseAddress || '';
  if ($('#desktopPincode')) $('#desktopPincode').value = settings.pincode || '';
  $('#debugVisibleBrowser').checked = Boolean(settings.debugVisibleBrowser);
  $('#productDebugVisibleBrowser').checked = Boolean(settings.productDebugVisibleBrowser);
  $('#refreshBullionOnStart').checked = Boolean(settings.refreshBullionOnStart);
  $('#refreshProductsOnStart').checked = Boolean(settings.refreshProductsOnStart);
  $('#productAutoRefresh').checked = Boolean(settings.productAutoRefresh);
  $('#productRefreshIntervalMin').value = settings.productRefreshIntervalMin || 5;
  render();
}
$('#refreshButton').addEventListener('click', async () => {
  $('#syncText').textContent = 'Checking sources...';
  $('#refreshButton').disabled = true;
  state.bullion.forEach((item) => {
    item.status = 'checking';
    delete item.error;
  });
  render();
  try {
    await startBullionRefresh('all');
  } catch (error) {
    $('#refreshButton').disabled = false;
    await load();
    toast(error.message || 'Could not refresh sources.');
  }
});
$('.rate-grid').addEventListener('click', async (event) => {
  const button = event.target.closest('[data-refresh-source]');
  if (!button) return;
  const sourceId = button.dataset.refreshSource;
  const item = state.bullion.find((entry) => entry.id === sourceId);
  if (!item) return;
  item.status = 'checking';
  delete item.error;
  render();
  try {
    await startBullionRefresh(sourceId);
  } catch (error) {
    await load();
    toast(error.message || 'Could not refresh source.');
  }
});
$('#restartButton')?.addEventListener('click', async () => {
  const button = $('#restartButton');
  if (button) {
    button.disabled = true;
    button.classList.add('loading');
  }
  $('#syncText').textContent = 'Restarting backend...';
  appendRefreshTerminal('SYSTEM · Restart initiated by user', 'active');
  toast('Restarting server and continuing background jobs...');
  await fetch('/api/restart', { method: 'POST' }).catch(() => null);
  setTimeout(async () => {
    for (let attempt = 0; attempt < 30; attempt += 1) {
      try {
        await load();
        $('#syncText').textContent = 'Ready';
        if (button) { button.disabled = false; button.classList.remove('loading'); }
        toast('Backend restarted and reconnected.');
        return;
      } catch { }
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    $('#syncText').textContent = 'Backend restart timed out';
    if (button) { button.disabled = false; button.classList.remove('loading'); }
  }, 1000);
});
$('#deltaValue')?.addEventListener('input', render);
$('#deltaValue')?.addEventListener('change', render);
const sortLabel = document.querySelector('.product-sort');
const sortButton = $('#sortDirection');
if (routeIsMobile && sortLabel && sortButton) {
  const sortControls = document.createElement('div');
  sortControls.className = 'sort-controls';
  sortLabel.parentNode.insertBefore(sortControls, sortLabel);
  sortControls.append(sortLabel, sortButton);
  const select = sortLabel.querySelector('select');
  if (select) select.hidden = false;
}
$('#productFilter').addEventListener('input', (event) => { productFilter = event.target.value.trim().toLowerCase(); render(); });
$('#puritySwitch')?.addEventListener('click', (event) => {
  const tab = event.target.closest('[data-purity-view]');
  if (!tab) return;
  purityView = tab.dataset.purityView;
  $('#puritySwitch').querySelectorAll('[data-purity-view]').forEach((button) => {
    const active = button === tab;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', String(active));
  });
  render();
});
const parseGramsBound = (value) => { const trimmed = String(value ?? '').trim(); return trimmed === '' ? null : Number(trimmed); };
$('#gramsMin').addEventListener('input', (event) => { gramsMinFilter = parseGramsBound(event.target.value); render(); });
$('#gramsMax').addEventListener('input', (event) => { gramsMaxFilter = parseGramsBound(event.target.value); render(); });
$('#productSort').addEventListener('change', (event) => { productSort = event.target.value; productSortCycle = 1; productSortDescending = false; render(); });
$('#sortDirection').addEventListener('click', (event) => { productSortDescending = !productSortDescending; productSortCycle = productSortDescending ? 2 : 1; event.currentTarget.textContent = productSortDescending ? '↓' : '↑'; render(); });
document.querySelector('.tracked-section table')?.addEventListener('click', (event) => {
  const th = event.target.closest('th[data-sort]');
  if (!th) return;
  const key = th.dataset.sort;
  if (productSort === key && productSortCycle > 0) {
    if (productSortCycle === 1) { productSortCycle = 2; productSortDescending = true; }
    else { productSortCycle = 0; productSort = 'pricePerGram'; productSortDescending = false; }
  } else {
    productSort = key; productSortCycle = 1; productSortDescending = false;
  }
  const select = $('#productSort');
  if (select) select.value = productSortCycle === 0 ? 'pricePerGram' : productSort;
  const dirBtn = $('#sortDirection');
  if (dirBtn) dirBtn.textContent = productSortDescending ? '↓' : '↑';
  render();
});

const deltaInput = $('#deltaValue');
const savedDelta = localStorage.getItem('aurum-deal-radar-value');
if (deltaInput) {
  if (savedDelta !== null && savedDelta !== '') {
    deltaInput.value = savedDelta;
  } else {
    deltaInput.value = mode === 'percent' ? '2' : '200';
  }
  deltaInput.step = mode === 'percent' ? '0.1' : '50';
  deltaInput.addEventListener('input', () => {
    localStorage.setItem('aurum-deal-radar-value', deltaInput.value);
    render();
  });
  deltaInput.addEventListener('change', () => {
    localStorage.setItem('aurum-deal-radar-value', deltaInput.value);
    render();
  });
}

document.querySelectorAll('[data-mode]').forEach((button) => {
  button.classList.toggle('active', button.dataset.mode === mode);
  button.addEventListener('click', () => {
    mode = button.dataset.mode;
    localStorage.setItem('aurum-deal-radar-mode', mode);
    document.querySelectorAll('[data-mode]').forEach((item) => item.classList.toggle('active', item === button));
    if (deltaInput) {
      if (!localStorage.getItem('aurum-deal-radar-value')) {
        deltaInput.value = mode === 'percent' ? '2' : '200';
      }
      deltaInput.step = mode === 'percent' ? '0.1' : '50';
    }
    render();
  });
});

document.querySelectorAll('[data-trend-karat]').forEach((button) => {
  button.addEventListener('click', () => {
    const karat = Number(button.dataset.trendKarat) || 24;
    trendKarat = karat;
    if (visibleTrendKarats.has(karat) && visibleTrendKarats.size > 1) visibleTrendKarats.delete(karat);
    else visibleTrendKarats.add(karat);
    document.querySelectorAll('[data-trend-karat]').forEach((item) => {
      const visible = visibleTrendKarats.has(Number(item.dataset.trendKarat) || 24);
      item.classList.toggle('active', visible);
      item.setAttribute('aria-pressed', String(visible));
    });
    renderBullionTrendChart();
  });
});

const blendedSeriesFromHistory = (history = [], karat = 24) => {
  const buckets = new Map();
  for (const item of history) {
    const price = Number(item.price);
    const timestamp = new Date(item.checked_at || item.fetchedAt || item.lastLiveAt || Date.now()).getTime();
    if (!Number.isFinite(price) || price <= 0 || !Number.isFinite(timestamp)) continue;
    const key = new Date(timestamp).toISOString();
    if (!buckets.has(key)) buckets.set(key, { timestamp, prices: [] });
    buckets.get(key).prices.push(price);
  }
  return [...buckets.values()].map((bucket) => {
    const sorted = [...bucket.prices].sort((a, b) => a - b);
    const median = sorted[Math.floor(sorted.length / 2)];
    const clean = sorted.filter((price) => Math.abs(price - median) <= median * 0.08);
    const prices = clean.length ? clean : sorted;
    const price = prices.reduce((sum, value) => sum + value, 0) / prices.length;
    return { karat, price, timestamp: bucket.timestamp, sourceCount: prices.length };
  }).sort((a, b) => a.timestamp - b.timestamp);
};

const liveBlendedPoint = (karat = 24) => {
  const prices = benchmarkRates(karat);
  if (!prices.length) return null;
  return { karat, price: prices.reduce((sum, value) => sum + value, 0) / prices.length, timestamp: Date.now(), sourceCount: prices.length };
};

async function renderBullionTrendChart() {
  const trendSvg = $('#trendSvg');
  if (!trendSvg) return;

  let history24 = [];
  let history22 = [];
  try {
    const [res24, res22] = await Promise.all([
      fetch('/api/history/bullion?karat=24&limit=240'),
      fetch('/api/history/bullion?karat=22&limit=240')
    ]);
    if (res24.ok) history24 = (await res24.json()).history || [];
    if (res22.ok) history22 = (await res22.json()).history || [];
  } catch { }

  let series24 = blendedSeriesFromHistory(history24, 24);
  let series22 = blendedSeriesFromHistory(history22, 22);
  if (!series24.length) series24 = [liveBlendedPoint(24)].filter(Boolean);
  if (!series22.length) series22 = [liveBlendedPoint(22)].filter(Boolean);

  document.querySelectorAll('[data-trend-karat]').forEach((button) => {
    const visible = visibleTrendKarats.has(Number(button.dataset.trendKarat) || 24);
    button.classList.toggle('active', visible);
    button.setAttribute('aria-pressed', String(visible));
  });

  const visibleSeries24 = visibleTrendKarats.has(24) ? series24 : [];
  const visibleSeries22 = visibleTrendKarats.has(22) ? series22 : [];
  const allSeries = [...visibleSeries24, ...visibleSeries22];
  const prices = allSeries.map((item) => item.price);
  if (!prices.length) {
    $('#trendMinPrice').textContent = '—';
    $('#trendMaxPrice').textContent = '—';
    $('#trendChange').textContent = '—';
    $('#trendAxes')?.replaceChildren();
    return;
  }

  const minPrice = Math.min(...prices);
  const maxPrice = Math.max(...prices);
  const first24 = visibleSeries24[0]?.price;
  const last24 = visibleSeries24.at(-1)?.price;
  const first22 = visibleSeries22[0]?.price;
  const last22 = visibleSeries22.at(-1)?.price;
  const diff24 = Number.isFinite(first24) && Number.isFinite(last24) ? last24 - first24 : 0;
  const diff22 = Number.isFinite(first22) && Number.isFinite(last22) ? last22 - first22 : 0;
  const pct24 = first24 > 0 ? (diff24 / first24) * 100 : 0;
  const pct22 = first22 > 0 ? (diff22 / first22) * 100 : 0;
  const changeParts = [];
  if (visibleSeries24.length) changeParts.push(`24K ${diff24 >= 0 ? '+' : '-'}₹${money(Math.abs(diff24))} (${pct24.toFixed(2)}%)`);
  if (visibleSeries22.length) changeParts.push(`22K ${diff22 >= 0 ? '+' : '-'}₹${money(Math.abs(diff22))} (${pct22.toFixed(2)}%)`);

  $('#trendMinPrice').textContent = `₹${money(minPrice)}`;
  $('#trendMaxPrice').textContent = `₹${money(maxPrice)}`;
  $('#trendChange').textContent = changeParts.join(' · ');
  const primaryDiff = visibleSeries24.length ? diff24 : diff22;
  $('#trendChange').style.color = primaryDiff < 0 ? 'var(--gold2)' : primaryDiff > 0 ? '#f59e0b' : 'var(--muted)';

  const width = 600;
  const height = 140;
  const padLeft = 58;
  const padRight = 14;
  const padTop = 14;
  const padBottom = 28;
  const chartW = width - padLeft - padRight;
  const chartH = height - padTop - padBottom;
  const axisMinPrice = Math.max(0, minPrice * 0.95);
  const axisMaxPrice = maxPrice * 1.05;
  const priceRange = axisMaxPrice === axisMinPrice ? 1 : axisMaxPrice - axisMinPrice;
  const minTime = Math.min(...allSeries.map((item) => item.timestamp));
  const maxTime = Math.max(...allSeries.map((item) => item.timestamp));
  const timeRange = maxTime === minTime ? 1 : maxTime - minTime;
  const dateLabel = (timestamp) => new Date(timestamp).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });

  const toPoints = (series) => series.map((item) => {
    const x = allSeries.length === 1 ? padLeft + chartW / 2 : padLeft + ((item.timestamp - minTime) / timeRange) * chartW;
    const y = padTop + chartH - ((item.price - axisMinPrice) / priceRange) * chartH;
    return { x, y, price: item.price, raw: item };
  });
  const points24 = toPoints(visibleSeries24);
  const points22 = toPoints(visibleSeries22);
  const linePath = (points) => points.map((pt, i) => `${i === 0 ? 'M' : 'L'} ${pt.x.toFixed(1)} ${pt.y.toFixed(1)}`).join(' ');

  const lineD = linePath(points24);
  const line22D = linePath(points22);
  const baselineY = padTop + chartH;
  const areaD = points24.length ? `${lineD} L ${points24.at(-1).x.toFixed(1)} ${baselineY.toFixed(1)} L ${points24[0].x.toFixed(1)} ${baselineY.toFixed(1)} Z` : '';

  $('#trendLine')?.setAttribute('d', lineD);
  $('#trendArea')?.setAttribute('d', areaD);
  let trendLine22 = $('#trendLine22');
  if (!trendLine22) {
    trendLine22 = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    trendLine22.id = 'trendLine22';
    trendLine22.classList.add('trend-line', 'trend-line-22');
    $('#trendDots')?.before(trendLine22);
  }
  trendLine22.setAttribute('d', line22D);
  let axesGroup = $('#trendAxes');
  if (!axesGroup) {
    axesGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    axesGroup.id = 'trendAxes';
    trendSvg.prepend(axesGroup);
  }
  const yTicks = [axisMaxPrice, (axisMaxPrice + axisMinPrice) / 2, axisMinPrice];
  const xTicks = allSeries.length === 1 ? [minTime] : [minTime, minTime + timeRange / 2, maxTime];
  axesGroup.innerHTML = `
    <path class="trend-axis-line" d="M ${padLeft} ${padTop} V ${baselineY} H ${width - padRight}"/>
    ${yTicks.map((price) => {
    const y = padTop + chartH - ((price - axisMinPrice) / priceRange) * chartH;
    return `<g><line class="trend-grid-line" x1="${padLeft}" y1="${y.toFixed(1)}" x2="${width - padRight}" y2="${y.toFixed(1)}"></line><text class="trend-axis-label y-axis" x="${padLeft - 7}" y="${(y + 3).toFixed(1)}">₹${money(price)}</text></g>`;
  }).join('')}
    ${xTicks.map((timestamp, index) => {
    const x = allSeries.length === 1 ? padLeft + chartW / 2 : padLeft + ((timestamp - minTime) / timeRange) * chartW;
    return `<text class="trend-axis-label x-axis" x="${x.toFixed(1)}" y="${height - 8}" text-anchor="${index === 0 ? 'start' : index === xTicks.length - 1 ? 'end' : 'middle'}">${dateLabel(timestamp)}</text>`;
  }).join('')}`;
  $('#trendTimelineLabels').innerHTML = `<span>${dateLabel(minTime)}</span>${visibleSeries24.length ? '<span class="trend-legend-24">24K blended benchmark</span>' : ''}${visibleSeries22.length ? '<span class="trend-legend-22">22K blended benchmark</span>' : ''}<span>${dateLabel(maxTime)}</span>`;

  const dotsGroup = $('#trendDots');
  const tooltip = $('#trendTooltip');
  if (dotsGroup) {
    const dotPoints = [...points24.map((point) => ({ ...point, karat: 24 })), ...points22.map((point) => ({ ...point, karat: 22 }))];
    dotsGroup.innerHTML = dotPoints.map((pt, idx) => `
      <circle class="trend-dot trend-dot-${pt.karat}" cx="${pt.x.toFixed(1)}" cy="${pt.y.toFixed(1)}" r="${dotPoints.length > 30 ? 2.5 : 3.5}" data-idx="${idx}"></circle>
    `).join('');

    dotsGroup.querySelectorAll('.trend-dot').forEach((dot) => {
      dot.addEventListener('mouseenter', () => {
        const pt = dotPoints[Number(dot.dataset.idx)];
        if (!pt || !tooltip) return;
        const dt = pt.raw?.timestamp ? new Date(pt.raw.timestamp).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }) : '';
        tooltip.innerHTML = `<strong>${pt.karat}K ₹${money(pt.price)}/g</strong> ${dt ? `<small>(${dt})</small>` : ''}`;
        tooltip.style.display = 'block';
        tooltip.style.left = `${(pt.x / width) * 100}%`;
        tooltip.style.top = `${pt.y}px`;
      });
      dot.addEventListener('mouseleave', () => {
        if (tooltip) tooltip.style.display = 'none';
      });
    });
  }
}

$('#addButton').addEventListener('click', () => {
  const form = $('#addForm');
  form.classList.toggle('mobile-visible');
  if (form.classList.contains('mobile-visible')) $('#productUrl').focus();
});
$('#storeTiles').addEventListener('click', (event) => {
  const tile = event.target.closest('[data-store], [data-store-all]');
  if (!tile) return;
  const stores = [...new Set(state.products.map((item) => normalizeStoreSource(item.source, item.url)).filter(Boolean))];
  if (tile.hasAttribute('data-store-all')) {
    // All is an explicit reset: it always means every current store.
    selectedStores = new Set(stores);
    allStoresSelected = true;
    render();
    return;
  }
  const store = normalizeStoreSource(tile.dataset.store);
  if (!store) return;
  // Choosing a store while All is active starts a focused selection. Further
  // clicks add/remove stores for multi-select. Selecting every store collapses
  // naturally back to All. Never leave the watchlist with an empty selection.
  if (allStoresSelected) {
    selectedStores = new Set([store]);
    allStoresSelected = false;
  } else if (selectedStores.has(store)) {
    selectedStores.delete(store);
  } else {
    selectedStores.add(store);
  }
  if (!selectedStores.size) {
    selectedStores = new Set(stores);
    allStoresSelected = true;
  } else {
    allStoresSelected = stores.length > 0 && selectedStores.size === stores.length;
  }
  render();
});
const getVisibleProducts = () => {
  const benchmark24 = average(24);
  const benchmark22 = average(22);
  const benchmarkForProduct = (item) => {
    const karat = productKarat(item);
    if (karat === 24) return benchmark24;
    if (karat === 22) return benchmark22;
    return null;
  };
  const purityMatches = (item) => {
    const karat = productKarat(item);
    return purityView === '24' ? karat === 24 : purityView === '22' ? karat === 22 : karat !== 24 && karat !== 22;
  };
  return state.products.filter((item) => {
    const normalizedStore = normalizeStoreSource(item.source, item.url);
    if (!selectedStores.has(normalizedStore)) return false;
    if (productFilter && !`${item.name} ${item.brand} ${item.source} ${item.grams} ${item.purity}`.toLowerCase().includes(productFilter)) return false;
    if (gramsMinFilter !== null && (Number(item.grams) || 0) < gramsMinFilter) return false;
    if (gramsMaxFilter !== null && (Number(item.grams) || 0) > gramsMaxFilter) return false;
    const pg = item.price && item.grams ? (item.couponPrice && item.couponPrice < item.price ? item.couponPrice : item.price) / item.grams : null;
    const itemBenchmark = benchmarkForProduct(item);
    const diff = pg && itemBenchmark ? ((pg - itemBenchmark) / itemBenchmark) * 100 : null;
    if (quickFilter === 'below' && !(diff !== null && diff < 0)) return false;
    if (quickFilter === 'live' && item.status !== 'live') return false;
    if (quickFilter === 'checking' && item.status !== 'checking') return false;
    if (quickFilter === 'stale' && item.status !== 'stale') return false;
    if (quickFilter === 'unverified' && item.status !== 'unverified') return false;
    if (quickFilter === 'failed' && item.status !== 'failed') return false;
    if (quickFilter === 'unavailable' && item.status !== 'unavailable') return false;
    if (quickFilter === 'not-live' && item.status === 'live') return false;
    return purityMatches(item);
  });
};

const startProductsRefresh = async (stores = null, options = {}) => {
  const staleOnly = Boolean(options.staleOnly);
  const productIds = Array.isArray(options.productIds) ? options.productIds : null;
  const karats = Array.isArray(options.karats) ? options.karats : null;
  const gramsMin = options.gramsMin ?? null;
  const gramsMax = options.gramsMax ?? null;
  let targets = state.products;
  if (productIds) {
    const idSet = new Set(productIds);
    targets = targets.filter((item) => idSet.has(item.id));
  } else {
    if (stores) targets = targets.filter((item) => stores.includes(normalizeStoreSource(item.source, item.url)));
    if (karats) targets = targets.filter((item) => karats.includes(productKarat(item)));
    if (gramsMin !== null) targets = targets.filter((item) => (Number(item.grams) || 0) >= gramsMin);
    if (gramsMax !== null) targets = targets.filter((item) => (Number(item.grams) || 0) <= gramsMax);
  }
  if (staleOnly) {
    targets = targets.filter((item) => item.status === 'stale' || item.status === 'unverified' || item.status === 'failed' || item.status === 'unavailable');
  }
  if (!targets.length) {
    toast(staleOnly ? 'No stale products to refresh.' : 'No products match the selected filters.');
    return;
  }
  const buttons = [$('#refreshProductsButton'), $('#refreshSelectedProductsButton'), $('#refreshStaleProductsButton')];
  buttons.forEach((button) => { if (button) { button.disabled = true; } });
  $('#refreshButton')?.classList.add('loading');
  activityStatsCleared = false;
  targets.forEach((item) => { item.status = 'checking'; });
  window.refreshProgress = { running: true, checked: 0, total: targets.length, live: 0, current: 'starting' };
  const activityPanel = document.querySelector('.activity-panel');
  if (activityPanel) activityPanel.open = true;
  render();
  await new Promise((resolve) => requestAnimationFrame(resolve));
  const payload = {
    ...(productIds ? { productIds } : {}),
    ...(stores ? { stores } : {}),
    ...(staleOnly ? { staleOnly: true } : {}),

    refreshMode:
      productIds
        ? 'targeted-products'
        : stores
          ? 'selected-stores'
          : staleOnly
            ? 'targeted-products'
            : 'full'
  };
  const response = await fetch('/api/products/refresh', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(payload) }).catch(() => null);
  if (!response || !response.ok) {
    buttons.forEach((button) => { if (button) { button.disabled = false; } });
    $('#refreshButton')?.classList.remove('loading');
    await load();
    appendRefreshTerminal('ERROR · Could not start product refresh', 'error');
    toast('Could not start product refresh.');
    return;
  }
  appendRefreshTerminal(`START · ${targets.length} ${staleOnly ? 'stale ' : ''}products queued`, 'active');
  toast(`Refreshing ${targets.length} ${staleOnly ? 'stale ' : ''}products...`);
  const poll = setInterval(async () => {
    await load();
    if (!window.refreshProgress?.running) {
      clearInterval(poll);
      buttons.forEach((button) => { if (button) { button.disabled = false; } });
      $('#refreshButton')?.classList.remove('loading');
    }
  }, 1000);
};
$('#refreshProductsButton')?.addEventListener('click', async () => {
  document.querySelectorAll('.refresh-command[open]').forEach((menu) => { menu.open = false; });
  await startProductsRefresh();
});
$('#refreshSelectedProductsButton')?.addEventListener('click', async () => {
  document.querySelectorAll('.refresh-command[open]').forEach((menu) => {
    menu.open = false;
  });

  const visible = getVisibleProducts();

  if (!visible.length) {
    toast('No products match current view/filters.');
    return;
  }

  const visibleStores = [
    ...new Set(
      visible
        .map((item) => normalizeStoreSource(item.source, item.url))
        .filter(Boolean)
    )
  ];

  const statusFiltered =
    ['stale', 'failed', 'unverified', 'unavailable', 'not-live']
      .includes(quickFilter);

  if (statusFiltered) {
    // FLOW 3:
    // Exact visible IDs -> PDP/product-page refresh only.
    await startProductsRefresh(null, {
      productIds: visible.map((p) => p.id)
    });
    return;
  }

  // FLOW 2:
  // Selected store(s) -> master first + residual PDP fallback.
  await startProductsRefresh(visibleStores);
});
$('#refreshStaleProductsButton')?.addEventListener('click', async () => {
  document.querySelectorAll('.refresh-command[open]').forEach((menu) => { menu.open = false; });
  await startProductsRefresh(null, { staleOnly: true });
});
$('#debugVisibleBrowser').addEventListener('change', async (event) => { const response = await fetch('/api/settings', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ debugVisibleBrowser: event.target.checked }) }); if (response.ok) { settings = await response.json(); toast(`Bullion browser view ${settings.debugVisibleBrowser ? 'enabled' : 'disabled'}.`); } else { event.target.checked = !event.target.checked; toast('Could not update browser view setting.'); } });
$('#productDebugVisibleBrowser').addEventListener('change', async (event) => { const response = await fetch('/api/settings', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ productDebugVisibleBrowser: event.target.checked }) }); if (response.ok) { settings = await response.json(); toast(`Product browser view ${settings.productDebugVisibleBrowser ? 'enabled' : 'disabled'}.`); } else { event.target.checked = !event.target.checked; toast('Could not update product browser view setting.'); } });
const saveStartupRefreshSetting = async (event) => {
  const key = event.target.id;
  const response = await fetch('/api/settings', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ [key]: event.target.checked }) });
  if (response.ok) {
    settings = await response.json();
    toast(`${key === 'refreshBullionOnStart' ? 'Bullion' : 'Product'} refresh at server start ${settings[key] ? 'enabled' : 'disabled'}.`);
  } else {
    event.target.checked = !event.target.checked;
    toast('Could not update server-start refresh setting.');
  }
};
$('#refreshBullionOnStart').addEventListener('change', saveStartupRefreshSetting);
$('#refreshProductsOnStart').addEventListener('change', saveStartupRefreshSetting);
const saveProductAutoRefreshSettings = async () => {
  const response = await fetch('/api/settings', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ productAutoRefresh: $('#productAutoRefresh').checked, productRefreshIntervalMin: Number($('#productRefreshIntervalMin').value || 5) }) });
  if (response.ok) {
    settings = await response.json();
    $('#productRefreshIntervalMin').value = settings.productRefreshIntervalMin;
    toast(`Product auto refresh ${settings.productAutoRefresh ? 'enabled' : 'disabled'}.`);
  } else {
    await load();
    toast('Could not update product auto refresh.');
  }
};
$('#productAutoRefresh').addEventListener('change', saveProductAutoRefreshSettings);
$('#productRefreshIntervalMin').addEventListener('change', saveProductAutoRefreshSettings);
$('#locationForm').addEventListener('submit', async (event) => { event.preventDefault(); const response = await fetch('/api/settings', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ preciseAddress: $('#preciseAddress').value, pincode: $('#pincode').value }) }); if (response.ok) { settings = await response.json(); toast('Location saved.'); } });
$('#desktopLocationForm')?.addEventListener('submit', async (event) => { event.preventDefault(); const response = await fetch('/api/settings', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ preciseAddress: $('#desktopPreciseAddress').value, pincode: $('#desktopPincode').value }) }); if (response.ok) { settings = await response.json(); $('#preciseAddress').value = settings.preciseAddress || ''; $('#pincode').value = settings.pincode || ''; toast('Location saved.'); } });
document.addEventListener('click', async (event) => {
  const editButton = event.target.closest('[data-edit-id], [data-mobile-edit]');
  if (editButton) {
    const productId = editButton.dataset.editId || editButton.dataset.mobileEdit;
    const product = state.products.find((item) => item.id === productId);
    if (!product) return;
    editingProductId = product.id;
    $('#editProductName').value = product.name || '';
    $('#editProductBrand').value = product.brand || '';
    $('#editProductGrams').value = product.grams || '';
    $('#editProductPurity').value = product.purity || '';
    $('#editProductPrice').value = product.price || '';
    $('#editProductCouponPrice').value = product.couponPrice || '';
    $('#editProductStatus').textContent = '';
    const histContainer = $('#productPriceHistory');
    if (histContainer) {
      histContainer.innerHTML = '<span>Loading price history...</span>';
      fetch(`/api/history/products/${product.id}?limit=15`).then(async (res) => {
        if (!res.ok) return;
        const json = await res.json();
        const records = json.history || [];
        if (!records.length) {
          histContainer.innerHTML = '<span style="color:var(--muted)">No past price changes recorded yet.</span>';
          return;
        }
        histContainer.innerHTML = '<strong style="display:block;margin-bottom:6px">Price History Timeline</strong>' + records.map((r) => {
          const dt = new Date(r.checked_at).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
          return `<div class="price-history-row"><span>${dt}</span><strong>₹${money(r.price)}${r.coupon_price ? ` (coupon ₹${money(r.coupon_price)})` : ''}</strong></div>`;
        }).join('');
      }).catch(() => {
        if (histContainer) histContainer.innerHTML = '';
      });
    }
    $('#editProductModal').classList.remove('hidden');
    $('#editProductName').focus();
    return;
  }
  const button = event.target.closest('[data-delete-id], [data-mobile-delete]');
  if (!button) return;
  const productId = button.dataset.deleteId || button.dataset.mobileDelete;
  if (pendingDeleteId !== productId) {
    pendingDeleteId = productId;
    if (pendingDeleteTimer) clearTimeout(pendingDeleteTimer);
    pendingDeleteTimer = setTimeout(() => {
      pendingDeleteId = null;
      pendingDeleteTimer = null;
    }, 4000);
    toast('Click delete again within 4s to confirm.');
    return;
  }
  pendingDeleteId = null;
  if (pendingDeleteTimer) {
    clearTimeout(pendingDeleteTimer);
    pendingDeleteTimer = null;
  }
  const response = await fetch(`/api/products/${productId}`, { method: 'DELETE' });
  if (response.ok) {
    await load();
    toast('Product deleted.');
  }
});
document.addEventListener('click', async (event) => {
  const button = event.target.closest('[data-retry-id], [data-mobile-retry]');
  if (!button) return;
  const productId = button.dataset.retryId || button.dataset.mobileRetry;
  const product = state.products.find((item) => item.id === productId);
  if (product) product.status = 'checking';
  render();
  startLivePolling();
  await fetch(`/api/products/${productId}/retry`, { method: 'POST' });
  await load();
  toast('Refresh started in a visible browser.');
});
$('#editProductCancel').addEventListener('click', () => { editingProductId = null; $('#editProductModal').classList.add('hidden'); });
$('#editProductForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!editingProductId) return;
  const status = $('#editProductStatus');
  status.textContent = 'Saving...';
  const response = await fetch(`/api/products/${editingProductId}`, { method: 'PATCH', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ name: $('#editProductName').value, brand: $('#editProductBrand').value, grams: $('#editProductGrams').value, purity: $('#editProductPurity').value, price: $('#editProductPrice').value, couponPrice: $('#editProductCouponPrice').value }) });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) { status.textContent = result.error || 'Could not save details.'; return; }
  editingProductId = null;
  $('#editProductModal').classList.add('hidden');
  await load();
  toast('Manual product details saved.');
});
$('#addForm').addEventListener('submit', async (event) => { event.preventDefault(); const input = $('#productUrl'); const note = $('#formNote'); note.textContent = 'Adding product...'; const response = await fetch('/api/products', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ url: input.value }) }); const result = await response.json(); if (!response.ok) { note.textContent = result.error || 'Could not add product.'; return; } input.value = ''; note.textContent = result.status === 'live' ? 'Product added with a live price. Updates run every 5 minutes.' : 'Product added. Reading price in the background; updates run every 5 minutes.'; await load(); });
$('#addListForm').addEventListener('submit', async (event) => { event.preventDefault(); const input = $('#productUrls'); const button = event.submitter; const pasted = input.value.split(/\r?\n/).map((url) => url.trim()).filter(Boolean); const urls = [...new Set(pasted)]; if (!urls.length) return; button.disabled = true; $('#formNote').textContent = `Adding ${urls.length} unique links from ${pasted.length} pasted...`; const response = await fetch('/api/products/bulk', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ urls }) }); const result = await response.json().catch(() => ({})); button.disabled = false; if (!response.ok) { $('#formNote').textContent = result.error || 'Could not add product links.'; return; } input.value = ''; const parts = [`Added ${result.added} of ${result.received ?? urls.length} links`]; if (result.skippedTracked) parts.push(`${result.skippedTracked} already tracked`); if (result.skippedRepeated) parts.push(`${result.skippedRepeated} repeated in paste`); if (result.skippedInvalid) parts.push(`${result.skippedInvalid} unreadable${result.invalidSamples?.length ? ` (e.g. ${result.invalidSamples[0]})` : ''}`); if (result.skippedNon24K) parts.push(`${result.skippedNon24K} non-24K`); if (pasted.length !== urls.length) parts.push(`${pasted.length - urls.length} identical lines merged before sending`); parts.push(`refreshing ${result.refreshing || result.added} products`); $('#formNote').textContent = `${parts.join('; ')}.`; await load(); });
$('#proxyAuthForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (await saveProxyCredentials()) toast('Proxy credentials saved for this browser session.');
});
$('#toggleProxyPassword').addEventListener('click', () => {
  const password = $('#proxyPassword');
  const visible = password.type === 'text';
  password.type = visible ? 'password' : 'text';
  $('#toggleProxyPassword').textContent = visible ? 'Show' : 'Hide';
  $('#toggleProxyPassword').setAttribute('aria-label', visible ? 'Show password' : 'Hide password');
  $('#toggleProxyPassword').title = visible ? 'Show password' : 'Hide password';
});
try {
  const savedCredentials = JSON.parse(sessionStorage.getItem(proxyStorageKey) || 'null');
  if (savedCredentials?.username && savedCredentials?.password) {
    $('#proxyUsername').value = savedCredentials.username;
    $('#proxyPassword').value = savedCredentials.password;
    setProxyStatus('Ready', 'ready');
  }
} catch { }
void confirmProxyCredentials();
function toast(message) { const element = $('#toast'); element.textContent = message; element.classList.add('show'); setTimeout(() => element.classList.remove('show'), 3500); }
const initialTerminal = $('#refreshTerminal');
if (initialTerminal && refreshTerminalLines.length) {
  initialTerminal.innerHTML = refreshTerminalLines.map((line) => `<div class="refresh-terminal-line ${line.kind}"><span class="refresh-terminal-time">${attr(line.time)}</span><span class="refresh-terminal-msg">${attr(line.message)}</span></div>`).join('');
  initialTerminal.scrollTop = initialTerminal.scrollHeight;
} else {
  appendRefreshTerminal('READY · Waiting for refresh job', 'info');
}
load().catch(() => { $('#syncText').textContent = 'Server unavailable'; appendRefreshTerminal('ERROR · Server unavailable', 'error'); });
document.querySelectorAll('[data-view]').forEach((button) => button.addEventListener('click', () => {
  const view = button.dataset.view;
  document.body.dataset.mobileView = view;
  document.querySelectorAll('[data-view]').forEach((item) => { item.classList.toggle('active', item === button); item.setAttribute('aria-selected', String(item === button)); });
}));
$('#copyLogsButton')?.addEventListener('click', async (event) => {
  event.preventDefault();
  event.stopPropagation();
  if (!refreshTerminalLines.length) {
    toast('No refresh activity logs to copy.');
    return;
  }
  const text = refreshTerminalLines.map((line) => `[${line.time}] [${line.kind.toUpperCase()}] ${line.message}`).join('\n');
  try {
    await navigator.clipboard.writeText(text);
    toast(`📋 Copied ${refreshTerminalLines.length} log lines to clipboard.`);
  } catch {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand('copy');
    document.body.removeChild(textarea);
    toast(`📋 Copied ${refreshTerminalLines.length} log lines to clipboard.`);
  }
});

$('#downloadLogsButton')?.addEventListener('click', (event) => {
  event.preventDefault();
  event.stopPropagation();
  if (!refreshTerminalLines.length) {
    toast('No refresh activity logs to download.');
    return;
  }
  const text = refreshTerminalLines.map((line) => `[${line.time}] [${line.kind.toUpperCase()}] ${line.message}`).join('\n');
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  a.download = `aurum-refresh-logs-${stamp}.log`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
  toast('💾 Activity logs downloaded.');
});

const rawBridgeButton = document.createElement('button');
rawBridgeButton.type = 'button';
rawBridgeButton.className = 'activity-btn';
rawBridgeButton.title = 'Download raw browser-script data';
rawBridgeButton.textContent = 'Raw data';
rawBridgeButton.addEventListener('click', () => {
  const download = document.createElement('a');
  download.href = '/api/browser-bridge/raw';
  download.download = 'aurum-browser-bridge-raw.json';
  document.body.appendChild(download);
  download.click();
  document.body.removeChild(download);
  toast('Raw browser-script data download started.');
});
$('#downloadLogsButton')?.after(rawBridgeButton);

$('#clearLogsButton')?.addEventListener('click', (event) => {
  event.preventDefault();
  event.stopPropagation();
  refreshTerminalLines.length = 0;
  activityStatsCleared = true;
  try { sessionStorage.removeItem(terminalStorageKey); } catch { }
  const terminal = $('#refreshTerminal');
  if (terminal) terminal.innerHTML = '';
  render();
  toast('🗑️ Activity terminal cleared.');
});
$('#quickFilters')?.addEventListener('click', (event) => {
  const button = event.target.closest('[data-quick]');
  if (!button) return;
  quickFilter = button.dataset.quick;
  document.querySelectorAll('[data-quick]').forEach((item) => item.classList.toggle('active', item === button));
  render();
});
document.addEventListener('keydown', (event) => {
  if (/INPUT|TEXTAREA|SELECT/.test(event.target.tagName)) return;
  if (event.metaKey || event.ctrlKey || event.altKey) return;
  if (event.key === '/') { event.preventDefault(); $('#productFilter')?.focus(); }
  if (event.key.toLowerCase() === 'a') $('#addButton')?.click();
  if (event.key.toLowerCase() === 'r' && !event.shiftKey) $('#refreshButton')?.click();
  if (event.key.toLowerCase() === 'r' && event.shiftKey) $('#refreshProductsButton')?.click();
});
document.addEventListener('click', (event) => {
  if (event.target.closest('.settings-menu')) return;
  const settingsMenu = document.querySelector('.settings-menu');
  if (!settingsMenu) return;
  settingsMenu.open = false;
  settingsMenu.querySelectorAll('details').forEach((panel) => { panel.open = false; });
});
document.addEventListener('click', (event) => {
  if (!event.target.closest('.refresh-command')) {
    document.querySelectorAll('.refresh-command[open]').forEach((menu) => { menu.open = false; });
  }
});
document.addEventListener('click', (event) => {
  if (event.target.closest('.modal-card') || event.target.closest('#editProductCancel')) return;
  if (event.target.id === 'editProductModal') {
    document.getElementById('editProductModal').classList.add('hidden');
    editingProductId = null;
  }
});
// Live state stream: avoids re-fetching the full application every 3 seconds.
// The existing REST endpoints remain the source of truth and are retained as a fallback.
let eventStreamHealthy = false;
let liveRenderTimer = null;
let lastLiveRenderAt = 0;
const scheduleLiveRender = () => {
  // Rebuilding hundreds of product rows/cards for every SSE packet can saturate
  // Safari/Chrome on a MacBook. During a refresh, coalesce snapshots into a
  // single paint roughly twice per second; idle changes remain near-immediate.
  const busy = Boolean(window.refreshProgress?.running || bullionProgress?.running);
  const minGap = busy ? 500 : 80;
  const elapsed = performance.now() - lastLiveRenderAt;
  if (elapsed >= minGap && !liveRenderTimer) {
    lastLiveRenderAt = performance.now();
    render();
    return;
  }
  if (liveRenderTimer) return;
  liveRenderTimer = setTimeout(() => {
    liveRenderTimer = null;
    lastLiveRenderAt = performance.now();
    render();
  }, Math.max(16, minGap - elapsed));
};
const applyLiveSnapshot = (snapshot) => {
  if (!snapshot || !Array.isArray(snapshot.bullion) || !Array.isArray(snapshot.products)) return;
  state = { ...state, settings: snapshot.settings || state.settings, bullion: snapshot.bullion, products: snapshot.products };
  settings = snapshot.settings || settings;
  if (snapshot.productRefresh) window.refreshProgress = snapshot.productRefresh;
  if (snapshot.bullionRefresh) bullionProgress = snapshot.bullionRefresh;
  scheduleLiveRender();
};
if ('EventSource' in window) {
  const events = new EventSource('/api/events');
  events.addEventListener('open', () => { eventStreamHealthy = true; });
  events.addEventListener('state', (event) => {
    eventStreamHealthy = true;
    try { applyLiveSnapshot(JSON.parse(event.data)); } catch { }
  });
  events.addEventListener('progress', (event) => {
    eventStreamHealthy = true;
    try {
      const progress = JSON.parse(event.data);
      if (progress.productRefresh) window.refreshProgress = progress.productRefresh;
      if (progress.bullionRefresh) bullionProgress = progress.bullionRefresh;
      scheduleLiveRender();
    } catch { }
  });
  events.addEventListener('error', () => { eventStreamHealthy = false; });
}
setInterval(async () => {
  if (eventStreamHealthy) return;
  try { await load(); } catch { }
}, 30000);

const scrollTopButton = $('#scrollTopButton');
const updateScrollTopButton = () => {
  if (!scrollTopButton) return;
  const inWatchlist = !routeIsMobile || document.body.dataset.mobileView === 'watchlist';
  scrollTopButton.classList.toggle('visible', routeIsMobile && inWatchlist && window.scrollY > 420);
};
window.addEventListener('scroll', updateScrollTopButton, { passive: true });
scrollTopButton?.addEventListener('click', () => window.scrollTo({ top: 0, behavior: 'smooth' }));
document.querySelectorAll('[data-view]').forEach((button) => button.addEventListener('click', () => setTimeout(updateScrollTopButton, 0)));
