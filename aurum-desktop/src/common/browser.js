/**
 * Shared browser utilities
 * Common browser launch, dialog suppression, and popup dismissal logic
 */

import { getEnvironmentConfig } from './environment.js';

const env = getEnvironmentConfig();

export const launchBrowser = (playwright, headless, browserName = 'firefox') =>
  playwright[browserName].launch({
    headless
  });

export function suppressDialogs(page) {
  page.on('dialog', async (dialog) => {
    try { await dialog.dismiss(); } catch {}
  });
}

export async function dismissCommonPopups(page) {
  await page.keyboard.press('Escape').catch(() => {});
  await page.evaluate(() => {
    const closeMatchers = [/close/i, /no thanks/i, /maybe later/i, /not now/i, /skip/i, /got it/i, /dismiss/i, /continue without/i, /reject all/i, /accept all/i];
    const elements = [...document.querySelectorAll('button,[role="button"],.close,.modal-close,[aria-label*="close" i],[class*="close" i],[id*="close" i]')];
    elements.forEach((element) => {
      const label = `${element.textContent || ''} ${element.getAttribute('aria-label') || ''}`.trim();
      if (closeMatchers.some((matcher) => matcher.test(label))) element.click();
    });
    // Remove common modal backdrops that block interactions after a close click.
    [...document.querySelectorAll('[class*="overlay" i],[class*="backdrop" i],[class*="modal" i]')]
      .filter((element) => {
        const style = window.getComputedStyle(element);
        return style.position === 'fixed' && Number(style.zIndex || 0) >= 100;
      })
      .forEach((element) => {
        if (element.children.length === 0 || element.textContent.trim().length < 2) element.remove();
      });
    if (document.body) document.body.style.overflow = 'auto';
  }).catch(() => {});
}

export function isAccessBlockedText(text = '') {
  return /access denied|request blocked|blocked due to security reasons|captcha|you don't have permission/i.test(String(text || ''));
}

export const createBrowserContext = async (browser, options = {}) => {
  const { useDefaultUserAgent = false, ...contextOptions } = options;
  const context = await browser.newContext({
    permissions: [],
    locale: 'en-IN',
    timezoneId: 'Asia/Kolkata',
    viewport: { width: 1366, height: 900 },
    ...(useDefaultUserAgent ? {} : { userAgent: env.browserUserAgent }),
    ...contextOptions
  });
  return context;
};
