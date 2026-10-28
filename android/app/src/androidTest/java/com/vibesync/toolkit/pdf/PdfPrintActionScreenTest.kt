package com.vibesync.toolkit.pdf

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PdfPrintActionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun printActionDispatchesPreparedDocumentToPrintHandler() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "print-viewer-ui-" + System.nanoTime()).apply { mkdirs() }
        val source = File(work, "viewer-print.pdf")
        createPdf(source)

        val printDispatched = CountDownLatch(1)
        val dispatchedUri = AtomicReference<Uri?>()
        val dispatchedJobName = AtomicReference<String?>()
        try {
            val viewModel = PdfViewerViewModel(application)
            composeRule.setContent {
                VibeSyncPdfTheme {
                    PdfViewerScreen(
                        documentUri = Uri.fromFile(source),
                        viewModel = viewModel,
                        onNavigateBack = {},
                        onNavigateToEditor = {},
                        onShareDocument = {},
                        onLaunchPrint = { _, uri, jobName ->
                            dispatchedUri.set(uri)
                            dispatchedJobName.set(jobName)
                            printDispatched.countDown()
                        }
                    )
                }
            }

            composeRule.onNodeWithTag("viewer_print").performClick()

            // Verify the app dispatched the prepared print request; do not wait for the
            // Android system print UI or its native PrintDocumentAdapter lifecycle.
            assertTrue(
                "Print request was not dispatched within 30 seconds.",
                printDispatched.await(30, TimeUnit.SECONDS)
            )
            val printUri = dispatchedUri.get()
            assertNotNull("Print handler must receive a prepared document URI.", printUri)
            assertEquals("file", printUri?.scheme)
            assertTrue("Print job name must be populated.", dispatchedJobName.get().orEmpty().isNotBlank())
            val preparedFile = File(requireNotNull(printUri).path!!)
            assertTrue("Print handler should receive a readable, non-empty staged PDF.", preparedFile.isFile && preparedFile.canRead() && preparedFile.length() > 0L)
            assertFalse("Print handler must receive the staged copy, not the original source.", preparedFile.canonicalFile == source.canonicalFile)
        } finally {
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
