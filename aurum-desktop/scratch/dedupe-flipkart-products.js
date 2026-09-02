import { readFile, rename, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const file = join(root, 'data', 'products', 'flipkart-com.json');
const products = JSON.parse(await readFile(file, 'utf8'));

const identity = (product) => {
  const url = new URL(product.url);
  const productId = url.searchParams.get('pid');
  if (!productId) return url.href;
  const marketplace = url.searchParams.get('marketplace')?.toUpperCase();
  if (marketplace === 'HYPERLOCAL') return `minutes:${productId}:${url.searchParams.get('shopId') || ''}`;
  return `website:${productId}`;
};

const rank = (product) => (product.status === 'live' ? 3 : Number(product.price) > 0 ? 2 : product.checkedAt ? 1 : 0);
const unique = new Map();
for (const product of products) {
  const key = identity(product);
  const current = unique.get(key);
  if (!current || rank(product) > rank(current) || (rank(product) === rank(current) && String(product.lastLiveAt || '') > String(current.lastLiveAt || ''))) unique.set(key, product);
}

const output = [...unique.values()];
await writeFile(`${file}.tmp`, JSON.stringify(output, null, 2) + '\n', 'utf8');
await rename(`${file}.tmp`, file);
console.log(JSON.stringify({ before: products.length, after: output.length, removed: products.length - output.length }, null, 2));