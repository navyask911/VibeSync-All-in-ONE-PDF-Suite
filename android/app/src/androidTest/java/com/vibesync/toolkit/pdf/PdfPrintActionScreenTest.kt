package com.vibesync.toolkit.pdf

import android.app.Application
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.print.PrintManager
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class PdfPrintActionScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun printActionFromViewerOpensSystemPrintActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "print-viewer-ui-" + System.nanoTime()).apply { mkdirs() }
        val source = File(work, "viewer-print.pdf")
        createPdf(source)
        val monitor = instrumentation.addMonitor("com.android.printspooler", null, false)
        try {
            setTestContent(source)
            composeRule.onNodeWithTag("viewer_print").performClick()
            val printActivity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000)
            assertNotNull("Viewer Print should open the Android system print UI.", printActivity)
            (context.getSystemService(Context.PRINT_SERVICE) as? PrintManager)?.printJobs?.forEach { it.cancel() }
        } finally {
            instrumentation.removeMonitor(monitor)
            work.deleteRecursively()
        }
    }

    private fun setTestContent(source: File) {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val viewModel = PdfViewerViewModel(application)
        composeRule.activity.runOnUiThread {
            val composeView = findComposeView(composeRule.activity.window.decorView)
                ?: error("MainActivity ComposeView was not found")
            composeView.setContent {
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
        }
        composeRule.waitForIdle()
    }

    private fun createPdf(file: File) {
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            document.save(file)
        }
    }

    private fun findComposeView(view: View): ComposeView? {
        if (view is ComposeView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findComposeView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
