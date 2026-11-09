package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Builds a PDF suitable for Android PrintManager from the already staged viewer source.
 * Passwords are provided only from the in-memory viewer session and are never persisted.
 */
internal fun preparePrintablePdf(
    context: Context,
    sourceFile: File,
    sessionPassword: String?
): File {
    if (!sourceFile.isFile || !sourceFile.canRead() || sourceFile.length() <= 0L) {
        throw IOException("The source PDF is empty or unreadable.")
    }
    PDFBoxResourceLoader.init(context.applicationContext)
    val printReadyFile = File.createTempFile("print_unlocked_", ".pdf", context.cacheDir)
    try {
        val document = if (sessionPassword.isNullOrBlank()) {
            PDDocument.load(sourceFile)
        } else {
            PDDocument.load(sourceFile, sessionPassword)
        }
        document.use { pdf ->
            pdf.isAllSecurityToBeRemoved = true
            FileOutputStream(printReadyFile).use { output ->
                pdf.save(output)
                output.flush()
                output.fd.sync()
            }
        }
        if (!printReadyFile.isFile || !printReadyFile.canRead() || printReadyFile.length() <= 0L) {
            throw IOException("The print-ready PDF could not be written.")
        }
        PDDocument.load(printReadyFile).use { verified ->
            check(!verified.isEncrypted) { "The print-ready PDF still contains security restrictions." }
            check(verified.numberOfPages > 0) { "The print-ready PDF contains no pages." }
        }
        return printReadyFile
    } catch (error: Exception) {
        printReadyFile.delete()
        throw error
    }
}
