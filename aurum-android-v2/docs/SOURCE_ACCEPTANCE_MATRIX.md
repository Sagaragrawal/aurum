# AURUM V2 SOURCE ACCEPTANCE MATRIX

This document records end-to-end acceptance status for every retail and bullion source in Aurum V2.

---

## Acceptance Verification Results

| Source ID | Display Name | Transport | Request | Response | Parser | Validation | DB Write | UI Display | Acceptance Status |
| :--- | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `ajio` | AJIO | Cronet (HTTP/2) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **ACCEPTED** |
| `amazon` | Amazon India | Cronet (HTTP/2) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **ACCEPTED** |
| `flipkart` | Flipkart | Cronet (HTTP/1.1) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **ACCEPTED** |
| `shopsy` | Shopsy | Cronet (HTTP/2) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **ACCEPTED** |
| `myntra` | Myntra | Cronet (HTTP/2) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **ACCEPTED** |
| `tan` | Tanishq | Cronet (HTTP/2) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **ACCEPTED** |
| `malabar` | Malabar Gold | Cronet (HTTP/2) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **ACCEPTED** |
| `kalyan` | Kalyan Jewellers | Cronet (HTTP/1.1) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **ACCEPTED** |
| `mmtc` | MMTC-PAMP | Cronet (HTTP/2) | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **ACCEPTED** |

---

## Audit Verification Summary
* **Unit Tests (`.\gradlew.bat test`)**: **PASS** (100% of test suite passing).
* **Debug APK Build (`.\gradlew.bat assembleDebug`)**: **PASS** (Zero compilation or packaging errors).
* **Device Verification**: Installed and launched on Android device (`192.168.88.3:37997`). Tapped "REFRESH NATIVE", verified real-time log activity stream and UI state updates across all sources.
