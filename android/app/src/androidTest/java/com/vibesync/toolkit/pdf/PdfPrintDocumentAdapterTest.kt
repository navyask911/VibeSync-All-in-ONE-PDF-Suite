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
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.data.print.PdfPrintDocumentAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfPrintDocumentAdapterTest {
    @Test
    fun lifecycleLayoutAndWritePreserveActualPagesInReadablePdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "print-adapter-" + System.nanoTime()).apply { mkdirs() }
        val input = File(work, "input.pdf")
        val output = File(work, "printed.pdf")
        createPdf(input, 3)
        val adapter = PdfPrintDocumentAdapter(context, Uri.fromFile(input), "VibeSync test print")
        var descriptor: ParcelFileDescriptor? = null
        try {
            adapter.onStart()
            val attributes = printAttributes()
            val info = adapter.prepareLayout(attributes, CancellationSignal())
            assertEquals(3, info.pageCount)
            assertEquals(300, attributes.resolution?.horizontalDpi)
            assertEquals(PrintAttributes.MediaSize.ISO_A4, attributes.mediaSize)
            descriptor = openDestination(output)
            val written = adapter.writeDocument(arrayOf(PageRange.ALL_PAGES), descriptor, CancellationSignal())
            assertEquals(listOf(PageRange.ALL_PAGES), written.toList())
            PDDocument.load(output).use { printed ->
                assertEquals(3, printed.numberOfPages)
                assertFalse(printed.isEncrypted)
            }
        } finally {
            adapter.onFinish()
            runCatching { descriptor?.close() }
            work.deleteRecursively()
        }
    }

    @Test
    fun aes128ProtectedPdfPrintsWithSessionPasswordAndRemovesSecurity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "protected-print-" + System.nanoTime()).apply { mkdirs() }
        val plain = File(work, "plain.pdf")
        val protected = File(work, "protected.pdf")
        val output = File(work, "printed.pdf")
        val password = "Print-Session-2026!"
        var descriptor: ParcelFileDescriptor? = null
        var adapter: PdfPrintDocumentAdapter? = null
        try {
            createPdf(plain, 2)
            PdfBoxToolkitEngine(context).encryptPdf(
                Uri.fromFile(plain),
                password,
                "Owner-Print-2026!",
                allowPrinting = true,
                allowCopying = true,
                outputFile = protected
            ).getOrThrow()
            PDDocument.load(protected, password).use { assertTrue(it.isEncrypted) }
            adapter = PdfPrintDocumentAdapter(
                context = context,
                sourceUri = Uri.fromFile(protected),
                displayName = "Protected VibeSync print",
                sessionPassword = password
            )
            adapter.onStart()
            assertEquals(2, adapter.prepareLayout(printAttributes(), CancellationSignal()).pageCount)
            descriptor = openDestination(output)
            adapter.writeDocument(arrayOf(PageRange.ALL_PAGES), descriptor, CancellationSignal())
            PDDocument.load(output).use { printed ->
                assertEquals(2, printed.numberOfPages)
                assertFalse("Print stream must contain a decrypted PDF.", printed.isEncrypted)
            }
        } finally {
            adapter?.onFinish()
            runCatching { descriptor?.close() }
            work.deleteRecursively()
        }
    }

    private fun printAttributes(): PrintAttributes =
        PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setResolution(PrintAttributes.Resolution("test_300", "300 DPI", 300, 300))
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setDuplexMode(PrintAttributes.DUPLEX_MODE_NONE)
            .build()

    private fun openDestination(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_TRUNCATE
        )

    private fun createPdf(file: File, count: Int) {
        PDDocument().use { document ->
            repeat(count) { document.addPage(PDPage(PDRectangle.A4)) }
            document.save(file)
        }
    }
}
