package com.omnidoc.toolkit.pdf.presentation.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Production wrapper for Google ML Kit Document Scanner API.
 * Provides physical document capture, auto edge-detection, perspective warp,
 * shadow removal, and direct PDF generation.
 */
class DocumentScannerManager(private val activity: Activity) {

    private val scanner: GmsDocumentScanner

    init {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(50)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        scanner = GmsDocumentScanning.getClient(options)
    }

    /**
     * Starts the scanner intent flow via an ActivityResultLauncher.
     */
    fun startScan(launcher: ActivityResultLauncher<IntentSenderRequest>, onFailure: (Exception) -> Unit) {
        scanner.getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }

    /**
     * Parses the scan result data into direct PDF Uri and page image Uris.
     */
    fun parseResult(resultCode: Int, data: Intent?): ScanResult? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val scanningResult = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return null

        val pdfUri = scanningResult.pdf?.uri
        val pagesUris = scanningResult.pages?.mapNotNull { it.imageUri } ?: emptyList()

        return ScanResult(
            pdfUri = pdfUri,
            pageImageUris = pagesUris,
            pageCount = scanningResult.pages?.size ?: 0
        )
    }

    data class ScanResult(
        val pdfUri: Uri?,
        val pageImageUris: List<Uri>,
        val pageCount: Int
    )
}
