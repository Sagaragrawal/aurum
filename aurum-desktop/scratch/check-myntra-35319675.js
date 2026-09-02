import { tryHttpFastPath } from '../src/product/stores/http-fast-path.js';
import { parse as parseMyntra } from '../src/product/stores/myntra/store.js';

async function main() {
  const url = 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-unisex-gold-coin/35319675/buy';
  const res = await fetch(url, {
    headers: {
      'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
      'accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
    }
  });
  console.log('Fetch status:', res.status);
  const html = await res.text();
  console.log('HTML length:', html.length);

  // Search for weight strings in HTML
  const weightMatches = [...html.matchAll(/(\d+(?:\.\d+)?)\s*(?:mg|gms|gm|grams|gram|g)\b/gi)].map(m => m[0]);
  console.log('Weight matches found in HTML:', weightMatches.slice(0, 30));

  // Search for index-rowKey / index-rowValue
  const specRows = [...html.matchAll(/<div[^>]*class=["'][^"']*index-rowKey[^"']*["'][^>]*>([\s\S]*?)<\/div>\s*<div[^>]*class=["'][^"']*index-rowValue[^"']*["'][^>]*>([\s\S]*?)<\/div>/gi)]
    .map(m => `${m[1].trim()}: ${m[2].trim()}`);
  console.log('Spec rows:', specRows);

  const parsed = parseMyntra(html, url);
  console.log('\nParsed result:', parsed);
}

main().catch(console.error);

