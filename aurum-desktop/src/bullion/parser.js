/**
 * Bullion price parser
 * Extracts 24K gold prices from various source websites
 */

import { numberFromText } from '../common/utils.js';
import { BULLION_SOURCES } from './sources.js';

/**
 * Parse gold price (24K) from HTML/text using source-specific patterns
 */
export function parseBullionPrice(text, sourceId) {
  // Support JSON API responses directly
  if (typeof text === 'string' && (text.trim().startsWith('{') || text.trim().startsWith('['))) {
    try {
      const data = JSON.parse(text);
      if (sourceId === 'malabar') {
        const items = data?.data?.getMetalRate?.items || [];
        const item24 = items.find((i) => String(i.purity).toLowerCase() === '24k' || i.purity === '99.99' || i.purity === '999');
        if (item24 && Number.isFinite(Number(item24.rate))) return Number(item24.rate);
      }
      if (sourceId === 'mmtc') {
        const p24 = Number(data.preTaxAmount || data.totalAmount);
        if (Number.isFinite(p24) && p24 > 0) return p24;
      }
    } catch {}
  }

  // Source-specific regex patterns for 24K gold price extraction
  const pattern = sourceId === 'malabar'
    ? /([\d,]+(?:\.\d+)?)\s*INR\s*\/\s*gms?[\s\S]{0,160}24k\s*\(999\)/i
    : sourceId === 'mmtc'
      ? /24k\s*Gold\s*Rate\s*\(Exc\.\s*GST\)[\s\S]*?(?:1\s*gm|1gm)\s*₹\s*([\d,]+(?:\.\d+)?)/i
      : sourceId === 'kalyan'
        ? /"karat_24\(999\)"\s*:\s*\{[\s\S]*?"price_per_gram"\s*:\s*(\d+(?:\.\d+)?)/i
        : /Gold\s*Rate\s*History\s*24\s*Karat[\s\S]*?Date\s+Rate\s+\d{1,2}-\d{1,2}-\d{4}\s*₹\s*([\d,]+)/i;

  const match = text.match(pattern);
  if (match) return Number(match[1].replaceAll(',', ''));

  // Source-specific fallback parsing
  if (sourceId === 'tan') {
    return parseTanishqPrice(text);
  }
  if (sourceId === 'mmtc') {
    return parseMMTCPrice(text);
  }
  if (sourceId === 'kalyan') {
    return parseKalyanPrice(text);
  }
  if (sourceId === 'malabar') {
    return parseMalabarPrice(text);
  }

  // Generic fallback: find any ₹ price > 1000
  const matches = [...text.matchAll(/₹\s*([\d,]+)/g)].map((item) => Number(item[1].replaceAll(',', ''))).filter((value) => value > 1000);
  return matches[0] || null;
}

function parse22KPrice(text, sourceId) {
  if (typeof text === 'string' && (text.trim().startsWith('{') || text.trim().startsWith('['))) {
    try {
      const data = JSON.parse(text);
      if (sourceId === 'malabar') {
        const items = data?.data?.getMetalRate?.items || [];
        const item22 = items.find((i) => String(i.purity).toLowerCase() === '22k' || i.purity === '916');
        if (item22 && Number.isFinite(Number(item22.rate))) return Number(item22.rate);
      }
    } catch {}
  }

  const bySource = sourceId === 'tan'
    ? /22\s*(?:Karat|Kt|K)[\s\S]{0,220}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i
    : sourceId === 'malabar'
    ? /([\d,]+(?:\.\d+)?)\s*INR\s*\/\s*gms?[\s\S]{0,200}22\s*k\s*\(\s*916\s*\)/i
    : sourceId === 'mmtc'
      ? /22k\s*Gold\s*Rate\s*\(Exc\.\s*GST\)[\s\S]{0,260}(?:1\s*gm|1gm)?\s*(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i
      : sourceId === 'kalyan'
        ? /"karat_22\(916\)"\s*:\s*\{[\s\S]*?"price_per_gram"\s*:\s*(\d+(?:\.\d+)?)/i
        : /22\s*(?:k|kt|karat)[\s\S]{0,220}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i;

  // MMTC must use its explicit 22K price pattern. Generic proximity patterns can
  // accidentally capture the preceding 24K price or the fineness token `916`.
  const match = text.match(bySource)
    || (sourceId !== 'mmtc' ? text.match(/(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)[\s\S]{0,120}22\s*(?:k|kt|karat)/i) : null)
    || (sourceId !== 'mmtc' ? text.match(/\b22\s*(?:k|kt|karat)\b[\s\S]{0,160}\b([\d,]+(?:\.\d+)?)\b/i) : null);
  if (!match) return null;
  const price = Number(String(match[1] || '').replaceAll(',', ''));
  return Number.isFinite(price) && price > 0 ? price : null;
}

export function parseBullionRates(text, sourceId) {
  const price24 = parseBullionPrice(text, sourceId);
  const raw22 = parse22KPrice(text, sourceId);
  // A 22K rupee/gram quote must be in the same order of magnitude as 24K.
  // This specifically prevents purity tokens such as `916` from being mistaken for ₹916/g.
  const plausible22 = Number.isFinite(raw22) && raw22 > 1000
    && (!Number.isFinite(price24) || price24 <= 0 || (raw22 >= price24 * 0.72 && raw22 <= price24 * 1.02));
  return {
    price24: Number.isFinite(price24) && price24 > 0 ? price24 : null,
    price22: plausible22 ? raw22 : null
  };
}

function parseTanishqPrice(text) {
  // Try to extract from data attributes first
  const goldRateValues = text.match(/id=["']goldRateValues["'][^>]*\bvalue=["']([^"']+)["']/i)?.[1];
  if (goldRateValues) {
    try {
      const payload = JSON.parse(goldRateValues.replaceAll('&quot;', '"'));
      const price = Number(payload?.GetDailyMetalRates?.[0]?.GoldRate24KT);
      if (Number.isFinite(price) && price > 0) return price;
    } catch {}
  }

  // Try data attribute
  const tanDataAttribute = text.match(/data-goldrate24kt\s*=\s*["']\s*([\d,]+(?:\.\d+)?)/i);
  if (tanDataAttribute) return Number(tanDataAttribute[1].replaceAll(',', ''));

  // Try text-based patterns
  const tanFallback = text.match(/24\s*Karat[\s\S]{0,180}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i);
  if (tanFallback) return Number(tanFallback[1].replaceAll(',', ''));

  const tanHistory = text.match(/24\s*Karat[\s\S]{0,800}?\b\d{1,2}-\d{1,2}-\d{4}\s*₹\s*([\d,]+(?:\.\d+)?)/i);
  return tanHistory ? Number(tanHistory[1].replaceAll(',', '')) : null;
}

function parseMMTCPrice(text) {
  const mmtcFallback = text.match(/24k\s*Gold\s*Rate\s*\(Exc\.\s*GST\)[\s\S]{0,280}(?:1\s*gm|1gm)\s*(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i)
    || text.match(/24k\s*Gold\s*Rate\s*Today[\s\S]{0,240}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i)
    || text.match(/24k[\s\S]{0,180}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i);
  return mmtcFallback ? Number(mmtcFallback[1].replaceAll(',', '')) : null;
}

function parseKalyanPrice(text) {
  const perGram = text.match(/Gold\s*Rate\s*in\s*India\s*for\s*1\s*gram\s*is\s*(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i);
  if (perGram) return Number(perGram[1].replaceAll(',', ''));

  const tenGram = text.match(/10g\s*of\s*24K\s*Gold[\s\S]{0,120}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i);
  if (tenGram) return Number(tenGram[1].replaceAll(',', '')) / 10;

  const row24k = text.match(/\b24k\b[\s\S]{0,120}(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)/i);
  if (row24k) {
    const value = Number(row24k[1].replaceAll(',', ''));
    return value > 100000 ? value / 10 : value;
  }

  return null;
}

function parseMalabarPrice(text) {
  const allRates = [...text.matchAll(/([\d,]+(?:\.\d+)?)\s*INR\s*\/\s*gms?/gi)]
    .map((match) => Number(match[1].replaceAll(',', '')))
    .filter((value) => Number.isFinite(value) && value > 1000);
  if (allRates.length) return Math.max(...allRates);
  return null;
}
