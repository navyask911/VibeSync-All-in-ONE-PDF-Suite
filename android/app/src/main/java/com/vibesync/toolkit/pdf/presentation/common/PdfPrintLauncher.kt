package com.vibesync.toolkit.pdf.presentation.common

import android.content.Context
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import com.vibesync.toolkit.pdf.data.print.PdfPrintDocumentAdapter

/** Opens Android's system print UI with A4, color, 300 DPI and no duplex as the defaults. */
fun launchSystemPdfPrint(context: Context, sourceUri: Uri, jobName: String) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        ?: throw IllegalStateException("Android printing is not available on this device.")
    val attributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setResolution(PrintAttributes.Resolution("vibesync_300dpi", "VibeSync 300 DPI", 300, 300))
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .setDuplexMode(PrintAttributes.DUPLEX_MODE_NONE)
        .build()
    printManager.print(jobName, PdfPrintDocumentAdapter(context, sourceUri, jobName), attributes)
}
