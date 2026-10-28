package com.vibesync.toolkit.pdf

import android.app.Application
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.data.print.PdfPrintDocumentAdapter
import com.vibesync.toolkit.pdf.presentation.common.launchSystemPdfPrint
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import io.mockk.*
import kotlin.requireNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PdfPrintActionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun printActionDispatchesA4Color300DpiAndAdapterLifecycleCompletes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "print-viewer-ui-" + System.nanoTime()).apply { mkdirs() }
        val source = File(work, "viewer-print.pdf")
        createPdf(source)

        val dispatchCount = AtomicInteger(0)
        val dispatchedJobName = AtomicReference<String?>()
        val dispatchedAdapter = AtomicReference<PdfPrintDocumentAdapter?>()
        val dispatchedAttributes = AtomicReference<PrintAttributes?>()
        try {
            composeRule.setContent {
                VibeSyncPdfTheme {
                    PdfViewerScreen(
                        documentUri = Uri.fromFile(source),
                        viewModel = PdfViewerViewModel(application),
                        onNavigateBack = {},
                        onNavigateToEditor = {},
                        onShareDocument = {},
                        onLaunchPrint = { printContext, uri, jobName ->
                            launchSystemPdfPrint(printContext, uri, jobName) { submittedName, adapter, attributes ->
                                dispatchCount.incrementAndGet()
                                dispatchedJobName.set(submittedName)
                                dispatchedAdapter.set(adapter as? PdfPrintDocumentAdapter)
                                dispatchedAttributes.set(attributes)
                                null
                            }
                        }
                    )
                }
            }

            composeRule.onNodeWithTag("viewer_print").performClick()
            composeRule.waitUntil(timeoutMillis = 30_000) {
                dispatchedAdapter.get() != null && dispatchedAttributes.get() != null && !dispatchedJobName.get().isNullOrBlank()
            }

            val attributes = requireNotNull(dispatchedAttributes.get())
            val adapter = requireNotNull(dispatchedAdapter.get())
            assertEquals("Viewer print must dispatch exactly once.", 1, dispatchCount.get())
            assertTrue("Print job name must be populated.", dispatchedJobName.get().orEmpty().isNotBlank())
            assertEquals(PrintAttributes.MediaSize.ISO_A4, attributes.mediaSize)
            val resolution = requireNotNull(attributes.resolution) {
                "Print resolution must be non-null for A4/300DPI test"
            }
            assertEquals(300, resolution.horizontalDpi)
            assertEquals(300, resolution.verticalDpi)
            assertEquals(PrintAttributes.COLOR_MODE_COLOR, attributes.colorMode)
            assertEquals(PrintAttributes.DUPLEX_MODE_NONE, attributes.duplexMode)

            val layoutCallback = mockk<PrintDocumentAdapter.LayoutResultCallback>(relaxed = true)
            adapter.onStart()
            adapter.onLayout(null, attributes, CancellationSignal(), layoutCallback, Bundle())
            verify(exactly = 1) {
                layoutCallback.onLayoutFinished(match { it.pageCount == 1 }, true)
            }

            val output = File(work, "printed-output.pdf")
            val destination = ParcelFileDescriptor.open(
                output,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE
            )
            val writeCallback = mockk<PrintDocumentAdapter.WriteResultCallback>(relaxed = true)
            adapter.onWrite(arrayOf(PageRange.ALL_PAGES), destination, CancellationSignal(), writeCallback)
            verify(exactly = 1) {
                writeCallback.onWriteFinished(match { ranges ->
                    ranges.size == 1 && ranges[0].start == 0 && ranges[0].end == 0
                })
            }
            assertTrue("The adapter should write a non-empty printable PDF.", output.isFile && output.length() > 0L)
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertEquals(1, renderer.pageCount)
                }
            }
        } finally {
            dispatchedAdapter.get()?.onFinish()
            work.deleteRecursively()
        }
    }

    private fun createPdf(file: File) {
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            document.save(file)
        }
    }
}
