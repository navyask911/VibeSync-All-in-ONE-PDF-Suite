````markdown
<p align="center">
  <img src="https://img.shields.io/badge/VibeSync-All%20in%20ONE%20PDF%20Suite-DC143C?style=for-the-badge&logo=adobeacrobatreader&logoColor=white" alt="VibeSync PDF Suite" />
</p>

<h1 align="center">📄 VibeSync All in ONE PDF Suite</h1>

<p align="center">
  <strong>High-performance, enterprise-grade, 100% on-device PDF productivity for Android.</strong><br/>
  View · Edit · Sign · Scan · Split · Merge · Encrypt · Decrypt · Watermark · Extract · Convert · Fill Forms
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-16%20%7C%20API%2036-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Min%20SDK-24-FF6F00?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/Privacy-100%25%20On--Device-DC143C?style=for-the-badge&logo=shield&logoColor=white" />
  <img src="https://img.shields.io/badge/Build-Passing-10B981?style=for-the-badge&logo=githubactions&logoColor=white" />
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Security-AES--256--GCM%20Vault-E0115F?style=flat-square&logo=letsencrypt&logoColor=white" />
  <img src="https://img.shields.io/badge/Scanner-ML%20Kit%20Document-0F52BA?style=flat-square&logo=google&logoColor=white" />
  <img src="https://img.shields.io/badge/PDF%20Engine-Apache%20PDFBox--Android-8B5CF6?style=flat-square&logo=apache&logoColor=white" />
  <img src="https://img.shields.io/badge/Locales-16-FFBF00?style=flat-square&logo=googletranslate&logoColor=black" />
</p>

---

## 🎨 Brand Palette

| Color | Hex Code | Usage |
|-------|----------|-------|
| 🔴 Crimson | `#DC143C` | Primary brand, CTAs, hero badges |
| 💖 Ruby | `#E0115F` | Accent highlights, security badges |
| 🟢 Emerald | `#10B981` | Success, privacy, build-passing |
| 🔵 Sapphire | `#0F52BA` | Info, links, scanner features |
| 🟡 Amber | `#FFBF00` | Warnings, build metadata |
| 🟣 Violet | `#8B5CF6` | Kotlin, Compose, PDF engine |

---

## ✨ Why VibeSync?

**VibeSync All in ONE PDF Suite** is a professional Android PDF workspace built with a **zero-cloud-transit** architecture. Every document operation — continuous rendering, vector annotation, digital signature vaulting, ML Kit scanning, splitting, merging, image extraction, encryption, decryption, watermarking, form filling, and AES-256 protection — runs **locally on the device**.

> 🛡️ **Your files never leave your phone. No uploads. No cloud dependency. No compromises.**

---

## 🆕 What’s New

| Feature | Description |
|---------|-------------|
| 🖼️ **Extract Images** | Pull every embedded raster XObject from a PDF as PNG. |
| 🔓 **Decrypt PDFs** | Password-based decryption with granular permission handling. |
| 💧 **Watermarking** | Apply subtle VibeSync footer or custom watermark text. |
| 📝 **AcroForm Filling** | Read, fill, and flatten interactive PDF forms. |
| 🗂️ **SAF Folder Scanning** | Persistently index PDFs from user-selected folders. |
| 🌍 **16 Locales** | Fully localized UI, dialogs, strings, and tool labels. |
| 📢 **Interstitial Ads** | Lifecycle-aware ads after heavy tasks with safe test-ID mapping. |
| 🛡️ **Hardware E-Sign Vault** | AES-256-GCM signatures secured by Android Keystore / TEE. |

---

## 🚀 Feature Matrix

### 📄 Core PDF Engine
- ⚡ **Continuous 72-DPI Vector Rendering** using native `android.graphics.pdf.PdfRenderer`
- 🧠 **OOM-safe LRU tile bitmap cache** (`BitmapCache`)
- 🎞️ Smooth **60/120 FPS** vertical scrolling
- 🔍 Pinch-to-zoom from **100% to 400%**
- 🧰 **Apache PDFBox-Android Clean-Room Toolkit** (`PdfBoxToolkitEngine.kt`)
  - ✂️ **Split** — arbitrary page intervals like `1-3, 5, 8-12`
  - 🔗 **Merge** — multi-document concatenation with mixed rotations
  - 🖼️ **Extract Images** — all embedded raster XObjects to PNG
  - 🖼️➡️📄 **Images to PDF** — aspect-ratio-preserving A4 centering
  - 🔐 **Encrypt / Decrypt** — AES-128/256 with access permissions
  - 💧 **Watermark** — automatic VibeSync footer or custom text
  - 📝 **Form Filling** — AcroForm read, fill, flatten
  - 🧷 **Vector Flattening** — ink, highlighters, text boxes, signatures baked at exact 72-DPI coordinates

