import { readFile, rename, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { extractGrams } from '../src/product/stores/weight-parser.js';

const productsPath = join(process.cwd(), 'data', 'products', 'flipkart-com.json');
const products = JSON.parse(await readFile(productsPath, 'utf8'));
const repaired = [];

for (const product of products) {
  if (product.manuallyEditedAt) continue;
  if (!/(?:^|[-_/])0-0\d+-g(?:[-_/]|$)/i.test(product.url || '')) continue;
  const grams = extractGrams('', '', product.url);
  if (!grams || Math.abs(Number(product.grams) - grams) < 0.000001) continue;
  repaired.push({ id: product.id, url: product.url, from: product.grams, to: grams });
  product.grams = grams;
  product.checkedAt = new Date().toISOString();
}

if (repaired.length) {
  const tempPath = `${productsPath}.tmp`;
  await writeFile(tempPath, JSON.stringify(products, null, 2) + '\n', 'utf8');
  await rename(tempPath, productsPath);
}

console.log(JSON.stringify({ repaired: repaired.length, products: repaired }, null, 2));