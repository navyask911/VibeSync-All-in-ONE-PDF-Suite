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
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.vibesync.toolkit.pdf.data.print.PdfPrintDocumentAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfPrintDocumentAdapterTest {
    @Test
    fun lifecycleLayoutWriteFinishPreservesPageCountAndA4At300Dpi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "print-adapter-" + System.nanoTime()).apply { mkdirs() }
        val input = File(work, "input.pdf")
        val output = File(work, "printed.pdf")
        createPdf(input, 3)
        val adapter = PdfPrintDocumentAdapter(context, Uri.fromFile(input), "VibeSync test print")
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("test_300", "300 DPI", 300, 300))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setDuplexMode(PrintAttributes.DUPLEX_MODE_NONE)
            .build()
        try {
            adapter.onStart()
            val info = adapter.prepareLayout(attributes, CancellationSignal())
            assertEquals(3, info.pageCount)
            assertEquals(300, attributes.resolution?.horizontalDpi)
            assertEquals(PrintAttributes.MediaSize.ISO_A4, attributes.mediaSize)

            val descriptor = ParcelFileDescriptor.open(
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
            assertEquals(listOf(PageRange(0, 2)), written.toList())
            assertTrue("Printed PDF should be non-empty.", output.isFile && output.length() > 0L)
            val embeddedImageSizes = PDDocument.load(output).use { printed ->
                val resources = printed.getPage(0).resources
                resources.xObjectNames.mapNotNull { name ->
                    resources.getXObject(name) as? PDImageXObject
                }.map { it.width to it.height }
            }
            assertTrue(
                "300 DPI A4 should embed a full-resolution page image; got $embeddedImageSizes",
                embeddedImageSizes.any { (width, height) ->
                    (width >= 2_000 && height >= 3_000) || (height >= 2_000 && width >= 3_000)
                }
            )
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    assertEquals(3, renderer.pageCount)
                    renderer.openPage(0).use { page ->
                        assertTrue(
                            "A4 portrait page should have expected rendered dimensions",
                            page.width >= 500 && page.height >= 700
                        )
                    }
                }
            }
        } finally {
            adapter.onFinish()
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
