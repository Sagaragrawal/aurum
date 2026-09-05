# Aurum V2 — Current Failure Analysis & Diagnosis

## 1. Why Stores Were Not Updating / Producing Zero Products
- **AjioNativeParser:** Had duplicate named parameter etailerId in BridgeRecord constructor.
- **MyntraNativeParser:** Had duplicate nested un parse() declarations, an illegal private modifier inside a function, and duplicate 	otalCount/productsArray/plaArray variables.
- **FlipkartNativeParser:** Severely mangled merge between Next.js __NEXT_DATA__ JSON parsing and HTML regex parsing inside outer loops, causing syntax errors, wrong variable scopes, and early empty returns.
- **NativeParallelRefreshEngine:** Duplicate aseUrl in Shopsy, duplicate esp and  in Myntra, broken loops, 4-argument call to ctivityRepository?.log(), and duplicate arguments in ProductEntity instantiation inside saveCandidates().

## 2. Why the Database Was Not Being Updated
Because the parser and engine files failed compilation, Gradle was never able to assemble a new APK with the latest native logic. Any installed test APK was running an older/incomplete build where parsing or saving failed.

## 3. Why Logs Were Missing or Incomplete
Bullion logging called ctivityRepository?.log(severity, sourceId, logStore, message) which expected 3 arguments (severity, store, message). In addition, store logging was either suppressed by parser crashes or swallowed due to unhandled exceptions.

## 4. Fix Action Plan
1. Fix AjioNativeParser.kt
2. Rewrite MyntraNativeParser.kt cleanly
3. Rewrite FlipkartNativeParser.kt cleanly (Next.js JSON first, regex fallback second)
4. Fix NativeParallelRefreshEngine.kt (clean Shopsy/Myntra paths, fix bullion log call, fix saveCandidates)
5. Verify build via compileDebugKotlin and 	estDebugUnitTest
6. Test end-to-end on Pixel 9 device via ADB.
