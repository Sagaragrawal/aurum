import assert from 'node:assert/strict';
import {
  ajioProductCode,
  applyDetailQualifiers,
  applyPurityFacet,
  detailQualifiers,
  normalizeListingProduct,
  qualificationReasons,
  toPersistedProduct
} from '../src/product/stores/ajio/listing.js';

const listing = normalizeListingProduct({
  code: '600771234',
  name: '10GG 22KT (916) PMJ Jewels Laxmi Gold Bar',
  url: '/pmj-10gg-gold-bar/p/6007712340_multi',
  price: { value: 160000 },
  wasPriceData: { value: 170000 },
  offerPrice: { value: 159000 },
  fnlColorVariantData: { brandName: 'PMJ Jewels', colorGroup: '6007712340_multi' },
  tags: { label: 'featured' }
}, 'category:830306012:page:0');

assert.equal(listing.ajioCode, '6007712340_multi');
assert.equal(listing.grams, 10);
assert.equal(listing.karat, 22);
assert.equal(listing.purity, '916');
assert.equal(listing.price, 160000);
assert.equal(listing.couponPrice, 159000);
assert.deepEqual(qualificationReasons(listing), []);

const platinum = normalizeListingProduct({
  name: '10 Gm (999) Platinum Rectangular Bar',
  url: '/platinum-bar/p/6005974440_multi',
  price: { value: 71495 }
}, 'category:830306012:page:0');
assert.equal(platinum.metal, 'conflict');
assert.ok(qualificationReasons(platinum).includes('metal:conflict'));

const unresolved = normalizeListingProduct({
  name: 'Malabar Yellow Gold Classic Coin',
  url: '/classic-coin/p/6007331310_multi',
  price: { value: 9154 }
}, 'category:830306012:page:0');
applyPurityFacet(unresolved, '24 Karat (999)');
assert.equal(unresolved.karat, 24);
assert.equal(unresolved.purity, '999');

const details = detailQualifiers({
  code: '6007331310_multi',
  name: 'Malabar Yellow Gold Classic Coin',
  baseOptions: [{
    selected: {
      code: '6007331310_multi',
      variantOptionQualifiers: [{ qualifier: 'metalPurity', value: '24 Karat (999)' }]
    }
  }],
  variantOptions: [{
    code: '600733131',
    variantOptionQualifiers: [
      { qualifier: 'metalWeight', value: '0.5' },
      { qualifier: 'uom', value: 'gm' }
    ]
  }],
  featureData: [{ name: 'Metal', featureValues: [{ value: 'Yellow Gold' }] }]
}, '6007331310_multi');
assert.deepEqual(details, { name: 'Malabar Yellow Gold Classic Coin', grams: 0.5, karat: 24, purity: '999', metal: 'gold' });
applyDetailQualifiers(unresolved, details);
assert.deepEqual(qualificationReasons(unresolved), []);

const persisted = toPersistedProduct(unresolved);
assert.ok(persisted.id);
assert.equal(persisted.source, 'ajio.com');
assert.equal(persisted.grams, 0.5);
assert.equal(persisted.status, 'live');
assert.equal(persisted.refreshMethod, 'ajio-plp');
assert.equal(ajioProductCode(persisted), '6007331310_multi');

console.log('AJIO listing tests passed');
