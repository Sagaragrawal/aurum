import { parse as parseMyntra, parseProductApi } from '../src/product/stores/myntra/store.js';

const html1 = `
<html>
<head><title>Muthoot Pappachan 3Pcs 24KT Gold Coin 10 G Each | Myntra</title></head>
<body>
<div class="index-rowKey">Metal Net Weight</div><div class="index-rowValue">10 g</div>
<div class="index-rowKey">Gold Purity</div><div class="index-rowValue">24 K</div>
<div>Selling Price ₹ 5,73,512</div>
</body>
</html>
`;

const res1 = parseMyntra(html1, 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-3pcs-24kt-gold-coin--10-g-each/33738641/buy');
console.log('Result 1 (30g expected):', res1.grams, 'g, Price/g:', res1.price / res1.grams);

const html2 = `
<html>
<head><title>Muthoot Pappachan 4Pcs 24KT Gold Coin 5 G Each | Myntra</title></head>
<body>
<div class="index-rowKey">Metal Net Weight</div><div class="index-rowValue">5 g</div>
<div class="index-rowKey">Gold Purity</div><div class="index-rowValue">24 K</div>
<div>Selling Price ₹ 3,82,341</div>
</body>
</html>
`;

const res2 = parseMyntra(html2, 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-4pcs-24kt-gold-coin--5-g-each/33738639/buy');
console.log('Result 2 (20g expected):', res2.grams, 'g, Price/g:', res2.price / res2.grams);

const htmlSilver = `
<html>
<head><title>Bangalore Refinery 999 50g Silver Coin | Myntra</title></head>
<body>
<div class="index-rowKey">Metal Net Weight</div><div class="index-rowValue">50 g</div>
<div class="index-rowKey">Metal Purity</div><div class="index-rowValue">999</div>
<div>Selling Price ₹ 4,500</div>
</body>
</html>
`;

try {
  const resSilver = parseMyntra(htmlSilver, 'https://www.myntra.com/silver-coin/bangalore-refinery/50g/123/buy');
  console.log('Silver result (should not reach here):', resSilver);
} catch (e) {
  console.log('✅ Silver properly rejected:', e.message);
}

const apiResult = parseProductApi({
  style: {
    name: 'Mia by Tanishq 24KT Gold Lotus Coin - 0.5 gm',
    brand: { name: 'Mia by Tanishq' },
    articleAttributes: { 'Gold Purity': '24 Kt' },
    productDetails: [{ description: 'Gross weight 0.5 GM' }],
    sizes: [{ sizeSellerData: [{ discountedPrice: 8583, mrp: 9000 }] }]
  }
}, 'https://www.myntra.com/gold-coin/mia-by-tanishq/lotus-coin-05-gm/30970331/buy');

if (apiResult?.price !== 8583 || apiResult?.grams !== 0.5 || apiResult?.purity !== '999' || apiResult?.karat !== 24 || apiResult?.refreshMethod !== 'api') {
  throw new Error(`Unexpected Myntra API parse result: ${JSON.stringify(apiResult)}`);
}
console.log('✅ Product API parser resolved 0.5g, 24K, and seller price.');

