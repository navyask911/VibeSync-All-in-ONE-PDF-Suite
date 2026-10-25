package com.vibesync.toolkit.pdf

import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vibesync.toolkit.pdf.data.print.PdfPrintDocumentAdapter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

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
        val layout = AtomicReference<android.print.PrintDocumentInfo?>()
        val layoutFailure = AtomicReference<String?>()
        try {
            adapter.onStart()
            adapter.onLayout(
                null,
                attributes,
                CancellationSignal(),
                object : android.print.PrintDocumentAdapter.LayoutResultCallback() {
                    override fun onLayoutFinished(info: android.print.PrintDocumentInfo, changed: Boolean) {
                        layout.set(info)
                    }
                    override fun onLayoutFailed(error: CharSequence?) {
                        layoutFailure.set(error?.toString() ?: "layout failed")
                    }
                    override fun onLayoutCancelled() {
                        layoutFailure.set("layout cancelled")
                    }
                },
                Bundle()
            )
            assertEquals(null, layoutFailure.get())
            assertEquals(3, layout.get()?.pageCount)
            assertEquals(300, attributes.resolution?.horizontalDpi)
            assertEquals(PrintAttributes.MediaSize.ISO_A4, attributes.mediaSize)

            val writeFailure = AtomicReference<String?>()
            var writeFinished = false
            adapter.onWrite(
                arrayOf(PageRange.ALL_PAGES),
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE),
                CancellationSignal(),
                object : android.print.PrintDocumentAdapter.WriteResultCallback() {
                    override fun onWriteFinished(pages: Array<out PageRange>) {
                        writeFinished = true
                    }
                    override fun onWriteFailed(error: CharSequence?) {
                        writeFailure.set(error?.toString() ?: "write failed")
                    }
                    override fun onWriteCancelled() {
                        writeFailure.set("write cancelled")
                    }
                }
            )
            assertEquals(null, writeFailure.get())
            assertTrue("Print adapter should finish writing the requested pages.", writeFinished)
            assertTrue("Printed PDF should be non-empty.", output.isFile && output.length() > 0L)
            android.graphics.pdf.PdfRenderer(
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY)
            ).use { renderer ->
                assertEquals(3, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    assertTrue("A4 portrait page should have expected rendered dimensions", page.width >= 500 && page.height >= 700)
                }
            }
            adapter.onFinish()
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
