package com.vibesync.toolkit.pdf

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PdfWatermarkIdempotencyTest {
    private lateinit var context: Context
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        testDirectory = File(context.cacheDir, "pdf_cache/watermark_idempotency").apply { mkdirs() }
    }

    @Test
    fun reWatermarkingAndSplittingAnAlreadyMarkedPdfDoesNotAddAnotherStamp() = runBlocking {
        val original = File(testDirectory, "original.pdf")
        val firstPass = File(testDirectory, "first_pass.pdf")
        val secondPass = File(testDirectory, "second_pass.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            document.save(original)
        }

        val engine = PdfBoxToolkitEngine(context, billingManager = null)
        val firstResult = engine.watermarkPdf(
            Uri.fromFile(original),
            PdfBoxToolkitEngine.DEFAULT_FOOTER_WATERMARK_TEXT,
            firstPass
        )
        assertTrue("First watermark failed: ${firstResult.exceptionOrNull()}", firstResult.isSuccess)

        val firstXObjectCount = PDDocument.load(firstPass).use { document ->
            document.getPage(0).resources?.xObjectNames?.count() ?: 0
        }
        assertEquals(1, firstXObjectCount)


        val shareCopy = File(testDirectory, "share_copy.pdf")
        val shareCopyResult = engine.watermarkPdf(
            Uri.fromFile(firstPass),
            PdfBoxToolkitEngine.DEFAULT_FOOTER_WATERMARK_TEXT,
            shareCopy
        )
        assertTrue("Preparing an already watermarked PDF for sharing failed: ${shareCopyResult.exceptionOrNull()}", shareCopyResult.isSuccess)
        assertArrayEquals(
            "An already VibeSync-watermarked PDF must be passed to the share target byte-for-byte.",
            firstPass.readBytes(),
            shareCopy.readBytes()
        )
        PDDocument.load(shareCopy).use { copied ->
            assertEquals(PDRectangle.A4.width, copied.getPage(0).mediaBox.width, 0.01f)
            assertEquals(PDRectangle.A4.height, copied.getPage(0).mediaBox.height, 0.01f)
        }

        val splitResult = engine.splitPdf(Uri.fromFile(firstPass), listOf(1..1))
        assertTrue("Split failed: ${splitResult.exceptionOrNull()}", splitResult.isSuccess)
        val splitFile = requireNotNull(splitResult.getOrThrow().single())

        val secondResult = engine.watermarkPdf(
            Uri.fromFile(splitFile),
            PdfBoxToolkitEngine.DEFAULT_FOOTER_WATERMARK_TEXT,
            secondPass
        )
        assertTrue("Second watermark failed: ${secondResult.exceptionOrNull()}", secondResult.isSuccess)

        PDDocument.load(secondPass).use { document ->
            val page = document.getPage(0)
            assertEquals(1, page.resources?.xObjectNames?.count() ?: 0)
            assertEquals(
                "true",
                page.cosObject.getString(COSName.getPDFName("VibeSync-Watermarked-V2-Page"))
            )
            assertEquals(
                "true",
                document.documentInformation.getCustomMetadataValue("VibeSync-Watermarked-V2")
            )
        }
        assertEquals(firstXObjectCount, PDDocument.load(secondPass).use { it.getPage(0).resources?.xObjectNames?.count() ?: 0 })

        listOf(original, firstPass, secondPass, splitFile, shareCopy).forEach { it.delete() }
    }
}
