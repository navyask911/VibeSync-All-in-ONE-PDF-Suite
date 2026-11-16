package com.vibesync.toolkit.pdf.presentation.common

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import com.vibesync.toolkit.pdf.core.util.UriUtils
import java.io.File
import java.io.IOException

/**
 * The only entry point for sharing one app-owned PDF with external applications.
 *
 * Shares a single application/pdf stream using ShareCompat's standard ACTION_SEND intent.
 * No preview images, sidecar files, or multi-stream PDF intents are generated here.
 */
object PdfShareHelper {

    fun sharePdf(context: Context, file: File, chooserTitle: String) {
        context.startActivity(createChooserIntent(context, file, chooserTitle))
    }

    internal fun createChooserIntent(
        context: Context,
        source: File,
        chooserTitle: String,
        uriProvider: (Context, File) -> Uri = UriUtils::getShareableUri
    ): Intent {
        val stagedPdf = stagePdfForSharing(context, source)
        val uri = uriProvider(context, stagedPdf)
        val sendIntent = ShareCompat.IntentBuilder(context)
            .setType(PDF_MIME_TYPE)
            .setStream(uri)
            .setSubject(stagedPdf.name)
            .intent
            .apply {
                putExtra(Intent.EXTRA_TITLE, stagedPdf.name)
                clipData = ClipData(
                    ClipDescription(CLIP_LABEL, arrayOf(PDF_MIME_TYPE)),
                    ClipData.Item(uri)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Keep the chooser alive after a target returns to the app on API 24+.
                run { flags = flags and Intent.FLAG_ACTIVITY_NO_HISTORY.inv() }
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        return Intent.createChooser(sendIntent, chooserTitle).apply {
            clipData = sendIntent.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            run { flags = flags and Intent.FLAG_ACTIVITY_NO_HISTORY.inv() }
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Copies a completed PDF into a FileProvider <files-path> before exposing it to another app.
     * The original filename is retained where available; collisions receive a numeric suffix.
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
            if (candidate.createNewFile()) reservedDestination = candidate
            else {
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
                    if (copiedBytes <= 0L) throw IOException("The PDF is not readable: ${source.name}")
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
        if (!file.isFile || file.length() <= 0L) throw IOException("The PDF is missing or empty: ${file.name}")
        file.inputStream().use { input ->
            if (input.read() == -1) throw IOException("The PDF is not readable: ${file.name}")
        }
    }

    private const val SHARED_PDF_DIRECTORY = "shared_pdfs"
    private const val MAX_FILENAME_COLLISION_ATTEMPTS = 1000
    private const val PDF_MIME_TYPE = "application/pdf"
    private const val CLIP_LABEL = "VibeSync PDF"
}
