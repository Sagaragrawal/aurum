/**
 * Bullion worker process
 * Runs in a separate Node child process to isolate bullion updates from product updates
 * Communicates via IPC messages with the parent server process
 */

import { refreshBullionSources, disposeBullionRuntime } from './collector.js';

let running = false;

const send = (requestId, payload) => {
  process.send?.({ requestId, ...payload });
};

process.on('message', async (message) => {
  if (!message?.action) return;
  const requestId = message.requestId || null;

  try {
    if (message.action === 'disposeRuntime') {
      await disposeBullionRuntime();
      send(requestId, { type: 'result', result: { ok: true } });
      return;
    }

    if (message.action !== 'refreshSources') return;
    if (running) {
      send(requestId, { type: 'error', error: 'bullion worker busy' });
      return;
    }
    running = true;

    const bullionData = Array.isArray(message.bullion) ? message.bullion : [];
    const settings = message.settings || {};
    const requestedSourceIds = Array.isArray(message.requestedSourceIds) ? message.requestedSourceIds : null;

    const result = await refreshBullionSources(bullionData, settings, requestedSourceIds, (progress) => {
      send(requestId, { type: 'progress', progress, bullion: true });
    });

    send(requestId, { type: 'result', result: { bullion: bullionData, summary: result } });
    running = false;
  } catch (error) {
    running = false;
    send(requestId, { type: 'error', error: error?.message || 'bullion worker failed' });
  }
});

process.on('disconnect', () => {
  void disposeBullionRuntime();
});
