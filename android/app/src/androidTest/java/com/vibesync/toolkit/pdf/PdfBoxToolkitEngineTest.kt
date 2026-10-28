package com.vibesync.toolkit.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.data.model.DrawingStroke
import com.vibesync.toolkit.pdf.data.model.PageAnnotations
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfBoxToolkitEngineTest {

    private lateinit var context: Context
    private lateinit var tempFolder: File
    private lateinit var engine: PdfBoxToolkitEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempFolder = File(context.cacheDir, "pdf_engine_tests_" + System.nanoTime()).apply { mkdirs() }
        engine = PdfBoxToolkitEngine(context)
    }

    @After
    fun tearDown() {
        // Cleanup generated test files
        tempFolder.deleteRecursively()
    }

    private fun createSamplePdf(pageCount: Int, fileName: String): File {
        val file = File(tempFolder, fileName)
        PDDocument().use { document ->
            for (i in 1..pageCount) {
                document.addPage(PDPage(PDRectangle.A4))
            }
            document.save(file)
        }
        return file
    }

    @Test
    fun testPdfSplittingEdgeCases() = runTest {
        // Create 6-page source PDF
        val sourcePdf = createSamplePdf(6, "source_split.pdf")
        val sourceUri = Uri.fromFile(sourcePdf)

        // 1. Valid intervals, boundary limits (1..2, 4..4, 5..10 where max is 6), and out-of-order (7..2)
        val intervals = listOf(
            IntRange(1, 2),   // 2 pages
            IntRange(4, 4),   // 1 single page
            IntRange(5, 10),  // Boundary clamped to 5..6 (2 pages)
            IntRange(7, 2)    // Invalid / out-of-order range -> should safely yield 0 pages or omit
        )

        val result = engine.splitPdf(sourceUri, intervals)
        assertTrue("Splitting should succeed even with boundary ranges", result.isSuccess)

        val generatedFiles = result.getOrThrow()
        assertEquals("Should produce 3 valid partition documents", 3, generatedFiles.size)

        // Validate partition 1 (pages 1..2)
        PDDocument.load(generatedFiles[0]).use { doc ->
            assertEquals("Part 1 should contain exactly 2 pages", 2, doc.numberOfPages)
        }

        // Validate partition 2 (single page 4)
        PDDocument.load(generatedFiles[1]).use { doc ->
            assertEquals("Part 2 should contain exactly 1 page", 1, doc.numberOfPages)
        }

        // Validate partition 3 (clamped 5..6)
        PDDocument.load(generatedFiles[2]).use { doc ->
            assertEquals("Part 3 should be clamped to remaining 2 pages", 2, doc.numberOfPages)
        }
    }

    @Test
    fun testMergeMultiplePdfsWithRotations() = runTest {
        val file1 = File(tempFolder, "doc_rot_90.pdf")
        createRotatedImagePdf(file1, PDRectangle.LETTER, 90, Color.RED)

        val file2 = File(tempFolder, "doc_rot_180.pdf")
        createRotatedImagePdf(file2, PDRectangle.A4, 180, Color.GREEN)

        val file3 = File(tempFolder, "doc_rot_270.pdf")
        createRotatedImagePdf(file3, PDRectangle.A3, 270, Color.BLUE)

        val outputFile = File(tempFolder, "merged_rotation_output.pdf")
        val uris = listOf(Uri.fromFile(file1), Uri.fromFile(file2), Uri.fromFile(file3))

        val mergeResult = engine.mergePdfs(uris, outputFile)
        assertTrue("Merge should complete successfully", mergeResult.isSuccess)

        PDDocument.load(outputFile).use { mergedDoc ->
            assertEquals("Merged doc must contain 3 pages", 3, mergedDoc.numberOfPages)
            assertEquals("Page 1 must preserve 90° rotation", 90, mergedDoc.getPage(0).rotation)
            assertEquals("Page 2 must preserve 180° rotation", 180, mergedDoc.getPage(1).rotation)
            assertEquals("Page 3 must preserve 270° rotation", 270, mergedDoc.getPage(2).rotation)
            for (index in 0 until mergedDoc.numberOfPages) {
                val resources = mergedDoc.getPage(index).resources
                val images = resources?.xObjectNames
                    ?.mapNotNull { name -> resources.getXObject(name) as? PDImageXObject }
                    .orEmpty()
                assertTrue(
                    "Merged page ${index + 1} must retain its embedded source image and watermark",
                    images.size >= 2
                )
            }
        }
    }

    private fun createRotatedImagePdf(file: File, pageSize: PDRectangle, rotation: Int, color: Int) {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        try {
            PDDocument().use { document ->
                val page = PDPage(pageSize)
                page.rotation = rotation
                document.addPage(page)
                val image = LosslessFactory.createFromImage(document, bitmap)
                PDPageContentStream(document, page).use { content ->
                    content.drawImage(image, 36f, 36f, 120f, 120f)
                }
                document.save(file)
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Test
    fun testPdfEncryptionAndAccessPermissionFlags() = runTest {
        val plainPdf = createSamplePdf(2, "plain_contract.pdf")
        val encryptedOutput = File(tempFolder, "secured_contract.pdf")
        val userPass = "UserSec2026!"
        val ownerPass = "OwnerAdmin2026!"

        // Restrict printing, allow content copying
        val encryptResult = engine.encryptPdf(
            sourceUri = Uri.fromFile(plainPdf),
            userPassword = userPass,
            ownerPassword = ownerPass,
            allowPrinting = false,
            allowCopying = true,
            outputFile = encryptedOutput
        )

        assertTrue("Encryption should succeed", encryptResult.isSuccess)
        assertTrue("Encrypted file must exist", encryptedOutput.exists())

        // 1. A non-empty user password must block an unauthenticated open.
        try {
            PDDocument.load(encryptedOutput).use {
                throw AssertionError("Encrypted document opened without a password")
            }
        } catch (expected: InvalidPasswordException) {
            // Correct behavior.
        }

        // 2. Open with User password and verify permissions
        PDDocument.load(encryptedOutput, userPass).use { userDoc ->
            val permissions: AccessPermission = userDoc.currentAccessPermission
            assertFalse("Printing must be explicitly restricted", permissions.canPrint())
            assertTrue("Content copying/extraction must be allowed", permissions.canExtractContent())
            assertFalse("User password must not grant owner access", permissions.isOwnerPermission)
        }

        // Owner password must grant full owner rights.
        PDDocument.load(encryptedOutput, ownerPass).use { ownerDoc ->
            assertTrue("Owner password must grant owner access", ownerDoc.currentAccessPermission.isOwnerPermission)
        }

        // 3. Test Decryption back to clean document
        val decryptedOutput = File(tempFolder, "decrypted_contract.pdf")
        val decryptResult = engine.decryptPdf(
            sourceUri = Uri.fromFile(encryptedOutput),
            password = userPass,
            outputFile = decryptedOutput
        )
        assertTrue("Decryption with valid password should succeed", decryptResult.isSuccess)

        PDDocument.load(decryptedOutput).use { unencryptedDoc ->
            assertFalse("Decrypted document must have security removed", unencryptedDoc.isEncrypted)
        }
    }

    @Test
    fun test72DpiCoordinateTransformationAndFlattening() = runTest {
        val samplePdf = createSamplePdf(1, "vector_canvas_target.pdf")
        val flattenedOutput = File(tempFolder, "flattened_output.pdf")

        // Define simulated screen coordinates on a 1080px wide mobile display
        val screenWidthPx = 1080f
        val a4WidthPt = PDRectangle.A4.width   // 595.27563 pt
        val a4HeightPt = PDRectangle.A4.height // 841.8898 pt
        val scaleFactor = a4WidthPt / screenWidthPx

        // Stroke from screen center (540px, 960px) to (700px, 1200px)
        val stroke = DrawingStroke(
            points = listOf(PointF(540f, 960f), PointF(700f, 1200f)),
            colorArgb = Color.RED,
            strokeWidth = 6f,
            isHighlighter = false
        )

        val pageAnnotations = PageAnnotations(
            strokes = listOf(stroke),
            textAnnotations = emptyList(),
            signatures = emptyList()
        )

        val flattenResult = engine.flattenAnnotations(
            sourceUri = Uri.fromFile(samplePdf),
            pageAnnotationsMap = mapOf(0 to pageAnnotations),
            screenWidthPx = screenWidthPx,
            outputFile = flattenedOutput
        )

        assertTrue("Flattening vector annotations onto PDF should succeed", flattenResult.isSuccess)
        assertTrue("Flattened file should exist", flattenedOutput.exists())

        // Mathematical verification of coordinate mapping
        val expectedStartPdfX = 540f * scaleFactor
        val expectedStartPdfY = a4HeightPt - (960f * scaleFactor)

        assertEquals("Horizontal mapping must match 72 DPI ratio", 297.6378f, expectedStartPdfX, 0.1f)
        assertTrue("Vertical inverted Cartesian PDF coordinate must be positive", expectedStartPdfY > 0)

        PDDocument.load(flattenedOutput).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertEquals(PDRectangle.A4.width, page.mediaBox.width, 0.01f)
            assertEquals(PDRectangle.A4.height, page.mediaBox.height, 0.01f)
        }
    }
    @Test
    fun testConvertImagesToPdfCreatesValidA4Document() = runTest {
        val imageFile = File(tempFolder, "input_photo.png")
        Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 60, 90))
            FileOutputStream(imageFile).use { output ->
                compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            recycle()
        }

        val outputFile = File(tempFolder, "images_to_pdf.pdf")
        val result = engine.convertImagesToPdf(
            imageUris = listOf(Uri.fromFile(imageFile)),
            outputFile = outputFile
        )

        assertTrue("Image to PDF conversion should succeed", result.isSuccess)
        assertTrue("Converted PDF must exist", outputFile.exists())

        PDDocument.load(outputFile).use { document ->
            assertEquals(1, document.numberOfPages)
            assertEquals(PDRectangle.A4.width, document.getPage(0).mediaBox.width, 0.01f)
            assertEquals(PDRectangle.A4.height, document.getPage(0).mediaBox.height, 0.01f)
        }
    }

    @Test
    fun testExtractEmbeddedImagesProducesPngFiles() = runTest {
        val sourceFile = File(tempFolder, "image_source.pdf")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }

        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val image = LosslessFactory.createFromImage(document, bitmap)
            PDPageContentStream(document, page).use { content ->
                content.drawImage(image, 36f, 700f, 120f, 80f)
            }
            document.save(sourceFile)
        }
        bitmap.recycle()

        val result = engine.extractEmbeddedImages(Uri.fromFile(sourceFile))

        assertTrue("Embedded image extraction should succeed", result.isSuccess)
        val extracted = result.getOrThrow()
        assertEquals(1, extracted.size)
        assertTrue(extracted.single().exists())
        assertTrue(extracted.single().length() > 0)
    }

    @Test
    fun testWatermarkPdfProducesValidOutput() = runTest {
        val sourceFile = createSamplePdf(2, "watermark_source.pdf")
        val outputFile = File(tempFolder, "watermarked.pdf")

        val result = engine.watermarkPdf(
            sourceUri = Uri.fromFile(sourceFile),
            watermarkText = "VibeSync TEST",
            outputFile = outputFile
        )

        assertTrue("Watermarking should succeed", result.isSuccess)
        PDDocument.load(outputFile).use { document ->
            assertEquals(2, document.numberOfPages)
        }
    }

}
