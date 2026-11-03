package com.vibesync.toolkit.pdf

import android.app.Application
import android.util.Log
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class VibeSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Apache POI's Android-shaded build relocates StAX to avoid Android's restricted javax namespace.
        System.setProperty("org.apache.poi.javax.xml.stream.XMLInputFactory", "com.fasterxml.aalto.stax.InputFactoryImpl")
        System.setProperty("org.apache.poi.javax.xml.stream.XMLOutputFactory", "com.fasterxml.aalto.stax.OutputFactoryImpl")
        System.setProperty("org.apache.poi.javax.xml.stream.XMLEventFactory", "com.fasterxml.aalto.stax.EventFactoryImpl")
        
        // 1. Initialize TomRoush/PdfBox-Android resource loader and crypto provider.
        try {
            PDFBoxResourceLoader.init(applicationContext)
            val bcProvider = Security.getProvider("BC")
            if (bcProvider == null || bcProvider.javaClass.name != BouncyCastleProvider::class.java.name) {
                Security.removeProvider("BC")
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
            Log.i(TAG, "PDFBoxResourceLoader and compatible Bouncy Castle provider initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PDFBox resources/security provider", e)
        }

        // Register test devices only in debug builds, before any ads can load.
        if (BuildConfig.DEBUG) {
            val testDeviceIds = mutableListOf<String>()
            // Add the test-device ID printed by Logcat after the first debug install.
            if (testDeviceIds.isNotEmpty()) {
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        .setTestDeviceIds(testDeviceIds)
                        .build()
                )
            }
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