### 🔐 Security & Privacy
- 🛡️ **Hardware-Backed E-Sign Vault** (`SecurityVault.kt`)
- 🔑 Android Jetpack Security `MasterKey`
- 🧊 **AES-256-GCM** encrypted signatures inside Android Keystore / TEE
- 🚫 **Zero plaintext storage**
- 📷 **On-Device ML Kit Scanner**
  - Boundary detection
  - Perspective warping
  - Shadow removal
  - Direct PDF generation on device NPU/GPU
- ☁️ **Zero-Cloud Architecture** — no document data ever leaves the device

### 📚 Viewer & Editor
- 👁️ **Viewer** (`PdfViewerScreen.kt`)
  - Continuous `LazyColumn` rendering
  - Password prompt for protected PDFs
  - Smooth gesture handling
- ✍️ **Editor** (`PdfEditorScreen.kt`)
  - Vector drawing
  - Highlighters
  - Text boxes
  - Signature pad
  - Lossless annotation flattening onto PDF

### 🧰 Toolkit & Utilities
- 🧭 **Dashboard** (`DashboardScreen.kt`) — Material 3 tool grid
- 🔎 **Automated Device PDF Discovery** (`DevicePdfScanner.kt`) via `MediaStore.Files`
- 🗂️ **SAF Folder Scanning** (`SafPdfFolderScanner.kt`) via Storage Access Framework
- 🌐 **Localized UI** — 16 locales with localized strings, dialogs, and tool labels

### 📈 Monetization & Ads
- 📢 **AdMob Integration** (`AdMobManager.kt`)
- 📐 Adaptive banner ads respecting orientation and screen size
- 🎬 Interstitial ads after heavy completions: Split, Merge, Export, etc.
- 🧪 Safe test-ID mapping for debug builds
- ♻️ Lifecycle-aware ad handling to prevent memory leaks

### 🧪 Testing & CI/CD
- ✅ `PdfBoxToolkitEngineTest.kt` — split, merge, encryption, decryption, image extraction, watermarking, flattening edge cases
- ✅ `SecurityVaultTest.kt` — MasterKey AES-256-GCM storage, key generation, bitmap serialization
- ✅ `PdfViewerScreenTest.kt` — continuous scroll/zoom, password prompt, memory-leak checks
- 🤖 GitHub Actions release workflow for `.aab` + universal `.apk`

---

## 🏗️ System Architecture

Built with **Clean Architecture + MVVM + Unidirectional Data Flow (UDF)**.

```text
android/app/src/main/java/com/vibesync/toolkit/pdf/
├── core/
│   ├── designsystem/       # VibeSync Crimson & Ruby design tokens, typography
│   ├── security/           # MasterKey AES-256-GCM cryptographic vault
│   └── util/               # UriUtils, SAF helpers, BitmapCache LRU manager
├── data/
│   ├── model/              # DocumentMetadata, PageMetadata, DrawingStroke, FormField
│   └── pdf/                # PdfRendererEngine, PdfBoxToolkitEngine, DevicePdfScanner,
│                           # SafPdfFolderScanner
├── presentation/
│   ├── dashboard/          # DashboardScreen, DashboardViewModel, Tools Grid
│   ├── editor/             # Annotation Canvas, Vector Drawing, Signature Pad
│   ├── monetization/       # AdMobManager, Adaptive BannerAd Composable
│   ├── scanner/            # ML Kit Scanner Manager & ActivityResult Contracts
│   ├── tools/              # Split, Merge, Encrypt, Convert, Extract, Form Fill Screens
│   └── viewer/             # Continuous LazyColumn PdfViewerScreen, PdfViewerViewModel
├── MainActivity.kt         # Single-activity Jetpack Compose navigation host
└── VibeSyncApp.kt          # Application entry point & PDFBox font cache initialization
```

---

## ⚙️ Build Specifications

| Specification | Value |
|---------------|-------|
| 📱 `compileSdk` | **36** (Android 16) |
| 🎯 `targetSdk` | **36** (Android 16) |
| 🧱 `minSdk` | **24** (Android 7.0 Nougat) |
| ☕ JDK | **Java 17 LTS** |
| 🟣 Kotlin | **2.0.21** + Compose Compiler Plugin |
| 🛠️ Gradle | **8.7+** |
| 🤖 AGP | **8.6.0+** |

