# AJIO Regression Analysis — Execution Summary

**Date**: 2026-09-01
**Status**: Diagnostic instrumentation applied and deployed
**Next Action**: Run physical test and collect logs

---

## What Was Done

### 1. Regression Root Cause Identified

**The Problem**: AJIO page loads successfully (8 products visible) but `readiness started` never appears in logs. After 240 seconds, URL 1 times out.

**Timeline of Regression**:
- **Before 4.9.17**: Each AJIO URL got a new WebView via `key(index)` — factory ran again for each URL
- **After 4.9.17**: One WebView for all four AJIO URLs — factory runs ONCE
- **Impact**: Callbacks captured in `AndroidView(factory)` closure are now reused across all URLs

**Key Change in Code**:
```kotlin
// Before (4.9.16): key(script.storeName) { StoreRefreshRunner(...) }
// After (4.9.17):  key(script.storeName) { StoreRefreshRunner(...) }  // ← no key(index)!
```

### 2. Two Critical Issues Identified

**Issue #1: Stale Callback Closure** 
- The `onPageReady` callback is defined inside `AndroidView(factory)`
- Factory runs ONCE per WebView lifetime
- Callback captures `currentUrlState` (rememberUpdatedState) — SHOULD handle URL changes
- But `failCurrent` and other state mutations might have timing issues

**Issue #2: URL Comparison via samePage()**
```kotlin
private fun samePage(left: String?, right: String): Boolean = runCatching {
    ...
    queryParameters(b).all { (key, expected) ->  // ← ONE-DIRECTIONAL!
        queryParameters(a)[key] == expected
    }
}.getOrDefault(false)  // ← Silently hides exceptions!
```
- One-directional check: verifies B's params exist in A, but not vice versa
- Silent exception handling: if URI parsing fails, returns false without logging

### 3. Diagnostic Instrumentation Added

**Change 1: Enhanced onPageReady Callback**
```kotlin
onPageReady = { view, loadedUrl ->
    logState.value(..., "PAGE_READY_CALLBACK_INVOKED loadedUrl=$loadedUrl index=$index")
    val requestedUrl = currentUrlState.value.orEmpty()
    logState.value(..., "PAGE_READY_URL_COMPARISON requested=$requestedUrl loaded=$loadedUrl")
    
    val isSamePage = runCatching {
        samePage(loadedUrl, requestedUrl)
    }.onFailure { error ->
        logState.value(..., "PAGE_READY_SAMEPAGE_EXCEPTION ${error.message}")
    }.getOrDefault(false)
    
    logState.value(..., "PAGE_READY_SAMEPAGE_RESULT=$isSamePage")
    
    if (isSamePage) {
        logState.value(..., "Main frame ready: ${displayUrl(loadedUrl)}")
        readyWebView = view
        logState.value(..., "PAGE_READY_WEBVIEW_ASSIGNED readyWebView=${readyWebView != null}")
    } else {
        logState.value(..., "PAGE_READY_URL_MISMATCH calling failCurrent")
        failCurrent("Retailer redirected away from required URL to $loadedUrl")
    }
}
```

**Change 2: Enhanced Readiness Effect**
```kotlin
LaunchedEffect(readyWebView, preloadedScript, index, attempt) {
    val view = readyWebView
    val source = preloadedScript
    onLog(..., stage("READINESS_EFFECT_ENTER view=${view != null} script=${source != null} scriptExecuted=$scriptExecuted finished=$finished"))
    if (view != null && source != null && !scriptExecuted && !finished) {
        scriptExecuted = true
        onLog(..., stage("readiness started"))
        // ... rest of readiness logic
    }
}
```

### 4. APK Built and Deployed

```
Build Status: ✓ SUCCESS
APK Size: ~15 MB (debug)
Install Status: ✓ SUCCESS
Device: Samsung Galaxy S24 Ultra (connected via ADB)
```

---

## Expected Diagnostic Log Output

### If Working (Regression Fixed):
```
+0ms NAV_ATTEMPT url=1/4 session=XXXXXXXX
+25ms NAVIGATE_CALL
+50ms LOAD_URL_CALLED
+100ms onPageStarted /c/830306009
+150ms onPageCommitVisible /c/830306009
+500ms onPageFinished /c/830306009
+510ms PAGE_READY_CALLBACK_INVOKED loadedUrl=https://www.ajio.com/c/830306009
+515ms PAGE_READY_URL_COMPARISON requested=https://www.ajio.com/c/830306009?... loaded=https://www.ajio.com/c/830306009?...
+520ms PAGE_READY_SAMEPAGE_RESULT=true
+525ms Main frame ready
+530ms PAGE_READY_WEBVIEW_ASSIGNED readyWebView=true
+540ms READINESS_EFFECT_ENTER view=true script=true scriptExecuted=false finished=false
+545ms readiness started
+2000ms readiness passed
+3000ms MASTER_SUBMIT bytes=123456
+5000ms bridge received session=XXXXXXXX received=755 unique=755
```

