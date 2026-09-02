# Aurum Android v4.9.17 - Chromium Tile Memory Crisis

## Screenshot Captured
- Time: Sep 1, 2026 20:41 UTC
- Device: Samsung S24 Ultra (SM_S908E)
- Android: 16 (API 36)
- App Version: 4.9.17 (build 40917)
- Display: 1440x3088 @ 600dpi, scaled to 640dpi

## Issue Summary
App is **completely frozen with rendering stalled**. Chromium tile memory exhausted causing:
- 3,906 tile memory limit warnings in ~2 min
- WebView unable to render new content
- Tab navigation stuck (1-2s delays)
- Layout corruption on screen transitions
- Frame rate throttled to -4.0 (unsustainable rendering)

## Memory Breakdown
```
Total Process Memory:  487 MB PSS / 641 MB RSS
  Native Heap:         167 MB (limit: 201 MB) - STRESSED
  Java Heap:            33 MB (limit: 46 MB)
  System:               37 MB
  Swap PSS:            158 MB (system memory pressure!)
```

## Chromium Rendering Error (Repeating Every 1-5 seconds)
```
ERROR:cc/tiles/tile_manager.cc:1014: WARNING: tile memory limits exceeded, some content may not draw
```

**Root Cause:** WebView trying to render too many tiles for the viewport.

## WebView Diagnostics
- Process: PID 23923 (chromium/webview subprocess)
- Frame Rate: -4.0 (auto-clamped by system)
- Tile Regeneration: Continuous (no stable state)
- Draw Calls: Per-frame (not skipped)

## Probable Root Causes (in priority order)
1. **DOM too large/unoptimized** — massive number of list items or deeply nested views
2. **No viewport scaling** — rendering at full 1440x3088 in tile memory pool
3. **View recycling broken** — holding references to off-screen views in memory
4. **CSS/image memory leak** — heavy animations, large uncompressed assets
5. **Chromium cache not configurable** — no tile pool limits set in WebView initialization

## Files to Investigate
- `aurum-android/app/src/main/java/com/aurum/intelligence/ui/` (WebView host)
- `aurum-desktop/public/app.js` (DOM/CSS rendering logic)
- `aurum-desktop/src/app/` (frontend framework)
- `aurum-android/app/build.gradle.kts` (WebView/Chromium versioning)

## Immediate Actions
1. **Verify WebView settings** — check device API calls, viewport configuration
2. **Profile with DevTools** — enable Chrome remote debugging
3. **Reduce DOM size** — implement virtual scrolling or pagination
4. **Check image assets** — look for large uncompressed or unoptimized images
5. **Monitor tile memory** — add logging to Chromium rendering path

## Files Captured for Analysis
- Screenshot: `/tmp/aurum-current.png` (240 KB)
- Full Logcat: `/tmp/aurum-diag/full-logcat-*.log` (19 MB)
- Summary: This file

---
Generated: 2026-09-01 20:41 UTC
