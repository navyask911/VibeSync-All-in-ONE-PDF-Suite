package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.sqrt

/** Raster rebuild with bounded, profile-driven DPI and one decoded page in memory at a time. */
class PdfCompressor(private val context: Context) {
    suspend fun compress(
        sourceUri: Uri,
        outputFile: File,
        profile: ResolutionProfile,
        password: String? = null,
        maxMemoryBytes: Long = Runtime.getRuntime().maxMemory(),
        onProgress: (completedPages: Int, totalPages: Int) -> Unit = { _, _ -> }
    ): Result<CompressionReport> = withContext(Dispatchers.IO) {
        var stagedSource: File? = null
        var renderSource: File? = null
        var outputWasCommitted = false
        val tempOutput = File(outputFile.parentFile ?: context.cacheDir, outputFile.name + ".partial")
        try {
            PDFBoxResourceLoader.init(context.applicationContext)
            stagedSource = stageSource(sourceUri)
            val beforeBytes = stagedSource.length()
            if (beforeBytes <= 0L) throw IOException("The selected PDF is empty.")

            val sourceDocument = try {
                if (password.isNullOrEmpty()) PDDocument.load(stagedSource)
                else PDDocument.load(stagedSource, password)
            } catch (error: InvalidPasswordException) {
                throw PdfPasswordRequiredException(
                    "This PDF is password-protected. Enter its password to compress it.",
                    error
                )
            }
            var pageCount = 0
            sourceDocument.use { source ->
                pageCount = source.numberOfPages
                require(pageCount > 0) { "The source PDF contains no pages." }

                // Android PdfRenderer cannot open encrypted PDFs. Remove security only after
                // PDFBox has successfully authenticated the supplied password.
                renderSource = if (source.isEncrypted) {
                    val decrypted = File.createTempFile("compress_decrypted_", ".pdf", context.cacheDir)
                    try {
                        source.setAllSecurityToBeRemoved(true)
                        source.save(decrypted)
                        decrypted
                    } catch (error: Exception) {
                        decrypted.delete()
                        throw error
                    }
                } else stagedSource

                val rendererDescriptor = ParcelFileDescriptor.open(
                    renderSource!!,
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                val androidRenderer = PdfRenderer(rendererDescriptor)
                val destination = PDDocument()
                try {
                    destination.use { rebuilt ->
                        for (index in 0 until pageCount) {
                            currentCoroutineContext().ensureActive()
                            val sourcePage = source.getPage(index)
                            val mediaBox = sourcePage.mediaBox
                            val pageWidthPt = mediaBox.width.toDouble().coerceAtLeast(1.0)
                            val pageHeightPt = mediaBox.height.toDouble().coerceAtLeast(1.0)
                            val referenceWidth = ceil(pageWidthPt * STANDARD_DPI / 72.0).toInt().coerceAtLeast(1)
                            val referenceHeight = ceil(pageHeightPt * STANDARD_DPI / 72.0).toInt().coerceAtLeast(1)
                            val profileSpec = profile.spec(maxMemoryBytes, referenceWidth, referenceHeight)
                            val requestedDpi = when (profile) {
                                ResolutionProfile.SMALLEST -> SMALLEST_DPI
                                ResolutionProfile.STANDARD -> STANDARD_DPI
                                ResolutionProfile.LARGEST -> if (profileSpec.fellBack) STANDARD_DPI else LARGEST_DPI
                                ResolutionProfile.HD_AUTO -> if (profileSpec.fellBack) STANDARD_DPI
                                    else if (profileSpec.targetWidthPx == null) LARGEST_DPI
                                    else maxOf(
                                        (profileSpec.targetWidthPx.toDouble() / pageWidthPt * 72.0).toInt(),
                                        ((profileSpec.targetHeightPx ?: profileSpec.targetWidthPx).toDouble() / pageHeightPt * 72.0).toInt()
                                    )
                            }
                            val dpi = boundedDpi(requestedDpi, pageWidthPt, pageHeightPt, maxMemoryBytes)
                            val bitmap = renderPage(androidRenderer, index, dpi, sourcePage.rotation)
                            try {
                                val jpeg = encodeJpeg(bitmap, profileSpec.jpegQuality)
                                val resultPage = PDPage(PDRectangle(mediaBox.width, mediaBox.height))
                                resultPage.rotation = normalizeRotation(sourcePage.rotation)
                                rebuilt.addPage(resultPage)
                                val image = PDImageXObject.createFromByteArray(
                                    rebuilt,
                                    jpeg,
                                    "compressed-page-" + (index + 1)
                                )
                                PDPageContentStream(rebuilt, resultPage).use { stream ->
                                    stream.drawImage(image, 0f, 0f, mediaBox.width, mediaBox.height)
                                }
                            } finally {
                                bitmap.recycle()
                            }
                            onProgress(index + 1, pageCount)
                        }
                        tempOutput.parentFile?.mkdirs()
                        rebuilt.save(tempOutput)
                    }
                } finally {
                    androidRenderer.close()
                    rendererDescriptor.close()
                }
            }

            currentCoroutineContext().ensureActive()
            if (!tempOutput.isFile || tempOutput.length() <= 0L) {
                throw IOException("Compression did not create a valid output PDF.")
            }
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists() && !outputFile.delete()) throw IOException("Unable to replace the output PDF.")
            if (!tempOutput.renameTo(outputFile)) {
                tempOutput.copyTo(outputFile, overwrite = true)
                tempOutput.delete()
            }
            outputWasCommitted = true
            val afterBytes = outputFile.length()
            Result.success(
                CompressionReport(
                    outputFile = outputFile,
                    pageCount = pageCount,
                    beforeBytes = beforeBytes,
                    afterBytes = afterBytes,
                    reductionPercent = if (beforeBytes == 0L) 0.0
                    else ((beforeBytes - afterBytes).toDouble() / beforeBytes) * 100.0
                )
            )
        } catch (cancelled: CancellationException) {
            tempOutput.delete()
            throw cancelled
        } catch (error: Exception) {
            tempOutput.delete()
            Result.failure(error)
        } finally {
            if (!outputWasCommitted) tempOutput.delete()
            if (renderSource != null && renderSource != stagedSource) renderSource?.delete()
            stagedSource?.delete()
        }
    }

