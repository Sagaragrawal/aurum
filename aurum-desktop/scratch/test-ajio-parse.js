import { parseFromApiPayload } from '../src/product/stores/ajio/store.js';

const testData = {
  name: "50 mg 24Kt (999) Queen Victoria Gold Coin",
  brandName: "Touch925",
  price: { value: 1699 },
  wasPriceData: { value: 1699 },
  stock: { stockLevelStatus: "inStock" },
  purchasable: true
};

const url = "https://www.ajio.com/touch925-50-mg-24kt-999-queen-victoria-gold-coin/p/6007569640_multi";

try {
  const parsed = parseFromApiPayload(testData, url);
  console.log('Parsed result:', parsed);
} catch (e) {
  console.log('Error:', e.message);
}

