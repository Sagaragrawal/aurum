import { readFile, rename, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const productsPath = join(process.cwd(), 'data', 'products', 'ajio-com.json');
const products = JSON.parse(await readFile(productsPath, 'utf8'));
const interruptedWorkerError = /^Persistent ajio\.com product worker exited \(code=130, signal=none\)$/;
const restored = [];

for (const product of products) {
  if (product.status !== 'failed' || !interruptedWorkerError.test(product.error || '')) continue;
  if (!(Number(product.price) > 0) || !(Number(product.grams) > 0)) continue;
  product.status = 'stale';
  delete product.error;
  restored.push(product.id);
}

if (restored.length) {
  const tempPath = `${productsPath}.tmp`;
  await writeFile(tempPath, JSON.stringify(products, null, 2) + '\n', 'utf8');
  await rename(tempPath, productsPath);
}

console.log(JSON.stringify({ restored: restored.length }, null, 2));