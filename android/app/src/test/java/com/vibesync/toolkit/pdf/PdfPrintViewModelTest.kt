package com.vibesync.toolkit.pdf

import android.app.Activity
import android.app.Application
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.data.print.PdfPrintDocumentAdapter
import com.vibesync.toolkit.pdf.data.print.copyPdfBytes
import com.vibesync.toolkit.pdf.data.pdf.FooterWatermarkLayout
import com.vibesync.toolkit.pdf.data.pdf.PageRangeParser
import com.vibesync.toolkit.pdf.core.util.generateSmartFileName
import com.vibesync.toolkit.pdf.presentation.editor.SignatureStrokeMetrics
import com.vibesync.toolkit.pdf.presentation.common.PrintTrigger
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfPrintViewModelTest {

    @Test
    fun viewModelFactoryCanInstantiatePdfViewerViewModel() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
            .getInstance(app)
            .create(PdfViewerViewModel::class.java)
        assertNotNull("ViewModelProvider reflection must succeed", viewModel)
    }

    @Test
    fun signatureStrokeWidthScalesProportionallyAndHasVisibleMinimum() {
        val relative = SignatureStrokeMetrics.relativeStrokeWidth(12f, 400f)
        assertEquals(0.03f, relative, 0.0001f)
        assertEquals(6f, SignatureStrokeMetrics.scaledStrokeWidth(relative, 160f), 0.001f)
        assertEquals(12f, SignatureStrokeMetrics.scaledStrokeWidth(0.075f, 160f), 0.001f)
    }

    @Test
    fun printByteCopyPreservesNonEmptyPdfBytes() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val source = File.createTempFile("print-copy-test-", ".pdf", app.cacheDir)
        val original = "%PDF-1.4\\n1 0 obj << /Type /Catalog >> endobj\\n%%EOF".toByteArray()
        try {
            source.writeBytes(original)
            val destination = java.io.ByteArrayOutputStream()
            copyPdfBytes(source, destination)
            assertTrue(destination.size() > 0)
            assertArrayEquals(original, destination.toByteArray())
        } finally {
            source.delete()
        }
    }

    @Test
    fun defaultFooterWatermarkCoordinatesAreBottomRight() {
        val pageWidth = 595f
        val pageHeight = 842f
        val position = FooterWatermarkLayout.topLeft(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            watermarkWidth = 120f,
            watermarkHeight = 10f,
            margin = 16f
        )
        assertTrue(position.x > pageWidth * 0.5f)
        assertTrue(position.topY > pageHeight * 0.9f)
    }

    @Test
    fun smartFileNameUsesBrandPrefixTimestampAndExtension() {
        val name = generateSmartFileName("VibeSync_BatchScan", "pdf")
        assertTrue(name.matches(Regex("VibeSync_BatchScan_\\d{8}_\\d{6}\\.pdf")))
        val dotted = generateSmartFileName("VibeSync_Scan", ".pdf")
        assertTrue(dotted.matches(Regex("VibeSync_Scan_\\d{8}_\\d{6}\\.pdf")))
    }

    @Test
    fun pageRangeParserSupportsAllAndCustomRanges() {
        assertEquals(listOf(1..25), PageRangeParser.parse("All", 25))
        assertEquals(listOf(1..10, 15..15, 20..25), PageRangeParser.parse("1-10, 15, 20-25", 25))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pageRangeParserRejectsOutOfBoundsPages() {
        PageRangeParser.parse("1-10, 27", 25)
    }

    @Test
    fun footerWatermarkUsesRequestedRightAndBottomMargins() {
        val position = FooterWatermarkLayout.topLeft(
            pageWidth = 595f,
            pageHeight = 842f,
            watermarkWidth = 120f,
            watermarkHeight = 9f,
            margin = 14f,
            bottomMargin = 10f
        )
        assertEquals(461f, position.x, 0.001f)
        assertEquals(823f, position.topY, 0.001f)
    }

    @Test
    fun printActionDispatchesA4Color300DpiAttributes() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val captured = mutableListOf<Triple<String, PrintDocumentAdapter, PrintAttributes>>()

        val fakeTrigger = object : PrintTrigger {
            override fun triggerPrint(
                activity: Activity,
                jobName: String,
                adapter: PrintDocumentAdapter,
                attributes: PrintAttributes
            ) {
                captured.add(Triple(jobName, adapter, attributes))
            }
        }

        val viewModel = PdfViewerViewModel(application, printTrigger = fakeTrigger)
        val testFile = File.createTempFile("print-viewmodel-test-", ".pdf", application.cacheDir)
            .apply { writeText("%PDF-1.4\n") }

        try {
            // printDocument intentionally dispatches only when a readable prepared file exists.
            viewModel.setPreparedPrintFile(testFile)

            val attributes = PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("300dpi", "300dpi", 300, 300))
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()

            val mockActivity = Robolectric.buildActivity(Activity::class.java).create().get()

            viewModel.printDocument(mockActivity, "test.pdf", attributes)

            assertEquals("Print trigger should be called exactly once", 1, captured.size)
            val (jobName, adapter, attrs) = captured.single()

            assertTrue(jobName.isNotBlank())
            assertNotNull(adapter)
            assertTrue(adapter is PdfPrintDocumentAdapter)
            assertEquals(PrintAttributes.MediaSize.ISO_A4, attrs.mediaSize)
            assertEquals(300, attrs.resolution?.horizontalDpi)
            assertEquals(PrintAttributes.COLOR_MODE_COLOR, attrs.colorMode)
        } finally {
            testFile.delete()
        }
    }
}
