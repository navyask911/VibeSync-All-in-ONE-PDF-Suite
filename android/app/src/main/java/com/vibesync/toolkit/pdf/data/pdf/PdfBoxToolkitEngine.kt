package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.net.Uri
import android.util.Log
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.model.FormFieldItem
import com.vibesync.toolkit.pdf.data.model.FormFieldType
import com.vibesync.toolkit.pdf.data.model.PageAnnotations
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.vibesync.toolkit.pdf.R
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterEngine
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.ceil
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Production-ready clean-room operational engine utilizing Apache PDFBox-Android (Apache 2.0).
 * Implements Split, Merge, Extract Images, Convert Images, Encrypt/Decrypt, Form Fill,
 * and 72-DPI Vector Annotation Flattening.
 */
class PdfBoxToolkitEngine(private val context: Context) {

    /**
     * Splits a PDF document by specified page ranges (e.g., "1-3", "4", "5-8").
     */
    suspend fun splitPdf(
        sourceUri: Uri,
        pageIntervals: List<IntRange>
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        val outputFiles = mutableListOf<File>()
        var sourceFileToDelete: File? = null
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "split_src_")
            sourceFileToDelete = sourceFile

            PDDocument.load(sourceFile).use { document ->
                val totalPages = document.numberOfPages
                require(totalPages > 0) { "The source PDF contains no pages." }

                pageIntervals.forEachIndexed { index, range ->
                    val start = range.first.coerceIn(1, totalPages) - 1
                    val end = range.last.coerceIn(1, totalPages) - 1

                    if (start <= end) {
                        val subDoc = PDDocument()
                        val splitFile = UriUtils.createExportCacheFile(context, "split_part_${index + 1}")
                        outputFiles.add(splitFile)
                        try {
                            for (i in start..end) {
                                subDoc.importPage(document.getPage(i))
                            }
                            applyVibeSyncWatermark(subDoc)
                            subDoc.save(splitFile)
                        } finally {
                            subDoc.close()
                        }
                    }
                }
            }
            Result.success(outputFiles)
        } catch (cancelled: CancellationException) {
            outputFiles.forEach { it.delete() }
            throw cancelled
        } catch (e: Exception) {
            outputFiles.forEach { it.delete() }
            Log.e(TAG, "Failed to split PDF", e)
            Result.failure(e)
        } finally {
            sourceFileToDelete?.delete()
        }
    }

    /**
     * Merges multiple PDF files into a single ordered destination document.
     */
    suspend fun mergePdfs(
        sourceUris: List<Uri>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val tempFiles = mutableListOf<File>()
        // Imported pages can retain COSStream references owned by source documents.
        // Keep sources open until the destination has completed save().
        val sourceDocuments = mutableListOf<PDDocument>()
        val mergedDocument = PDDocument()
        try {
            if (sourceUris.isEmpty()) {
                outputFile.delete()
                return@withContext Result.failure(IllegalArgumentException(context.getString(R.string.error_select_at_least_one_pdf)))
            }

            for (uri in sourceUris) {
                val tempFile = UriUtils.copyUriToCacheFile(context, uri, "merge_src_")
                tempFiles.add(tempFile)
                val sourceDocument = PDDocument.load(tempFile)
                sourceDocuments.add(sourceDocument)

                for (pageIndex in 0 until sourceDocument.numberOfPages) {
                    val sourcePage = sourceDocument.getPage(pageIndex)
                    val importedPage = mergedDocument.importPage(sourcePage)
                    sourcePage.resources?.let { importedPage.resources = it }
                    importedPage.rotation = normalizePageRotation(sourcePage.rotation)
                }
            }

            applyVibeSyncWatermark(mergedDocument)
            mergedDocument.save(outputFile)
            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (e: Exception) {
            outputFile.delete()
            Log.e(TAG, "Failed to merge PDFs", e)
            Result.failure(e)
        } finally {
            runCatching { mergedDocument.close() }
            sourceDocuments.asReversed().forEach { source -> runCatching { source.close() } }
            tempFiles.forEach { it.delete() }
        }
    }

    /**
     * Extracts all embedded raster images (XObjects) from a PDF to PNG files.
     */
    suspend fun extractEmbeddedImages(
        sourceUri: Uri
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        val extractedFiles = mutableListOf<File>()
        var sourceFileToDelete: File? = null
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "extract_src_")
            sourceFileToDelete = sourceFile
            val cacheDir = File(context.cacheDir, "pdf_cache/extracted_images").apply {
                if (!exists() && !mkdirs()) throw java.io.IOException("Unable to prepare the image export directory.")
                if (!isDirectory) throw java.io.IOException("The image export path is not a directory.")
            }

            PDDocument.load(sourceFile).use { document ->
                var imageIndex = 1
                for (pageIndex in 0 until document.numberOfPages) {
                    val page = document.getPage(pageIndex)
                    val resources = page.resources ?: continue

                    for (xName in resources.xObjectNames) {
                        val xObject = resources.getXObject(xName)
                        if (xObject is PDImageXObject) {
                            val bitmap = xObject.image
                            val imageFile = File.createTempFile(
                                "img_p${pageIndex + 1}_${imageIndex++}_",
                                ".png",
                                cacheDir
                            )
                            // Track immediately so the operation-level finally/catch cleans a partial file too.
                            extractedFiles.add(imageFile)
                            writeVibeSyncWatermarkedPng(bitmap, imageFile)
                        }
                    }
                }
            }
            if (extractedFiles.isEmpty()) {
                Result.failure(IllegalArgumentException(context.getString(R.string.error_no_embedded_raster_images)))
            } else {
                Result.success(extractedFiles)
            }
        } catch (cancelled: CancellationException) {
            extractedFiles.forEach { it.delete() }
            throw cancelled
        } catch (e: Exception) {
            extractedFiles.forEach { it.delete() }
            Log.e(TAG, "Failed to extract embedded images", e)
            Result.failure(e)
        } finally {
            sourceFileToDelete?.delete()
        }
    }

    /**
     * Compiles multiple selected gallery images into an A4/Letter margin PDF.
     */
    suspend fun convertImagesToPdf(
        imageUris: List<Uri>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        var documentToClose: PDDocument? = null
        try {
            if (imageUris.isEmpty()) {
                outputFile.delete()
                return@withContext Result.failure(IllegalArgumentException(context.getString(R.string.error_select_at_least_one_image)))
            }

            val document = PDDocument().also { documentToClose = it }
            var decodedImageCount = 0

            for (imageUri in imageUris) {
                val sourceFile = UriUtils.copyUriToCacheFile(context, imageUri, "image_src_")
                val bitmap = try {
                    BitmapFactory.decodeFile(sourceFile.absolutePath)
                } finally {
                    sourceFile.delete()
                }

                if (bitmap != null) {
                    decodedImageCount++
                    try {
                    // Standard A4 dimensions in PDF Points (72 DPI)
                    val pageWidthPt = PDRectangle.A4.width
                    val pageHeightPt = PDRectangle.A4.height

                    val page = PDPage(PDRectangle.A4)
                    document.addPage(page)

                    val pdImage = LosslessFactory.createFromImage(document, bitmap)

                    // Calculate aspect ratio preserving fit with 36 pt (0.5 inch) margins
                    val margin = 36f
                    val maxDrawableWidth = pageWidthPt - (margin * 2)
                    val maxDrawableHeight = pageHeightPt - (margin * 2)

                    val widthRatio = maxDrawableWidth / bitmap.width.toFloat()
                    val heightRatio = maxDrawableHeight / bitmap.height.toFloat()
                    val scale = minOf(widthRatio, heightRatio)

                    val renderWidth = bitmap.width * scale
                    val renderHeight = bitmap.height * scale

                    // Center the image on page
                    val xOffset = margin + (maxDrawableWidth - renderWidth) / 2
                    val yOffset = margin + (maxDrawableHeight - renderHeight) / 2

                    PDPageContentStream(document, page).use { contentStream ->
                        contentStream.drawImage(pdImage, xOffset, yOffset, renderWidth, renderHeight)
                    }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            }

            if (decodedImageCount == 0) {
                outputFile.delete()
                return@withContext Result.failure(IllegalArgumentException(context.getString(R.string.error_decode_selected_images)))
            }

            applyVibeSyncWatermark(document)
            document.save(outputFile)
            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (e: Exception) {
            outputFile.delete()
            Log.e(TAG, "Failed to convert images to PDF", e)
            Result.failure(e)
        } finally {
            runCatching { documentToClose?.close() }
        }
    }

    /**
     * Compiles captured page rasters in their requested order. Only one decoded/transformed
     * bitmap is held at a time; the output is written to a sibling temporary file and published
     * only after PDFBox has closed and saved the complete document.
     */
    suspend fun assembleBatchPages(
        pageFiles: List<File>,
        outputFile: File,
        profile: ResolutionProfile,
        filterId: FilterId? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<PdfBatchAssemblyResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        var temporaryOutput: File? = null
        try {
            require(pageFiles.isNotEmpty()) { context.getString(R.string.batch_no_pages) }
            val memoryLimit = Runtime.getRuntime().maxMemory() / 4L
            var estimatedBytes = 0L
            pageFiles.forEach { file ->
                currentCoroutineContext().ensureActive()
                require(file.isFile && file.length() > 0L) { context.getString(R.string.batch_page_missing) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { context.getString(R.string.batch_page_decode_failed) }
                val pageBytes = bounds.outWidth.toLong() * bounds.outHeight.toLong() * 4L
                if (pageBytes > memoryLimit || estimatedBytes > memoryLimit - pageBytes) {
                    throw IOException(context.getString(R.string.batch_memory_limit))
                }
                estimatedBytes += pageBytes
            }

            val parent = outputFile.parentFile ?: context.cacheDir
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create output directory.")
            temporaryOutput = File.createTempFile("batch_compile_", ".pdf", parent)
            document = PDDocument()
            val filterEngine = if (filterId == null) null else FilterEngine()
            var fellBack = false

            pageFiles.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                var bitmap: Bitmap? = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw IOException(context.getString(R.string.batch_page_decode_failed))
                try {
                    val source = requireNotNull(bitmap)
                    val spec = profile.spec(Runtime.getRuntime().maxMemory(), source.width, source.height)
                    fellBack = fellBack || spec.fellBack
                    val scaled = spec.scaleBitmap(source)
                    if (scaled !== source) source.recycle()
                    bitmap = scaled

                    val selectedFilterEngine = filterEngine
                    if (filterId != null && selectedFilterEngine != null) {
                        val working = requireNotNull(bitmap)
                        val pixels = IntArray(working.width * working.height)
                        working.getPixels(pixels, 0, working.width, 0, 0, working.width, working.height)
                        val filteredPixels = selectedFilterEngine.apply(filterId, pixels, working.width, working.height)
                        val filtered = Bitmap.createBitmap(working.width, working.height, Bitmap.Config.ARGB_8888)
                        filtered.setPixels(filteredPixels, 0, filtered.width, 0, 0, filtered.width, filtered.height)
                        working.recycle()
                        bitmap = filtered
                    }

                    val page = PDPage(PDRectangle.A4)
                    requireNotNull(document).addPage(page)
                    val image = LosslessFactory.createFromImage(requireNotNull(document), requireNotNull(bitmap))
                    val margin = 36f
                    val availableWidth = PDRectangle.A4.width - margin * 2f
                    val availableHeight = PDRectangle.A4.height - margin * 2f
                    val scale = minOf(
                        availableWidth / requireNotNull(bitmap).width.toFloat(),
                        availableHeight / requireNotNull(bitmap).height.toFloat()
                    )
                    val drawWidth = requireNotNull(bitmap).width * scale
                    val drawHeight = requireNotNull(bitmap).height * scale
                    val x = margin + (availableWidth - drawWidth) / 2f
                    val y = margin + (availableHeight - drawHeight) / 2f
                    PDPageContentStream(requireNotNull(document), page).use { stream ->
                        stream.drawImage(image, x, y, drawWidth, drawHeight)
                    }
                } finally {
                    bitmap?.let { if (!it.isRecycled) it.recycle() }
                }
                onProgress((index + 1).toFloat() / pageFiles.size.toFloat())
            }

            currentCoroutineContext().ensureActive()
            requireNotNull(document).save(requireNotNull(temporaryOutput))
            document.close()
            document = null
            currentCoroutineContext().ensureActive()
            if (outputFile.exists() && !outputFile.delete()) throw IOException("Could not replace the previous batch PDF.")
            if (!requireNotNull(temporaryOutput).renameTo(outputFile)) {
                requireNotNull(temporaryOutput).copyTo(outputFile, overwrite = true)
                requireNotNull(temporaryOutput).delete()
            }
            Result.success(PdfBatchAssemblyResult(outputFile, pageFiles.size, fellBack))
        } catch (cancelled: CancellationException) {
            temporaryOutput?.delete()
            outputFile.delete()
            throw cancelled
        } catch (error: Exception) {
            temporaryOutput?.delete()
            outputFile.delete()
            Log.e(TAG, "Failed to assemble captured pages", error)
            Result.failure(error)
        } finally {
            runCatching { document?.close() }
        }
    }

    /**
     * Encrypts a PDF document with AES-128/256 standard security and permission flags.
     */
    suspend fun encryptPdf(
        sourceUri: Uri,
        userPassword: String,
        ownerPassword: String,
        allowPrinting: Boolean,
        allowCopying: Boolean,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        var sourceFile: File? = null
        try {
            require(userPassword.isNotEmpty()) { "User password cannot be empty" }
            require(ownerPassword.isNotEmpty()) { "Owner password cannot be empty" }

            ensureBouncyCastleProvider()

            sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "encrypt_src_")
            PDDocument.load(sourceFile).use { document ->
                val permissions = AccessPermission().apply {
                    setCanPrint(allowPrinting)
                    setCanExtractContent(allowCopying)
                    setCanModify(!allowPrinting && !allowCopying)
                }

                applyVibeSyncWatermark(document)

                // Use PDFBox's strongest standard password protection supported by
                // pdfbox-android 2.0.27.0: AES-256 (revision 6).
                val policy = StandardProtectionPolicy(ownerPassword, userPassword, permissions)
                policy.encryptionKeyLength = 256
                policy.setPreferAES(true)
                policy.permissions = permissions

                document.protect(policy)
                document.save(outputFile)
            }

            // Fail the operation immediately if the produced file cannot be reopened
            // with both credentials. This prevents broken encrypted PDFs reaching users.
            PDDocument.load(outputFile, userPassword).use { userDocument ->
                check(userDocument.isEncrypted) { "Encrypted output did not retain PDF security" }
                check(userDocument.currentAccessPermission.canPrint() == allowPrinting) {
                    "Encrypted output printing permission does not match requested policy"
                }
                check(userDocument.currentAccessPermission.canExtractContent() == allowCopying) {
                    "Encrypted output extraction permission does not match requested policy"
                }
            }
            PDDocument.load(outputFile, ownerPassword).use { ownerDocument ->
                check(ownerDocument.isEncrypted) { "Encrypted output did not retain PDF security" }
                check(ownerDocument.currentAccessPermission.isOwnerPermission) {
                    "Owner password did not grant owner access"
                }
            }

            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt PDF", e)
            outputFile.delete()
            Result.failure(e)
        } finally {
            sourceFile?.delete()
        }
    }

    /**
     * Decrypts a protected PDF document given the valid user or owner password.
     */
    suspend fun decryptPdf(
        sourceUri: Uri,
        password: String,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        var sourceFileToDelete: File? = null
        try {
            ensureBouncyCastleProvider()
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "decrypt_src_")
            sourceFileToDelete = sourceFile
            PDDocument.load(sourceFile, password).use { document ->
                document.isAllSecurityToBeRemoved = true
                applyVibeSyncWatermark(document)
                document.save(outputFile)
            }
            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (e: Exception) {
            outputFile.delete()
            Log.e(TAG, "Failed to decrypt PDF with provided credentials", e)
            Result.failure(e)
        } finally {
            sourceFileToDelete?.delete()
        }
    }

    /**
     * Rewrites a PDF through PDFBox's stream serializer into a fresh private output file.
     * This can remove redundant object layout and lets PDFBox apply its normal stream filters.
     */
    suspend fun compressPdf(sourceUri: Uri, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        var sourceFile: File? = null
        try {
            val stagedFile = UriUtils.copyUriToCacheFile(context, sourceUri, "compress_src")
            sourceFile = stagedFile
            PDDocument.load(stagedFile).use { document ->
                require(document.numberOfPages > 0) { "The source PDF contains no pages." }
                document.save(outputFile)
            }
            if (!outputFile.isFile || outputFile.length() <= 0L) {
                throw java.io.IOException("PDF compression did not produce a valid output.")
            }
            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (error: Exception) {
            outputFile.delete()
            Log.e(TAG, "Failed to compress PDF", error)
            Result.failure(error)
        } finally {
            sourceFile?.delete()
        }
    }

    /**
     * Adds the small VibeSync footer watermark to every PDF page.
     */
    suspend fun watermarkPdf(
        sourceUri: Uri,
        watermarkText: String,
        outputFile: File,
        opacity: Float = 150f / 255f,
        angleDegrees: Float = 0f
    ): Result<File> = withContext(Dispatchers.IO) {
        var sourceFileToDelete: File? = null
        val watermarkPreferences = context.getSharedPreferences("pdf_watermark_options", Context.MODE_PRIVATE)
        val useCustomOptions = watermarkPreferences.getBoolean("watermark_custom_enabled", false)
        val effectiveText = if (useCustomOptions) {
            watermarkPreferences.getString("watermark_text", watermarkText)?.takeIf { it.isNotBlank() } ?: watermarkText
        } else {
            watermarkText
        }
        val effectiveOpacity = (if (useCustomOptions) {
            watermarkPreferences.getFloat("watermark_opacity", opacity)
        } else {
            opacity
        }).coerceIn(0f, 1f)
        val effectiveAngle = (if (useCustomOptions) {
            watermarkPreferences.getFloat("watermark_angle", angleDegrees)
        } else {
            angleDegrees
        }).coerceIn(0f, 90f)
        if (useCustomOptions) {
            watermarkPreferences.edit().putBoolean("watermark_custom_enabled", false).apply()
        }

        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "watermark_src_")
            sourceFileToDelete = sourceFile
            PDDocument.load(sourceFile).use { document ->
                val documentInfo = document.documentInformation
                if (
                    documentInfo.getCustomMetadataValue(WATERMARK_METADATA_KEY) != WATERMARK_METADATA_VALUE &&
                    effectiveText.isNotBlank()
                ) {
                    // Android text layout shapes scripts that PDFBox's built-in fonts cannot encode.
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(142, 0, 0)
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                        textSize = 28f
                    }
                    val measuredTextWidth = ceil(paint.measureText(effectiveText)).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(
                        measuredTextWidth + 16,
                        (ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt() + 8).coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    try {
                        val textLayout = StaticLayout.Builder
                            .obtain(effectiveText, 0, effectiveText.length, paint, measuredTextWidth)
                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                            .setIncludePad(true)
                            .setMaxLines(1)
                            .build()
                        Canvas(bitmap).apply {
                            save()
                            translate(8f, ((bitmap.height - textLayout.height) / 2f).coerceAtLeast(0f))
                            textLayout.draw(this)
                            restore()
                        }

                        val watermarkBitmap = if (effectiveAngle == 0f) {
                            bitmap
                        } else {
                            Bitmap.createBitmap(
                                bitmap,
                                0,
                                0,
                                bitmap.width,
                                bitmap.height,
                                android.graphics.Matrix().apply { postRotate(effectiveAngle) },
                                true
                            )
                        }
                        try {
                            val watermarkImage = LosslessFactory.createFromImage(document, watermarkBitmap)
                            for (page in document.pages) {
                                val mediaBox = page.mediaBox
                                val maxWidthPt = mediaBox.width * 0.85f
                                val maxHeightPt = mediaBox.height * 0.72f
                                val scale = minOf(
                                    maxWidthPt / watermarkBitmap.width.toFloat().coerceAtLeast(1f),
                                    maxHeightPt / watermarkBitmap.height.toFloat().coerceAtLeast(1f)
                                )
                                val targetWidthPt = watermarkBitmap.width * scale
                                val targetHeightPt = watermarkBitmap.height * scale
                                val x = mediaBox.lowerLeftX + (mediaBox.width - targetWidthPt) / 2f
                                val y = mediaBox.lowerLeftY + (mediaBox.height - targetHeightPt) / 2f
                                val graphicsState = PDExtendedGraphicsState().apply {
                                    nonStrokingAlphaConstant = effectiveOpacity
                                    strokingAlphaConstant = effectiveOpacity
                                }
                                PDPageContentStream(
                                    document,
                                    page,
                                    PDPageContentStream.AppendMode.APPEND,
                                    true,
                                    true
                                ).use { stream ->
                                    stream.setGraphicsStateParameters(graphicsState)
                                    stream.drawImage(watermarkImage, x, y, targetWidthPt, targetHeightPt)
                                }
                            }
                        } finally {
                            if (watermarkBitmap !== bitmap && !watermarkBitmap.isRecycled) watermarkBitmap.recycle()
                        }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                    documentInfo.setCustomMetadataValue(WATERMARK_METADATA_KEY, WATERMARK_METADATA_VALUE)
                }
                document.save(outputFile)
            }
            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (e: Exception) {
            outputFile.delete()
            Log.e(TAG, "Failed to watermark PDF", e)
            Result.failure(e)
        } finally {
            sourceFileToDelete?.delete()
        }
    }

    /**
     * Flattens and bakes real-time vector annotations, highlighters, text boxes,
     * and digital signatures onto the original PDF stream mapped precisely at 72 DPI.
     */
    suspend fun flattenAnnotations(
        sourceUri: Uri,
        pageAnnotationsMap: Map<Int, PageAnnotations>,
        screenWidthPx: Float,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        var sourceFileToDelete: File? = null
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "flatten_src_")
            sourceFileToDelete = sourceFile
            PDDocument.load(sourceFile).use { document ->

                pageAnnotationsMap.forEach { (pageIndex, annotations) ->
                    if (pageIndex < document.numberOfPages && !annotations.isEmpty) {
                        val page = document.getPage(pageIndex)
                        val mediaBox = page.mediaBox
                        val pageWidthPt = mediaBox.width
                        val pageHeightPt = mediaBox.height

                        // Calculate horizontal and vertical scale factor from screen pixels to 72 DPI PDF points
                        val scaleFactor = pageWidthPt / screenWidthPx.coerceAtLeast(1f)

                        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->

                            // 1. Highlighters (semi-transparent stroke with graphics state)
                            annotations.strokes.filter { it.isHighlighter }.forEach { stroke ->
                                if (stroke.points.size >= 2) {
                                    val gs = PDExtendedGraphicsState().apply {
                                        strokingAlphaConstant = 0.35f
                                    }
                                    cs.setGraphicsStateParameters(gs)
                                    val r = Color.red(stroke.colorArgb) / 255f
                                    val g = Color.green(stroke.colorArgb) / 255f
                                    val b = Color.blue(stroke.colorArgb) / 255f
                                    cs.setStrokingColor(r, g, b)
                                    cs.setLineWidth(stroke.strokeWidth * scaleFactor)

                                    val first = stroke.points.first()
                                    val startPdfX = first.x * scaleFactor
                                    val startPdfY = pageHeightPt - (first.y * scaleFactor)
                                    cs.moveTo(startPdfX, startPdfY)

                                    for (i in 1 until stroke.points.size) {
                                        val pt = stroke.points[i]
                                        cs.lineTo(pt.x * scaleFactor, pageHeightPt - (pt.y * scaleFactor))
                                    }
                                    cs.stroke()
                                }
                            }

                            // 2. Freehand Pen Vector Strokes (Opaque)
                            annotations.strokes.filter { !it.isHighlighter }.forEach { stroke ->
                                if (stroke.points.size >= 2) {
                                    val gs = PDExtendedGraphicsState().apply {
                                        strokingAlphaConstant = 1.0f
                                    }
                                    cs.setGraphicsStateParameters(gs)
                                    val r = Color.red(stroke.colorArgb) / 255f
                                    val g = Color.green(stroke.colorArgb) / 255f
                                    val b = Color.blue(stroke.colorArgb) / 255f
                                    cs.setStrokingColor(r, g, b)
                                    cs.setLineWidth(stroke.strokeWidth * scaleFactor)

                                    val first = stroke.points.first()
                                    val startPdfX = first.x * scaleFactor
                                    val startPdfY = pageHeightPt - (first.y * scaleFactor)
                                    cs.moveTo(startPdfX, startPdfY)

                                    for (i in 1 until stroke.points.size) {
                                        val pt = stroke.points[i]
                                        cs.lineTo(pt.x * scaleFactor, pageHeightPt - (pt.y * scaleFactor))
                                    }
                                    cs.stroke()
                                }
                            }

                            // 3. Text Annotations
                            annotations.textAnnotations.forEach { textItem ->
                                cs.beginText()
                                cs.setFont(PDType1Font.HELVETICA_BOLD, textItem.fontSizeSp * scaleFactor * 0.9f)
                                val r = Color.red(textItem.colorArgb) / 255f
                                val g = Color.green(textItem.colorArgb) / 255f
                                val b = Color.blue(textItem.colorArgb) / 255f
                                cs.setNonStrokingColor(r, g, b)

                                val pdfX = textItem.x * scaleFactor
                                val pdfY = pageHeightPt - (textItem.y * scaleFactor)
                                cs.newLineAtOffset(pdfX, pdfY)
                                cs.showText(textItem.text)
                                cs.endText()
                            }

                            // 4. Digital Signatures (Vector / Bitmap stamps)
                            annotations.signatures.forEach { sig ->
                                val bitmap = sig.bitmap
                                if (bitmap != null && !bitmap.isRecycled) {
                                    val pdImage = LosslessFactory.createFromImage(document, bitmap)
                                    val sigPdfX = sig.x * scaleFactor
                                    val sigPdfY = pageHeightPt - ((sig.y + sig.height) * scaleFactor)
                                    val sigWidthPt = sig.width * scaleFactor
                                    val sigHeightPt = sig.height * scaleFactor
                                    cs.drawImage(pdImage, sigPdfX, sigPdfY, sigWidthPt, sigHeightPt)
                                }
                            }
                        }
                    }
                }

                applyVibeSyncWatermark(document)
                document.save(outputFile)
            }
            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (e: Exception) {
            outputFile.delete()
            Log.e(TAG, "Failed to flatten annotations onto PDF", e)
            Result.failure(e)
        } finally {
            sourceFileToDelete?.delete()
        }
    }

    /**
     * Extracts AcroForm fields from an interactive PDF.
     */
    suspend fun getAcroFormFields(sourceUri: Uri): Result<List<FormFieldItem>> = withContext(Dispatchers.IO) {
        var sourceFileToDelete: File? = null
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "acro_src_")
            sourceFileToDelete = sourceFile
            val fieldList = mutableListOf<FormFieldItem>()

            PDDocument.load(sourceFile).use { document ->
                val acroForm: PDAcroForm? = document.documentCatalog.acroForm
                if (acroForm != null) {
                    for (field in acroForm.fields) {
                        val type = when (field) {
                            is PDTextField -> FormFieldType.TEXT
                            is PDCheckBox -> FormFieldType.CHECKBOX
                            else -> FormFieldType.UNKNOWN
                        }
                        fieldList.add(
                            FormFieldItem(
                                fullyQualifiedName = field.fullyQualifiedName,
                                partialName = field.partialName ?: field.fullyQualifiedName,
                                value = field.valueAsString ?: "",
                                type = type,
                                isReadOnly = field.isReadOnly,
                                isRequired = field.isRequired
                            )
                        )
                    }
                }
            }
            Result.success(fieldList)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract AcroForm fields", e)
            Result.failure(e)
        } finally {
            sourceFileToDelete?.delete()
        }
    }

    /**
     * Updates AcroForm field values and optionally flattens them for archiving.
     */
    suspend fun updateAcroForm(
        sourceUri: Uri,
        fieldValues: Map<String, String>,
        flatten: Boolean,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        var sourceFileToDelete: File? = null
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "acro_fill_")
            sourceFileToDelete = sourceFile
            PDDocument.load(sourceFile).use { document ->
                val acroForm: PDAcroForm? = document.documentCatalog.acroForm
                if (acroForm != null) {
                    fieldValues.forEach { (name, value) ->
                        val field = acroForm.getField(name)
                        if (field is PDTextField) {
                            field.value = value
                        } else if (field is PDCheckBox) {
                            if (value.equals("true", ignoreCase = true) || value == "1" || value == "Yes") {
                                field.check()
                            } else {
                                field.unCheck()
                            }
                        }
                    }

                    if (flatten) {
                        acroForm.flatten()
                    }
                }
                applyVibeSyncWatermark(document)
                document.save(outputFile)
            }
            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (e: Exception) {
            outputFile.delete()
            Log.e(TAG, "Failed to fill AcroForm", e)
            Result.failure(e)
        } finally {
            sourceFileToDelete?.delete()
        }
    }

    private fun ensureBouncyCastleProvider() {
        val provider = Security.getProvider("BC")
        if (provider == null || provider.javaClass.name != BouncyCastleProvider::class.java.name) {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private fun normalizePageRotation(rotation: Int): Int {
        val normalized = rotation % 360
        return when {
            normalized < 0 -> normalized + 360
            normalized == 360 -> 0
            normalized % 90 == 0 -> normalized
            else -> ((normalized + 45) / 90 * 90) % 360
        }
    }

    private fun applyVibeSyncWatermark(
        document: PDDocument,
        watermarkText: String = context.getString(R.string.pdf_watermark_text)
    ) {
        val documentInfo = document.documentInformation
        if (documentInfo.getCustomMetadataValue(WATERMARK_METADATA_KEY) == WATERMARK_METADATA_VALUE) {
            return
        }
        if (watermarkText.isBlank()) return

        // Built-in Type1 fonts cannot encode Arabic, CJK, and many Indic glyphs. Shape the
        // localized footer with Android text layout and embed a transparent image in the PDF.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 142, 0, 0)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = 28f
        }
        val measuredTextWidth = ceil(paint.measureText(watermarkText)).toInt().coerceAtLeast(1)
        val sidePaddingPx = 8
        val bitmap = Bitmap.createBitmap(
            measuredTextWidth + sidePaddingPx * 2,
            (ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt() + 8).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        try {
            val textLayout = StaticLayout.Builder
                .obtain(watermarkText, 0, watermarkText.length, paint, measuredTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(true)
                .setMaxLines(1)
                .build()
            Canvas(bitmap).apply {
                save()
                translate(sidePaddingPx.toFloat(), ((bitmap.height - textLayout.height) / 2f).coerceAtLeast(0f))
                textLayout.draw(this)
                restore()
            }

            val watermarkImage = LosslessFactory.createFromImage(document, bitmap)
            for (page in document.pages) {
                val mediaBox = page.mediaBox
                val maxWidthPt = mediaBox.width * 0.85f
                val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
                val targetHeightPt = minOf(10f, maxWidthPt / aspectRatio)
                val targetWidthPt = targetHeightPt * aspectRatio
                val x = mediaBox.lowerLeftX + (mediaBox.width - targetWidthPt) / 2f
                val y = mediaBox.lowerLeftY + 6f

                PDPageContentStream(
                    document,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                ).use { stream ->
                    stream.drawImage(watermarkImage, x, y, targetWidthPt, targetHeightPt)
                }
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        documentInfo.setCustomMetadataValue(WATERMARK_METADATA_KEY, WATERMARK_METADATA_VALUE)
    }

    private fun writeVibeSyncWatermarkedPng(sourceBitmap: Bitmap, outputFile: File) {
        val mutableBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException(context.getString(R.string.error_create_mutable_bitmap))

        try {
            val canvas = Canvas(mutableBitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 142, 0, 0)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = (minOf(mutableBitmap.width, mutableBitmap.height) * 0.035f).coerceIn(10f, 22f)
            }

            val watermarkText = context.getString(R.string.pdf_watermark_text)
            val x = mutableBitmap.width / 2f - paint.measureText(watermarkText) / 2f
            val y = mutableBitmap.height - 10f
            canvas.drawText(watermarkText, x, y, paint)

            FileOutputStream(outputFile).use { output ->
                if (!mutableBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IllegalStateException(context.getString(R.string.error_write_watermarked_image))
                }
            }
        } catch (error: Exception) {
            outputFile.delete()
            throw error
        } finally {
            if (!mutableBitmap.isRecycled) mutableBitmap.recycle()
        }
    }

    companion object {
        private const val TAG = "PdfBoxToolkitEngine"
        private const val WATERMARK_METADATA_KEY = "VibeSync-Watermarked"
        private const val WATERMARK_METADATA_VALUE = "true"
    }
}

/** Result details for the batch PDF compiler. */
data class PdfBatchAssemblyResult(val file: File, val pageCount: Int, val fellBack: Boolean)