---

## 🛠️ Build the App

### 🧪 Debug Build — Local Testing
Uses Google Test AdMob unit IDs to avoid invalid traffic strikes.

```bash
./gradlew assembleDebug
```

📦 Output: `android/app/build/outputs/apk/debug/app-debug.apk`

### 🚀 Release APK

```bash
./gradlew assembleRelease
```

📦 Output: `android/app/build/outputs/apk/release/app-release.apk`

### 🏪 Release Android App Bundle (.aab for Google Play)

```bash
./gradlew bundleRelease
```

📦 Output: `android/app/build/outputs/bundle/release/app-release.aab`

---

## 🧪 Automated Test Execution

```bash
# JUnit 5 & Robolectric JVM Unit Tests
./gradlew testDebugUnitTest

# Jetpack Compose UI Instrumentation Tests
./gradlew connectedDebugAndroidTest
```

### Included Test Suites
- 🧰 **`PdfBoxToolkitEngineTest.kt`** — split edge cases, merge with mixed rotations, AES-256 encryption/decryption, image extraction, watermarking, form filling, 72-DPI coordinate transformation
- 🔐 **`SecurityVaultTest.kt`** — MasterKey AES256_GCM hardware storage, key generation, bitmap serialization, vault purge
- 👁️ **`PdfViewerScreenTest.kt`** — rapid scroll/zoom without memory leaks, password dialog behavior, localization smoke tests

---

## 🤖 CI/CD — Automated Build & Release

The repository includes a production GitHub Actions workflow:

📄 `.github/workflows/android_release.yml`

It builds and signs **both** targets simultaneously:

1. 🏪 **Google Play App Bundle (`.aab`)** — minified, resource-shrunk for Play Store publishing
2. 📲 **Direct-Install Universal APK (`.apk`)** — standalone APK for direct device testing

### 🔐 Setup

Generate a signing keystore and encode it as Base64. Add these secrets in **GitHub → Settings → Secrets and variables → Actions**:

| Secret | Purpose |
|--------|---------|
| `KEYSTORE_BASE64` | Base64-encoded keystore |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Push a tag or run the workflow manually. Artifacts appear under the run summary.

---

## 🛡️ Google Play Data Safety Submission Guide

| Data Type | Collected? | Shared? | Purpose |
|-----------|------------|---------|---------|
| 📄 **Files & Documents** | **NO** | **NO** | All processing is strictly on-device. Zero upload. |
| 🖼️ **Photos & Videos** | **NO** | **NO** | Only accessed locally when converting images to PDF. |
| ✍️ **Biometric / Signatures** | **NO** | **NO** | Vaulted locally via MasterKey AES-256. Never transmitted. |
| 📢 **Device ID / Advertising ID** | **YES** | **YES** | Collected by Google Mobile Ads SDK (AdMob) for fraud prevention & ad serving. |
| 📊 **App Performance & Diagnostics** | **YES** | **YES** | Collected by Google Play Services / AdMob for crash logs and latency metrics. |

- 🔐 **Data Encryption in Transit:** YES — HTTPS enforced for AdMob requests.
- 🗑️ **Data Deletion Mechanism:** YES — uninstalling the app or clearing app data permanently wipes all documents and keys.

---

## 📜 License & Attribution

- 🧱 Built with **Jetpack Compose**, **Material 3**, and **Apache PDFBox-Android**.
- 🤝 Third-party components retain their own licenses.
- 📷 Google Play Services **ML Kit Document Scanner** is subject to its own terms.
- 📄 **License:** See [LICENSE](https://github.com/navyask911/VibeSync-All-in-ONE-PDF-Suite/blob/main/LICENSE) for VibeSync proprietary ownership and usage restrictions.
- 🔒 **Privacy Policy:** See [PRIVACY_POLICY.md](https://github.com/navyask911/VibeSync-All-in-ONE-PDF-Suite/blob/main/PRIVACY_POLICY.md).
- 📧 **Developer Support:** [vibesyncrotechnologies@gmail.com](mailto:vibesyncrotechnologies@gmail.com)
- 🌐 **Official Repository:** [https://github.com/navyask911/VibeSync-All-in-ONE-PDF-Suite](https://github.com/navyask911/VibeSync-All-in-ONE-PDF-Suite)

---

<p align="center">
  <strong>Made with ❤️ by VibeSync</strong><br/>
  <em>Private by design. Powerful by engineering. All in ONE PDF Suite.</em>
</p>
````
