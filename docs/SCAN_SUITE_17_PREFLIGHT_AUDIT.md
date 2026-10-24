# Scan Suite 17-Feature Pre-flight Audit

**Status: BLOCKED — audit-only PR. No feature implementation is included.**

- Audited `main` at `7e5656fc721d2cafc7c43112a71391ee3a4b4567`.
- PR #4 (“Add persistent scanner history and scan management actions”) is merged.
- PR #4 quality run #386, signed release run #276, current-main quality run #402 and current-main signed release run #287 are green.
- The current release workflow ran `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `bundleRelease` and `assembleRelease`; quality workflow ran `testDebugUnitTest`, `lintDebug` and `connectedDebugAndroidTest`.
- Local clone/build could not run in this environment: GitHub hostname resolution failed and no standalone Gradle executable is installed. CI is the available baseline evidence.
- Repository issue tracking the full audit and blockers: https://github.com/navyask911/VibeSync-All-in-ONE-PDF-Suite/issues/6

## Requested feature audit

| # | Feature | Status | Existing file(s) | Action required |
|---:|---|---|---|---|
| 1 | Auto-edge detection & perspective correction | PARTIAL | `presentation/scanner/DocumentScannerManager.kt` | FULL ML Kit scanner and JPEG/PDF results exist; add manual 4-corner perspective fallback. |
| 2 | Batch scan mode | MISSING | `DocumentScannerManager.kt`, `MainActivity.kt`, scanner history | Accumulate pages, build a PDF, and add review/reorder/delete. |
| 3 | Shadow/glare removal + MagicColor/BW/Lighten/OriginalNoShadow | MISSING | Scanner/editor integration points; no `data/pdf/filters/` | Implement pure-Kotlin filters, background execution, cancellation/progress, synthetic tests. |
| 4 | Moiré reduction / Screen filter | MISSING | No filter engine | Implement 3×3 blur/unsharp luminance filter and deterministic tests. |
| 5 | Book mode / dual-page splitter | MISSING | Scanner integration points | Detect/adjust gutter, split sequentially, and test synthetic spreads. |
| 6 | ID Card Composer | MISSING | Dashboard, `MainActivity.kt`, `PdfBoxToolkitEngine.kt` | Compose exactly two images onto one A4 page. |
| 7 | ID Photo Maker | MISSING | Dashboard/tool route integration points | Presets, aspect-locked crop, high-DPI export, MediaStore save. |
| 8 | Clean Marks / erase notes filter | MISSING | No filter engine | Suppress colored ink and pencil while retaining dark printed text; test fixtures. |
| 9 | Annotations & markups | PARTIAL | `PdfEditorScreen.kt`, `EditorCanvasComponents.kt`, `EditorViewModel.kt`, annotation models, `PdfBoxToolkitEngine.kt` | Pen/highlighter/text/signature flattening exist; audit and finish eraser/markup interactions without regression. |
| 10 | E-signature deployment / manage signatures | PARTIAL | `SecurityVault.kt`, editor, `PdfBoxToolkitEngine.kt` | Encrypted signature storage and flattening exist; add list/rename/delete and drag/resize/rotate. |
| 11 | Local document importation | PARTIAL | `MainActivity.kt`, `DashboardScreen.kt`, `ToolkitScreens.kt`, `UriUtils.kt` | SAF OpenDocument/OpenDocumentTree exist; verify Photo Picker and editor Add-to-document flows; resolve storage access conflict. |
| 12 | Document encryption & app lock | PARTIAL | `ToolkitScreens.kt`, `PdfBoxToolkitEngine.kt`, `SecurityVault.kt` | PDF encrypt/decrypt and signature vault exist; add private document vault + biometric/device-credential gate if approved. |
| 13 | File compression tool | PARTIAL | `PdfBoxToolkitEngine.kt`, `PdfQuickActionScreen.kt`, `MainActivity.kt` | Existing compression load/saves PDFBox; add profile-driven page rasterization/rebuild, memory fallback and tests. |
| 14 | Watermark customization | PARTIAL | `PdfBoxToolkitEngine.kt`, `PdfQuickActionScreen.kt`, `MainActivity.kt` | Custom text parameter exists but quick action uses fixed localized text; add opacity/angle and shared profile routing. |
| 15 | On-device OCR + PDF/JPG/TXT export | PARTIAL | `MainActivity.kt`, viewer, scanner history | PDF/JPG sharing exists; add on-device ML Kit OCR and TXT export. |
| 16 | On-device translation scan mode | MISSING | No translation screen/package/dependency | Add offline-capable OCR→Translate, model consent/errors and no-payload-network tests. |
| 17 | Local wireless printing | MISSING | No print adapter, PrintManager integration or print tests | Add platform PrintDocumentAdapter and viewer/history actions. |
| — | Four-tier ResolutionProfile engine | MISSING | No `data/pdf/ResolutionProfile.kt` or selector | Add profiles, memory preflight/fallback, persistence, export routing and tests. |

## Blocking conflicts — resolve before feature implementation

1. **Locale resources:** Current tree contains 40 `strings.xml` files: base plus 39 translated `values-*` directories. CI currently audits 16 buckets (base plus 15 locales), but the request says “all 16 existing locale files.” Confirm whether new strings must be added to all 39 existing translations (preferred for parity) or whether some locale directories are intentionally out of scope. Do not delete or silently omit existing languages.
2. **Storage-access policy:** `AndroidManifest.xml` declares `MANAGE_EXTERNAL_STORAGE` for Android 11+ device-wide discovery and `READ_EXTERNAL_STORAGE` (maxSdk 32) for legacy device discovery; `MainActivity.kt` requests the legacy permission on older versions. The new non-goal forbids `READ_EXTERNAL_STORAGE` and requires SAF/Photo Picker, while existing behavior includes device-wide PDF discovery. Confirm whether to remove broad access and replace discovery with SAF-selected folders/documents (behavior change), or preserve current legacy discovery. Do not guess.
3. **Ordering:** The table is posted in issue #6 before branch creation because GitHub requires a branch diff before a PR can exist. This branch contains only this audit document and is intended to remain a draft until the blockers are resolved.

## Dependency review

- Existing: ML Kit Document Scanner, PDFBox-Android, Jetpack Security `MasterKey.AES256_GCM` / encrypted preferences.
- Not found: `play-services-mlkit-text-recognition`, `com.google.mlkit:translate`, `androidx.biometric:biometric`.
- Proposed only after approval: on-device ML Kit text recognition and Translate; model download is the only permitted network event for Translate. Add Biometric only if document-vault behavior is approved, with licensing/purpose documented in the PR.
