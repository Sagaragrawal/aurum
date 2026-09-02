async function inspectProduct(id, url) {
  try {
    const res = await fetch(url, {
      headers: {
        'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
        'accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
      }
    });
    const html = await res.text();
    const scriptMatch = html.match(/<script>window\.__myx\s*=\s*([\s\S]*?)<\/script>/i);
    if (!scriptMatch) {
      console.log(`[${id}] No scriptMatch`);
      return;
    }
    const scriptContent = scriptMatch[1];
    const pdpIndex = scriptContent.indexOf('"pdpData":');
    if (pdpIndex === -1) return;
    const raw = scriptContent.slice(pdpIndex + 10);
    let depth = 0, end = 0;
    for (let i = 0; i < raw.length; i++) {
      if (raw[i] === '{') depth++;
      else if (raw[i] === '}') {
        depth--;
        if (depth === 0) { end = i + 1; break; }
      }
    }
    const pdp = JSON.parse(raw.slice(0, end));
    const title = pdp.name || pdp.title;
    const price = pdp.sizes?.[0]?.sizeSellerData?.[0]?.discountedPrice || pdp.price?.discounted || pdp.price?.mrp;
    const attrs = pdp.articleAttributes || {};
    const descs = (pdp.descriptors || []).map(d => d.description).join(' ');
    console.log(`\n--- [${id}] ---`);
    console.log(`Title: ${title}`);
    console.log(`Price: ₹${price}`);
    console.log(`Metal Net Weight attr: "${attrs['Metal Net Weight'] || ''}"`);
    console.log(`Net Quantity: "${attrs['Net Quantity'] || ''}" ${attrs['Net Quantity Unit'] || ''}`);
    console.log(`Descriptors: ${descs.replace(/<[^>]+>/g, ' ')}`);
  } catch (e) {
    console.log(`[${id}] Error: ${e.message}`);
  }
}

async function main() {
  const products = [
    { id: '41493417', url: 'https://www.myntra.com/gold-coin/c+krishniah+chetty+jewellers+pvt+ltd/c-krishniah-chetty-jewellers-pvt-ltd-24kt-gold-goddess-lakshmi-coin---500-g/41493417/buy' },
    { id: '41728871', url: 'https://www.myntra.com/gold-coin/c+krishniah+chetty+jewellers+pvt+ltd/c-krishniah-chetty-jewellers-pvt-ltd-set-of-10-24kt-gold-coin-1g/41728871/buy' },
    { id: '41728870', url: 'https://www.myntra.com/gold-coin/c+krishniah+chetty+jewellers+pvt+ltd/c-krishniah-chetty-jewellers-pvt-ltd-set-of-9-24kt-gold-coin-1g/41728870/buy' },
    { id: '41728876', url: 'https://www.myntra.com/gold-coin/c+krishniah+chetty+jewellers+pvt+ltd/c-krishniah-chetty-jewellers-pvt-ltd-set-of-5-24kt-gold-coin-05g/41728876/buy' },
    { id: '41350873', url: 'https://www.myntra.com/gold-coin/malabar+gold+%26+diamonds/malabar-gold--diamonds-24kt-set-of-3-rose-gold-coin-45g/41350873/buy' },
    { id: '33738574', url: 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-pack-of-3-24kt-999-combo-gold-oval-lakshmi-pendant-6-gm/33738574/buy' },
    { id: '41350871', url: 'https://www.myntra.com/gold-coin/malabar+gold+%26+diamonds/malabar-gold--diamonds-set-of-2-24k-rose-gold-coin---4-g/41350871/buy' },
    { id: '41350866', url: 'https://www.myntra.com/gold-coin/malabar+gold+%26+diamonds/malabar-gold--diamonds-set-of-2-24k-laxmi-gold-coin---4-g/41350866/buy' },
    { id: '41350872', url: 'https://www.myntra.com/gold-coin/malabar+gold+%26+diamonds/malabar-gold--diamonds-set-of-2-24k-rose-gold-coin---15-g-/41350872/buy' },
    { id: '33738611', url: 'https://www.myntra.com/gold-coin/muthoot+pappachan/muthoot-pappachan-pack-of-2-24kt-999-combo-gold-oval-lakshmi-pendant-20-gm/33738611/buy' }
  ];

  for (const p of products) {
    await inspectProduct(p.id, p.url);
  }
}

main().catch(console.error);

