package com.vibesync.toolkit.pdf.presentation.scanner

import android.app.Activity
import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.data.pdf.PageRangeParser
import com.vibesync.toolkit.pdf.core.util.generateSmartFileName
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfile
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfilePrefs
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatchScanViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val prefs = app.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, 0)
    private val scansDirectory = File(app.filesDir, "VibeSyncScans")
    private val engine = PdfBoxToolkitEngine(app)
    private val _state = MutableStateFlow(
        BatchScanState(batchModeEnabled = prefs.getBoolean(KEY_BATCH_ENABLED, false))
    )
    val state: StateFlow<BatchScanState> = _state.asStateFlow()

    private var activeBatchDirectory: File? = null
    private var compileJob: Job? = null

    init {
        refreshBatchFolders()
    }

    /** Creates a dedicated timestamped folder without changing any existing batch. */
    fun startNewBatchScan() {
        try {
            if (!scansDirectory.exists() && !scansDirectory.mkdirs()) {
                throw IOException("Could not create the batch scan library.")
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val baseName = "$BATCH_FOLDER_PREFIX$stamp"
            var directory = File(scansDirectory, baseName)
            var suffix = 1
            while (directory.exists()) {
                directory = File(scansDirectory, "${baseName}_${suffix++}")
            }
            if (!directory.mkdirs()) throw IOException("Could not create a new batch folder.")

            activeBatchDirectory = directory
            prefs.edit().putBoolean(KEY_BATCH_ENABLED, true).apply()
            _state.value = _state.value.copy(
                batchModeEnabled = true,
                awaitingCapture = false,
                isCompiling = false,
                progress = 0f,
                pages = emptyList(),
                activeBatchId = directory.name,
                messageRes = null,
                savedFile = null,
                fellBack = false
            )
            refreshBatchFolders()
        } catch (error: Exception) {
            _state.value = _state.value.copy(messageRes = R.string.batch_capture_failed)
        }
    }

    /** Opens a saved batch and restores its manifest-backed page order. */
    fun openBatch(batchId: String) {
        val directory = if (batchId == LEGACY_BATCH_ID) {
            scansDirectory
        } else {
            File(scansDirectory, batchId)
        }
        if (!directory.isDirectory ||
            (directory != scansDirectory && directory.parentFile?.canonicalFile != scansDirectory.canonicalFile) ||
            (directory != scansDirectory && !directory.name.startsWith(BATCH_FOLDER_PREFIX))
        ) return

        activeBatchDirectory = directory
        _state.value = _state.value.copy(
            pages = loadBatchPages(directory),
            activeBatchId = batchId,
            awaitingCapture = false,
            isCompiling = false,
            progress = 0f,
            savedFile = null,
            messageRes = null,
            fellBack = false
        )
        refreshBatchFolders()
    }

    fun closeBatch() {
        activeBatchDirectory = null
        _state.value = _state.value.copy(
            activeBatchId = null,
            pages = emptyList(),
            awaitingCapture = false,
            isCompiling = false,
            progress = 0f,
            savedFile = null,
            messageRes = null
        )
        refreshBatchFolders()
    }

    private fun refreshBatchFolders() {
        if (!scansDirectory.exists()) {
            _state.value = _state.value.copy(batchFolders = emptyList())
            return
        }
        val directories = mutableListOf<File>()
        // Preserve the old single-folder layout as a legacy batch on upgrade.
        val legacyHasPages = File(scansDirectory, PAGE_MANIFEST).isFile ||
            scansDirectory.listFiles().orEmpty().any {
                it.isFile && it.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS
            }
        if (legacyHasPages) directories += scansDirectory
        directories += scansDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(BATCH_FOLDER_PREFIX) }

        val folders = directories.distinctBy { it.absolutePath }.map { directory ->
            BatchScanFolder(
                id = if (directory == scansDirectory) LEGACY_BATCH_ID else directory.name,
                name = if (directory == scansDirectory) LEGACY_BATCH_NAME else readBatchDisplayName(directory) ?: directory.name.removePrefix(BATCH_FOLDER_PREFIX).replace('_', ' '),
                directory = directory,
                pageCount = loadBatchPages(directory).size,
                lastModified = directory.lastModified()
            )
        }.sortedWith(compareByDescending<BatchScanFolder> { it.lastModified }.thenByDescending { it.name })
        _state.value = _state.value.copy(batchFolders = folders)
    }

    private fun loadBatchPages(directory: File): List<BatchScanPage> {
        if (!directory.isDirectory) return emptyList()
        return runCatching {
            val manifest = File(directory, PAGE_MANIFEST)
            if (manifest.isFile) {
                val entries = JSONArray(manifest.readText())
                (0 until entries.length()).mapNotNull { index ->
                    val entry = entries.getJSONObject(index)
                    val name = entry.getString("file")
                    if (File(name).name != name) return@mapNotNull null
                    val file = File(directory, name)
                    if (!file.isFile || file.length() <= 0L) return@mapNotNull null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    val width = entry.optInt("width", bounds.outWidth).takeIf { it > 0 } ?: bounds.outWidth
                    val height = entry.optInt("height", bounds.outHeight).takeIf { it > 0 } ?: bounds.outHeight
                    if (width <= 0 || height <= 0) return@mapNotNull null
                    BatchScanPage(
                        id = entry.optString("id", file.name),
                        file = file,
                        width = width,
                        height = height,
                        estimatedBytes = entry.optLong("estimatedBytes", width.toLong() * height * 4L)
                    )
                }
            } else {
                directory.listFiles().orEmpty()
                    .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS }
                    .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
                    .mapNotNull { file ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else BatchScanPage(
                            id = file.name,
                            file = file,
                            width = bounds.outWidth,
                            height = bounds.outHeight,
                            estimatedBytes = bounds.outWidth.toLong() * bounds.outHeight * 4L
                        )
                    }
            }
        }.getOrElse { emptyList() }
    }

    private fun persistPageManifest(directory: File, pages: List<BatchScanPage>) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create persistent scan folder.")
        }
        val entries = JSONArray()
        pages.forEach { page ->
            entries.put(
                JSONObject()
                    .put("id", page.id)
                    .put("file", page.file.name)
                    .put("width", page.width)
                    .put("height", page.height)
                    .put("estimatedBytes", page.estimatedBytes)
            )
        }
        val manifest = File(directory, PAGE_MANIFEST)
        val staging = File(directory, "$PAGE_MANIFEST.tmp")
        staging.writeText(entries.toString())
        if (manifest.exists() && !manifest.delete()) {
            staging.delete()
            throw IOException("Could not replace saved batch scan order.")
        }
        if (!staging.renameTo(manifest)) {
            try {
                staging.copyTo(manifest, overwrite = true)
            } finally {
                staging.delete()
            }
        }
    }

    fun setBatchModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BATCH_ENABLED, enabled).apply()
        _state.value = _state.value.copy(
            batchModeEnabled = enabled,
            messageRes = null,
            awaitingCapture = if (enabled) _state.value.awaitingCapture else false
        )
    }

    fun requestCapture() {
        if (_state.value.activeBatchId == null || activeBatchDirectory == null) {
            startNewBatchScan()
        }
        if (activeBatchDirectory == null) return
        if (!_state.value.batchModeEnabled) setBatchModeEnabled(true)
        _state.value = _state.value.copy(awaitingCapture = true, messageRes = null)
    }

    /** Returns true only when this activity-result belongs to an explicitly requested batch capture. */
    fun handleCaptureResult(resultCode: Int, pageUris: List<Uri>): Boolean {
        if (!_state.value.awaitingCapture) return false
        _state.value = _state.value.copy(awaitingCapture = false)
        if (resultCode != Activity.RESULT_OK) return true
        if (pageUris.isEmpty()) {
            _state.value = _state.value.copy(messageRes = R.string.batch_no_pages_captured)
            return true
        }

        val targetDirectory = activeBatchDirectory ?: return true
        viewModelScope.launch {
            val appended = withContext(Dispatchers.IO) { appendPageImages(pageUris, targetDirectory) }
            appended.fold(
                onSuccess = { newPages ->
                    val existingPages = if (activeBatchDirectory?.absolutePath == targetDirectory.absolutePath) {
                        _state.value.pages
                    } else {
                        withContext(Dispatchers.IO) { loadBatchPages(targetDirectory) }
                    }
                    val orderedPages = existingPages + newPages
                    runCatching {
                        withContext(Dispatchers.IO) { persistPageManifest(targetDirectory, orderedPages) }
                    }.onSuccess {
                        if (activeBatchDirectory?.absolutePath == targetDirectory.absolutePath) {
                            _state.value = _state.value.copy(pages = orderedPages, messageRes = null)
                        }
                        refreshBatchFolders()
                    }.onFailure {
                        newPages.forEach { it.file.delete() }
                        if (activeBatchDirectory?.absolutePath == targetDirectory.absolutePath) {
                            _state.value = _state.value.copy(messageRes = R.string.batch_capture_failed)
                        }
                        refreshBatchFolders()
                    }
                },
                onFailure = { error ->
                    if (activeBatchDirectory?.absolutePath == targetDirectory.absolutePath) {
                        _state.value = _state.value.copy(
                            messageRes = if (error is BatchMemoryLimitException) {
                                R.string.batch_memory_limit
                            } else {
                                R.string.batch_capture_failed
                            }
                        )
                    }
                }
            )
        }
        return true
    }

    private fun appendPageImages(uris: List<Uri>, directory: File): Result<List<BatchScanPage>> {
        val pending = mutableListOf<BatchScanPage>()
        return try {
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create the batch page folder.")
            }
            uris.forEachIndexed { index, uri ->
                val sourceFile = File.createTempFile("batch_capture_source_", ".img", directory)
                var destination: File? = null
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        sourceFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("Unable to read a captured page.")

                    val baseName = generateSmartFileName("VibeSync_BatchScan_Page_${index + 1}", "jpg")
                    var finalFile = File(directory, baseName)
                    var suffix = 1
                    while (finalFile.exists()) {
                        finalFile = File(directory, baseName.removeSuffix(".jpg") + "_${suffix++}.jpg")
                    }
                    destination = finalFile
                    val image = BatchScanImageProcessor.process(sourceFile, finalFile)
                    pending += BatchScanPage(
                        id = "${System.nanoTime()}_$index",
                        file = finalFile,
                        width = image.width,
                        height = image.height,
                        estimatedBytes = image.width.toLong() * image.height * 4L
                    )
                    finalFile.setLastModified(System.currentTimeMillis() + index)
                } catch (error: Exception) {
                    destination?.delete()
                    throw error
                } finally {
                    sourceFile.delete()
                }
            }
            Result.success(pending)
        } catch (cancelled: CancellationException) {
            pending.forEach { it.file.delete() }
            throw cancelled
        } catch (error: Exception) {
            pending.forEach { it.file.delete() }
            Result.failure(error)
        }
    }

    fun movePage(from: Int, to: Int) {
        val directory = activeBatchDirectory ?: return
        val pages = _state.value.pages.toMutableList()
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val moved = pages.removeAt(from)
        pages.add(to, moved)
        persistPageManifest(directory, pages)
        _state.value = _state.value.copy(pages = pages)
        refreshBatchFolders()
    }

    fun deletePage(pageId: String) {
        val directory = activeBatchDirectory ?: return
        val page = _state.value.pages.firstOrNull { it.id == pageId } ?: return
        val remaining = _state.value.pages.filterNot { it.id == pageId }
        page.file.delete()
        persistPageManifest(directory, remaining)
        _state.value = _state.value.copy(pages = remaining)
        refreshBatchFolders()
    }

    fun clearPages() {
        val directory = activeBatchDirectory ?: return
        compileJob?.cancel()
        _state.value.pages.forEach { it.file.delete() }
        File(directory, PAGE_MANIFEST).delete()
        File(directory, "$PAGE_MANIFEST.tmp").delete()
        _state.value = _state.value.copy(
            pages = emptyList(),
            isCompiling = false,
            progress = 0f,
            savedFile = null,
            messageRes = null
        )
        refreshBatchFolders()
    }

    /**
     * Deletes a saved batch folder from the app-owned scan library and refreshes the folder list.
     * Folder identity is resolved from current state and canonical-path checked so callers cannot
     * delete the scans root, the legacy root batch, or a path outside VibeSyncScans.
     */
    suspend fun deleteBatchFolder(batchId: String): Result<Unit> {
        val folder = _state.value.batchFolders.firstOrNull { it.id == batchId }
            ?: return Result.failure(IllegalArgumentException("The batch folder no longer exists."))
        if (batchId == LEGACY_BATCH_ID) {
            return Result.failure(IllegalArgumentException("The legacy scan collection cannot be deleted as a batch folder."))
        }
        if (compileJob?.isActive == true) {
            return Result.failure(IOException("Wait for the current batch PDF compilation to finish before deleting a folder."))
        }

        val directory: File
        try {
            directory = folder.directory.canonicalFile
            val scansRoot = scansDirectory.canonicalFile
            if (directory.parentFile != scansRoot ||
                !directory.name.startsWith(BATCH_FOLDER_PREFIX)
            ) {
                return Result.failure(IllegalArgumentException("The selected folder is outside the batch scan library."))
            }
        } catch (error: Exception) {
            return Result.failure(error)
        }

        val deleted = try {
            withContext(Dispatchers.IO) {
                !directory.exists() || directory.deleteRecursively()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.failure(error)
        }
        if (!deleted || directory.exists()) {
            return Result.failure(IOException("Could not delete the batch folder and its contents."))
        }

        if (activeBatchDirectory?.canonicalFile == directory) {
            activeBatchDirectory = null
            _state.value = _state.value.copy(
                activeBatchId = null,
                pages = emptyList(),
                awaitingCapture = false,
                isCompiling = false,
                progress = 0f,
                savedFile = null,
                messageRes = null
            )
        }
        refreshBatchFolders()
        return Result.success(Unit)
    }


    /** Saves a user-facing label without changing the folder ID or any page-file paths. */
    suspend fun renameBatchFolder(batchId: String, requestedName: String): Result<Unit> {
        if (batchId == LEGACY_BATCH_ID) {
            return Result.failure(IllegalArgumentException("The legacy scan collection cannot be renamed."))
        }
        if (compileJob?.isActive == true) {
            return Result.failure(IOException("Wait for the current batch PDF compilation to finish before renaming a folder."))
        }
        val folder = _state.value.batchFolders.firstOrNull { it.id == batchId }
            ?: return Result.failure(IllegalArgumentException("The batch folder no longer exists."))
        val displayName = requestedName.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .filterNot { it.isISOControl() }
            .take(MAX_BATCH_DISPLAY_NAME_LENGTH)
            .trim()
        if (displayName.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a valid batch name."))
        }
        if (_state.value.batchFolders.any { it.id != batchId && it.name.equals(displayName, ignoreCase = true) }) {
            return Result.failure(IOException("Another batch already uses that name."))
        }

        return try {
            val directory = resolveManagedBatchDirectory(folder)
            withContext(Dispatchers.IO) {
                writeBatchDisplayName(directory, displayName)
            }
            refreshBatchFolders()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Copies a whole batch (page images, manifest, and any compiled PDFs) into a new safe sibling. */
    suspend fun duplicateBatchFolder(batchId: String): Result<String> {
        if (batchId == LEGACY_BATCH_ID) {
            return Result.failure(IllegalArgumentException("The legacy scan collection cannot be duplicated as a batch folder."))
        }
        if (compileJob?.isActive == true) {
            return Result.failure(IOException("Wait for the current batch PDF compilation to finish before duplicating a folder."))
        }
        val folder = _state.value.batchFolders.firstOrNull { it.id == batchId }
            ?: return Result.failure(IllegalArgumentException("The batch folder no longer exists."))
        val source = try {
            resolveManagedBatchDirectory(folder)
        } catch (error: Exception) {
            return Result.failure(error)
        }

        var createdId: String? = null
        try {
            withContext(Dispatchers.IO) {
                val root = scansDirectory.canonicalFile
                val baseName = source.name + "_Copy"
                var destination = File(root, baseName)
                var suffix = 2
                while (destination.exists()) {
                    destination = File(root, baseName + "_" + suffix)
                    suffix++
                }
                val staging = File(root, ".batch_copy_" + System.nanoTime())
                try {
                    source.copyRecursively(staging, overwrite = false)
                    val sourceName = readBatchDisplayName(source)
                        ?: source.name.removePrefix(BATCH_FOLDER_PREFIX).replace('_', ' ')
                    writeBatchDisplayName(staging, sourceName + " (Copy)")
                    if (!staging.renameTo(destination)) {
                        throw IOException("Could not publish the duplicated batch folder.")
                    }
                    createdId = destination.name
                } catch (error: Exception) {
                    staging.deleteRecursively()
                    throw error
                }
            }
            refreshBatchFolders()
            return Result.success(requireNotNull(createdId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return Result.failure(error)
        }
    }

    private fun resolveManagedBatchDirectory(folder: BatchScanFolder): File {
        require(folder.id != LEGACY_BATCH_ID) { "The legacy batch is not a managed child folder." }
        val directory = folder.directory.canonicalFile
        val scansRoot = scansDirectory.canonicalFile
        require(directory.isDirectory && directory.parentFile == scansRoot &&
            directory.name.startsWith(BATCH_FOLDER_PREFIX)
        ) { "The selected folder is outside the batch scan library." }
        return directory
    }

    private fun readBatchDisplayName(directory: File): String? = runCatching {
        File(directory, BATCH_DISPLAY_NAME_FILE)
            .takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun writeBatchDisplayName(directory: File, displayName: String) {
        val target = File(directory, BATCH_DISPLAY_NAME_FILE)
        val staging = File(directory, "$BATCH_DISPLAY_NAME_FILE.tmp")
        val backup = File(directory, "$BATCH_DISPLAY_NAME_FILE.bak")
        if (staging.exists() && !staging.delete()) throw IOException("Could not prepare the batch name update.")
        if (backup.exists() && !backup.delete()) throw IOException("Could not prepare the batch name backup.")
        staging.writeText(displayName)
        if (target.exists() && !target.renameTo(backup)) {
            staging.delete()
            throw IOException("Could not stage the previous batch name.")
        }
        if (!staging.renameTo(target)) {
            backup.takeIf { it.exists() }?.renameTo(target)
            staging.delete()
            throw IOException("Could not save the batch name.")
        }
        backup.delete()
    }

    fun compileBatch(pageRangeInput: String = "All") {
        if (_state.value.isCompiling || _state.value.pages.isEmpty()) return
        val directory = activeBatchDirectory ?: return
        val pages = _state.value.pages.toList()
        val pageRanges = runCatching { PageRangeParser.parse(pageRangeInput, pages.size) }.getOrElse {
            _state.value = _state.value.copy(messageRes = R.string.batch_compile_failed)
            return
        }
        compileJob = viewModelScope.launch {
            _state.value = _state.value.copy(isCompiling = true, progress = 0f, messageRes = null, savedFile = null)
            val output = withContext(Dispatchers.IO) {
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Could not create the batch output directory.")
                }
                val baseName = generateSmartFileName("VibeSync_BatchScan_Compiled", "pdf")
                var file = File(directory, baseName)
                var suffix = 1
                while (file.exists()) {
                    file = File(directory, baseName.removeSuffix(".pdf") + "_${suffix++}.pdf")
                }
                file
            }
            val selectedProfile = ResolutionProfilePrefs(app).read()
            val selectedFilter = prefs.getString(KEY_FILTER_ID, null)?.let { name ->
                runCatching { FilterId.valueOf(name) }.getOrNull()
            }
            try {
                val result = engine.assembleBatchPages(
                    pageFiles = pages.map { it.file },
                    outputFile = output,
                    profile = selectedProfile,
                    filterId = selectedFilter,
                    pageRanges = pageRanges,
                    onProgress = { progress ->
                        if (activeBatchDirectory?.absolutePath == directory.absolutePath) {
                            _state.value = _state.value.copy(progress = progress)
                        }
                    }
                ).getOrThrow()
                if (activeBatchDirectory?.absolutePath == directory.absolutePath) {
                    _state.value = _state.value.copy(
                        isCompiling = false,
                        progress = 1f,
                        savedFile = result.file,
                        fellBack = result.fellBack,
                        messageRes = if (result.fellBack) {
                            R.string.resolution_profile_memory_fallback
                        } else {
                            null
                        }
                    )
                }
                refreshBatchFolders()
            } catch (cancelled: CancellationException) {
                output.delete()
                if (activeBatchDirectory?.absolutePath == directory.absolutePath) {
                    _state.value = _state.value.copy(isCompiling = false, progress = 0f, savedFile = null)
                }
                throw cancelled
            } catch (error: Exception) {
                output.delete()
                if (activeBatchDirectory?.absolutePath == directory.absolutePath) {
                    _state.value = _state.value.copy(
                        isCompiling = false,
                        progress = 0f,
                        messageRes = if (error is BatchMemoryLimitException ||
                            error.message == app.getString(R.string.batch_memory_limit)
                        ) {
                            R.string.batch_memory_limit
                        } else {
                            R.string.batch_compile_failed
                        }
                    )
                }
            }
        }
    }

    fun clearCompletion() {
        _state.value = _state.value.copy(savedFile = null, messageRes = null)
    }

    fun cancelCompile() {
        compileJob?.cancel()
    }

    override fun onCleared() {
        compileJob?.cancel()
        super.onCleared()
    }

    private class BatchMemoryLimitException : IOException()

    companion object {
        const val KEY_BATCH_ENABLED = "batch_mode_enabled"
        const val KEY_FILTER_ID = "scan_filter_id"
        const val LEGACY_BATCH_ID = "legacy_batch"
        const val LEGACY_BATCH_NAME = "BatchScan_Legacy"
        const val BATCH_FOLDER_PREFIX = "BatchScan_"
        private const val PAGE_MANIFEST = "batch_pages.json"
        private const val BATCH_DISPLAY_NAME_FILE = ".batch_display_name"
        private const val MAX_BATCH_DISPLAY_NAME_LENGTH = 64
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg")
    }
}
