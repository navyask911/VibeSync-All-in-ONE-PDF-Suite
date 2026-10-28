package com.vibesync.toolkit.pdf.presentation.common

import android.content.Context
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintJob
import android.print.PrintManager
import com.vibesync.toolkit.pdf.data.print.PdfPrintDocumentAdapter
import com.vibesync.toolkit.pdf.core.util.UriUtils
import java.io.File
import java.io.IOException

/** Opens Android's system print UI with A4, color, 300 DPI and no duplex as the defaults. */
@Volatile private var activeSystemPrintJob: PrintJob? = null

fun launchSystemPdfPrint(
    context: Context,
    sourceUri: Uri,
    jobName: String,
    printDispatcher: ((String, PrintDocumentAdapter, PrintAttributes) -> PrintJob?)? = null
) {
    // Viewer passes a fully staged print_ready file; other callers are staged here before
    // PrintManager is invoked. This avoids the system print UI racing a provider stream.
    val sourceFile = if (sourceUri.scheme == "file") {
        File(sourceUri.path ?: throw IOException("The print source path is missing."))
    } else {
        UriUtils.copyUriToCacheFile(context, sourceUri, "print_ready")
    }
    val printReadyFile = if (
        sourceUri.scheme == "file" &&
        sourceFile.parentFile?.canonicalFile == File(context.cacheDir, "pdf_cache").canonicalFile &&
        sourceFile.name.startsWith("print_ready_")
    ) {
        sourceFile
    } else if (sourceUri.scheme == "file") {
        UriUtils.copyUriToCacheFile(context, sourceUri, "print_ready")
    } else {
        sourceFile
    }

    try {
        if (!printReadyFile.isFile || !printReadyFile.canRead() || printReadyFile.length() <= 0L) {
            throw IOException("The selected PDF is empty or unreadable; printing was not started.")
        }
        val printManager = if (printDispatcher == null) {
            context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                ?: throw IllegalStateException("Android printing is not available on this device.")
        } else {
            null
        }
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("vibesync_300dpi", "VibeSync 300 DPI", 300, 300))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setDuplexMode(PrintAttributes.DUPLEX_MODE_NONE)
            .build()
        activeSystemPrintJob?.takeIf { it.isQueued || it.isStarted || it.isBlocked }?.cancel()
        val adapter = PdfPrintDocumentAdapter(
            context,
            Uri.fromFile(printReadyFile),
            jobName,
            preparedSource = printReadyFile
        )
        activeSystemPrintJob = if (printDispatcher != null) {
            printDispatcher(jobName, adapter, attributes)
        } else {
            requireNotNull(printManager).print(jobName, adapter, attributes)
        }
    } catch (error: Exception) {
        if (printReadyFile != sourceFile || sourceUri.scheme != "file" ||
            sourceFile.parentFile?.canonicalFile == File(context.cacheDir, "pdf_cache").canonicalFile &&
            sourceFile.name.startsWith("print_ready_")
        ) {
            printReadyFile.delete()
        }
        throw error
    }
}
