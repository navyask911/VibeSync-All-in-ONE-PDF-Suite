package com.vibesync.toolkit.pdf.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

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
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "split_src_")
            val outputFiles = mutableListOf<File>()

            PDDocument.load(sourceFile).use { document ->
                val totalPages = document.numberOfPages

                pageIntervals.forEachIndexed { index, range ->
                    val start = range.first.coerceIn(1, totalPages) - 1
                    val end = range.last.coerceIn(1, totalPages) - 1

                    if (start <= end) {
                        val subDoc = PDDocument()
                        for (i in start..end) {
                            subDoc.importPage(document.getPage(i))
                        }
                        val splitFile = UriUtils.createExportCacheFile(context, "split_part_${index + 1}")
                        subDoc.save(splitFile)
                        subDoc.close()
                        outputFiles.add(splitFile)
                    }
                }
            }
            sourceFile.delete()
            Result.success(outputFiles)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to split PDF", e)
            Result.failure(e)
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
        try {
            val merger = PDFMergerUtility()
            merger.destinationFileName = outputFile.absolutePath

            for (uri in sourceUris) {
                val tempFile = UriUtils.copyUriToCacheFile(context, uri, "merge_src_")
                tempFiles.add(tempFile)
                merger.addSource(tempFile)
            }

            // Execute merge using main memory with temp-file overflow support
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to merge PDFs", e)
            Result.failure(e)
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }

    /**
     * Extracts all embedded raster images (XObjects) from a PDF to PNG files.
     */
    suspend fun extractEmbeddedImages(
        sourceUri: Uri
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "extract_src_")
            val extractedFiles = mutableListOf<File>()
            val cacheDir = File(context.cacheDir, "pdf_cache/extracted_images").apply { if (!exists()) mkdirs() }

            PDDocument.load(sourceFile).use { document ->
                var imageIndex = 1
                for (pageIndex in 0 until document.numberOfPages) {
                    val page = document.getPage(pageIndex)
                    val resources = page.resources ?: continue

                    for (xName in resources.xObjectNames) {
                        val xObject = resources.getXObject(xName)
                        if (xObject is PDImageXObject) {
                            val bitmap = xObject.image
                            val imageFile = File(cacheDir, "img_p${pageIndex + 1}_${imageIndex++}.png")
                            FileOutputStream(imageFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            extractedFiles.add(imageFile)
                        }
                    }
                }
            }
            sourceFile.delete()
            if (extractedFiles.isEmpty()) {
                Result.failure(IllegalArgumentException("No embedded raster images were found in this PDF"))
            } else {
                Result.success(extractedFiles)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract embedded images", e)
            Result.failure(e)
        }
    }

    /**
     * Compiles multiple selected gallery images into an A4/Letter margin PDF.
     */
    suspend fun convertImagesToPdf(
        imageUris: List<Uri>,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (imageUris.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Select at least one image"))
            }

            val document = PDDocument()
            var decodedImageCount = 0

            for (imageUri in imageUris) {
                val sourceFile = UriUtils.copyUriToCacheFile(context, imageUri, "image_src_")
                val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
                sourceFile.delete()

                if (bitmap != null) {
                    decodedImageCount++
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
                }
            }

            if (decodedImageCount == 0) {
                document.close()
                return@withContext Result.failure(IllegalArgumentException("None of the selected images could be decoded"))
            }

            document.save(outputFile)
            document.close()
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert images to PDF", e)
            Result.failure(e)
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
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "encrypt_src_")
            PDDocument.load(sourceFile).use { document ->
                val permissions = AccessPermission().apply {
                    setCanPrint(allowPrinting)
                    setCanExtractContent(allowCopying)
                    setCanModify(!allowPrinting && !allowCopying)
                }

                // Standard 128-bit / 256-bit AES protection policy
                val policy = StandardProtectionPolicy(ownerPassword, userPassword, permissions)
                policy.encryptionKeyLength = 128
                policy.permissions = permissions
                document.protect(policy)

                document.save(outputFile)
            }
            sourceFile.delete()
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt PDF", e)
            Result.failure(e)
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
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "decrypt_src_")
            PDDocument.load(sourceFile, password).use { document ->
                document.isAllSecurityToBeRemoved = true
                document.save(outputFile)
            }
            sourceFile.delete()
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt PDF with provided credentials", e)
            Result.failure(e)
        }
    }

    /**
     * Overlays a subtle diagonal text watermark onto every page of the document.
     */
    suspend fun watermarkPdf(
        sourceUri: Uri,
        watermarkText: String,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "watermark_src_")
            PDDocument.load(sourceFile).use { document ->
                val font = PDType1Font.HELVETICA_BOLD
                val fontSize = 42f

                for (page in document.pages) {
                    val mediaBox = page.mediaBox
                    PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        val graphicsState = PDExtendedGraphicsState().apply {
                            nonStrokingAlphaConstant = 0.20f
                        }
                        cs.setGraphicsStateParameters(graphicsState)
                        cs.setNonStrokingColor(128, 128, 128)

                        cs.beginText()
                        cs.setFont(font, fontSize)
                        // Position in center rotated at 45 degrees
                        val centerX = mediaBox.width / 4
                        val centerY = mediaBox.height / 2
                        cs.setTextMatrix(0.707, 0.707, -0.707, 0.707, centerX.toDouble(), centerY.toDouble())
                        cs.showText(watermarkText)
                        cs.endText()
                    }
                }
                document.save(outputFile)
            }
            sourceFile.delete()
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to watermark PDF", e)
            Result.failure(e)
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
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "flatten_src_")
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

                document.save(outputFile)
            }
            sourceFile.delete()
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flatten annotations onto PDF", e)
            Result.failure(e)
        }
    }

    /**
     * Extracts AcroForm fields from an interactive PDF.
     */
    suspend fun getAcroFormFields(sourceUri: Uri): Result<List<FormFieldItem>> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "acro_src_")
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
            sourceFile.delete()
            Result.success(fieldList)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract AcroForm fields", e)
            Result.failure(e)
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
        try {
            val sourceFile = UriUtils.copyUriToCacheFile(context, sourceUri, "acro_fill_")
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
                document.save(outputFile)
            }
            sourceFile.delete()
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fill AcroForm", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "PdfBoxToolkitEngine"
    }
}
