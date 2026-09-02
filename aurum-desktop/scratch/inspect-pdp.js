async function main() {
  const url = 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-unisex-gold-coin/35319675/buy';
  const res = await fetch(url, {
    headers: {
      'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
      'accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
    }
  });
  const html = await res.text();
  const scriptContent = html.match(/<script>window\.__myx\s*=\s*([\s\S]*?)<\/script>/i)?.[1] || '';
  const pdpIndex = scriptContent.indexOf('"pdpData":');
  console.log('pdpIndex:', pdpIndex);
  if (pdpIndex !== -1) {
    const raw = scriptContent.slice(pdpIndex + 10);
    // Find matching bracket
    let depth = 0;
    let end = 0;
    for (let i = 0; i < raw.length; i++) {
      if (raw[i] === '{') depth++;
      else if (raw[i] === '}') {
        depth--;
        if (depth === 0) { end = i + 1; break; }
      }
    }
    const pdp = JSON.parse(raw.slice(0, end));
    console.log('sizes:', JSON.stringify(pdp.sizes, null, 2));
    console.log('descriptors:', JSON.stringify(pdp.descriptors, null, 2));
    console.log('productDetails:', JSON.stringify(pdp.productDetails, null, 2));
    console.log('flags:', pdp.flags);
    console.log('brand:', pdp.brand);
  }
}

main().catch(console.error);

