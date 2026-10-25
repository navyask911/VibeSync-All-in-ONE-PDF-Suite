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
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
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

/** Bitmap-based PDF rebuild that trades raster dimensions and JPEG quality for a smaller file. */
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
                throw PdfPasswordRequiredException("This PDF is password-protected. Enter its password to compress it.", error)
            }
            var pageCount = 0
            sourceDocument.use { source ->
                pageCount = source.numberOfPages
                require(pageCount > 0) { "The source PDF contains no pages." }
                val rebuilt = PDDocument()
                rebuilt.use { destination ->
                    val pdfRenderer = PDFRenderer(source)
                    val androidRenderer = openAndroidRenderer(stagedSource, password)
                    try {
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
                            val bitmap = renderPage(androidRenderer, index, pdfRenderer, dpi, sourcePage.rotation)
                            try {
                                val jpeg = encodeJpeg(bitmap, profileSpec.jpegQuality)
                                val resultPage = PDPage(PDRectangle(mediaBox.width, mediaBox.height))
                                resultPage.mediaBox = PDRectangle(mediaBox.lowerLeftX, mediaBox.lowerLeftY, mediaBox.width, mediaBox.height)
                                resultPage.cropBox = PDRectangle(
                                    sourcePage.cropBox.lowerLeftX,
                                    sourcePage.cropBox.lowerLeftY,
                                    sourcePage.cropBox.width,
                                    sourcePage.cropBox.height
                                )
                                resultPage.rotation = normalizeRotation(sourcePage.rotation)
                                destination.addPage(resultPage)
                                val image = PDImageXObject.createFromByteArray(destination, jpeg, "compressed-page-\${index + 1}")
                                PDPageContentStream(destination, resultPage).use { stream ->
                                    stream.drawImage(image, mediaBox.lowerLeftX, mediaBox.lowerLeftY, mediaBox.width, mediaBox.height)
                                }
                            } finally {
                                bitmap.recycle()
                            }
                            onProgress(index + 1, pageCount)
                        }
                    } finally {
                        androidRenderer?.close()
                    }
                    tempOutput.parentFile?.mkdirs()
                    destination.save(tempOutput)
                }
            }
            currentCoroutineContext().ensureActive()
            if (!tempOutput.isFile || tempOutput.length() <= 0L) throw IOException("Compression did not create a valid PDF.")
            if (outputFile.exists() && !outputFile.delete()) throw IOException("Unable to replace the output PDF.")
            if (!tempOutput.renameTo(outputFile)) {
                tempOutput.copyTo(outputFile, overwrite = true)
                tempOutput.delete()
            }
            val afterBytes = outputFile.length()
            Result.success(
                CompressionReport(
                    outputFile = outputFile,
                    pageCount = pageCount,
                    beforeBytes = beforeBytes,
                    afterBytes = afterBytes,
                    reductionPercent = if (beforeBytes == 0L) 0.0 else ((beforeBytes - afterBytes).toDouble() / beforeBytes) * 100.0
                )
            )
        } catch (cancelled: CancellationException) {
            tempOutput.delete()
            outputFile.delete()
            throw cancelled
        } catch (error: Exception) {
            tempOutput.delete()
            outputFile.delete()
            Result.failure(error)
        } finally {
            stagedSource?.delete()
        }
    }

    private fun stageSource(uri: Uri): File {
        val target = File.createTempFile("compress_source_", ".pdf", context.cacheDir)
        try {
            val input = if (uri.scheme == "file") {
                File(uri.path ?: throw IOException("The selected file path is missing.")).inputStream()
            } else {
                context.contentResolver.openInputStream(uri) ?: throw IOException("Unable to open the selected PDF.")
            }
            input.use { source -> FileOutputStream(target).use { output -> source.copyTo(output) } }
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun openAndroidRenderer(file: File, password: String?): PdfRenderer? {
        if (!password.isNullOrEmpty()) return null
        return runCatching {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor)
        }.getOrNull()
    }

    private fun renderPage(
        androidRenderer: PdfRenderer?,
        index: Int,
        pdfBoxRenderer: PDFRenderer,
        dpi: Int,
        rotation: Int
    ): Bitmap {
        if (androidRenderer != null) {
            runCatching {
                androidRenderer.openPage(index).use { page ->
                    val width = ceil(page.width.toDouble() * dpi / 72.0).toInt().coerceAtLeast(1)
                    val height = ceil(page.height.toDouble() * dpi / 72.0).toInt().coerceAtLeast(1)
                    val candidate = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(candidate, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        return normalizeRenderedRotation(candidate, rotation)
                    } catch (error: Exception) {
                        candidate.recycle()
                        throw error
                    }
                }
            }.onFailure { /* PDFBox fallback handles files Android PdfRenderer cannot render. */ }
        }
        val candidate = pdfBoxRenderer.renderImageWithDPI(index, dpi.toFloat(), ImageType.RGB)
        return normalizeRenderedRotation(candidate, rotation)
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

    private fun boundedDpi(requestedDpi: Int, widthPt: Double, heightPt: Double, memoryBytes: Long): Int {
        val safeBytes = (memoryBytes.coerceAtLeast(16L * 1024L * 1024L) / MEMORY_SAFETY_DIVISOR).coerceAtLeast(4L * 1024L * 1024L)
        val allowedPixels = (safeBytes / BYTES_PER_PIXEL).coerceAtLeast(1L).toDouble()
        val memoryDpi = sqrt(allowedPixels * 72.0 * 72.0 / (widthPt * heightPt)).toInt().coerceAtLeast(MINIMUM_DPI)
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
