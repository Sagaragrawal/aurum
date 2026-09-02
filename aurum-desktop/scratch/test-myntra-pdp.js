import { extractGrams } from '../src/product/stores/weight-parser.js';

function extractPdpFromJson(html) {
  try {
    const scriptMatch = html.match(/<script>window\.__myx\s*=\s*([\s\S]*?)<\/script>/i);
    if (!scriptMatch) return null;
    const scriptContent = scriptMatch[1];
    const pdpIndex = scriptContent.indexOf('"pdpData":');
    if (pdpIndex === -1) return null;
    const raw = scriptContent.slice(pdpIndex + 10);
    let depth = 0;
    let end = 0;
    for (let i = 0; i < raw.length; i++) {
      if (raw[i] === '{') depth++;
      else if (raw[i] === '}') {
        depth--;
        if (depth === 0) { end = i + 1; break; }
      }
    }
    if (end > 0) return JSON.parse(raw.slice(0, end));
  } catch {}
  return null;
}

async function main() {
  const url = 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-unisex-gold-coin/35319675/buy';
  const res = await fetch(url, {
    headers: {
      'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
      'accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
    }
  });
  const html = await res.text();
  const pdp = extractPdpFromJson(html);
  if (pdp) {
    const title = pdp.name;
    const descText = (pdp.descriptors || []).map(d => d.description).join(' ') + ' ' + (pdp.productDetails || []).map(d => d.description).join(' ');
    const attrs = { ...pdp.articleAttributes };
    delete attrs['Weight Range']; // Ignore filter range
    const attrText = Object.entries(attrs).map(([k, v]) => `${k} ${v}`).join(' ');
    const combined = `${title} ${descText} ${attrText}`.replace(/<[^>]+>/g, ' ');
    console.log('Cleaned combined text:', combined);
    const grams = extractGrams(title, combined, new URL(url).pathname);
    const price = pdp.sizes?.[0]?.sizeSellerData?.[0]?.discountedPrice || pdp.sizes?.[0]?.sizeSellerData?.[0]?.mrp;
    console.log('Extracted grams:', grams);
    console.log('Extracted price:', price);
    console.log('Price per gram:', price / grams);
  }
}

main().catch(console.error);

