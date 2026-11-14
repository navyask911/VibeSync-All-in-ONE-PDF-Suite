package com.vibesync.toolkit.pdf.presentation.common

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.vibesync.toolkit.pdf.core.util.UriUtils
import java.io.File
import java.io.IOException

/**
 * The only entry point for sharing app-owned PDF files with external applications.
 *
 * Callers must finish and close their output stream before invoking this helper. This helper
 * verifies that each file is a non-empty readable regular file before creating a FileProvider URI.
 */
object PdfShareHelper {

    fun sharePdf(context: Context, file: File, chooserTitle: String) {
        sharePdfs(context, listOf(file), chooserTitle)
    }

    fun sharePdfs(
        context: Context,
        files: List<File>,
        chooserTitle: String,
        forceMultiple: Boolean = false
    ) {
        require(files.isNotEmpty()) { "At least one PDF is required for sharing." }
        context.startActivity(createChooserIntent(context, files, chooserTitle, forceMultiple))
    }

    internal fun createChooserIntent(
        context: Context,
        files: List<File>,
        chooserTitle: String,
        forceMultiple: Boolean = false,
        uriProvider: (Context, File) -> Uri = UriUtils::getShareableUri
    ): Intent {
        require(files.isNotEmpty()) { "At least one PDF is required for sharing." }

        // Stage every outgoing PDF in app-owned files storage. Some share targets,
        // notably WhatsApp's document preview pipeline, do not reliably thumbnail cache-backed URIs.
        val stagedFiles = files.map { file -> stagePdfForSharing(context, file) }
        val uris = stagedFiles.map { file -> uriProvider(context, file) }

        // newUri() lets the provider supply its MIME type. ClipDescription is immutable,
        // so wrap its first item in an explicit PDF description to guarantee application/pdf.
        val providerClip = ClipData.newUri(context.contentResolver, "PDF", uris.first())
        val clipData = ClipData(
            ClipDescription("PDF", arrayOf(PDF_MIME_TYPE)),
            providerClip.getItemAt(0)
        )
        uris.drop(1).forEach { uri -> clipData.addItem(ClipData.Item(uri)) }

        val sendIntent = if (uris.size == 1 && !forceMultiple) {
            Intent(Intent.ACTION_SEND).apply {
                type = PDF_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uris.first())
                putExtra(Intent.EXTRA_TITLE, files.first().name)
                putExtra(Intent.EXTRA_SUBJECT, files.first().name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                this.clipData = clipData
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = PDF_MIME_TYPE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra(Intent.EXTRA_TITLE, files.first().name)
                putExtra(Intent.EXTRA_SUBJECT, files.first().name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                this.clipData = clipData
            }
        }

        return Intent.createChooser(sendIntent, chooserTitle).apply {
            // The wrapper also needs the read grant so the selected target can open the URI.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            this.clipData = clipData
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Copies a completed PDF into a FileProvider <files-path> before exposing it to another app.
     * The original name is retained where available; collisions receive a numeric suffix.
     */
    private fun stagePdfForSharing(context: Context, source: File): File {
        if (!source.isFile || source.length() <= 0L) {
            throw IOException("The PDF is missing or empty: ${source.name}")
        }

        val shareDirectory = File(context.filesDir, SHARED_PDF_DIRECTORY)
        if (!shareDirectory.exists() && !shareDirectory.mkdirs()) {
            throw IOException("Unable to prepare the persistent PDF sharing directory.")
        }
        if (!shareDirectory.isDirectory) {
            throw IOException("The persistent PDF sharing path is not a directory.")
        }

        val sourceFile = source.canonicalFile
        val targetDirectory = shareDirectory.canonicalFile
        if (sourceFile.parentFile?.canonicalFile == targetDirectory) {
            verifyReadablePdf(sourceFile)
            return sourceFile
        }

        val stem = source.nameWithoutExtension.ifBlank { "document" }
        val extension = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".pdf"
        var suffix = 0
        var reservedDestination: File? = null
        while (reservedDestination == null) {
            val candidateName = if (suffix == 0) source.name else "${stem}_${suffix}${extension}"
            val candidate = File(shareDirectory, candidateName)
            if (candidate.createNewFile()) {
                reservedDestination = candidate
            } else {
                suffix++
                if (suffix > MAX_FILENAME_COLLISION_ATTEMPTS) {
                    throw IOException("Unable to reserve a unique file name for ${source.name}.")
                }
            }
        }
        val destination = requireNotNull(reservedDestination)

        var copiedSuccessfully = false
        try {
            val lengthBeforeCopy = source.length()
            source.inputStream().use { input ->
                destination.outputStream().use { output ->
                    val copiedBytes = input.copyTo(output)
                    output.flush()
                    if (copiedBytes <= 0L) {
                        throw IOException("The PDF is not readable: ${source.name}")
                    }
                }
            }
            if (source.length() != lengthBeforeCopy || destination.length() != lengthBeforeCopy) {
                throw IOException("The PDF changed while it was being prepared: ${source.name}")
            }
            verifyReadablePdf(destination)
            copiedSuccessfully = true
            return destination
        } finally {
            if (!copiedSuccessfully) destination.delete()
        }
    }

    private fun verifyReadablePdf(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("The PDF is missing or empty: ${file.name}")
        }
        file.inputStream().use { input ->
            if (input.read() == -1) {
                throw IOException("The PDF is not readable: ${file.name}")
            }
        }
    }

    private const val SHARED_PDF_DIRECTORY = "shared_pdfs"
    private const val MAX_FILENAME_COLLISION_ATTEMPTS = 1000
    private const val PDF_MIME_TYPE = "application/pdf"
}
