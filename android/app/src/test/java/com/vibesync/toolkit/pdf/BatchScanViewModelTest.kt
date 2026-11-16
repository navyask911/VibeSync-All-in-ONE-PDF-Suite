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
import kotlinx.coroutines.runBlocking
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
            val firstFolder = requireNotNull(firstPages.first().file.parentFile)
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
    fun deleteEmptyBatchFolderRemovesDirectoryAndAllRemainingContents() {
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
            val batchId = requireNotNull(viewModel.state.value.activeBatchId)
            val batchDirectory = File(directory, batchId)
            captureTwo(viewModel, sources)

            viewModel.clearPages()
            assertTrue("Clearing pages leaves the empty batch directory behind.", batchDirectory.isDirectory)
            assertEquals(0, batchDirectory.listFiles().orEmpty().size)

            // A batch may also contain non-page artifacts (for example, a compiled PDF).
            val nestedArtifact = File(batchDirectory, "exports/compiled.pdf").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            assertTrue(nestedArtifact.isFile)

            val result = runBlocking { viewModel.deleteBatchFolder(batchId) }

            assertTrue("Folder deletion failed: ${result.exceptionOrNull()}", result.isSuccess)
            assertFalse(batchDirectory.exists())
            assertFalse(nestedArtifact.exists())
            assertFalse(viewModel.state.value.batchFolders.any { it.id == batchId })
            assertEquals(null, viewModel.state.value.activeBatchId)
            assertTrue(viewModel.state.value.pages.isEmpty())
        } finally {
            sources.forEach { it.delete() }
            directory.deleteRecursively()
            restorePref(prefs, hadToggle, previousToggle)
        }
    }


    @Test
    fun batchFolderRenamePersistsDisplayNameWithoutMovingPageFiles() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(app.filesDir, "VibeSyncScans")
        val prefs = app.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, 0)
        val hadToggle = prefs.contains(BatchScanViewModel.KEY_BATCH_ENABLED)
        val previousToggle = prefs.getBoolean(BatchScanViewModel.KEY_BATCH_ENABLED, false)
        root.deleteRecursively()
        try {
            val viewModel = BatchScanViewModel(app)
            viewModel.startNewBatchScan()
            val batchId = requireNotNull(viewModel.state.value.activeBatchId)
            val batchDirectory = File(root, batchId)
            val marker = File(batchDirectory, "keep-page-data.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }

            val renamed = runBlocking { viewModel.renameBatchFolder(batchId, "  Client   Records  ") }

            assertTrue("Rename failed: ${renamed.exceptionOrNull()}", renamed.isSuccess)
            assertEquals("Client Records", viewModel.state.value.batchFolders.first { it.id == batchId }.name)
            assertTrue(marker.isFile)
            assertEquals(batchId, viewModel.state.value.activeBatchId)

            val recreated = BatchScanViewModel(app)
            assertEquals("Client Records", recreated.state.value.batchFolders.first { it.id == batchId }.name)
            assertTrue(marker.isFile)
        } finally {
            root.deleteRecursively()
            restorePref(prefs, hadToggle, previousToggle)
        }
    }

    @Test
    fun duplicateBatchFolderCopiesManifestPagesAndCompiledPdfIntoNewBatch() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val root = File(app.filesDir, "VibeSyncScans")
        val prefs = app.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, 0)
        val hadToggle = prefs.contains(BatchScanViewModel.KEY_BATCH_ENABLED)
        val previousToggle = prefs.getBoolean(BatchScanViewModel.KEY_BATCH_ENABLED, false)
        root.deleteRecursively()
        val sources = sourcePages(app)
        try {
            val viewModel = BatchScanViewModel(app)
            viewModel.startNewBatchScan()
            val batchId = requireNotNull(viewModel.state.value.activeBatchId)
            val originalDirectory = File(root, batchId)
            val pageFile = sources.first().copyTo(File(originalDirectory, "page_1.jpg"))
            File(originalDirectory, "batch_pages.json").writeText(
                """[{"id":"page-1","file":"page_1.jpg","width":96,"height":144,"estimatedBytes":55296}]"""
            )
            val compiledPdf = File(originalDirectory, "compiled.pdf").apply { writeBytes(byteArrayOf(9, 8, 7, 6)) }
            val renameResult = runBlocking { viewModel.renameBatchFolder(batchId, "Client Records") }
            assertTrue(renameResult.isSuccess)

            val duplicateResult = runBlocking { viewModel.duplicateBatchFolder(batchId) }

            assertTrue("Duplicate failed: ${duplicateResult.exceptionOrNull()}", duplicateResult.isSuccess)
            val duplicateId = requireNotNull(duplicateResult.getOrNull())
            val duplicateDirectory = File(root, duplicateId)
            assertTrue(duplicateId.startsWith(BatchScanViewModel.BATCH_FOLDER_PREFIX))
            assertEquals(pageFile.readBytes().toList(), File(duplicateDirectory, "page_1.jpg").readBytes().toList())
            assertEquals(compiledPdf.readBytes().toList(), File(duplicateDirectory, "compiled.pdf").readBytes().toList())
            assertTrue(File(duplicateDirectory, "batch_pages.json").isFile)
            assertEquals("Client Records (Copy)", viewModel.state.value.batchFolders.first { it.id == duplicateId }.name)

            viewModel.openBatch(duplicateId)
            assertEquals(1, viewModel.state.value.pages.size)
            assertEquals(duplicateDirectory.canonicalFile, viewModel.state.value.pages.single().file.parentFile?.canonicalFile)
        } finally {
            sources.forEach { it.delete() }
            root.deleteRecursively()
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
