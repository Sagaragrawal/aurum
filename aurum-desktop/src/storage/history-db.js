import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const dataDirectory = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..', 'data');
const dbFile = join(dataDirectory, 'aurum.sqlite');
mkdirSync(dirname(dbFile), { recursive: true });
const db = new DatabaseSync(dbFile);
db.exec(`
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
PRAGMA temp_store=MEMORY;
PRAGMA busy_timeout=3000;
CREATE TABLE IF NOT EXISTS products (
 id TEXT PRIMARY KEY, source TEXT, url TEXT, name TEXT, brand TEXT,
 grams REAL, purity TEXT, price REAL, coupon_price REAL, status TEXT,
 checked_at TEXT, last_live_at TEXT, updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_products_source ON products(source);
CREATE INDEX IF NOT EXISTS idx_products_status ON products(status);
CREATE TABLE IF NOT EXISTS price_history (
 id INTEGER PRIMARY KEY AUTOINCREMENT, product_id TEXT NOT NULL,
 price REAL, coupon_price REAL, checked_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_price_history_product_time ON price_history(product_id, checked_at DESC);
CREATE TABLE IF NOT EXISTS bullion_history (
 id INTEGER PRIMARY KEY AUTOINCREMENT, source_id TEXT NOT NULL, karat INTEGER NOT NULL,
 price REAL NOT NULL, checked_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bullion_history_source_time ON bullion_history(source_id, karat, checked_at DESC);
CREATE TABLE IF NOT EXISTS refresh_runs (
 id INTEGER PRIMARY KEY AUTOINCREMENT, store TEXT NOT NULL, started_at TEXT NOT NULL,
 completed_at TEXT, total INTEGER DEFAULT 0, live INTEGER DEFAULT 0,
 duration_ms INTEGER, products_per_second REAL
);
`);

const upsertProduct = db.prepare(`INSERT INTO products
(id,source,url,name,brand,grams,purity,price,coupon_price,status,checked_at,last_live_at,updated_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
source=excluded.source,url=excluded.url,name=excluded.name,brand=excluded.brand,grams=excluded.grams,
purity=excluded.purity,price=excluded.price,coupon_price=excluded.coupon_price,status=excluded.status,
checked_at=excluded.checked_at,last_live_at=excluded.last_live_at,updated_at=excluded.updated_at`);
const lastPrice = db.prepare('SELECT price,coupon_price FROM price_history WHERE product_id=? ORDER BY id DESC LIMIT 1');
const insertPrice = db.prepare('INSERT INTO price_history(product_id,price,coupon_price,checked_at) VALUES(?,?,?,?)');
const lastBullion = db.prepare('SELECT price FROM bullion_history WHERE source_id=? AND karat=? ORDER BY id DESC LIMIT 1');
const insertBullion = db.prepare('INSERT INTO bullion_history(source_id,karat,price,checked_at) VALUES(?,?,?,?)');

export function mirrorStateToDatabase(state) {
  const now = new Date().toISOString();
  db.exec('BEGIN IMMEDIATE');
  try {
    for (const p of state.products || []) {
      upsertProduct.run(p.id, p.source || '', p.url || '', p.name || '', p.brand || '', Number(p.grams)||null,
        String(p.purity || ''), Number.isFinite(p.price)?p.price:null, Number.isFinite(p.couponPrice)?p.couponPrice:null,
        p.status || '', p.checkedAt || null, p.lastLiveAt || null, now);
      if (p.checkedAt && Number.isFinite(p.price) && p.price > 0) {
        const prev = lastPrice.get(p.id);
        if (!prev || Number(prev.price) !== Number(p.price) || Number(prev.coupon_price || 0) !== Number(p.couponPrice || 0))
          insertPrice.run(p.id, p.price, Number.isFinite(p.couponPrice)?p.couponPrice:null, p.checkedAt);
      }
    }
    for (const b of state.bullion || []) {
      const at = b.fetchedAt || b.lastLiveAt || now;
      for (const [karat, price] of [[24, b.price24 ?? b.price], [22, b.price22]]) {
        if (!Number.isFinite(price) || price <= 0) continue;
        const prev = lastBullion.get(b.id, karat);
        if (!prev || Number(prev.price) !== Number(price)) insertBullion.run(b.id, karat, price, at);
      }
    }
    db.exec('COMMIT');
  } catch (error) { db.exec('ROLLBACK'); throw error; }
}

export function recordRefreshRun({ store, startedAt, completedAt, total, live, durationMs }) {
  db.prepare('INSERT INTO refresh_runs(store,started_at,completed_at,total,live,duration_ms,products_per_second) VALUES(?,?,?,?,?,?,?)')
    .run(store, startedAt, completedAt, total, live, durationMs, durationMs > 0 ? total / (durationMs / 1000) : 0);
}

export function getBullionHistory(karat = 24, limit = 200) {
  return db.prepare(`
    SELECT source_id, price, checked_at 
    FROM bullion_history 
    WHERE karat = ? 
    ORDER BY checked_at ASC
    LIMIT ?
  `).all(karat, limit);
}

export function getProductHistory(productId, limit = 100) {
  return db.prepare(`
    SELECT price, coupon_price, checked_at 
    FROM price_history 
    WHERE product_id = ? 
    ORDER BY checked_at ASC
    LIMIT ?
  `).all(productId, limit);
}

export { dbFile };
