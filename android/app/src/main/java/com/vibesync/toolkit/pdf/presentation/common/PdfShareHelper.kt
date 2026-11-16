package com.vibesync.toolkit.pdf.presentation.common

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.app.ShareCompat
import com.vibesync.toolkit.pdf.core.util.UriUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

/**
 * The only entry point for sharing app-owned PDF files with external applications.
 *
 * In addition to the PDF, every outgoing document includes a JPEG sidecar rendered from its
 * first page. This lets receivers that support image previews display the page without needing
 * to decode the PDF themselves. The sidecar remains in app cache long enough for background
 * recipient workers to read it after the chooser closes.
 */
object PdfShareHelper {

    fun sharePdf(context: Context, file: File, chooserTitle: String) {
        sharePdfs(context, listOf(file), chooserTitle)
    }

    fun sharePdfs(
        context: Context,
        files: List<File>,
        chooserTitle: String,
        @Suppress("UNUSED_PARAMETER") forceMultiple: Boolean = false
    ) {
        require(files.isNotEmpty()) { "At least one PDF is required for sharing." }
        context.startActivity(createChooserIntent(context, files, chooserTitle))
    }

    internal fun createChooserIntent(
        context: Context,
        files: List<File>,
        chooserTitle: String,
        @Suppress("UNUSED_PARAMETER") forceMultiple: Boolean = false,
        uriProvider: (Context, File) -> Uri = UriUtils::getShareableUri,
        thumbnailGenerator: (Context, File) -> File = ::createFirstPageThumbnail
    ): Intent {
        require(files.isNotEmpty()) { "At least one PDF is required for sharing." }

        // Stage each outgoing PDF in persistent app-owned storage so its content URI remains
        // available while the recipient generates its preview in a background worker.
        val stagedPdfs = files.map { file -> stagePdfForSharing(context, file) }

        // Emit a stable PDF/JPEG pair for every document. Keep the PDF first so it remains the
        // primary document in applications that inspect the first EXTRA_STREAM item.
        val outgoingFiles = ArrayList<File>(stagedPdfs.size * 2)
        stagedPdfs.forEach { pdf ->
            outgoingFiles += pdf
            outgoingFiles += thumbnailGenerator(context, pdf).also(::verifyReadableJpeg)
        }
        val uris = outgoingFiles.map { file -> uriProvider(context, file) }
        require(uris.size >= 2) { "A PDF and its first-page preview are required for sharing." }

        // ShareCompat supplies Android's stream metadata. We explicitly enforce SEND_MULTIPLE
        // and populate both EXTRA_STREAM and ClipData so every URI receives a read grant.
        val builder = ShareCompat.IntentBuilder(context)
            .setType(PDF_MIME_TYPE)
            .setStream(uris.first())
            .setSubject(stagedPdfs.first().name)
        uris.drop(1).forEach(builder::addStream)

        val clipData = createShareClipData(context, uris)
        val sendIntent = builder.intent.apply {
            action = Intent.ACTION_SEND_MULTIPLE
            putExtra(Intent.EXTRA_TITLE, stagedPdfs.first().name)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            this.clipData = clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            removeFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return Intent.createChooser(sendIntent, chooserTitle).apply {
            this.clipData = sendIntent.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            removeFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun createShareClipData(context: Context, uris: List<Uri>): ClipData {
        val description = ClipDescription(
            CLIP_LABEL,
            arrayOf(PDF_MIME_TYPE, JPEG_MIME_TYPE)
        )
        return ClipData(description, ClipData.Item(uris.first())).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
    }

    /**
     * Renders page one through Android's native PdfRenderer and creates a white-backed JPEG
     * thumbnail fitted within 300x300 pixels. The output uses cache/pdf_cache, which is already
     * exposed through the app's FileProvider cache path.
     */
    private fun createFirstPageThumbnail(context: Context, pdfFile: File): File {
        if (!pdfFile.isFile || pdfFile.length() <= 0L) {
            throw IOException("The PDF is missing or empty: ${pdfFile.name}")
        }

        val thumbnailDirectory = File(context.cacheDir, THUMBNAIL_DIRECTORY)
        if (!thumbnailDirectory.exists() && !thumbnailDirectory.mkdirs()) {
            throw IOException("Unable to prepare the PDF preview directory.")
        }
        if (!thumbnailDirectory.isDirectory) {
            throw IOException("The PDF preview path is not a directory.")
        }
        removeExpiredThumbnails(thumbnailDirectory)

        val thumbnailFile = File.createTempFile(THUMBNAIL_PREFIX, ".jpg", thumbnailDirectory)
        var completed = false
        try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0) {
                        throw IOException("The PDF does not contain a renderable page.")
                    }
                    renderer.openPage(0).use { page ->
                        val pageWidth = page.width.coerceAtLeast(1)
                        val pageHeight = page.height.coerceAtLeast(1)
                        val scale = minOf(
                            MAX_THUMBNAIL_SIZE.toFloat() / pageWidth,
                            MAX_THUMBNAIL_SIZE.toFloat() / pageHeight
                        )
                        val width = (pageWidth * scale).roundToInt().coerceIn(1, MAX_THUMBNAIL_SIZE)
                        val height = (pageHeight * scale).roundToInt().coerceIn(1, MAX_THUMBNAIL_SIZE)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            val transform = Matrix().apply {
                                setScale(width.toFloat() / pageWidth, height.toFloat() / pageHeight)
                            }
                            page.render(
                                bitmap,
                                null,
                                transform,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )
                            FileOutputStream(thumbnailFile).use { output ->
                                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                                    throw IOException("Unable to encode the PDF first-page preview.")
                                }
                                output.flush()
                            }
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }
            }

            verifyReadableJpeg(thumbnailFile)
            completed = true
            return thumbnailFile
        } catch (error: Exception) {
            throw IOException("Unable to render the first page of ${pdfFile.name} for sharing.", error)
        } finally {
            if (!completed) thumbnailFile.delete()
        }
    }

    private fun removeExpiredThumbnails(directory: File) {
        val expiry = System.currentTimeMillis() - THUMBNAIL_RETENTION_MILLIS
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(THUMBNAIL_PREFIX) && it.lastModified() < expiry }
            ?.forEach { it.delete() }
    }

    private fun verifyReadableJpeg(file: File) {
        if (!file.isFile || file.length() < MIN_JPEG_BYTES) {
            throw IOException("The PDF first-page preview is missing or empty.")
        }
        file.inputStream().use { input ->
            val start = ByteArray(2)
            if (input.read(start) != 2 || start[0] != 0xFF.toByte() || start[1] != 0xD8.toByte()) {
                throw IOException("The generated PDF preview is not a JPEG image.")
            }
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
    private const val THUMBNAIL_DIRECTORY = "pdf_cache/share_thumbnails"
    private const val THUMBNAIL_PREFIX = "vibesync_preview_"
    private const val THUMBNAIL_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_FILENAME_COLLISION_ATTEMPTS = 1000
    private const val MAX_THUMBNAIL_SIZE = 300
    private const val JPEG_QUALITY = 88
    private const val MIN_JPEG_BYTES = 4L
    private const val PDF_MIME_TYPE = "application/pdf"
    private const val JPEG_MIME_TYPE = "image/jpeg"
    private const val CLIP_LABEL = "VibeSync PDF and first-page preview"
}