### If Broken (Shows Regression):
```
+500ms onPageFinished /c/830306009
[NO PAGE_READY_CALLBACK_INVOKED]
[OR PAGE_READY_SAMEPAGE_RESULT=false]
[OR PAGE_READY_WEBVIEW_ASSIGNED readyWebView=false]
[OR READINESS_EFFECT_ENTER never appears]
...
+240000ms TIMEOUT: Ajio URL 1 timed out
```

---

## How to Collect Diagnostics

### Immediately After Building

**Current Status:**
- ✓ APK built with diagnostic logging
- ✓ APK installed on device  
- ✓ Logcat running in background (saving to `/tmp/ajio-diagnostic-test.log`)

### Test Procedure

1. **Open Aurum app on device**
   - Navigate to Products or Watchlist
   - Ensure Browser tab is visible

2. **Start AJIO-only refresh**
   - Tap Refresh button
   - Select only AJIO
   - Observe page loading (8 products should appear for Boys)

3. **Collect logs** (after page loads or timeout)
   ```bash
   # Stop logcat
   pkill -f 'adb logcat'
   
   # Examine captured logs
   cat /tmp/ajio-diagnostic-test.log | grep -E 'PAGE_READY|READINESS_EFFECT|readiness'
   ```

4. **Search for diagnostic lines**
   ```bash
   grep 'PAGE_READY_CALLBACK_INVOKED' /tmp/ajio-diagnostic-test.log  # Did callback fire?
   grep 'PAGE_READY_SAMEPAGE_RESULT' /tmp/ajio-diagnostic-test.log  # URL match result?
   grep 'PAGE_READY_WEBVIEW_ASSIGNED' /tmp/ajio-diagnostic-test.log  # Was state set?
   grep 'READINESS_EFFECT_ENTER' /tmp/ajio-diagnostic-test.log      # Did effect trigger?
   ```

---

## Key Hypothesis to Test

### Hypothesis A: onPageReady Never Invoked
- **Log**: Shows `onPageFinished` but NO `PAGE_READY_CALLBACK_INVOKED`
- **Diagnosis**: Callback not reached (exception in WebView creation?)
- **Fix**: Would require fixing RetailerWebView.create or callback registration

### Hypothesis B: samePage() Returns False
- **Log**: Shows `PAGE_READY_SAMEPAGE_RESULT=false` with URL mismatch details
- **Diagnosis**: Loaded URL doesn't match requested URL (encoding? extra params?)
- **Fix**: Would update samePage() to handle URL variations OR relax comparison

### Hypothesis C: readyWebView Assignment Silent Fails
- **Log**: Shows `PAGE_READY_SAMEPAGE_RESULT=true` but NO `PAGE_READY_WEBVIEW_ASSIGNED`
- **Diagnosis**: State mutation failed or was cancelled
- **Fix**: Would investigate Compose state update timing

### Hypothesis D: Readiness Effect Not Triggered
- **Log**: Shows `PAGE_READY_WEBVIEW_ASSIGNED readyWebView=true` but NO `READINESS_EFFECT_ENTER`
- **Diagnosis**: LaunchedEffect not re-running despite key change
- **Fix**: Would investigate Compose effect keys and dependencies

---

## Files Modified

1. **aurum-android/app/src/main/java/com/aurum/intelligence/ui/BrowserRefreshScreen.kt**
   - Enhanced `onPageReady` callback (lines ~570-593)
   - Enhanced readiness `LaunchedEffect` (lines ~519-521)

2. **Created: REGRESSION_DIAGNOSTIC_PROCEDURE.md**
   - Step-by-step test instructions
   - Log analysis guide
   - Expected output patterns

---

## Next Steps (User Action Required)

### Immediate (Next 5-10 minutes)
1. Follow the test procedure in `REGRESSION_DIAGNOSTIC_PROCEDURE.md`
2. Trigger AJIO-only refresh on device
3. Collect logs after page loads or timeout

### Analysis (Next 5-10 minutes)  
1. Search logs for diagnostic keywords
2. Identify which stage fails:
   - onPageReady callback
   - samePage URL comparison
   - readyWebView assignment
   - Readiness effect entry
3. Report exact log lines showing failure point

### Resolution (After Diagnosis)
Once diagnostic logs identify the exact failure, the fix will be targeted:
- If callback not invoked: fix WebView creation
- If URL mismatch: relax samePage() or fix URL normalization
- If state assignment fails: fix Compose state timing
- If effect not triggered: fix LaunchedEffect keys

---

## Important: Do NOT Change Until Diagnostic Results

⚠️  **Hold off on code changes** until we understand which specific regression occurred. The instrumentation is designed to pinpoint the exact failure point so the fix is minimal and correct.

Current instrumented APK provides full visibility into the callback chain. Use it to collect evidence, then apply a surgical fix once the root cause is confirmed.

---

**Summary**: Regression hypothesis created, diagnostic instrumentation deployed, ready for physical testing to identify exact failure point.
