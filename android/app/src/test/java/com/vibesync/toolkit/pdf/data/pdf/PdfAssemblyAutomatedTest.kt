package com.vibesync.toolkit.pdf.data.pdf

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfAssemblyAutomatedTest {
    @Test
    fun testBatchAssemblyRetainsAllPages() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PDFBoxResourceLoader.init(app)

        val scansDirectory = File(app.filesDir, "VibeSyncScans")
        val preferences = app.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, 0)
        val hadBatchPreference = preferences.contains(BatchScanViewModel.KEY_BATCH_ENABLED)
        val previousBatchPreference = preferences.getBoolean(BatchScanViewModel.KEY_BATCH_ENABLED, false)
        scansDirectory.deleteRecursively()

        val workDirectory = File(app.cacheDir, "pdf-assembly-automated-${System.nanoTime()}").apply {
            check(mkdirs())
        }
        val sourceImages = (0 until 6).map { index ->
            createOnePixelJpeg(File(workDirectory, "captured-page-${index + 1}.jpg"), index)
        }
        val outputFile = File(workDirectory, "six-page-batch.pdf")

        try {
            val viewModel = BatchScanViewModel(app)
            viewModel.setBatchModeEnabled(true)

            // Simulate three consecutive scanner launches, each returning two page JPEGs.
            // Submit the next round immediately so copy and commit work can overlap.
            sourceImages.chunked(2).forEach { captureRound ->
                viewModel.requestCapture()
                assertTrue(
                    "Batch scanner result should be accepted.",
                    viewModel.handleCaptureResult(
                        Activity.RESULT_OK,
                        captureRound.map(Uri::fromFile)
                    )
                )
            }

            waitForPageCount(viewModel, expected = 6)
            val capturedPages = viewModel.state.value.pages.map { it.file }
            assertEquals("All six capture pages must be retained by the ViewModel.", 6, capturedPages.size)

            val engine = PdfBoxToolkitEngine(app, billingManager = null)
            val assembled = engine.assembleBatchPages(
                pageFiles = capturedPages,
                outputFile = outputFile,
                profile = ResolutionProfile.LARGEST
            )
            assertTrue("Batch assembly failed: ${assembled.exceptionOrNull()}", assembled.isSuccess)
            assertEquals(6, assembled.getOrThrow().pageCount)

            PDDocument.load(outputFile).use { document ->
                assertEquals(
                    "The compiled PDF must contain all six captured pages.",
                    6,
                    document.numberOfPages
                )
            }
        } finally {
            sourceImages.forEach { it.delete() }
            outputFile.delete()
            workDirectory.deleteRecursively()
            scansDirectory.deleteRecursively()
            if (hadBatchPreference) {
                preferences.edit().putBoolean(BatchScanViewModel.KEY_BATCH_ENABLED, previousBatchPreference).apply()
            } else {
                preferences.edit().remove(BatchScanViewModel.KEY_BATCH_ENABLED).apply()
            }
        }
    }

    private fun createOnePixelJpeg(file: File, index: Int): File {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA)
            bitmap.setPixel(0, 0, colors[index])
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun waitForPageCount(viewModel: BatchScanViewModel, expected: Int) {
        val deadline = System.currentTimeMillis() + 15_000L
        val mainLooper = Shadows.shadowOf(Looper.getMainLooper())
        while (viewModel.state.value.pages.size < expected && System.currentTimeMillis() < deadline) {
            mainLooper.idle()
            Thread.sleep(15L)
        }
        mainLooper.idle()
        assertEquals(
            "Timed out waiting for all captured pages to be appended.",
            expected,
            viewModel.state.value.pages.size
        )
    }
}
