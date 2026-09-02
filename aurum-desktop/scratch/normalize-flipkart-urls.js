import { readFile, rename, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const productsPath = join(process.cwd(), 'data', 'products', 'flipkart-com.json');
const products = JSON.parse(await readFile(productsPath, 'utf8'));
const volatileKeys = ['otracker', 'otracker1', 'lid', 'fm', 'ppt', 'ppn', 'srno', 'spotlightTagId', 'iid', 'ssid', 'ov_redirect', 'store'];
let updated = 0;

for (const product of products) {
  const url = new URL(product.url);
  for (const key of volatileKeys) url.searchParams.delete(key);
  const pid = url.searchParams.get('pid');
  if (pid) url.searchParams.set('pid', pid);
  url.searchParams.set('marketplace', 'FLIPKART');
  url.hash = '';
  if (product.url === url.href && product.canonicalUrl === url.href) continue;
  product.url = url.href;
  product.canonicalUrl = url.href;
  updated += 1;
}

if (updated) {
  const tempPath = `${productsPath}.tmp`;
  await writeFile(tempPath, JSON.stringify(products, null, 2) + '\n', 'utf8');
  await rename(tempPath, productsPath);
}

console.log(JSON.stringify({ updated }, null, 2));