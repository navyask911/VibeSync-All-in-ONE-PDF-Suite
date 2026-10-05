# VibeSync All in ONE PDF Suite 📄📱

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Jetpack%20Compose-green.svg)](https://developer.android.com/compose)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36%20(Android%2016)-blue.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-24%20(Android%207.0)-orange.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-red.svg)](LICENSE)
[![Security](https://img.shields.io/badge/Security-AES--256--GCM%20MasterKey-emerald.svg)](PRIVACY_POLICY.md)

**VibeSync All in ONE PDF Suite** is a high-performance, enterprise-grade, **100% on-device** PDF productivity suite for Android. Engineered with **zero-cloud-transit** architecture, all operations—including multi-page continuous rendering, vector annotation, digital signature vaulting, ML Kit document scanning, PDF splitting, merging, watermarking, and AES-256 encryption—execute locally without exposing sensitive user documents to external networks.

---

## 🚀 Key Architectural Features

1. **Continuous 72-DPI Vector Rendering Engine:**
   - Powered by native `android.graphics.pdf.PdfRenderer` coordinated with an OOM-safe, LRU tile bitmap cache (`BitmapCache`).
   - Smooth 60/120 FPS continuous vertical scrolling and gesture pinch-to-zoom (100% to 400%).

2. **Hardware-Backed E-Sign Security Vault (`SecurityVault.kt`):**
   - Utilizes Android Jetpack Security (`androidx.security.crypto.MasterKey`).
   - Digital signatures are encrypted with **AES-256-GCM** inside the Android Keystore / Trusted Execution Environment (TEE).
   - Zero plaintext storage or unencrypted serialization.

3. **On-Device Machine Learning Scanner:**
   - Integrated with Google Play Services ML Kit Document Scanner (`play-services-mlkit-document-scanner`).
   - Performs automatic boundary detection, perspective warping, shadow removal, and direct PDF generation locally on the device NPU/GPU.

4. **Apache PDFBox-Android Clean-Room Toolkit (`PdfBoxToolkitEngine.kt`):**
   - **Split Document:** Arbitrary page intervals (e.g. `1-3, 5, 8-12`) with automatic boundary clamping and out-of-order range safety.
   - **Merge Documents:** Multi-document concatenation preserving mixed page rotations (90°, 180°, 270°).
   - **AES-128/256 Encryption:** Standard protection policies enforcing granular access permissions (printing, content copying, modification).
   - **72-DPI Vector Flattening:** Exact mathematical transformation baking ink strokes, highlighters, and signatures directly onto PDF content streams.

5. **Automated Device PDF Discovery (`DevicePdfScanner.kt`):**
   - Queries `MediaStore.Files` asynchronously on `Dispatchers.IO` to index local PDF documents.
   - Transparent rationale dialog explaining local indexing before requesting permissions.

6. **Monetization & AdMob Integration (`AdMobManager.kt`):**
   - Google Mobile Ads SDK with adaptive banner sizing (`AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize`).
   - Safe build-variant mapping: Standard Google Test IDs for `DEBUG` builds; official production ad units for `RELEASE`.

---

## 🏛️ System Architecture

The codebase adheres strictly to **Clean Architecture & MVVM** with unidirectional data flow (UDF):

```
android/app/src/main/java/com/vibesync/toolkit/pdf/
├── core/
│   ├── designsystem/     # VibeSync Crimson & Ruby design tokens, typography, surfaces
│   ├── security/         # MasterKey AES-256-GCM cryptographic vault
│   └── util/             # UriUtils, SAF helpers, BitmapCache LRU manager
├── data/
│   ├── model/            # DocumentMetadata, PageMetadata, DrawingStroke, FormField
│   └── pdf/              # PdfRendererEngine, PdfBoxToolkitEngine, DevicePdfScanner
├── presentation/
│   ├── dashboard/        # DashboardScreen, DashboardViewModel, Tools Grid, Privacy Badge
│   ├── editor/           # Annotation Canvas, Vector Drawing, Signature Pad
│   ├── monetization/     # AdMobManager, Adaptive BannerAd Composable
│   ├── scanner/          # ML Kit Scanner Manager & ActivityResult Contracts
│   ├── tools/            # Split, Merge, Encrypt, Convert Tool Screens
│   └── viewer/           # Continuous LazyColumn PdfViewerScreen, PdfViewerViewModel
├── MainActivity.kt       # Single-activity Jetpack Compose navigation host
└── VibeSyncApp.kt        # Application entry point & PDFBox font cache initialization
```

---

## 🛠️ Build Specifications & Requirements

- **Android SDK:**
  - `compileSdk`: **36** (Android 16)
  - `targetSdk`: **36** (Android 16)
  - `minSdk`: **24** (Android 7.0 Nougat)
- **Java / Kotlin:**
  - JDK: **Java 17** (LTS)
  - Kotlin: **2.0.21** with Compose Compiler Plugin
- **Gradle:** **8.7+** with Android Gradle Plugin (AGP) **8.6.0+**

---

## 📦 Building the App

### 1. Debug Build (Local Testing)
Runs with Google Test AdMob unit IDs to avoid invalid traffic strikes:
```bash
./gradlew assembleDebug
```
Output APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### 2. Release APK
```bash
./gradlew assembleRelease
```
Output APK: `android/app/build/outputs/apk/release/app-release.apk`

### 3. Release Android App Bundle (.aab for Google Play Store)
```bash
./gradlew bundleRelease
```
Output AAB: `android/app/build/outputs/bundle/release/app-release.aab`

---

## 🧪 Automated Test Execution

VibeSync includes comprehensive test suites covering the end-to-end PDF lifecycle:

```bash
# Run JUnit 5 & Robolectric JVM Unit Tests
./gradlew testDebugUnitTest

# Run Jetpack Compose UI Instrumentation Tests on Emulator / Device
./gradlew connectedDebugAndroidTest
```

### Test Suites Included:
1. **`PdfBoxToolkitEngineTest.kt` (JUnit 5):**
   - Splitting with edge-case interval ranges (boundary limits, single page, out-of-order).
   - Merging multiple PDDocuments with mixed page rotations (90°, 180°, 270°).
   - AES-256 standard encryption & granular AccessPermission flags verification.
   - 72-DPI coordinate transformation and vector annotation flattening.
2. **`SecurityVaultTest.kt` (Robolectric):**
   - MasterKey AES256_GCM hardware storage, key generation, and bitmap serialization/deserialization.
   - Vault purge and lifecycle integrity.
3. **`PdfViewerScreenTest.kt` (Compose UI Test):**
   - Rapid continuous vertical scroll and zoom gestures without memory leaks or dropped frames.
   - Password prompt dialog submission and password visibility toggle behavior.

---

## 🔐 CI/CD Automated Build & Release (`android_release.yml`)

The repository includes a production GitHub Actions workflow at `.github/workflows/android_release.yml` that builds and signs **BOTH** targets simultaneously:
1. **Google Play Store App Bundle (`.aab`)**: Minified, resource-shrunk bundle for publishing to the Play Store.
2. **Direct-Install Universal APK (`.apk`)**: Standalone, installable APK file ready for testing directly on your Android phone.

### 1. Generating & Encoding Your Keystore
Generate a production signing keystore (if you don't already have one):
```bash
keytool -genkey -v -keystore release-upload.jks -alias vibesync -keyalg RSA -keysize 2048 -validity 10000
```
Then encode it into a Base64 string:
```bash
base64 -w 0 release-upload.jks > keystore_base64.txt
```

### 2. Configure GitHub Repository Secrets
Navigate to **GitHub Repository → Settings → Secrets and variables → Actions** and add:
- `KEYSTORE_BASE64`: The full content of `keystore_base64.txt`.
- `RELEASE_STORE_PASSWORD`: Keystore password.
- `RELEASE_KEY_ALIAS`: Keystore alias name (e.g. `vibesync`).
- `RELEASE_KEY_PASSWORD`: Key password.

### 3. Workflow Triggers
The workflow automatically executes on:
- Any `git push` to `main` or `master`.
- Any version release tag push (`git tag v1.0.0 && git push --tags`).
- **Manual 1-Click Trigger:** Go to the **Actions** tab in GitHub, select **"Build & Sign Android Release (AAB & APK)"**, and click **"Run workflow"**.

### 4. 📲 How to Download & Install the APK onto Your Phone
1. Open your GitHub repository in your web browser (or on your Android phone).
2. Tap the **Actions** tab at the top.
3. Tap on the latest successful workflow run (marked with a green checkmark).
4. Scroll down to the **Artifacts** section at the bottom of the run summary:
   - **`VibeSync-Installable-APK`**: Download this zip file, extract `app-release.apk`, and tap it on your Android phone to install!
   - **`VibeSync-GooglePlay-AAB`**: Ready to upload directly to the Google Play Console release track.

---

## 📋 Google Play Store Data Safety Submission Guide

When completing the **Data Safety form** in Google Play Console:

| Data Type | Collected? | Shared? | Purpose |
|---|---|---|---|
| **Files & Documents** | **NO** | **NO** | All processing is strictly on-device. Zero upload. |
| **Photos & Videos** | **NO** | **NO** | Only accessed locally when the user converts images to PDF. |
| **Biometric / Signatures** | **NO** | **NO** | Vaulted locally via MasterKey AES-256. Never transmitted. |
| **Device ID / Advertising ID** | **YES** | **YES** | Collected by Google Mobile Ads SDK (AdMob) for fraud prevention & ad serving. |
| **App Performance & Diagnostics** | **YES** | **YES** | Collected by Google Play Services / AdMob for crash logs and latency metrics. |

- **Data Encryption in Transit:** YES (HTTPS enforced for AdMob network requests).
- **Data Deletion Mechanism:** YES (Users can delete all app data or uninstall to permanently wipe all documents and keys).

---

## 📄 License & Attribution

- Built with **Jetpack Compose**, **Material 3**, and **Apache PDFBox-Android** (Apache License 2.0).
- Google Play Services **ML Kit Document Scanner**.
- **Privacy Policy:** See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
