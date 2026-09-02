import { readFile } from 'node:fs/promises';

async function main() {
  const url = 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-unisex-gold-coin/35319675/buy';
  const res = await fetch(url, {
    headers: {
      'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
      'accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
    }
  });
  const html = await res.text();
  
  // Find script containing pdpData
  const myxMatch = html.match(/<script>window\.__myx\s*=\s*(\{[\s\S]*?\})<\/script>/i)
    || html.match(/window\.__myx\s*=\s*(\{[\s\S]*?\});/i)
    || html.match(/<script[^>]*id=["']__NEXT_DATA__[^"']*["'][^>]*>([\s\S]*?)<\/script>/i);
  
  if (myxMatch) {
    try {
      const data = JSON.parse(myxMatch[1]);
      console.log('Found __myx / NEXT_DATA!');
      const pdp = data.pdpData || data.props?.pageProps?.initialData?.data?.product || data;
      console.log('Product Name:', pdp.name || pdp.title || pdp.productName);
      console.log('Product Article Attributes:', JSON.stringify(pdp.articleAttributes || pdp.attributes || pdp.specifications || {}, null, 2));
      console.log('Product Price:', pdp.price || pdp.discountedPrice);
    } catch (e) {
      console.log('JSON parse error:', e.message);
    }
  } else {
    console.log('__myx not found, searching for weight in script tags:');
    const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)]
      .map(m => m[1])
      .filter(s => s.includes('35319675') || s.includes('Muthoot') || s.includes('articleAttributes'));
    console.log('Matching scripts count:', scripts.length);
    if (scripts.length) {
      console.log('Sample script preview:', scripts[0].slice(0, 1000));
    }
  }
}

main().catch(console.error);

