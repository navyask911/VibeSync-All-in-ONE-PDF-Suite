package com.omnidoc.toolkit.pdf.presentation.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Production wrapper for Google ML Kit Document Scanner API.
 * Provides physical document capture, auto edge-detection, perspective warp,
 * shadow removal, and direct PDF generation with safe Google Play Services fallback.
 */
class DocumentScannerManager(private val activity: Activity) {

    private val scanner: GmsDocumentScanner? by lazy {
        try {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(50)
                .setResultFormats(
                    GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                    GmsDocumentScannerOptions.RESULT_FORMAT_PDF
                )
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize GmsDocumentScanning client", e)
            null
        }
    }

    /**
     * Checks whether Google Play Services and scanner models are accessible on device.
     */
    fun isScannerAvailable(): Boolean {
        return try {
            val availability = GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(activity)
            resultCode == ConnectionResult.SUCCESS && scanner != null
        } catch (e: Throwable) {
            Log.w(TAG, "Google Play Services availability check encountered error", e)
            scanner != null
        }
    }

    /**
     * Starts the scanner intent flow via an ActivityResultLauncher.
     */
    fun startScan(launcher: ActivityResultLauncher<IntentSenderRequest>, onFailure: (Exception) -> Unit) {
        try {
            val client = scanner
            if (client == null) {
                onFailure(IllegalStateException("Google ML Kit Document Scanner is not available on this device"))
                return
            }

            client.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    try {
                        launcher.launch(IntentSenderRequest.Builder(intentSender).build())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch IntentSender for document scanner", e)
                        onFailure(e)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "getStartScanIntent failed", exception)
                    onFailure(exception)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error starting document scan", e)
            onFailure(e)
        }
    }

    /**
     * Parses the scan result data into direct PDF Uri and page image Uris.
     */
    fun parseResult(resultCode: Int, data: Intent?): ScanResult? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        return try {
            val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return null
            val pdfUri = scanningResult.pdf?.uri
            val pagesUris = scanningResult.pages?.mapNotNull { it.imageUri } ?: emptyList()

            ScanResult(
                pdfUri = pdfUri,
                pageImageUris = pagesUris,
                pageCount = scanningResult.pages?.size ?: 0
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to parse document scanning result", e)
            null
        }
    }

    data class ScanResult(
        val pdfUri: Uri?,
        val pageImageUris: List<Uri>,
        val pageCount: Int
    )

    companion object {
        private const val TAG = "DocumentScannerManager"
    }
}
