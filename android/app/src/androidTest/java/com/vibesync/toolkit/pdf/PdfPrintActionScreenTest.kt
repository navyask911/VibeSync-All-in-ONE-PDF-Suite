package com.vibesync.toolkit.pdf

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
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
import com.vibesync.toolkit.pdf.data.print.PrintTrigger
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class PdfPrintActionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun printActionDispatchesA4Color300DpiAttributes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "print-viewer-ui-" + System.nanoTime()).apply { mkdirs() }
        val source = File(work, "viewer-print.pdf")
        createPdf(source)
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

        try {
            val viewModel = PdfViewerViewModel(application, printTrigger = fakeTrigger)
            composeRule.setContent {
                VibeSyncPdfTheme {
                    PdfViewerScreen(
                        documentUri = Uri.fromFile(source),
                        viewModel = viewModel,
                        onNavigateBack = {},
                        onNavigateToEditor = {},
                        onShareDocument = {}
                    )
                }
            }

            composeRule.onNodeWithTag("viewer_print").performClick()

            composeRule.runOnIdle {
                assertEquals(1, captured.size)
                val (jobName, adapter, attrs) = captured.single()
                assertTrue(jobName.isNotBlank())
                assertNotNull(adapter)
                assertEquals(PrintAttributes.MediaSize.ISO_A4, attrs.mediaSize)
                assertEquals(300, attrs.resolution?.horizontalDpi)
                assertEquals(PrintAttributes.COLOR_MODE_COLOR, attrs.colorMode)
            }
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
