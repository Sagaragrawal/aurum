import { extractGrams, normalizeGoldWeight, isNonGoldProductText } from '../src/product/stores/weight-parser.js';

const testCases = [
  // User's 10 reported items:
  {
    title: 'C KRISHNIAH CHETTY JEWELLERS PVT LTD 24KT Gold Goddess Lakshmi Coin - 500 g',
    url: 'https://www.myntra.com/gold-coin/c+krishniah+chetty+jewellers+pvt+ltd/c-krishniah-chetty-jewellers-pvt-ltd-24kt-gold-goddess-lakshmi-coin---500-g/41493417/buy',
    price: 9750,
    expectedGrams: 0.5
  },
  {
    title: 'C KRISHNIAH CHETTY JEWELLERS PVT LTD Set Of 10 24Kt Gold Coin-1g',
    url: 'https://www.myntra.com/gold-coin/c+krishniah+chetty+jewellers+pvt+ltd/c-krishniah-chetty-jewellers-pvt-ltd-set-of-10-24kt-gold-coin-1g/41728871/buy',
    price: 18910,
    expectedGrams: 1
  },
  {
    title: 'C KRISHNIAH CHETTY JEWELLERS PVT LTD Set Of 9 24Kt Gold Coin-1g',
    url: 'https://www.myntra.com/gold-coin/c+krishniah+chetty+jewellers+pvt+ltd/c-krishniah-chetty-jewellers-pvt-ltd-set-of-9-24kt-gold-coin-1g/41728870/buy',
    price: 18910,
    expectedGrams: 1
  },
  {
    title: 'C KRISHNIAH CHETTY JEWELLERS PVT LTD Set Of 5 24Kt Gold Coin-0.5g',
    url: 'https://www.myntra.com/gold-coin/c+krishniah+chetty+jewellers+pvt+ltd/c-krishniah-chetty-jewellers-pvt-ltd-set-of-5-24kt-gold-coin-05g/41728876/buy',
    price: 9750,
    expectedGrams: 0.5
  },
  {
    title: 'Malabar Gold & Diamonds 24KT Set Of 3 Rose Gold Coin-4.5g',
    url: 'https://www.myntra.com/gold-coin/malabar+gold+%26+diamonds/malabar-gold--diamonds-24kt-set-of-3-rose-gold-coin-45g/41350873/buy',
    price: 78589,
    expectedGrams: 4.5
  },
  {
    title: 'Muthoot Pappachan Pack Of 3 24Kt 999 Combo Gold Oval Lakshmi Pendant 6 Gm',
    desc: '24Kt 999 Combo Gold Oval Lakshmi Pendant 6 Gm (2gm each x 3 Pcs) 22X11X2MM',
    url: 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-pack-of-3-24kt-999-combo-gold-oval-lakshmi-pendant-6-gm/33738574/buy',
    price: 115127,
    expectedGrams: 6
  },
  {
    title: 'Malabar Gold & Diamonds Set Of 2 24K Rose Gold Coin - 4 g',
    url: 'https://www.myntra.com/gold-coin/malabar+gold+%26+diamonds/malabar-gold--diamonds-set-of-2-24k-rose-gold-coin---4-g/41350871/buy',
    price: 69229,
    expectedGrams: 4
  },
  {
    title: 'Malabar Gold & Diamonds Set Of 2 24K Laxmi Gold Coin - 4 g',
    url: 'https://www.myntra.com/gold-coin/malabar+gold+%26+diamonds/malabar-gold--diamonds-set-of-2-24k-laxmi-gold-coin---4-g/41350866/buy',
    price: 69229,
    expectedGrams: 4
  },
  {
    title: 'Malabar Gold & Diamonds Set of 2 24K Rose Gold Coin - 1.5 g',
    url: 'https://www.myntra.com/gold-coin/malabar+gold+%26+diamonds/malabar-gold--diamonds-set-of-2-24k-rose-gold-coin---15-g-/41350872/buy',
    price: 26269,
    expectedGrams: 1.5
  },
  {
    title: 'Muthoot Pappachan Pack Of 2 24Kt 999 Combo Gold Oval Lakshmi Pendant 20 Gm',
    desc: 'Pack Of 2 24Kt 999 Combo Gold Oval Lakshmi Pendant 20 Gm 22X11X2MM',
    url: 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-pack-of-2-24kt-999-combo-gold-oval-lakshmi-pendant-20-gm/33738611/buy',
    price: 382342,
    expectedGrams: 20
  },

  // Explicit "each" items (MUST multiply):
  {
    title: 'Muthoot Pappachan 3Pcs 24KT Gold Coin 10 G Each',
    url: 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-3pcs-24kt-gold-coin--10-g-each/33738641/buy',
    price: 573512,
    expectedGrams: 30
  },
  {
    title: 'Muthoot Pappachan 4Pcs 24KT Gold Coin 5 G Each',
    url: 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-4pcs-24kt-gold-coin--5-g-each/33738639/buy',
    price: 382342,
    expectedGrams: 20
  },
  {
    title: 'Muthoot Pappachan 24K 999 Purity 25g Lakshmi Gold Coin Pendant 25 Gm (5gm Each X 5 Pcs)',
    url: 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-24k-999-purity-25g-lakshmi-gold-coin-pendant--25-gm-5gm-each-x-5-pcs/33738583/buy',
    price: 478317,
    expectedGrams: 25
  },
  {
    title: 'Kalyan Jewellers 24k (999) 2.5g Yellow Gold Coin',
    url: 'https://www.ajio.com/kalyan-jewellers-24k-999-25g-yellow-gold-coin/p/460834316_gold',
    price: 43000,
    expectedGrams: 2.5
  },
  {
    title: 'Touch925 50 mg 24Kt (999) Queen Victoria Gold Coin | 24 Kt (999) | 0.05 gm',
    url: 'https://www.ajio.com/touch925-50-mg-24kt-999-queen-victoria-gold-coin/p/6007569640_multi',
    price: 1699,
    expectedGrams: 0.05
  }
];

let failed = 0;
for (const tc of testCases) {
  let g = extractGrams(tc.title, tc.desc || '', tc.url);
  g = normalizeGoldWeight(g, tc.price);
  const pass = Math.abs(g - tc.expectedGrams) < 0.001;
  console.log(`${pass ? '✅ PASS' : '❌ FAIL'}: "${tc.title}" => ${g}g (expected ${tc.expectedGrams}g)`);
  if (!pass) failed++;
}

console.log(`\nTotal failures: ${failed}`);

