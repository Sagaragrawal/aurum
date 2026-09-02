/**
 * Centralized environment configuration
 * Separates environment variables from business logic
 */

export const getEnvironmentConfig = () => ({
  // Network & timing
  minDelayMs: Number(process.env.MIN_DELAY_MS || 1500),
  chromeDebugPort: Number(process.env.CHROME_DEBUG_PORT || 9222),
  browserUserAgent: process.env.BROWSER_USER_AGENT || 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
  
  // Bullion-specific
  bullionConcurrency: Number(process.env.BULLION_CONCURRENCY || 4),
  bullionFetchTimeoutMs: Number(process.env.BULLION_FETCH_TIMEOUT_MS || 12000),
  bullionRenderTimeoutMs: Number(process.env.BULLION_RENDER_TIMEOUT_MS || 22000),
  bullionFetchTimeoutMmtcMs: Number(process.env.BULLION_FETCH_TIMEOUT_MMTC_MS || 6000),
  bullionRenderTimeoutTanMs: Number(process.env.BULLION_RENDER_TIMEOUT_TAN_MS || 50000),
  bullionRenderTimeoutMmtcMs: Number(process.env.BULLION_RENDER_TIMEOUT_MMTC_MS || 16000),
  bullionRenderSources: new Set(String(process.env.BULLION_RENDER_SOURCES || 'tan,malabar,mmtc,kalyan').split(',').map((id) => id.trim()).filter(Boolean)),
  // Sources that don't work in headless mode and always need visible browser
  bullionHeadlessIncompatibleSources: new Set(String(process.env.BULLION_HEADLESS_INCOMPATIBLE || '').split(',').map((id) => id.trim()).filter(Boolean)),
  bullionVisibleBrowser: process.env.BULLION_VISIBLE_BROWSER === '1',
  
  // Product-specific
  productConcurrency: Number(process.env.PRODUCT_CONCURRENCY || 5)
});
