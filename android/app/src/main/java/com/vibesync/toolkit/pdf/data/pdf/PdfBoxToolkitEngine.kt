package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer as AndroidPdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.net.Uri
import android.util.Log
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.billing.BillingManagerProvider
import com.vibesync.toolkit.pdf.data.billing.LocalAdBillingManager
import com.vibesync.toolkit.pdf.data.model.FormFieldItem
import com.vibesync.toolkit.pdf.data.model.FormFieldType
import com.vibesync.toolkit.pdf.data.model.PageAnnotations
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.vibesync.toolkit.pdf.R
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
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
import kotlin.math.roundToInt
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Production-ready clean-room operational engine utilizing Apache PDFBox-Android (Apache 2.0).
 * Implements Split, Merge, Extract Images, Convert Images, Encrypt/Decrypt, Form Fill,
 * and print-resolution raster flattening of edited PDF pages.
 */
class PdfBoxToolkitEngine(
    private val context: Context,
    private val billingManager: LocalAdBillingManager? = BillingManagerProvider.current()
) {

    /**
     * Splits a PDF document by specified page ranges (e.g., "1-3", "4", "5-8").
     */
    private fun importPagePreservingWatermark(
        destinationDocument: PDDocument,
        sourceDocument: PDDocument,
        sourcePage: PDPage
    ): PDPage {
        val sourceDocumentWasWatermarked = sourceDocument.documentInformation
            .getCustomMetadataValue(WATERMARK_METADATA_KEY) == WATERMARK_METADATA_VALUE
        val sourcePageWasWatermarked = sourcePage.cosObject
            .getString(WATERMARK_PAGE_METADATA_COS_KEY) == WATERMARK_METADATA_VALUE
        val importedPage = destinationDocument.importPage(sourcePage)
        if (sourceDocumentWasWatermarked || sourcePageWasWatermarked) {
            importedPage.cosObject.setString(WATERMARK_PAGE_METADATA_COS_KEY, WATERMARK_METADATA_VALUE)
        }
        return importedPage
    }

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
                                importPagePreservingWatermark(subDoc, document, document.getPage(i))
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
        val openDocuments = mutableListOf<PDDocument>()
        val mergedDocument = PDDocument()
        val resourceCloner = PDFCloneUtility(mergedDocument)
        try {
            if (sourceUris.isEmpty()) {
                outputFile.delete()
                return@withContext Result.failure(IllegalArgumentException(context.getString(R.string.error_select_at_least_one_pdf)))
            }

            for (uri in sourceUris) {
                val tempFile = UriUtils.copyUriToCacheFile(context, uri, "merge_src_")
                tempFiles.add(tempFile)
                val sourceDocument = PDDocument.load(tempFile)
                openDocuments.add(sourceDocument)

                for (pageIndex in 0 until sourceDocument.numberOfPages) {
                    val sourcePage = sourceDocument.getPage(pageIndex)
                    val importedPage = importPagePreservingWatermark(mergedDocument, sourceDocument, sourcePage)
                    sourcePage.resources?.let { sourceResources ->
                        val clonedResources = resourceCloner.cloneForNewDocument(sourceResources.cosObject) as COSDictionary
                        importedPage.resources = PDResources(clonedResources)
                    }
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
            openDocuments.asReversed().forEach { source -> runCatching { source.close() } }
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
        outputFile: File,
        profile: ResolutionProfile = ResolutionProfilePrefs(context).read()
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
                    decodeImageForProfile(sourceFile, profile)
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
     * Decodes image-to-PDF input within the selected output profile's pixel bounds.
     * Small source images are not enlarged; larger images are sampled/scaled before PDF embedding.
     */
    private fun decodeImageForProfile(sourceFile: File, profile: ResolutionProfile): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val spec = profile.spec(
            Runtime.getRuntime().maxMemory(),
            bounds.outWidth,
            bounds.outHeight
        )
        val targetWidth = minOf(spec.targetWidthPx ?: bounds.outWidth, bounds.outWidth).coerceAtLeast(1)
        val targetHeight = minOf(spec.targetHeightPx ?: bounds.outHeight, bounds.outHeight).coerceAtLeast(1)
        val maxDimension = maxOf(targetWidth, targetHeight)
        val decoded = PdfBitmapUtils.decodeFileDownsampled(sourceFile.absolutePath, maxDimension) ?: return null
        try {
            val scale = minOf(
                1f,
                targetWidth.toFloat() / decoded.width.toFloat(),
                targetHeight.toFloat() / decoded.height.toFloat()
            )
            val outputWidth = (decoded.width * scale).roundToInt().coerceAtLeast(1)
            val outputHeight = (decoded.height * scale).roundToInt().coerceAtLeast(1)
            if (outputWidth == decoded.width && outputHeight == decoded.height) return decoded
            return Bitmap.createScaledBitmap(decoded, outputWidth, outputHeight, true).also {
                if (it !== decoded && !decoded.isRecycled) decoded.recycle()
            }
        } catch (error: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw error
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
        pageRanges: List<IntRange>? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<PdfBatchAssemblyResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        var temporaryOutput: File? = null
        try {
            require(pageFiles.isNotEmpty()) { context.getString(R.string.batch_no_pages) }
            val selectedPageFiles = if (pageRanges == null) {
                pageFiles
            } else {
                require(pageRanges.isNotEmpty()) { "Select at least one page." }
                val selectedIndexes = pageRanges.flatMap { range ->
                    require(range.first >= 1 && range.last <= pageFiles.size && range.first <= range.last) {
                        "The selected page range is outside the available pages."
                    }
                    range.map { it - 1 }
                }.distinct()
                require(selectedIndexes.isNotEmpty()) { "Select at least one page." }
                selectedIndexes.map(pageFiles::get)
            }
            val memoryLimit = Runtime.getRuntime().maxMemory() / 4L
            var estimatedBytes = 0L
            selectedPageFiles.forEach { file ->
                currentCoroutineContext().ensureActive()
                require(file.isFile && file.length() > 0L) { context.getString(R.string.batch_page_missing) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { context.getString(R.string.batch_page_decode_failed) }
                val sampleSize = PdfBitmapUtils.calculateInSampleSize(bounds)
                val decodedWidth = (bounds.outWidth / sampleSize).coerceAtLeast(1)
                val decodedHeight = (bounds.outHeight / sampleSize).coerceAtLeast(1)
                val pageBytes = decodedWidth.toLong() * decodedHeight.toLong() * 4L
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

            selectedPageFiles.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                var bitmap: Bitmap? = PdfBitmapUtils.decodeFileDownsampled(file.absolutePath)
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
                onProgress((index + 1).toFloat() / selectedPageFiles.size.toFloat())
            }

            currentCoroutineContext().ensureActive()
            applyVibeSyncWatermark(requireNotNull(document))
            requireNotNull(document).save(requireNotNull(temporaryOutput))
            document.close()
            document = null
            currentCoroutineContext().ensureActive()
            if (outputFile.exists() && !outputFile.delete()) throw IOException("Could not replace the previous batch PDF.")
            if (!requireNotNull(temporaryOutput).renameTo(outputFile)) {
                requireNotNull(temporaryOutput).copyTo(outputFile, overwrite = true)
                requireNotNull(temporaryOutput).delete()
            }
            Result.success(PdfBatchAssemblyResult(outputFile, selectedPageFiles.size, fellBack))
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
        outputFile: File,
        oldPassword: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        var sourceFile: File? = null
        try {
            require(userPassword.isNotEmpty()) { "User password cannot be empty" }
            require(ownerPassword.isNotEmpty()) { "Owner password cannot be empty" }

            // PDFBox 2.x encodes AES-128 (revision 4) passwords as ISO-8859-1.
            // Validate the encoding instead of silently replacing unsupported characters.
            val encodedUserPassword = requireAes128Password(userPassword, "User")
            val encodedOwnerPassword = requireAes128Password(ownerPassword, "Owner")

            ensureBouncyCastleProvider()

            sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "encrypt_src_")
            val permissions = AccessPermission().apply {
                setCanPrint(allowPrinting)
                setCanExtractContent(allowCopying)
                setCanModify(!allowPrinting && !allowCopying)
            }
            sourceFile.inputStream().use { inputStream ->
                encryptPdfOverwritingExisting(
                    inputStream = inputStream,
                    outputFile = outputFile,
                    oldPassword = oldPassword,
                    newPassword = encodedUserPassword,
                    ownerPassword = encodedOwnerPassword,
                    permissions = permissions
                )
            }

            // Fail the operation immediately if the produced file cannot be reopened
            // with both credentials. This prevents broken encrypted PDFs reaching users.
            PDDocument.load(outputFile, encodedUserPassword).use { userDocument ->
                check(userDocument.isEncrypted) { "Encrypted output did not retain PDF security" }
                check(userDocument.currentAccessPermission.canPrint() == allowPrinting) {
                    "Encrypted output printing permission does not match requested policy"
                }
                check(userDocument.currentAccessPermission.canExtractContent() == allowCopying) {
                    "Encrypted output extraction permission does not match requested policy"
                }
            }
            PDDocument.load(outputFile, encodedOwnerPassword).use { ownerDocument ->
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
     * Reads all source bytes once so PDFBox can safely retry loading with a blank password.
     */
    private fun loadPdfFromInputStream(inputStream: InputStream, oldPassword: String?): PDDocument {
        val bytes = inputStream.readBytes()
        return try {
            PDDocument.load(bytes, oldPassword ?: "")
        } catch (error: InvalidPasswordException) {
            if (oldPassword != null) {
                PDDocument.load(bytes, "")
            } else {
                throw error
            }
        }
    }

    /**
     * Watermarks the decrypted source, clears prior security, then applies the replacement
     * AES-128 policy. PDDocument.protect() re-enables encryption for the final save.
     */
    private fun encryptPdfOverwritingExisting(
        inputStream: InputStream,
        outputFile: File,
        oldPassword: String?,
        newPassword: String,
        ownerPassword: String = newPassword,
        permissions: AccessPermission? = null
    ) {
        val document = loadPdfFromInputStream(inputStream, oldPassword)
        try {
            document.isAllSecurityToBeRemoved = true
            applyVibeSyncWatermark(document)

            val protectionPolicy = StandardProtectionPolicy(
                ownerPassword,
                newPassword,
                permissions ?: document.currentAccessPermission
            ).apply {
                encryptionKeyLength = 128
                setPreferAES(true)
            }
            document.protect(protectionPolicy)
            document.save(outputFile)
        } finally {
            document.close()
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
                applyVibeSyncWatermark(document)
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
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "watermark_src_")
            sourceFileToDelete = sourceFile
            var copyOriginalBytes = false
            sourceFile.inputStream().use { inputStream ->
                loadPdfFromInputStream(inputStream, oldPassword = null).use { document ->
                    val alreadyWatermarked =
                        document.documentInformation.getCustomMetadataValue(WATERMARK_METADATA_KEY) == WATERMARK_METADATA_VALUE ||
                            (document.numberOfPages > 0 && document.pages.all { page ->
                                page.cosObject.getString(WATERMARK_PAGE_METADATA_COS_KEY) == WATERMARK_METADATA_VALUE
                            })
                    if (alreadyWatermarked) {
                        // A VibeSync-generated PDF already contains the entitlement-aware watermark.
                        // Do not serialize it again during sharing: preserve original page geometry,
                        // rotation, metadata, and all existing PDF objects byte-for-byte.
                        copyOriginalBytes = true
                    } else {
                        applyVibeSyncWatermark(document, watermarkText, opacity, angleDegrees)
                        val watermarkWasApplied =
                            document.documentInformation.getCustomMetadataValue(WATERMARK_METADATA_KEY) == WATERMARK_METADATA_VALUE
                        if (watermarkWasApplied) {
                            document.save(outputFile)
                        } else {
                            // Active Pro users may share an unwatermarked document when no custom
                            // watermark was requested. Preserve that source rather than needlessly
                            // rewriting it.
                            copyOriginalBytes = true
                        }
                    }
                }
            }
            if (copyOriginalBytes) sourceFile.copyTo(outputFile, overwrite = true)
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
     * Composites strokes, highlighters, text and signatures into annotated PDF page bitmaps at
     * up to 300 DPI, then writes the resulting document through a managed output stream.
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
                compositeAnnotatedPages(sourceFile, document, pageAnnotationsMap, screenWidthPx)
                applyVibeSyncWatermark(document)
                FileOutputStream(outputFile).use { outputStream -> document.save(outputStream) }
            }
            Result.success(outputFile)
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (error: Exception) {
            outputFile.delete()
            Log.e(TAG, "Failed to flatten annotations onto PDF", error)
            Result.failure(error)
        } finally {
            sourceFileToDelete?.delete()
        }
    }

    /**
     * Rasterizes annotated pages at up to 300 DPI and composites the complete editor layer.
     * PdfRenderer, its file descriptor, and each open page have deterministic close scopes.
     */
    private suspend fun compositeAnnotatedPages(
        sourceFile: File,
        document: PDDocument,
        pageAnnotationsMap: Map<Int, PageAnnotations>,
        screenWidthPx: Float
    ) {
        val annotatedPages = pageAnnotationsMap.filter { (pageIndex, annotations) ->
            pageIndex in 0 until document.numberOfPages && !annotations.isEmpty
        }
        if (annotatedPages.isEmpty()) return

        ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            AndroidPdfRenderer(descriptor).use { renderer ->
                annotatedPages.toSortedMap().forEach { (pageIndex, annotations) ->
                    currentCoroutineContext().ensureActive()
                    if (pageIndex !in 0 until renderer.pageCount) return@forEach

                    renderer.openPage(pageIndex).use { renderedPage ->
                        // Android PdfRenderer exposes page dimensions as rounded integer pixels.
                        // Preserve PDFBox's precise point dimensions for the output MediaBox so
                        // A4 (595.27563 x 841.8898 pt) does not silently become (595 x 842 pt).
                        val outputPage = document.getPage(pageIndex)
                        val sourceMediaBox = outputPage.mediaBox
                        val isQuarterTurned = outputPage.rotation % 180 != 0
                        val pageWidthPt = (
                            if (isQuarterTurned) sourceMediaBox.height else sourceMediaBox.width
                        ).coerceAtLeast(1f)
                        val pageHeightPt = (
                            if (isQuarterTurned) sourceMediaBox.width else sourceMediaBox.height
                        ).coerceAtLeast(1f)
                        val rendererWidthPx = renderedPage.width.toFloat().coerceAtLeast(1f)
                        val rendererHeightPx = renderedPage.height.toFloat().coerceAtLeast(1f)
                        val maxScale = kotlin.math.sqrt(
                            MAX_EDITOR_EXPORT_PIXELS.toDouble() /
                                (pageWidthPt.toDouble() * pageHeightPt.toDouble())
                        ).toFloat()
                        val renderScale = minOf(EDITOR_EXPORT_DPI / 72f, maxScale).coerceAtLeast(0.001f)
                        val bitmap = Bitmap.createBitmap(
                            ceil(pageWidthPt * renderScale).toInt().coerceAtLeast(1),
                            ceil(pageHeightPt * renderScale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            val matrix = Matrix().apply {
                                // Scale from the renderer's rounded raster extent into the bitmap
                                // whose point-space dimensions came from the original MediaBox.
                                setScale(
                                    bitmap.width / rendererWidthPx,
                                    bitmap.height / rendererHeightPx
                                )
                            }
                            renderedPage.render(
                                bitmap, null, matrix,
                                AndroidPdfRenderer.Page.RENDER_MODE_FOR_PRINT
                            )
                            drawEditorAnnotations(bitmap, annotations, screenWidthPx)

                            outputPage.mediaBox = PDRectangle(pageWidthPt, pageHeightPt)
                            outputPage.cropBox = outputPage.mediaBox
                            outputPage.rotation = 0
                            val image = LosslessFactory.createFromImage(document, bitmap)
                            PDPageContentStream(
                                document, outputPage,
                                PDPageContentStream.AppendMode.OVERWRITE, true, true
                            ).use { it.drawImage(image, 0f, 0f, pageWidthPt, pageHeightPt) }
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                    }
                }
            }
        }
    }

    private fun drawEditorAnnotations(
        bitmap: Bitmap,
        annotations: PageAnnotations,
        screenWidthPx: Float
    ) {
        val canvas = Canvas(bitmap)
        val pageWidthPx = bitmap.width.toFloat()
        val pageHeightPx = bitmap.height.toFloat()
        val safeScreenWidth = screenWidthPx.takeIf { it.isFinite() && it > 0f } ?: 595f
        val scaleX = pageWidthPx / safeScreenWidth
        val screenHeightPx = safeScreenWidth * pageHeightPx / pageWidthPx
        val scaleY = pageHeightPx / screenHeightPx
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }

        fun strokePath(points: List<com.vibesync.toolkit.pdf.data.model.Point2D>): Path =
            Path().apply {
                val first = points.first()
                moveTo(first.x.coerceIn(0f, 1f) * pageWidthPx, first.y.coerceIn(0f, 1f) * pageHeightPx)
                points.drop(1).forEach { point ->
                    lineTo(point.x.coerceIn(0f, 1f) * pageWidthPx, point.y.coerceIn(0f, 1f) * pageHeightPx)
                }
            }

        annotations.strokes.filter { it.isHighlighter }.forEach { stroke ->
            if (stroke.points.size >= 2) {
                paint.color = stroke.colorArgb
                paint.alpha = (stroke.alpha.coerceIn(0f, 1f) * 255f).roundToInt()
                paint.strokeWidth = (stroke.strokeWidth * pageWidthPx).coerceAtLeast(1f)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                canvas.drawPath(strokePath(stroke.points), paint)
            }
        }
        annotations.strokes.filter { !it.isHighlighter }.forEach { stroke ->
            if (stroke.points.size >= 2) {
                paint.color = stroke.colorArgb
                paint.alpha = (stroke.alpha.coerceIn(0f, 1f) * 255f).roundToInt()
                paint.strokeWidth = (stroke.strokeWidth * pageWidthPx).coerceAtLeast(1f)
                paint.xfermode = null
                canvas.drawPath(strokePath(stroke.points), paint)
            }
        }

        paint.xfermode = null
        paint.style = Paint.Style.FILL
        annotations.textAnnotations.forEach { annotation ->
            paint.color = annotation.colorArgb
            paint.textSize = (annotation.fontSizeSp * scaleX * 0.9f).coerceAtLeast(1f)
            canvas.drawText(
                annotation.text,
                annotation.x.coerceAtLeast(0f) * scaleX,
                annotation.y.coerceAtLeast(0f) * scaleY + paint.textSize,
                paint
            )
        }
        annotations.signatures.forEach { signature ->
            val signatureBitmap = signature.bitmap
            if (signatureBitmap != null && !signatureBitmap.isRecycled) {
                val destination = RectF(
                    signature.x.coerceAtLeast(0f) * scaleX,
                    signature.y.coerceAtLeast(0f) * scaleY,
                    (signature.x + signature.width).coerceAtLeast(0f) * scaleX,
                    (signature.y + signature.height).coerceAtLeast(0f) * scaleY
                )
                canvas.drawBitmap(
                    signatureBitmap, null, destination,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
            }
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

    private fun forceDefaultFooterWatermark() {
        // Never read a free user's custom watermark values. Free exports always use the
        // original built-in footer; custom settings are reserved for an active Pro pass.
        context.getSharedPreferences(WATERMARK_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(WATERMARK_ENABLED_KEY, false)
            .apply()
    }

    private fun applyVibeSyncWatermark(
        document: PDDocument,
        watermarkText: String = DEFAULT_FOOTER_WATERMARK_TEXT,
        opacity: Float = DEFAULT_WATERMARK_OPACITY,
        angleDegrees: Float = 0f
    ) {
        val proActive = billingManager?.checkAdStatusForThisDevice() == true
        if (!proActive) {
            forceDefaultFooterWatermark()
            drawFooterWatermark(
                document = document,
                text = DEFAULT_FOOTER_WATERMARK_TEXT,
                opacity = DEFAULT_WATERMARK_OPACITY,
                angleDegrees = 0f
            )
            return
        }

        // Custom settings are read only after confirming the entitlement on this device.
        val preferences = context.getSharedPreferences(WATERMARK_PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(WATERMARK_ENABLED_KEY, false)) {
            return // Pro exports are watermark-free unless the user explicitly applies a custom mark.
        }

        val customText = preferences.getString(WATERMARK_TEXT_KEY, watermarkText) ?: watermarkText
        val customOpacity = preferences.getFloat(WATERMARK_OPACITY_KEY, opacity).coerceIn(0f, 1f)
        val customAngle = preferences.getFloat(WATERMARK_ANGLE_KEY, angleDegrees).coerceIn(0f, 90f)
        preferences.edit().putBoolean(WATERMARK_ENABLED_KEY, false).apply()

        drawFooterWatermark(
            document = document,
            text = customText,
            opacity = customOpacity,
            angleDegrees = customAngle
        )
    }

    private fun drawFooterWatermark(
        document: PDDocument,
        text: String,
        opacity: Float,
        angleDegrees: Float
    ) {
        val documentInfo = document.documentInformation
        // Legacy V2 documents carry a document-level marker. Treat their pages as stamped
        // when importing them; new documents also carry a per-page marker.
        if (documentInfo.getCustomMetadataValue(WATERMARK_METADATA_KEY) == WATERMARK_METADATA_VALUE) {
            return
        }
        if (text.isBlank()) return
        val pagesToWatermark = document.pages.filter { page ->
            page.cosObject.getString(WATERMARK_PAGE_METADATA_COS_KEY) != WATERMARK_METADATA_VALUE
        }
        if (pagesToWatermark.isEmpty()) {
            documentInfo.setCustomMetadataValue(WATERMARK_METADATA_KEY, WATERMARK_METADATA_VALUE)
            return
        }

        // Built-in Type1 fonts cannot encode Arabic, CJK, and many Indic glyphs. Shape the
        // localized footer with Android text layout and embed a transparent image in the PDF.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(142, 0, 0)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = 28f * WATERMARK_SIZE_SCALE
        }
        val measuredTextWidth = ceil(paint.measureText(text)).toInt().coerceAtLeast(1)
        val sidePaddingPx = 8
        val bitmap = Bitmap.createBitmap(
            measuredTextWidth + sidePaddingPx * 2,
            (ceil(paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt() + 8).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        var rotatedBitmap: Bitmap? = null
        try {
            val textLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, measuredTextWidth)
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

            val drawableBitmap = if (angleDegrees != 0f) {
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    Matrix().apply { postRotate(angleDegrees) },
                    true
                ).also { rotatedBitmap = it }
            } else {
                bitmap
            }
            val watermarkImage = LosslessFactory.createFromImage(document, drawableBitmap)
            for (page in pagesToWatermark) {
                val mediaBox = page.mediaBox
                val maxWidthPt = mediaBox.width * 0.85f
                val aspectRatio = drawableBitmap.width.toFloat() / drawableBitmap.height.toFloat().coerceAtLeast(1f)
                val targetHeightPt = minOf(9f * WATERMARK_SIZE_SCALE, maxWidthPt / aspectRatio)
                val targetWidthPt = targetHeightPt * aspectRatio
                val footer = FooterWatermarkLayout.topLeft(
                    pageWidth = mediaBox.width,
                    pageHeight = mediaBox.height,
                    watermarkWidth = targetWidthPt,
                    watermarkHeight = targetHeightPt,
                    margin = 14f,
                    bottomMargin = 10f
                )
                val x = mediaBox.lowerLeftX + footer.x
                val y = mediaBox.lowerLeftY + mediaBox.height - footer.topY - targetHeightPt

                val graphicsState = PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = opacity.coerceIn(0f, 1f)
                    strokingAlphaConstant = opacity.coerceIn(0f, 1f)
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
                page.cosObject.setString(WATERMARK_PAGE_METADATA_COS_KEY, WATERMARK_METADATA_VALUE)
            }
        } finally {
            rotatedBitmap?.let { if (!it.isRecycled) it.recycle() }
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        documentInfo.setCustomMetadataValue(WATERMARK_METADATA_KEY, WATERMARK_METADATA_VALUE)
    }

    private fun writeVibeSyncWatermarkedPng(sourceBitmap: Bitmap, outputFile: File) {
        val mutableBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException(context.getString(R.string.error_create_mutable_bitmap))

        try {
            val canvas = Canvas(mutableBitmap)
            val proActive = billingManager?.checkAdStatusForThisDevice() == true
            if (!proActive) {
                forceDefaultFooterWatermark()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(150, 40, 40, 40)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    textSize = (minOf(mutableBitmap.width, mutableBitmap.height) * 0.012f).coerceIn(10f, 36f) * WATERMARK_SIZE_SCALE
                }
                val watermarkText = DEFAULT_FOOTER_WATERMARK_TEXT
                val margin = (minOf(mutableBitmap.width, mutableBitmap.height) * 0.015f).coerceAtLeast(16f)
                val x = (mutableBitmap.width - margin - paint.measureText(watermarkText))
                    .coerceAtLeast(mutableBitmap.width * 0.5f)
                canvas.drawText(watermarkText, x, mutableBitmap.height - margin, paint)
            } else {
                // Pro users may export without a watermark. Apply a custom mark only for an
                // explicit one-shot request, and consume that request after reading its options.
                val preferences = context.getSharedPreferences(WATERMARK_PREFERENCES, Context.MODE_PRIVATE)
                if (preferences.getBoolean(WATERMARK_ENABLED_KEY, false)) {
                    val watermarkText = preferences.getString(
                        WATERMARK_TEXT_KEY,
                        DEFAULT_FOOTER_WATERMARK_TEXT
                    ) ?: DEFAULT_FOOTER_WATERMARK_TEXT
                    val opacity = preferences.getFloat(WATERMARK_OPACITY_KEY, DEFAULT_WATERMARK_OPACITY)
                        .coerceIn(0f, 1f)
                    val angle = preferences.getFloat(WATERMARK_ANGLE_KEY, 0f).coerceIn(0f, 90f)
                    preferences.edit().putBoolean(WATERMARK_ENABLED_KEY, false).apply()

                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb((opacity * 255f).toInt(), 40, 40, 40)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                        textSize = (minOf(mutableBitmap.width, mutableBitmap.height) * 0.012f).coerceIn(10f, 36f) * WATERMARK_SIZE_SCALE
                    }
                    val margin = (minOf(mutableBitmap.width, mutableBitmap.height) * 0.015f).coerceAtLeast(16f)
                    val x = (mutableBitmap.width - margin - paint.measureText(watermarkText))
                        .coerceAtLeast(mutableBitmap.width * 0.5f)
                    canvas.save()
                    canvas.rotate(angle, mutableBitmap.width / 2f, mutableBitmap.height / 2f)
                    canvas.drawText(watermarkText, x, mutableBitmap.height - margin, paint)
                    canvas.restore()
                }
            }

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

    private fun requireAes128Password(password: String, label: String): String {
        require(Charsets.ISO_8859_1.newEncoder().canEncode(password)) {
            "$label password contains characters unsupported by PDF AES-128 (ISO-8859-1). " +
                "Use Latin-1 characters or ASCII."
        }
        return password
    }

    companion object {
        const val DEFAULT_FOOTER_WATERMARK_TEXT = "VibeSync All in ONE PDF Suite"
        private const val TAG = "PdfBoxToolkitEngine"
        private const val WATERMARK_METADATA_KEY = "VibeSync-Watermarked-V2"
        private val WATERMARK_PAGE_METADATA_COS_KEY = COSName.getPDFName("VibeSync-Watermarked-V2-Page")
        private const val WATERMARK_METADATA_VALUE = "true"
        private const val WATERMARK_PREFERENCES = "pdf_watermark_options"
        private const val WATERMARK_TEXT_KEY = "watermark_text"
        private const val WATERMARK_OPACITY_KEY = "watermark_opacity"
        private const val WATERMARK_ANGLE_KEY = "watermark_angle"
        private const val WATERMARK_ENABLED_KEY = "watermark_custom_enabled"
        private const val WATERMARK_SIZE_SCALE = 1.5f
        private const val DEFAULT_WATERMARK_OPACITY = 0.45f
        private const val EDITOR_EXPORT_DPI = 300f
        private const val MAX_EDITOR_EXPORT_PIXELS = 8_000_000L
    }
}

/** Result details for the batch PDF compiler. */
data class PdfBatchAssemblyResult(val file: File, val pageCount: Int, val fellBack: Boolean)
