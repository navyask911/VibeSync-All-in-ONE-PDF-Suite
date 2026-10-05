# Privacy Policy for VibeSync All in ONE PDF Suite

**Effective Date:** October 5, 2026  
**Last Updated:** October 5, 2026  
**Application Name:** VibeSync All in ONE PDF Suite  
**Developer Contact:** support@vibesync.app  

---

## 1. Overview & Commitment to Privacy

VibeSync All in ONE PDF Suite ("VibeSync", "the App", "we", "us", or "our") is designed from the ground up as a **100% on-device, zero-cloud-transit** mobile document workstation. 

We strongly believe that your personal, legal, business, and medical PDF documents belong exclusively to you. **VibeSync operates entirely offline on your local device without uploading, transmitting, scanning, or replicating your document contents to any external remote servers, cloud infrastructure, or third parties.**

---

## 2. On-Device Data Processing & Storage

All core PDF functions are executed locally via native hardware acceleration and on-device libraries:
- **PDF Rendering & Viewing:** Rendered strictly on-device utilizing Android's native `android.graphics.pdf.PdfRenderer` with local memory tile caching.
- **Document Toolkit (Split, Merge, Watermark, Compress, Convert):** Processed locally using clean-room Apache PDFBox-Android (`com.tom-roush:pdfbox-android`) within your device's isolated application sandbox (`context.cacheDir`).
- **Camera Document Scanning:** Powered by Google Play Services ML Kit Document Scanner (`play-services-mlkit-document-scanner`). Edge detection, perspective rectification, and shadow removal are performed locally on your device's Neural Processing Unit (NPU) / GPU.
- **Digital Signatures & Security Vault:** Signatures created via the drawing pad are encrypted using Android Jetpack Security (`androidx.security.crypto.MasterKey`) with hardware-backed **AES-256-GCM** encryption and stored in `EncryptedSharedPreferences`. They are decrypted solely in memory when stamped onto your document and are never transmitted over any network.

---

## 3. Storage & Device Permissions

VibeSync requests only the permissions necessary to provide its core file-handling capabilities:

| Permission | Purpose | Data Safety Rationale |
|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` (Android 11–16, API 30–36) | Required to discover, index, and organize local `.pdf` files across device directories. | Used solely to read local files requested by the user. Document contents are never sent off-device. |
| `READ_MEDIA_DOCUMENTS` / `READ_MEDIA_IMAGES` (Android 13–16) | Enables selecting photos to compile into PDF documents ("Images to PDF"). | Access is confined to files explicitly chosen by the user. |
| `READ_EXTERNAL_STORAGE` (Android 10 and below, API 24–29) | Legacy file access for older Android OS versions. | Strictly local indexing. |
| `INTERNET` & `ACCESS_NETWORK_STATE` | Required strictly by the Google Mobile Ads (AdMob) SDK to serve ads and check network connectivity. | **Never used to transmit PDF documents, signatures, or user document metadata.** |

*Note: Users who prefer not to grant broad storage permissions may decline the prompt and use Android's Storage Access Framework (SAF) system picker to open individual documents one at a time.*

---

## 4. Third-Party Advertising (Google AdMob)

To support ongoing development while keeping the application free, VibeSync integrates the **Google Mobile Ads (AdMob) SDK**:
- **Official AdMob App ID:** `ca-app-pub-4783826505860771~4377815936`
- **Banner Ad Unit ID:** `ca-app-pub-4783826505860771/3755922842`

### What Google AdMob May Collect:
In accordance with Google Play's Families and Advertising policies, the AdMob SDK may collect:
- **Device Advertising ID (AAID):** Used for frequency capping, fraud prevention, and personalized advertising (subject to your device's ad tracking settings).
- **Diagnostics & App Performance:** Crash logs, load latency, and anonymized interaction metrics.
- **IP Address:** For general coarse geographic targeting (city/region level).

Google AdMob processes data subject to Google's Privacy Policy: [https://policies.google.com/privacy](https://policies.google.com/privacy) and [How Google uses information from sites or apps that use our services](https://policies.google.com/technologies/partner-sites).

You can reset or opt out of personalized ads at any time via:  
**Device Settings → Google → Ads → Reset advertising ID / Delete advertising ID**.

---

## 5. Data Retention & User Deletion Rights

Because VibeSync stores zero document data on cloud servers:
- **Instant Deletion:** You have complete, direct control over your data. Clearing the application's cache or data via **Android Settings → Apps → VibeSync → Storage → Clear Storage** instantly and irreversibly wipes all cached documents, temporary files, and encrypted signatures.
- **Uninstallation:** Uninstalling the app permanently purges all files from the application sandbox and revokes hardware cryptographic keys.
- **Original Documents:** Files opened via the system storage picker remain in their original external folders (e.g., Downloads, Documents) unless modified or deleted by the user.

---

## 6. Children's Privacy

VibeSync does not knowingly collect, request, or solicit any personal information from children under the age of 13 (or under 16 in the European Union). The app does not target children.

---

## 7. Security Safeguards

- **No Remote Telemetry on Documents:** Zero external analytics or tracking libraries are bundled for document contents.
- **Hardware-Backed Keystore:** Cryptographic keys reside in the device's hardware Secure Element / Trusted Execution Environment (TEE).
- **ProGuard / R8 Obfuscation:** Release builds are minified, obfuscated, and resource-shrunk to prevent reverse engineering.

---

## 8. Changes to This Policy

We may periodically update our Privacy Policy to reflect application updates or regulatory requirements. Any modifications will be reflected by the "Last Updated" date at the top of this document and notified within the app.

---

## 9. Contact Us

If you have questions, feedback, or concerns regarding your privacy or data safety while using VibeSync All in ONE PDF Suite, please contact:

**Developer Support Team**  
Email: [support@vibesync.app](mailto:support@vibesync.app)  
Website: [https://vibesync.app/privacy](https://vibesync.app/privacy)
