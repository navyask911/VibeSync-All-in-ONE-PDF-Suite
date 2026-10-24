package com.vibesync.toolkit.pdf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfile
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfilePrefs
import com.vibesync.toolkit.pdf.data.pdf.PdfBatchAssemblyResult
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class BatchScanAssemblyTest {
    @Test
    fun assemblesThreeStandardPagesInOrderAndReflectsReorderDeleteAndPersistedToggle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val work = File(context.cacheDir, "batch-assembly-" + System.nanoTime()).apply { mkdirs() }
        val originalPrefs = context.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, 0)
        val hadToggle = originalPrefs.contains(BatchScanViewModel.KEY_BATCH_ENABLED)
        val previousToggle = originalPrefs.getBoolean(BatchScanViewModel.KEY_BATCH_ENABLED, false)
        val pageRed = rasterFile(work, "red", Color.RED)
        val pageGreen = rasterFile(work, "green", Color.GREEN)
        val pageBlue = rasterFile(work, "blue", Color.BLUE)
        val engine = PdfBoxToolkitEngine(context)
        val firstPdf = File(work, "standard-three-pages.pdf")
        val mutatedPdf = File(work, "reordered-after-delete.pdf")
        try {
            val assembled: PdfBatchAssemblyResult = engine.assembleBatchPages(
                listOf(pageRed, pageGreen, pageBlue),
                firstPdf,
                ResolutionProfile.STANDARD
            ).getOrThrow()
            assertEquals(3, assembled.pageCount)
            assertFalse(assembled.fellBack)
            PDDocument.load(firstPdf).use { document ->
                assertEquals(3, document.numberOfPages)
                for (page in document.pages) {
                    assertEquals(PDRectangle.A4.width, page.mediaBox.width, 0.2f)
                    assertEquals(PDRectangle.A4.height, page.mediaBox.height, 0.2f)
                }
            }
            assertDominant(renderCenter(firstPdf, 0), Color.RED)
            assertDominant(renderCenter(firstPdf, 1), Color.GREEN)
            assertDominant(renderCenter(firstPdf, 2), Color.BLUE)

            // Equivalent to reviewing [red, green, blue], moving blue first, then deleting green.
            val mutated = engine.assembleBatchPages(
                listOf(pageBlue, pageRed),
                mutatedPdf,
                ResolutionProfile.STANDARD
            ).getOrThrow()
            assertEquals(2, mutated.pageCount)
            assertDominant(renderCenter(mutatedPdf, 0), Color.BLUE)
            assertDominant(renderCenter(mutatedPdf, 1), Color.RED)

            val viewModel = BatchScanViewModel(context.applicationContext as Application)
            viewModel.setBatchModeEnabled(true)
            assertTrue(originalPrefs.getBoolean(BatchScanViewModel.KEY_BATCH_ENABLED, false))
            viewModel.setBatchModeEnabled(false)

            val fallback = ResolutionProfile.LARGEST.spec(
                256L * 1024L * 1024L,
                12_000,
                12_000
            )
            assertTrue("The LARGEST tier must signal its STANDARD fallback.", fallback.fellBack)
        } finally {
            if (hadToggle) originalPrefs.edit().putBoolean(BatchScanViewModel.KEY_BATCH_ENABLED, previousToggle).apply()
            else originalPrefs.edit().remove(BatchScanViewModel.KEY_BATCH_ENABLED).apply()
            work.deleteRecursively()
        }
    }

    private fun rasterFile(directory: File, name: String, color: Int): File {
        val file = File(directory, name + ".png")
        val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun renderCenter(pdf: File, pageIndex: Int): Int {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val bitmap = Bitmap.createBitmap(300, 424, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun assertDominant(actual: Int, expected: Int) {
        when (expected) {
            Color.RED -> assertTrue("Expected red page, got " + Integer.toHexString(actual), Color.red(actual) > Color.green(actual) + 80 && Color.red(actual) > Color.blue(actual) + 80)
            Color.GREEN -> assertTrue("Expected green page, got " + Integer.toHexString(actual), Color.green(actual) > Color.red(actual) + 50 && Color.green(actual) > Color.blue(actual) + 50)
            Color.BLUE -> assertTrue("Expected blue page, got " + Integer.toHexString(actual), Color.blue(actual) > Color.red(actual) + 80 && Color.blue(actual) > Color.green(actual) + 80)
        }
    }
}
