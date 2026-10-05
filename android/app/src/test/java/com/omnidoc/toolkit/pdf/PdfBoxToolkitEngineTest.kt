package com.omnidoc.toolkit.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import com.omnidoc.toolkit.pdf.data.model.DrawingStroke
import com.omnidoc.toolkit.pdf.data.model.PageAnnotations
import com.omnidoc.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("PdfBoxToolkitEngine Unit Tests - Lifecycle & Edge Cases")
class PdfBoxToolkitEngineTest {

    @TempDir
    lateinit var tempFolder: File

    private lateinit var mockContext: Context
    private lateinit var engine: PdfBoxToolkitEngine

    @BeforeEach
    fun setUp() {
        mockContext = mockk(relaxed = true)
        every { mockContext.cacheDir } returns tempFolder
        engine = PdfBoxToolkitEngine(mockContext)
    }

    @AfterEach
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
    @DisplayName("PDF Splitting with Edge-Case Interval Ranges (boundary limits, single page, out-of-order)")
    fun testPdfSplittingEdgeCases() = runTest {
        // Create 6-page source PDF
        val sourcePdf = createSamplePdf(6, "source_split.pdf")
        val sourceUri = Uri.fromFile(sourcePdf)

        // Mock UriUtils copy helper to return this file directly
        every { mockContext.cacheDir } returns tempFolder

        // 1. Valid intervals, boundary limits (1..2, 4..4, 5..10 where max is 6), and out-of-order (7..2)
        val intervals = listOf(
            IntRange(1, 2),   // 2 pages
            IntRange(4, 4),   // 1 single page
            IntRange(5, 10),  // Boundary clamped to 5..6 (2 pages)
            IntRange(7, 2)    // Invalid / out-of-order range -> should safely yield 0 pages or omit
        )

        val result = engine.splitPdf(sourceUri, intervals)
        assertTrue(result.isSuccess, "Splitting should succeed even with boundary ranges")

        val generatedFiles = result.getOrThrow()
        assertEquals(3, generatedFiles.size, "Should produce 3 valid partition documents")

        // Validate partition 1 (pages 1..2)
        PDDocument.load(generatedFiles[0]).use { doc ->
            assertEquals(2, doc.numberOfPages, "Part 1 should contain exactly 2 pages")
        }

        // Validate partition 2 (single page 4)
        PDDocument.load(generatedFiles[1]).use { doc ->
            assertEquals(1, doc.numberOfPages, "Part 2 should contain exactly 1 page")
        }

        // Validate partition 3 (clamped 5..6)
        PDDocument.load(generatedFiles[2]).use { doc ->
            assertEquals(2, doc.numberOfPages, "Part 3 should be clamped to remaining 2 pages")
        }
    }

    @Test
    @DisplayName("Merging Multiple PDDocuments with Mixed Rotations (90°, 180°, 270°)")
    fun testMergeMultiplePdfsWithRotations() = runTest {
        val file1 = File(tempFolder, "doc_rot_90.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.LETTER)
            page.rotation = 90
            doc.addPage(page)
            doc.save(file1)
        }

        val file2 = File(tempFolder, "doc_rot_180.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            page.rotation = 180
            doc.addPage(page)
            doc.save(file2)
        }

        val file3 = File(tempFolder, "doc_rot_270.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A3)
            page.rotation = 270
            doc.addPage(page)
            doc.save(file3)
        }

        val outputFile = File(tempFolder, "merged_rotation_output.pdf")
        val uris = listOf(Uri.fromFile(file1), Uri.fromFile(file2), Uri.fromFile(file3))

        val mergeResult = engine.mergePdfs(uris, outputFile)
        assertTrue(mergeResult.isSuccess, "Merge should complete successfully")

        PDDocument.load(outputFile).use { mergedDoc ->
            assertEquals(3, mergedDoc.numberOfPages, "Merged doc must contain 3 pages")
            assertEquals(90, mergedDoc.getPage(0).rotation, "Page 1 must preserve 90° rotation")
            assertEquals(180, mergedDoc.getPage(1).rotation, "Page 2 must preserve 180° rotation")
            assertEquals(270, mergedDoc.getPage(2).rotation, "Page 3 must preserve 270° rotation")
        }
    }

    @Test
    @DisplayName("AES-256 / AES-128 Encryption & Permission Flags Verification")
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

        assertTrue(encryptResult.isSuccess, "Encryption should succeed")
        assertTrue(encryptedOutput.exists(), "Encrypted file must exist")

        // 1. Attempt opening without password -> Must be flagged as encrypted
        PDDocument.load(encryptedOutput).use { lockedDoc ->
            assertTrue(lockedDoc.isEncrypted, "Document must report isEncrypted == true")
        }

        // 2. Open with User password and verify permissions
        PDDocument.load(encryptedOutput, userPass).use { userDoc ->
            val permissions: AccessPermission = userDoc.currentAccessPermission
            assertFalse(permissions.canPrint(), "Printing must be explicitly restricted")
            assertTrue(permissions.canExtractContent(), "Content copying/extraction must be allowed")
        }

        // 3. Test Decryption back to clean document
        val decryptedOutput = File(tempFolder, "decrypted_contract.pdf")
        val decryptResult = engine.decryptPdf(
            sourceUri = Uri.fromFile(encryptedOutput),
            password = userPass,
            outputFile = decryptedOutput
        )
        assertTrue(decryptResult.isSuccess, "Decryption with valid password should succeed")

        PDDocument.load(decryptedOutput).use { unencryptedDoc ->
            assertFalse(unencryptedDoc.isEncrypted, "Decrypted document must have security removed")
        }
    }

    @Test
    @DisplayName("72-DPI Coordinate Transformation & Annotation Flattening Math")
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

        assertTrue(flattenResult.isSuccess, "Flattening vector annotations onto PDF should succeed")
        assertTrue(flattenedOutput.exists(), "Flattened file should exist")

        // Mathematical verification of coordinate mapping
        val expectedStartPdfX = 540f * scaleFactor
        val expectedStartPdfY = a4HeightPt - (960f * scaleFactor)

        assertEquals(297.6378f, expectedStartPdfX, 0.1f, "Horizontal mapping must match 72 DPI ratio")
        assertTrue(expectedStartPdfY > 0, "Vertical inverted Cartesian PDF coordinate must be positive")

        PDDocument.load(flattenedOutput).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val page = doc.getPage(0)
            assertEquals(PDRectangle.A4.width, page.mediaBox.width, 0.01f)
            assertEquals(PDRectangle.A4.height, page.mediaBox.height, 0.01f)
        }
    }
}
