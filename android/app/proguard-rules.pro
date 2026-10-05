# OmniPDF Studio - Production ProGuard & R8 Optimization Rules

# ==========================================
# Apache PDFBox-Android Keep Rules
# ==========================================
# PdfBox dynamically reflects on font handlers, glyph lists, and encryption filters
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**

# Preserve BouncyCastle cryptographic algorithms if referenced internally
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Preserve Apache Commons Logging / SLF4J shims used by PdfBox
-keep class org.apache.commons.logging.** { *; }
-dontwarn org.apache.commons.logging.**

# ==========================================
# Google Mobile Ads (AdMob) Keep Rules
# ==========================================
-keep public class com.google.android.gms.ads.** {
   public *;
}
-keep public class com.google.ads.** {
   public *;
}
-keepattributes *Annotation*
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ==========================================
# Google ML Kit Document Scanner
# ==========================================
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_document_scanner.** { *; }
-dontwarn com.google.android.gms.internal.mlkit_vision_document_scanner.**

# ==========================================
# Androidx Security Crypto
# ==========================================
-keepclassmembers class androidx.security.crypto.** {
    public *;
}
-dontwarn androidx.security.crypto.**

# ==========================================
# Kotlin Coroutines & Jetpack Compose
# ==========================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembers class androidx.compose.runtime.Recomposer {
    private void <init>(...);
}
