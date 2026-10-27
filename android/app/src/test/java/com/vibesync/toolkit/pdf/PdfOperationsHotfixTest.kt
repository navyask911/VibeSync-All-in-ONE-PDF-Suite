package com.vibesync.toolkit.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.pdf.IdPhotoExporter
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.data.pdf.PdfBitmapUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PdfOperationsHotfixTest {
    private lateinit var context: Context
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        testDirectory = File(context.cacheDir, "pdf_cache/hotfix_tests").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testDirectory.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun generatedPageBitmapHasActualOpaqueWhitePixels() {
        val bitmap = PdfBitmapUtils.createWhiteBackgroundBitmap(8, 8)
        try {
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    assertEquals("pixel ($x,$y) should be white", Color.WHITE, bitmap.getPixel(x, y))
                }
            }
            assertEquals(255, Color.alpha(bitmap.getPixel(0, 0)))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun jpegExportFlattensTransparencyToWhiteAndWritesJpegBytes() {
        val transparentBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            transparentBitmap.eraseColor(Color.TRANSPARENT)
            // Exercise the public utility API as well as the actual JPEG encoding path.
            PdfBitmapUtils.flattenBitmapOnWhiteBackground(transparentBitmap).recycle()

            val bytes = IdPhotoExporter.jpegBytesWithDpi(transparentBitmap)
            assertTrue("JPEG output should contain a complete image payload.", bytes.size > 100)
            assertEquals(0xff, bytes[0].toInt() and 0xff)
            assertEquals(0xd8, bytes[1].toInt() and 0xff)

            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("Encoded JPEG should decode into an actual bitmap.", decoded)
            val generatedBitmap = requireNotNull(decoded)
            try {
                for (y in 0 until generatedBitmap.height) {
                    for (x in 0 until generatedBitmap.width) {
                        val pixel = generatedBitmap.getPixel(x, y)
                        assertTrue(
                            "JPEG pixel ($x,$y) should have a white background (pixel=$pixel).",
                            Color.red(pixel) >= 250 &&
                                Color.green(pixel) >= 250 &&
                                Color.blue(pixel) >= 250
                        )
                        assertEquals(255, Color.alpha(pixel))
                    }
                }
            } finally {
                generatedBitmap.recycle()
            }
        } finally {
            transparentBitmap.recycle()
        }
    }

    @Test
    fun fileProviderCreatesShareableUriForExistingOutputFile() {
        val output = File(testDirectory, "shared_hotfix.pdf").apply { writeText("test payload") }
        assertTrue(output.isFile && output.length() > 0L)
        val uri = UriUtils.getShareableUri(context, output)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
    }

    @Test
    fun mergeKeepsImageStreamsUsableAndWritesActualDestinationFile() = runBlocking {
        val first = File(testDirectory, "merge_first.pdf")
        val second = File(testDirectory, "merge_second.pdf")
        val output = File(testDirectory, "merge_output.pdf")
        try {
            createImagePdf(first, Color.RED)
            createImagePdf(second, Color.BLUE)
            val result = PdfBoxToolkitEngine(context).mergePdfs(
                listOf(Uri.fromFile(first), Uri.fromFile(second)),
                output
            )
            assertTrue("Merge failed: ${result.exceptionOrNull()}", result.isSuccess)
            assertTrue("Merged PDF must exist and contain bytes.", output.isFile && output.length() > 0L)
            PDDocument.load(output).use { merged ->
                assertEquals(2, merged.numberOfPages)
                for (index in 0 until merged.numberOfPages) {
                    val resources = merged.getPage(index).resources
                    val images = resources?.xObjectNames
                        ?.mapNotNull { name -> resources.getXObject(name) as? PDImageXObject }
                        .orEmpty()
                    assertTrue("Page ${index + 1} should retain its embedded image.",
                        images.any { it.width == 8 && it.height == 8 })
                }
            }
        } finally {
            listOf(first, second, output).forEach { it.delete() }
        }
    }

    private fun createImagePdf(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            PDDocument().use { document ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                val image = LosslessFactory.createFromImage(document, bitmap)
                PDPageContentStream(document, page).use { stream ->
                    stream.drawImage(image, 36f, 36f, 64f, 64f)
                }
                document.save(file)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
