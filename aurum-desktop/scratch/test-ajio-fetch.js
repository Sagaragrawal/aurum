async function testAjioApi() {
  const code = '6007569640_multi';
  const url1 = `https://www.ajio.com/api/p/${encodeURIComponent(code)}`;
  const url2 = `https://www.ajio.com/api/p/${encodeURIComponent('6007569640')}`;
  
  console.log('Testing url1:', url1);
  try {
    const res1 = await fetch(url1, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
        'Accept': 'application/json',
        'Referer': 'https://www.ajio.com/'
      }
    });
    console.log('res1 status:', res1.status);
    if (res1.ok) {
      const data = await res1.json();
      console.log('res1 name:', data.name, 'price:', data.price?.value, 'wasPrice:', data.wasPriceData?.value, 'promo:', data.promoDiscountedPrice);
    }
  } catch (e) {
    console.log('res1 error:', e.message);
  }

  console.log('\nTesting url2:', url2);
  try {
    const res2 = await fetch(url2, {
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
        'Accept': 'application/json',
        'Referer': 'https://www.ajio.com/'
      }
    });
    console.log('res2 status:', res2.status);
    if (res2.ok) {
      const data = await res2.json();
      console.log('res2 name:', data.name, 'price:', data.price?.value);
    }
  } catch (e) {
    console.log('res2 error:', e.message);
  }
}

testAjioApi().catch(console.error);

