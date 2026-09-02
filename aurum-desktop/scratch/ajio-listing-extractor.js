globalThis.__ajioExtractorPromise = (async () => {
  'use strict';

  const CONFIG = {
    categoryIds: ['830306012', '830306009'],
    pageSize: 45,
    requestTimeoutMs: 12000,
    requestAttempts: 3,
    requestDelayMinMs: 250,
    requestDelayMaxMs: 430,
    detailDelayMs: 300,
    maxDetailRequests: 50,
    minimumKarat: 22,
    minimumGrams: 0.5,
    enablePurityFacetEnrichment: true,
    enableDetailApiEnrichment: true
  };

  const originalTitle = document.title;
  const sleep = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
  const clean = (value) => String(value ?? '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
  const randomDelay = () => CONFIG.requestDelayMinMs
    + Math.floor(Math.random() * (CONFIG.requestDelayMaxMs - CONFIG.requestDelayMinMs + 1));

  const state = globalThis.ajioExtractorState = {
    status: 'running',
    startedAt: new Date().toISOString(),
    phase: 'listing',
    source: null,
    page: 0,
    totalPages: 0,
    requests: { listing: 0, facet: 0, detail: 0, retries: 0 },
    failures: [],
    observations: [],
    products: []
  };

  const numberFrom = (value) => {
    const numeric = Number(value?.value ?? value);
    return Number.isFinite(numeric) && numeric > 0 ? numeric : null;
  };

  const canonicalId = (product) => {
    const fromUrl = String(product?.url || '').match(/\/p\/([^/?#]+)/i)?.[1];
    return fromUrl || String(product?.fnlColorVariantData?.colorGroup || product?.code || '') || null;
  };

  const canonicalUrl = (value) => {
    try {
      const url = new URL(value, location.origin);
      url.search = '';
      url.hash = '';
      return url.href;
    } catch {
      return null;
    }
  };

  const fetchJson = async (url, requestType) => {
    let lastError;
    for (let attempt = 1; attempt <= CONFIG.requestAttempts; attempt += 1) {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), CONFIG.requestTimeoutMs);
      state.requests[requestType] += 1;
      try {
        const response = await fetch(url, {
          credentials: 'include',
          cache: 'no-store',
          headers: { accept: 'application/json, text/plain, */*' },
          signal: controller.signal
        });
        if (response.status === 403 || response.status === 429) {
          throw new Error(`AJIO blocked request with HTTP ${response.status}`);
        }
        if (!response.ok) throw new Error(`AJIO returned HTTP ${response.status}`);
        return await response.json();
      } catch (error) {
        lastError = error;
        const blocked = /HTTP (403|429)/.test(error?.message || '');
        if (blocked || attempt === CONFIG.requestAttempts) break;
        state.requests.retries += 1;
        await sleep(500 * attempt);
      } finally {
        clearTimeout(timeout);
      }
    }
    throw lastError;
  };

  const sourceText = (raw) => [
    raw?.fnlColorVariantData?.brandName,
    raw?.brandName,
    raw?.name,
    raw?.url,
    ...(Array.isArray(raw?.images) ? raw.images : []).map((image) => image?.altText),
    ...(Array.isArray(raw?.extraImages) ? raw.extraImages : []).map((image) => image?.altText),
    ...(Array.isArray(raw?.tags) ? raw.tags : raw?.tags ? [raw.tags] : [])
      .map((tag) => typeof tag === 'string' ? tag : JSON.stringify(tag))
  ].filter(Boolean).map(clean).join(' ');

  const unitToGrams = (value, unit) => {
    let grams = Number(value);
    const normalizedUnit = String(unit || 'g').toLowerCase();
    if (normalizedUnit === 'mg') grams /= 1000;
    if (normalizedUnit === 'kg') grams *= 1000;
    return Number.isFinite(grams) && grams > 0 ? grams : null;
  };

  const parseExactGrams = (text, price) => {
    const value = clean(text);
    const combo = value.match(/(?:\(\s*)?(\d+(?:\.\d+)?)\s*\+\s*(\d+(?:\.\d+)?)(?:\s*\))?\s*(mg|kg|g|gm|gms|gram|grams)\b/i);
    let grams = combo ? unitToGrams(Number(combo[1]) + Number(combo[2]), combo[3]) : null;

    if (!grams) {
      const each = value.match(/(\d+)\s*(?:pcs?|pieces?|coins?|bars?)\b[^,\n()]*?(\d+(?:\.\d+)?)\s*(mg|gms?|grams?|gg|g)\s*(?:each|per\s*(?:pc|piece|coin|bar))/i);
      if (each) grams = Number(each[1]) * unitToGrams(each[2], each[3]);
    }

    if (!grams) {
      const ordinary = value.match(/\b(\d+(?:\.\d+)?)\s*(mg|kg|gg|g|gm|gms|gram|grams)\b/i);
      if (ordinary) grams = unitToGrams(ordinary[1], ordinary[2]);
    }

    if (grams >= 50 && Number(price) > 0 && price / grams < 3000 && price < 100000) grams /= 1000;
    return Number.isFinite(grams) && grams > 0 ? grams : null;
  };

  const purityFromLabel = (text) => {
    const value = clean(text);
    const karatMatch = value.match(/(?<![0-9a-z])(24|23|22|21|20|18|14|10|9)\s*-?\s*(?:k|kt|karat|carat)(?![a-z])/i);
    const purityMatch = value.match(/(?<!\d)(999\.99\+?|999\.9\+?|9999|999\+?|995|990|958|950|916|875|833|750|585|417|375)(?!\d)/i);
    const purity = purityMatch?.[1] || null;
    let karat = karatMatch ? Number(karatMatch[1]) : null;
    if (!karat && purity) {
      const numeric = Number.parseFloat(purity);
      if (numeric >= 990) karat = 24;
      else if (numeric >= 915 && numeric <= 917) karat = 22;
      else if (numeric >= 874 && numeric <= 876) karat = 21;
      else if (numeric >= 749 && numeric <= 751) karat = 18;
      else if (numeric >= 584 && numeric <= 586) karat = 14;
      else if (numeric >= 416 && numeric <= 418) karat = 10;
      else if (numeric >= 374 && numeric <= 376) karat = 9;
    }
    return { karat, purity };
  };

  const metalFromText = (text, karat) => {
    const value = clean(text).toLowerCase();
    const plated = /\b(?:gold[- ]?plated|gold tone|imitation|brass|copper)\b/.test(value);
    const silver = /\b(?:silver|sterling|925)\b/.test(value);
    const platinum = /\bplatinum\b/.test(value);
    const gold = Boolean(karat || /\bgold\b/.test(value));
    if (plated) return 'non-gold';
    if (platinum) return gold ? 'conflict' : 'platinum';
    if (silver) return gold ? 'conflict' : 'silver';
    if (gold) return 'gold';
    return null;
  };

  const normalizeListingProduct = (raw, source) => {
    const id = canonicalId(raw);
    const url = canonicalUrl(raw?.url);
    const brand = clean(raw?.brandName || raw?.fnlColorVariantData?.brandName);
    const rawName = clean(raw?.name);
    const name = clean(brand && rawName ? `${brand} ${rawName}` : rawName);
    const price = numberFrom(raw?.price);
    const wasPrice = numberFrom(raw?.wasPriceData);
    const offers = [raw?.offerPrice, raw?.promoDiscountedPrice, raw?.discountedPrice, raw?.cartOfferPrice]
      .map(numberFrom).filter((offer) => offer && price && offer < price);
    const evidenceText = sourceText(raw);
    const { karat, purity } = purityFromLabel(evidenceText);
    const grams = parseExactGrams(evidenceText, price);
    const metal = metalFromText(evidenceText, karat);

    return {
      id,
      url,
      name,
      rawName,
      brand,
      metal,
      grams,
      karat,
      purity,
      price,
      wasPrice,
      couponPrice: offers.length ? Math.min(...offers) : null,
      discountPercent: numberFrom(raw?.discountPercent),
      planningCategory: clean(raw?.fnlProductData?.planningCategory),
      brickName: clean(raw?.brickNameText || raw?.brickName),
      verticalName: clean(raw?.verticalNameText || raw?.verticalName),
      imageAlt: clean(Array.isArray(raw?.images) ? raw.images[0]?.altText : ''),
      sources: [source],
      evidence: {
        grams: grams ? 'listing-text' : null,
        karat: karat ? 'listing-text' : null,
        purity: purity ? 'listing-text' : null,
        metal: metal ? 'listing-text' : null
      }
    };
  };

  const mergeProduct = (target, incoming) => {
    if (!target) return incoming;
    const sources = [...new Set([...(target.sources || []), ...(incoming.sources || [])])];
    return {
      ...target,
      ...Object.fromEntries(Object.entries(incoming).filter(([, value]) => value !== null && value !== '' && value !== undefined)),
      sources,
      evidence: { ...target.evidence, ...Object.fromEntries(Object.entries(incoming.evidence || {}).filter(([, value]) => value)) }
    };
  };

  const categoryRoot = async (categoryId, pageNumber = 0, query = ':relevance', requestType = 'listing') => {
    const url = new URL(`/api/category/${categoryId}`, location.origin);
    url.searchParams.set('fields', 'SITE');
    url.searchParams.set('currentPage', String(pageNumber));
    url.searchParams.set('pageSize', String(CONFIG.pageSize));
    url.searchParams.set('format', 'json');
    url.searchParams.set('query', query);
    url.searchParams.set('gridColumns', '3');
    return fetchJson(url, requestType);
  };

  const products = new Map();
  const roots = new Map();

  for (const categoryId of CONFIG.categoryIds) {
    state.source = categoryId;
    const firstPage = await categoryRoot(categoryId);
    roots.set(categoryId, firstPage);
    const totalPages = Number(firstPage.pagination?.totalPages) || 1;
    state.totalPages = totalPages;

    for (let pageNumber = 0; pageNumber < totalPages; pageNumber += 1) {
      state.phase = 'listing';
      state.page = pageNumber;
      document.title = `AJIO ${categoryId} ${pageNumber + 1}/${totalPages} | ${products.size}`;
      let pageData = firstPage;
      if (pageNumber > 0) {
        try {
          pageData = await categoryRoot(categoryId, pageNumber);
        } catch (error) {
          state.failures.push({ phase: 'listing', categoryId, page: pageNumber, error: error?.message || String(error) });
          if (/HTTP (403|429)/.test(error?.message || '')) throw error;
          continue;
        }
      }
      for (const raw of pageData.products || []) {
        const observation = normalizeListingProduct(raw, `category:${categoryId}:page:${pageNumber}`);
        if (!observation.id || !observation.url || !observation.price) continue;
        state.observations.push(observation);
        products.set(observation.id, mergeProduct(products.get(observation.id), observation));
      }
      state.products = [...products.values()];
      await sleep(randomDelay());
    }
  }

  const parseFacetLabel = (label) => {
    const parsed = purityFromLabel(label);
    return { ...parsed, metal: /gold/i.test(label) ? 'gold' : /silver/i.test(label) ? 'silver' : /platinum/i.test(label) ? 'platinum' : null };
  };

  if (CONFIG.enablePurityFacetEnrichment) {
    state.phase = 'facet';
    for (const [categoryId, root] of roots) {
      const unresolved = new Set([...products.values()]
        .filter((product) => product.sources.some((source) => source.startsWith(`category:${categoryId}:`)))
        .filter((product) => !product.karat || !product.purity)
        .map((product) => product.id));
      const purityFacet = (root.facets || []).find((facet) => facet.code === 'verticalmetalpurity');
      if (!unresolved.size || !purityFacet) continue;

      for (const facetValue of purityFacet.values || []) {
        const query = new URL(facetValue.query?.url || '', location.origin).searchParams.get('q');
        if (!query) continue;
        const firstPage = await categoryRoot(categoryId, 0, query, 'facet');
        const totalPages = Number(firstPage.pagination?.totalPages) || 1;
        for (let pageNumber = 0; pageNumber < totalPages; pageNumber += 1) {
          const pageData = pageNumber === 0 ? firstPage : await categoryRoot(categoryId, pageNumber, query, 'facet');
          for (const raw of pageData.products || []) {
            const id = canonicalId(raw);
            if (!id || !unresolved.has(id) || !products.has(id)) continue;
            const parsed = parseFacetLabel(facetValue.name || facetValue.code);
            const product = products.get(id);
            if (!product.karat && parsed.karat) {
              product.karat = parsed.karat;
              product.evidence.karat = `facet:${facetValue.name}`;
            }
            if (!product.purity && parsed.purity) {
              product.purity = parsed.purity;
              product.evidence.purity = `facet:${facetValue.name}`;
            }
            unresolved.delete(id);
          }
          await sleep(randomDelay());
        }
        if (!unresolved.size) break;
      }
    }
  }

  const variantDetails = (payload, productId) => {
    const baseOptions = (payload.baseOptions || []).flatMap((group) => [
      ...(group.options || []),
      ...(group.selected ? [group.selected] : [])
    ]);
    const exactBase = baseOptions.find((option) => option.code === productId) || baseOptions[0];
    const rootCode = String(payload.code || productId).replace(/_[a-z0-9]+$/i, '');
    const variant = (payload.variantOptions || []).find((option) => rootCode.startsWith(String(option.code || '')))
      || (payload.variantOptions || [])[0];
    const baseValues = new Map((exactBase?.variantOptionQualifiers || [])
      .map((item) => [String(item.qualifier || item.name || '').toLowerCase(), item.value]));
    const variantValues = new Map((variant?.variantOptionQualifiers || [])
      .map((item) => [String(item.qualifier || item.name || '').toLowerCase(), item.value]));
    const unit = variantValues.get('uom') || 'g';
    const rawWeight = variantValues.get('metalweight')
      ?? variantValues.get('netweight')
      ?? variantValues.get('grossweight');
    const grams = unitToGrams(rawWeight, unit);
    const purityLabel = baseValues.get('metalpurity');
    const purity = purityFromLabel(purityLabel || payload.name || '');
    const featureText = (payload.featureData || []).flatMap((feature) => [
      feature?.name,
      feature?.value,
      ...(feature?.featureValues || []).map((item) => item?.value)
    ]).filter(Boolean).join(' ');
    const metal = metalFromText(`${payload.name || ''} ${featureText}`, purity.karat);
    return { name: clean(payload.name), grams, ...purity, metal };
  };

  if (CONFIG.enableDetailApiEnrichment) {
    state.phase = 'detail-api';
    const unresolved = [...products.values()]
      .filter((product) => !product.grams || !product.karat || !product.purity || !product.metal)
      .slice(0, CONFIG.maxDetailRequests);

    for (const product of unresolved) {
      document.title = `AJIO details ${state.requests.detail + 1}/${unresolved.length}`;
      try {
        const payload = await fetchJson(new URL(`/api/p/${encodeURIComponent(product.id)}`, location.origin), 'detail');
        const details = variantDetails(payload, product.id);
        if (!product.grams && details.grams) {
          product.grams = details.grams;
          product.evidence.grams = 'detail-api:variant-qualifier';
        }
        if (!product.karat && details.karat) {
          product.karat = details.karat;
          product.evidence.karat = 'detail-api:base-option';
        }
        if (!product.purity && details.purity) {
          product.purity = details.purity;
          product.evidence.purity = 'detail-api:base-option';
        }
        if ((!product.metal || product.metal === 'gold') && details.metal) {
          product.metal = details.metal;
          product.evidence.metal = 'detail-api:feature-data';
        }
        if ((!product.name || !product.grams) && details.name) {
          const improvedGrams = parseExactGrams(details.name, product.price);
          if (!product.grams && improvedGrams) {
            product.grams = improvedGrams;
            product.evidence.grams = 'detail-api:name';
          }
        }
      } catch (error) {
        state.failures.push({ phase: 'detail-api', id: product.id, error: error?.message || String(error) });
        if (/HTTP (403|429)/.test(error?.message || '')) break;
      }
      await sleep(CONFIG.detailDelayMs);
    }
  }

  state.phase = 'qualification';
  const finalProducts = [...products.values()].map((product) => {
    const reasons = [];
    if (product.metal !== 'gold') reasons.push(product.metal ? `metal:${product.metal}` : 'missing-metal');
    if (!product.grams) reasons.push('missing-weight');
    else if (product.grams < CONFIG.minimumGrams) reasons.push('under-minimum-weight');
    if (!product.karat) reasons.push('missing-karat');
    else if (product.karat < CONFIG.minimumKarat) reasons.push('under-minimum-karat');
    if (!product.price) reasons.push('missing-price');
    return {
      ...product,
      effectivePrice: product.couponPrice || product.price,
      pricePerGram: product.grams ? (product.couponPrice || product.price) / product.grams : null,
      qualifies: reasons.length === 0,
      reasons
    };
  });

  const qualifying = finalProducts.filter((product) => product.qualifies)
    .sort((left, right) => left.pricePerGram - right.pricePerGram);
  const incomplete = finalProducts.filter((product) => product.reasons.some((reason) => reason.startsWith('missing-')));
  const rejected = finalProducts.filter((product) => !product.qualifies && !incomplete.includes(product));

  state.status = state.failures.some((failure) => /HTTP (403|429)/.test(failure.error)) ? 'blocked'
    : state.failures.length ? 'partial'
      : 'complete';
  state.completedAt = new Date().toISOString();
  state.products = finalProducts;
  state.summary = {
    status: state.status,
    sources: CONFIG.categoryIds.length,
    observations: state.observations.length,
    unique: finalProducts.length,
    qualifying: qualifying.length,
    incomplete: incomplete.length,
    rejected: rejected.length,
    withCouponPrice: finalProducts.filter((product) => product.couponPrice).length,
    requests: state.requests,
    failures: state.failures.length
  };

  globalThis.ajioProducts = finalProducts;
  globalThis.ajioQualifying = qualifying;
  globalThis.ajioIncomplete = incomplete;
  globalThis.ajioRejected = rejected;
  globalThis.ajioStats = state.summary;

  const download = (value, filename) => {
    const blob = new Blob([JSON.stringify(value, null, 2)], { type: 'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  globalThis.ajioDownloadJSON = () => download(finalProducts, `ajio-listing-${Date.now()}.json`);
  globalThis.ajioDownloadQualifyingJSON = () => download(qualifying, `ajio-qualifying-${Date.now()}.json`);

  document.title = `AJIO ${state.status} | ${qualifying.length}/${finalProducts.length} qualify`;
  console.log('AJIO listing extraction complete', state.summary);
  console.table(qualifying.slice(0, 25).map(({ id, name, grams, karat, purity, effectivePrice, pricePerGram }) => ({
    id, name, grams, karat, purity, effectivePrice, pricePerGram: Math.round(pricePerGram)
  })));
  console.log('Results: ajioProducts, ajioQualifying, ajioIncomplete, ajioRejected, ajioStats');
  console.log('Downloads: ajioDownloadJSON(), ajioDownloadQualifyingJSON()');
  console.log('Original title:', originalTitle);

  return state.summary;
})().catch((error) => {
  if (globalThis.ajioExtractorState) {
    globalThis.ajioExtractorState.status = 'failed';
    globalThis.ajioExtractorState.error = error?.message || String(error);
  }
  document.title = 'AJIO extractor failed';
  console.error('AJIO listing extractor failed', error);
  throw error;
});
