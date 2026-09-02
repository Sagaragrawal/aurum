import assert from 'node:assert/strict';
import { applyProductDetails, myntraProductId, normalizeListingProduct, qualificationReasons, toPersistedProduct } from '../src/product/stores/myntra/listing.js';

const listing = normalizeListingProduct({
  productId: 30970331,
  productName: 'Mia by Tanishq 24KT Gold Lotus Coin - 0.5 gm',
  brand: 'Mia by Tanishq',
  landingPageUrl: 'Gold-Coin/Mia+by+Tanishq/Mia-by-Tanishq-24KT-Gold-Lotus-Coin---05-gm/30970331/buy',
  price: 8583,
  couponData: { couponDescription: { bestPrice: 8283 } }
}, 'search:0');

assert.equal(listing.productId, '30970331');
assert.equal(listing.grams, 0.5);
assert.equal(listing.karat, 24);
assert.equal(listing.price, 8583);
assert.equal(listing.couponPrice, 8283);
assert.deepEqual(qualificationReasons(listing), []);

const unresolved = normalizeListingProduct({
  productId: 45028876,
  productName: 'PARSHWA PADMAVATI Gold Coin',
  brand: 'PARSHWA PADMAVATI GOLD',
  landingPageUrl: 'gold-coin/parshwa/coin/45028876/buy',
  price: 1999
}, 'search:50');
applyProductDetails(unresolved, {
  style: {
    name: 'PARSHWA PADMAVATI GOLD 24 Kt Gold Coin - 50 mg',
    articleAttributes: { 'Gold Purity': '24 Kt', 'Metal Net Weight': '50 MG' },
    sizes: [{ sizeSellerData: [{ discountedPrice: 1999 }] }]
  }
});
assert.equal(unresolved.grams, 0.05);
assert.equal(unresolved.karat, 24);
assert.deepEqual(qualificationReasons(unresolved), []);

const conflict = normalizeListingProduct({ productId: 34, productName: 'CKC Gold Coin', landingPageUrl: 'gold-coin/ckc/coin/34/buy', price: 10000 }, 'search:0');
applyProductDetails(conflict, {
  style: {
    name: 'CKC Gold Coin 1 g',
    articleAttributes: { 'Gold Purity': '24 Kt' },
    productDetails: [{ description: 'A 22kt gold coin with gross weight 1 g' }]
  }
});
assert.equal(conflict.karat, 24);
assert.equal(conflict.purityConflict, true);

const eighteenKarat = normalizeListingProduct({ productId: 35, productName: '18KT Gold Coin - 1 g', landingPageUrl: 'gold-coin/test/18k/35/buy', price: 9000 }, 'search:0');
assert.deepEqual(qualificationReasons(eighteenKarat), []);

const silver = normalizeListingProduct({ productId: 12, productName: '999 Silver Coin 10g', landingPageUrl: 'silver-coin/test/12/buy', price: 4500 }, 'search:0');
assert.ok(qualificationReasons(silver).includes('metal:non-gold'));

const persisted = toPersistedProduct(listing);
assert.ok(persisted.id);
assert.equal(persisted.source, 'myntra.com');
assert.equal(persisted.refreshMethod, 'myntra-plp');
assert.equal(myntraProductId(persisted), '30970331');

console.log('Myntra listing tests passed');