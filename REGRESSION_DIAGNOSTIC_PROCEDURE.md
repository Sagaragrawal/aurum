# AJIO Regression Diagnostic Test Procedure

## Status: APK Deployed with Enhanced Logging

**APK Version**: Debug build with diagnostic instrumentation
- **Change 1**: `onPageReady` callback now logs every step
- **Change 2**: Readiness `LaunchedEffect` now logs entry condition
- **Logcat**: Currently running in background

## Step-by-Step Test Procedure

### Phase 1: Prepare for Testing

1. **Device Setup**
   - Ensure device is connected via ADB
   - Ensure airplane mode is OFF (normal network available)
   - Ensure location is set to or near Bangalore/Bengaluru  
   - App is installed with fresh data (recommended: clear app data first)

2. **Logcat Preparation**
   - Logcat is currently running in background
   - All logs are being captured to `/tmp/ajio-diagnostic-test.log`
   - You can monitor in parallel or wait until test completes

### Phase 2: Trigger AJIO Collection

1. **Open the App**
   - Launch Aurum on the device
   - Navigate to Watchlist or Products view

2. **Start AJIO Refresh Only**
   - Tap Refresh button (or use Settings if needed)
   - Select only AJIO from available stores
   - Make sure Browser is visible (important for log collection)

3. **Observe Page Load**
   - Confirm the AJIO Boys page loads and shows ~8 products
   - Note the time this happens
   - DO NOT cancel or interrupt

4. **Wait for Result or Timeout**
   - Collect full logs from page visible until:
     - Either: readiness starts + master executes + bridge received (SUCCESS)
     - Or: 240-second timeout occurs (FAILURE)
   - Note exact times and visible outcomes

### Phase 3: Collect Diagnostic Output

#### Option A: Live Monitoring (via ADB)
```bash
# In another terminal, monitor logs in real-time:
export HTTP_PROXY=http://proxy.aexp.com:8080 HTTPS_PROXY=http://proxy.aexp.com:8080 NO_PROXY=.aexp.com,localhost
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
$ADB logcat | grep -E 'PAGE_READY|READINESS_EFFECT|readiness|bridge|AJIO'
```

#### Option B: File Capture (recommended)
```bash
# Stop background logcat:
pkill -f 'adb logcat'

# Save the captured output:
cp /tmp/ajio-diagnostic-test.log ~/Desktop/ajio-regression-logs.txt

# Search for key diagnostic lines:
grep -E 'PAGE_READY|READINESS_EFFECT|readiness|bridge|NAV_REQUEST|LOAD_URL_CALLED' ~/Desktop/ajio-regression-logs.txt
```

### Phase 4: Analyze Diagnostic Output

#### Expected Log Sequence (if WORKING)
```
...+XXms NAV_REQUEST /c/830306009?...
...+XXms LOAD_URL_CALLED
...+XXms onPageStarted
...+XXms onPageCommitVisible
...+XXms onPageFinished
...+XXms PAGE_READY_CALLBACK_INVOKED loadedUrl=...
...+XXms PAGE_READY_URL_COMPARISON requested=... loaded=...
...+XXms PAGE_READY_SAMEPAGE_RESULT=true
...+XXms Main frame ready
...+XXms PAGE_READY_WEBVIEW_ASSIGNED readyWebView=true
...+XXms READINESS_EFFECT_ENTER view=true script=true scriptExecuted=false finished=false
...+XXms readiness started
...+XXms readiness passed
...+XXms MASTER_SUBMIT
...+XXms bridge received
```

#### If FAILING, Look For:

**Case 1: onPageReady Never Called**
- Log shows: `onPageFinished` but NO `PAGE_READY_CALLBACK_INVOKED`
- Diagnosis: RetailerWebView callback not invoked (possible exception?)
- **Root Cause**: Issue in WebView creation or callback registration

**Case 2: URL Mismatch**
- Log shows: `PAGE_READY_CALLBACK_INVOKED` followed by:
- `PAGE_READY_URL_COMPARISON requested=/c/830306009?param1=value1 loaded=/c/830306009?param1=value1&extra=param`
- `PAGE_READY_SAMEPAGE_RESULT=false`
- **Root Cause**: samePage() one-directional check OR URL encoding difference

**Case 3: readyWebView Assignment Fails**
- Log shows: `PAGE_READY_SAMEPAGE_RESULT=true` but NO `PAGE_READY_WEBVIEW_ASSIGNED`
- Diagnosis: Assignment executed but silent failure
- **Root Cause**: Possible exception during state assignment OR state mutation issue

**Case 4: Readiness Effect Doesn't Enter**
- Log shows: `PAGE_READY_WEBVIEW_ASSIGNED readyWebView=true` but NO `READINESS_EFFECT_ENTER`
- Diagnosis: Compose effect not triggered even though readyWebView is set
- **Root Cause**: Possible issue with LaunchedEffect key or Compose state mutation

## Critical Log Search Commands

```bash
# Search for the exact failure point:
grep -n 'PAGE_READY\|READINESS_EFFECT' /tmp/ajio-diagnostic-test.log | head -20

# Find any exceptions:
grep -i 'exception\|error' /tmp/ajio-diagnostic-test.log | head -20

# Find the callback sequence:
grep -E 'onPageStarted|onPageCommitVisible|onPageFinished|PAGE_READY' /tmp/ajio-diagnostic-test.log

# Timeline of events:
grep -oE '^\[.*\] +\[[A-Za-z]+\] .*PAGE_READY|readiness|bridge' /tmp/ajio-diagnostic-test.log
```

## Success Criteria

**PASS**: All of these appear in order:
1. ✓ `onPageFinished`
2. ✓ `PAGE_READY_CALLBACK_INVOKED`
3. ✓ `PAGE_READY_SAMEPAGE_RESULT=true`
4. ✓ `PAGE_READY_WEBVIEW_ASSIGNED readyWebView=true`
5. ✓ `READINESS_EFFECT_ENTER view=true`
6. ✓ `readiness started`
7. ✓ `readiness passed`
8. ✓ Bridge received

**FAIL**: Any of these are missing or false

## Next Action

After collecting logs, analyze using the diagnostic commands above and report:

```
Page loads? YES / NO
onPageReady invoked? YES / NO
samePage result: TRUE / FALSE
readyWebView assigned? YES / NO
Readiness effect entered? YES / NO
Last successful log line: [paste here]
First failed/missing log line: [paste here]
```

This will pinpoint the exact regression location.
