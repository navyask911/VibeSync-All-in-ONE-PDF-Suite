package com.vibesync.toolkit.pdf

import android.app.Activity
import android.app.Application
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import androidx.test.core.app.ApplicationProvider
import com.vibesync.toolkit.pdf.data.print.PdfPrintDocumentAdapter
import com.vibesync.toolkit.pdf.presentation.common.PrintTrigger
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfPrintViewModelTest {

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
