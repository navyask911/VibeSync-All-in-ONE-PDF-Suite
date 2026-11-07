package com.vibesync.toolkit.pdf

import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.data.print.PdfPrintDocumentAdapter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfPrintDocumentAdapterTest {
    @Test
    fun lifecycleLayoutAndWritePreserveActualPagesAndOriginalPdfBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "print-adapter-" + System.nanoTime()).apply { mkdirs() }
        val input = File(work, "input.pdf")
        val output = File(work, "printed.pdf")
        createPdf(input, 3)
        val originalBytes = input.readBytes()
        val adapter = PdfPrintDocumentAdapter(context, Uri.fromFile(input), "VibeSync test print")
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("test_300", "300 DPI", 300, 300))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setDuplexMode(PrintAttributes.DUPLEX_MODE_NONE)
            .build()
        var descriptor: ParcelFileDescriptor? = null
        try {
            adapter.onStart()
            val info = adapter.prepareLayout(attributes, CancellationSignal())
            assertEquals(3, info.pageCount)
            assertEquals(300, attributes.resolution?.horizontalDpi)
            assertEquals(PrintAttributes.MediaSize.ISO_A4, attributes.mediaSize)

            descriptor = ParcelFileDescriptor.open(
                output,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_READ_WRITE or
                    ParcelFileDescriptor.MODE_TRUNCATE
            )
            val written = adapter.writeDocument(
                arrayOf(PageRange.ALL_PAGES),
                descriptor,
                CancellationSignal()
            )
            assertEquals(listOf(PageRange.ALL_PAGES), written.toList())
            assertTrue("Print output should be non-empty.", output.isFile && output.length() > 0L)
            assertArrayEquals("Printer output must retain source PDF bytes", originalBytes, output.readBytes())
            PDDocument.load(output).use { printed ->
                assertEquals(3, printed.numberOfPages)
            }
        } finally {
            adapter.onFinish()
            runCatching { descriptor?.close() }
            work.deleteRecursively()
        }
    }

    private fun createPdf(file: File, count: Int) {
        PDDocument().use { document ->
            repeat(count) { document.addPage(PDPage(PDRectangle.A4)) }
            document.save(file)
        }
    }
}
