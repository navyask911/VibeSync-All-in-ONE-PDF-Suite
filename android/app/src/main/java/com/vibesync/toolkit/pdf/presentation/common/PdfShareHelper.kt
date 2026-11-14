package com.vibesync.toolkit.pdf.presentation.common

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
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
        forceMultiple: Boolean = false
    ): Intent {
        require(files.isNotEmpty()) { "At least one PDF is required for sharing." }

        val uris = files.map { file ->
            if (!file.isFile || file.length() <= 0L) {
                throw IOException("The PDF is missing or empty: ${file.name}")
            }
            val lengthBeforeRead = file.length()
            file.inputStream().use { input ->
                if (input.read() == -1) {
                    throw IOException("The PDF is not readable: ${file.name}")
                }
            }
            if (file.length() != lengthBeforeRead) {
                throw IOException("The PDF changed while it was being prepared: ${file.name}")
            }
            UriUtils.getShareableUri(context, file)
        }

        val clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first())
        uris.drop(1).forEach { uri -> clipData.addItem(ClipData.Item(uri)) }

        val sendIntent = if (uris.size == 1 && !forceMultiple) {
            Intent(Intent.ACTION_SEND).apply {
                type = PDF_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                this.clipData = clipData
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = PDF_MIME_TYPE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
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

    private const val PDF_MIME_TYPE = "application/pdf"
}
