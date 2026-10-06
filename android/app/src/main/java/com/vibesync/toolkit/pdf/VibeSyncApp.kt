package com.vibesync.toolkit.pdf

import android.app.Application
import android.util.Log
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.google.android.gms.ads.MobileAds
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class VibeSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // 1. Initialize TomRoush/PdfBox-Android resource loader and crypto provider.
        try {
            PDFBoxResourceLoader.init(applicationContext)
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            Log.i(TAG, "PDFBoxResourceLoader and Bouncy Castle initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PDFBox resources/security provider", e)
        }

        // 2. Initialize Google Mobile Ads SDK (AdMob)
        MobileAds.initialize(this) { initializationStatus ->
            Log.i(TAG, "AdMob initialization status: ${initializationStatus.adapterStatusMap}")
        }
    }

    companion object {
        private const val TAG = "VibeSyncApp"
    }
}