    private fun stageSource(uri: Uri): File {
        val target = File.createTempFile("compress_source_", ".pdf", context.cacheDir)
        try {
            val input = if (uri.scheme == "file") {
                File(uri.path ?: throw IOException("The selected file path is missing.")).inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Unable to open the selected PDF.")
            }
            input.use { source -> FileOutputStream(target).use { output -> source.copyTo(output) } }
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun renderPage(
        renderer: PdfRenderer,
        index: Int,
        dpi: Int,
        rotation: Int
    ): Bitmap {
        renderer.openPage(index).use { page ->
            val width = ceil(page.width.toDouble() * dpi / 72.0).toInt().coerceAtLeast(1)
            val height = ceil(page.height.toDouble() * dpi / 72.0).toInt().coerceAtLeast(1)
            val candidate = createWhiteBackgroundBitmap(width, height)
            try {
                page.render(candidate, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                return normalizeRenderedRotation(candidate, rotation)
            } catch (error: Exception) {
                candidate.recycle()
                throw error
            }
        }
    }

    private fun normalizeRenderedRotation(bitmap: Bitmap, rotation: Int): Bitmap {
        val normalized = when (normalizeRotation(rotation)) {
            90 -> rotate(bitmap, -90f)
            180 -> rotate(bitmap, -180f)
            270 -> rotate(bitmap, -270f)
            else -> bitmap
        }
        if (normalized !== bitmap) bitmap.recycle()
        return normalized
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), output)) {
            throw IOException("Unable to encode a PDF page as JPEG.")
        }
        return output.toByteArray()
    }

    /** Preflight keeps each decoded page within one quarter of the available memory. */
    private fun boundedDpi(requestedDpi: Int, widthPt: Double, heightPt: Double, memoryBytes: Long): Int {
        val safeBytes = (memoryBytes.coerceAtLeast(16L * 1024L * 1024L) / MEMORY_SAFETY_DIVISOR)
            .coerceAtLeast(4L * 1024L * 1024L)
        val allowedPixels = (safeBytes / BYTES_PER_PIXEL).coerceAtLeast(1L).toDouble()
        val memoryDpi = sqrt(allowedPixels * 72.0 * 72.0 / (widthPt * heightPt))
            .toInt().coerceAtLeast(MINIMUM_DPI)
        return minOf(requestedDpi.coerceAtLeast(MINIMUM_DPI), memoryDpi, MAXIMUM_DPI)
    }

    private fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    companion object {
        const val SMALLEST_DPI = 150
        const val STANDARD_DPI = 300
        const val LARGEST_DPI = 600
        private const val MINIMUM_DPI = 72
        private const val MAXIMUM_DPI = 600
        private const val BYTES_PER_PIXEL = 4L
        private const val MEMORY_SAFETY_DIVISOR = 4L
    }
}

data class CompressionReport(
    val outputFile: File,
    val pageCount: Int,
    val beforeBytes: Long,
    val afterBytes: Long,
    val reductionPercent: Double
)

class PdfPasswordRequiredException(message: String, cause: Throwable? = null) : IOException(message, cause)
