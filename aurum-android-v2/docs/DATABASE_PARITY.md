# Database Parity Matrix: Legacy Aurum vs V2

| Entity / Table | Legacy | V2 | Parity Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| products | 28 columns, indices on store,retailerId, canonicalUrl, status | Exactly identical 28 columns, identical indices | **100% PARITY** | Room DB v5 preserved. All fields (weights, karats, blink deals, microcoins, location) intact. |
| product_price_history | id, productId (FK cascade), price, couponPrice, checkedAt | Exactly identical schema and indices | **100% PARITY** | Retains price fluctuation logs. |
| aw_bridge_payloads | id, store, eceivedAt, json | Exactly identical schema | **100% PARITY** | Preserved for auditing/debugging raw network payloads. |
| ullion_sources | id, source, label, url, price24, price22, price22Derived, status, 	ransport, timestamps, error | Exactly identical schema | **100% PARITY** | All 4 sources (malabar, mmtc, kalyan, 	an) supported. |
| ullion_history | id, sourceId, price24, price22, price22Derived, etchedAt | Exactly identical schema | **100% PARITY** | Powering BullionTrendCard. |
| efresh_activity_logs | id, 	imestamp, severity, store, message | Exactly identical schema | **100% PARITY** | Backed by RefreshActivityRepository capped at 2,000 logs. |

## DAO Query Parity
All 28 DAO queries from AurumDao in legacy are fully present in V2 without modification or deletion.
