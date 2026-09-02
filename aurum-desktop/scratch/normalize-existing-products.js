import { loadState, saveState } from '../src/storage/state-store.js';
import { extractGrams, isNonGoldProductText } from '../src/product/stores/weight-parser.js';

async function main() {
  const state = await loadState();
  let updatedWeights = 0;
  let nonGoldFiltered = 0;

  for (const product of state.products || []) {
    const accurateGrams = extractGrams(product.name, '', product.url);
    if (accurateGrams && accurateGrams > 0 && Math.abs(product.grams - accurateGrams) > 0.001) {
      console.log(`Updating weight for "${product.name}": ${product.grams}g -> ${accurateGrams}g`);
      product.grams = accurateGrams;
      updatedWeights++;
    }

    if (isNonGoldProductText(`${product.name || ''} ${product.url || ''} ${product.purity || ''}`)) {
      console.log(`Filtering non-gold product: "${product.name}"`);
      product.status = 'unavailable';
      product.price = null;
      product.couponPrice = null;
      product.error = 'Filtered: Silver/Platinum product (not gold).';
      nonGoldFiltered++;
    }
  }

  saveState(state);
  console.log(`\nDone. Updated ${updatedWeights} product weights, filtered ${nonGoldFiltered} non-gold products.`);
}

main().catch(console.error);

