import fs from 'node:fs';
import path from 'node:path';
import { extractGrams, normalizeGoldWeight, isNonGoldProductText } from '../src/product/stores/weight-parser.js';
import { mirrorStateToDatabase } from '../src/storage/history-db.js';

const productDir = path.resolve('data', 'products');
const files = fs.readdirSync(productDir).filter(f => f.endsWith('.json'));

let totalUpdated = 0;
let allProducts = [];

for (const file of files) {
  const filePath = path.join(productDir, file);
  const products = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  let updatedInFile = 0;

  for (const product of products) {
    const title = product.name || '';
    const url = product.url || '';
    const price = product.price || null;
    const currentGrams = product.grams;

    if (isNonGoldProductText(`${title} ${url}`)) {
      console.log(`[Non-Gold Filter] ${product.source}: "${title}"`);
      product.status = 'unavailable';
      product.error = 'Filtered: Non-gold item';
      updatedInFile++;
      continue;
    }

    let newGrams = extractGrams(title, '', url);
    if (price) {
      newGrams = normalizeGoldWeight(newGrams, price);
    }

    if (newGrams && Math.abs(newGrams - (currentGrams || 0)) > 0.0001) {
      console.log(`[Weight Fix] ${product.source}: "${title}" (Price: ₹${price}) -> old: ${currentGrams}g, new: ${newGrams}g`);
      product.grams = newGrams;
      updatedInFile++;
    }
  }

  if (updatedInFile > 0) {
    fs.writeFileSync(filePath, JSON.stringify(products, null, 2));
    totalUpdated += updatedInFile;
  }
  allProducts.push(...products);
}

console.log(`Normalized all products: ${totalUpdated} changes across ${files.length} store files.`);

// Mirror all products to SQLite
try {
  let bullion = [];
  try {
    const bData = JSON.parse(fs.readFileSync(path.resolve('data', 'bullion.json'), 'utf8'));
    bullion = Array.isArray(bData) ? bData : (bData.sources || []);
  } catch {}
  mirrorStateToDatabase({ products: allProducts, bullion });
  console.log(`SQLite database successfully synchronized with ${allProducts.length} products.`);
} catch (e) {
  console.log('SQLite sync error:', e.message);
}
