/**
 * Ajio product worker process
 */

import { refreshProductBatch, closePersistentBrowsers } from './collector.js';

const STORE_DOMAIN = 'ajio.com';
let running = false;

const send = (requestId, payload) => {
  process.send?.({ requestId, ...payload });
};

const normalizeStoreHostname = (inputHostname = '') => {
  const hostname = String(inputHostname || '').toLowerCase().trim();
  if (!hostname) return '';
  return hostname === STORE_DOMAIN || hostname.endsWith(`.${STORE_DOMAIN}`) ? STORE_DOMAIN : '';
};

const getHostname = (product) => {
  try {
    return product?.source || new URL(product?.url || '').hostname;
  } catch {
    return product?.source || '';
  }
};

process.on('message', async (message) => {
  if (!message?.action) return;
  const requestId = message.requestId || null;

  try {
    if (message.action === 'disposeRuntime') {
      await closePersistentBrowsers();
      send(requestId, { type: 'result', result: { ok: true } });
      return;
    }

    if (message.action === 'refreshProducts') {
      if (running) {
        send(requestId, { type: 'error', error: 'ajio product worker busy' });
        return;
      }
      running = true;
      const products = (Array.isArray(message.products) ? message.products : []).filter((product) => normalizeStoreHostname(getHostname(product)) === STORE_DOMAIN);
      const settings = message.settings || {};

      const result = await refreshProductBatch(products, settings, (progress) => {
        send(requestId, { type: 'progress', progress, products });
      });

      send(requestId, { type: 'result', result: { products, summary: result } });
      running = false;
      return;
    }
  } catch (error) {
    running = false;
    send(requestId, { type: 'error', error: error?.message || 'ajio product worker failed' });
  }
});

const shutdown = async () => { await closePersistentBrowsers().catch(() => {}); process.exit(0); };
process.once('SIGTERM', shutdown);
process.once('SIGINT', shutdown);
process.once('disconnect', shutdown);
