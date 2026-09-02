/**
 * Shared utility functions
 * Common helpers used across modules
 */

export const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

export function withTimeout(promise, milliseconds, label = 'operation') {
  const timeout = new Promise((_, reject) => {
    const id = setTimeout(() => reject(new Error(`${label} timed out after ${milliseconds}ms`)), milliseconds);
    id.unref?.();
  });
  return Promise.race([promise, timeout]);
}

export const numberFromText = (value) => Number(String(value || '').replace(/[^\d.]/g, ''));

export const lastRequestByHost = new Map();

export const getHostDelay = (host, minDelayMs) => {
  const lastTime = lastRequestByHost.get(host) || 0;
  const elapsed = Date.now() - lastTime;
  return Math.max(0, minDelayMs - elapsed);
};

export const recordHostRequest = (host) => {
  lastRequestByHost.set(host, Date.now());
};
