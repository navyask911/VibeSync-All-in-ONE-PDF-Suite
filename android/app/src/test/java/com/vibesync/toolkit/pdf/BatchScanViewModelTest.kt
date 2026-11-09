package com.vibesync.toolkit.pdf

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfilePrefs
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class BatchScanViewModelTest {
    @Test
    fun separateBatchFoldersKeepTheirOwnPagesAndRestoreInOrder() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(app.filesDir, "VibeSyncScans")
        val prefs = app.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, 0)
        val hadToggle = prefs.contains(BatchScanViewModel.KEY_BATCH_ENABLED)
        val previousToggle = prefs.getBoolean(BatchScanViewModel.KEY_BATCH_ENABLED, false)
        directory.deleteRecursively()
        val sources = sourcePages(app)
        try {
            val viewModel = BatchScanViewModel(app)
            viewModel.startNewBatchScan()
            val firstBatchId = requireNotNull(viewModel.state.value.activeBatchId)
            captureTwo(viewModel, sources)
            val firstPages = viewModel.state.value.pages
            val firstFolder = firstPages.first().file.parentFile
            assertTrue(firstFolder.name.startsWith(BatchScanViewModel.BATCH_FOLDER_PREFIX))
            assertTrue(File(firstFolder, "batch_pages.json").isFile)
            assertEquals(2, firstPages.size)

            viewModel.startNewBatchScan()
            val secondBatchId = requireNotNull(viewModel.state.value.activeBatchId)
            assertNotEquals(firstBatchId, secondBatchId)
            assertTrue(viewModel.state.value.pages.isEmpty())
            assertEquals(2, viewModel.state.value.batchFolders.size)

            viewModel.openBatch(firstBatchId)
            assertEquals(firstPages.map { it.file.name }, viewModel.state.value.pages.map { it.file.name })
            assertEquals(firstPages.map { it.id }, viewModel.state.value.pages.map { it.id })
            assertTrue(viewModel.state.value.pages.all { it.file.isFile })

            val recreated = BatchScanViewModel(app)
            recreated.openBatch(firstBatchId)
            assertEquals(2, recreated.state.value.pages.size)
            assertEquals(firstPages.map { it.id }, recreated.state.value.pages.map { it.id })
            recreated.clearPages()
            assertTrue(recreated.state.value.pages.isEmpty())
            assertTrue(firstPages.none { it.file.exists() })
            assertFalse(File(firstFolder, "batch_pages.json").exists())
            assertTrue(recreated.state.value.batchFolders.any { it.id == secondBatchId })
        } finally {
            sources.forEach { it.delete() }
            directory.deleteRecursively()
            restorePref(prefs, hadToggle, previousToggle)
        }
    }

    @Test
    fun compileSuccessKeepsPersistentPagesInsideTheirBatchFolderUntilClearAll() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val directory = File(app.filesDir, "VibeSyncScans")
        val prefs = app.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, 0)
        val hadToggle = prefs.contains(BatchScanViewModel.KEY_BATCH_ENABLED)
        val previousToggle = prefs.getBoolean(BatchScanViewModel.KEY_BATCH_ENABLED, false)
        directory.deleteRecursively()
        PDFBoxResourceLoader.init(app)
        val sources = sourcePages(app)
        var pdf: File? = null
        try {
            val viewModel = BatchScanViewModel(app)
            viewModel.startNewBatchScan()
            captureTwo(viewModel, sources)
            val originalFiles = viewModel.state.value.pages.map { it.file }
            val batchDirectory = requireNotNull(originalFiles.first().parentFile)
            viewModel.compileBatch("All")
            waitFor {
                viewModel.state.value.savedFile != null ||
                    (!viewModel.state.value.isCompiling && viewModel.state.value.messageRes != null)
            }
            pdf = viewModel.state.value.savedFile
            assertTrue("Compile should succeed.", pdf?.isFile == true)
            assertEquals(batchDirectory, pdf?.parentFile)
            assertEquals(2, viewModel.state.value.pages.size)
            assertTrue(originalFiles.all { it.isFile })
            assertTrue(File(batchDirectory, "batch_pages.json").isFile)
            viewModel.clearPages()
            assertTrue(viewModel.state.value.pages.isEmpty())
            assertTrue(originalFiles.none { it.exists() })
            assertFalse(File(batchDirectory, "batch_pages.json").exists())
            assertTrue("Compiled PDF remains accessible in the batch folder.", pdf?.isFile == true)
        } finally {
            pdf?.delete()
            sources.forEach { it.delete() }
            directory.deleteRecursively()
            restorePref(prefs, hadToggle, previousToggle)
        }
    }

    private fun captureTwo(viewModel: BatchScanViewModel, sources: List<File>) {
        viewModel.setBatchModeEnabled(true)
        viewModel.requestCapture()
        assertTrue(viewModel.handleCaptureResult(Activity.RESULT_OK, sources.map(Uri::fromFile)))
        waitFor { viewModel.state.value.pages.size == 2 }
    }

    private fun sourcePages(app: Application): List<File> = (1..2).map { index ->
        val file = File.createTempFile("batch-vm-$index-", ".jpg", app.cacheDir)
        val bitmap = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(if (index == 1) android.graphics.Color.RED else android.graphics.Color.BLUE)
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
            }
        } finally {
            bitmap.recycle()
        }
        file
    }

    private fun restorePref(prefs: android.content.SharedPreferences, had: Boolean, previous: Boolean) {
        if (had) prefs.edit().putBoolean(BatchScanViewModel.KEY_BATCH_ENABLED, previous).apply()
        else prefs.edit().remove(BatchScanViewModel.KEY_BATCH_ENABLED).apply()
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000L
        val main = Shadows.shadowOf(Looper.getMainLooper())
        while (!predicate() && System.currentTimeMillis() < deadline) {
            main.idle()
            Thread.sleep(15L)
        }
        main.idle()
        assertTrue("Timed out waiting for BatchScanViewModel.", predicate())
    }
}
