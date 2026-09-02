import { loadState, saveState } from '../src/storage/state-store.js';
import { parse as parseMyntra } from '../src/product/stores/myntra/store.js';

async function main() {
  const state = await loadState();
  const p = state.products.find(item => item.url?.includes('35319675'));
  console.log('Found 35319675:', p);
  if (p) {
    p.grams = 1;
    p.purity = '995';
    p.price = 17614;
    p.status = 'live';
    saveState(state);
    console.log('Updated 35319675 to 1g!');
  }
}

main().catch(console.error);

