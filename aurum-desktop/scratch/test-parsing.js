import { extractGrams, isNonGoldProductText, tokenToGrams } from '../src/product/stores/weight-parser.js';
import { parse as parseMyntra } from '../src/product/stores/myntra/store.js';
import { parse as parseAmazon } from '../src/product/stores/amazon/store.js';
import { parse as parseFlipkart } from '../src/product/stores/flipkart/store.js';

console.log('--- Testing Weight Extraction ---');

const testCases = [
  {
    title: 'Muthoot Pappachan 3Pcs 24KT Gold Coin 10 G Each',
    url: 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-3pcs-24kt-gold-coin--10-g-each/33738641/buy',
    expectedGrams: 30
  },
  {
    title: 'Muthoot Pappachan 4Pcs 24KT Gold Coin 5 G Each',
    url: 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-4pcs-24kt-gold-coin--5-g-each/33738639/buy',
    expectedGrams: 20
  },
  {
    title: 'Muthoot Pappachan 24K 999 Purity 25g Lakshmi Gold Coin Pendant 25 Gm (5gm Each X 5 Pcs)',
    url: 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-24k-999-purity-25g-lakshmi-gold-coin-pendant--25-gm-5gm-each-x-5-pcs/33738583/buy',
    expectedGrams: 25
  },
  {
    title: 'Pack of 2 10 Gram 24K Gold Coin',
    url: 'https://www.amazon.in/dp/B012345678',
    expectedGrams: 20
  },
  {
    title: 'Set of 5 1g 24K Gold Coin',
    url: 'https://www.ajio.com/p/12345',
    expectedGrams: 5
  },
  {
    title: 'Kalyan Jewellers 24k (999) 2.5g Yellow Gold Coin',
    url: 'https://www.ajio.com/kalyan-jewellers-24k-999-25g-yellow-gold-coin/p/460834316_gold',
    expectedGrams: 2.5
  },
  {
    title: '500 mg 24K Gold Coin',
    url: 'https://www.myntra.com/500-mg-coin',
    expectedGrams: 0.5
  },
  {
    title: 'PPG 24K Gold Coin',
    url: 'https://www.flipkart.com/ppg-24-999-k-0-05-g-coin?pid=CONHKUGYZCFZHNQ3',
    expectedGrams: 0.05
  }
];

let weightFailures = 0;
for (const tc of testCases) {
  const result = extractGrams(tc.title, '', tc.url);
  const pass = Math.abs(result - tc.expectedGrams) < 0.001;
  console.log(`${pass ? '✅ PASS' : '❌ FAIL'}: "${tc.title}" => ${result}g (expected ${tc.expectedGrams}g)`);
  if (!pass) weightFailures++;
}

console.log('\n--- Testing Non-Gold Detection ---');

const nonGoldCases = [
  { text: 'Bangalore Refinery 999 Silver Coin 10g', expectedNonGold: true },
  { text: 'MMTC-PAMP 999.9 Fine Silver Bar 50g', expectedNonGold: true },
  { text: 'GIVA 925 Sterling Silver Coin Pendant', expectedNonGold: true },
  { text: 'Platinum Guild International Pt 950 2g Coin', expectedNonGold: true },
  { text: 'Muthoot Pappachan 3Pcs 24KT Gold Coin 10 G Each', expectedNonGold: false },
  { text: 'Malabar 24K (999) 5g Gold Coin', expectedNonGold: false },
  { text: 'Kalyan Jewellers 22k Yellow Gold Coin 8g', expectedNonGold: false }
];

let nonGoldFailures = 0;
for (const tc of nonGoldCases) {
  const result = isNonGoldProductText(tc.text);
  const pass = result === tc.expectedNonGold;
  console.log(`${pass ? '✅ PASS' : '❌ FAIL'}: "${tc.text}" => nonGold=${result} (expected ${tc.expectedNonGold})`);
  if (!pass) nonGoldFailures++;
}

if (weightFailures > 0 || nonGoldFailures > 0) {
  console.error(`\nFAILED: ${weightFailures} weight tests, ${nonGoldFailures} non-gold tests`);
  process.exit(1);
} else {
  console.log('\nAll tests passed successfully! 🎉');
}

