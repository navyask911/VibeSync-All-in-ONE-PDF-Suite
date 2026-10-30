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
import java.io.File
import java.io.IOException

class BatchScanViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val prefs = app.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, 0)
    private val scansDirectory = File(app.filesDir, "VibeSyncScans")
    private val batchPdfDirectory = File(app.filesDir, "scans")
    private val engine = PdfBoxToolkitEngine(app)
    private val _state = MutableStateFlow(
        BatchScanState(batchModeEnabled = prefs.getBoolean(KEY_BATCH_ENABLED, false))
    )
    val state: StateFlow<BatchScanState> = _state.asStateFlow()
    private var compileJob: Job? = null

    fun setBatchModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BATCH_ENABLED, enabled).apply()
        _state.value = _state.value.copy(batchModeEnabled = enabled, messageRes = null)
        if (!enabled && _state.value.pages.isEmpty()) _state.value = _state.value.copy(awaitingCapture = false)
    }

    fun requestCapture() {
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
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { appendPageImages(pageUris) }
            result.fold(
                onSuccess = { pages -> _state.value = _state.value.copy(pages = _state.value.pages + pages, messageRes = null) },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        messageRes = if (error is BatchMemoryLimitException) R.string.batch_memory_limit else R.string.batch_capture_failed
                    )
                }
            )
        }
        return true
    }

    private fun appendPageImages(uris: List<Uri>): Result<List<BatchScanPage>> = runCatching {
        if (!scansDirectory.exists() && !scansDirectory.mkdirs()) throw IOException("Could not create persistent scan folder.")
        val pending = mutableListOf<BatchScanPage>()
        var totalBytes = _state.value.pages.sumOf { it.estimatedBytes }
        val memoryLimit = Runtime.getRuntime().maxMemory() / 4L
        try {
            uris.forEachIndexed { index, uri ->
                val stagingFile = File.createTempFile("batch_capture_", ".jpg", scansDirectory)
                var destination: File? = null
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        stagingFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("Unable to read a captured page.")
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(temp.absolutePath, options)
                    if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Unable to decode a captured page.")
                    val bytes = options.outWidth.toLong() * options.outHeight.toLong() * 4L
                    if (bytes > memoryLimit || totalBytes > memoryLimit - bytes) throw BatchMemoryLimitException()
                    totalBytes += bytes
                    val baseName = generateSmartFileName("VibeSync_BatchScan_Page_${index + 1}", "jpg")
                    var finalFile = File(scansDirectory, baseName)
                    var suffix = 1
                    while (finalFile.exists()) {
                        finalFile = File(scansDirectory, baseName.removeSuffix(".jpg") + "_${suffix++}.jpg")
                    }
                    if (!stagingFile.renameTo(finalFile)) throw IOException("Could not persist captured page image.")
                    destination = finalFile
                    pending.add(
                        BatchScanPage(
                            id = "${System.nanoTime()}_$index",
                            file = finalFile,
                            width = options.outWidth,
                            height = options.outHeight,
                            estimatedBytes = bytes
                        )
                    )
                } catch (error: Exception) {
                    stagingFile.delete()
                    destination?.delete()
                    throw error
                }
            }
            pending
        } catch (error: Exception) {
            pending.forEach { it.file.delete() }
            throw error
        }
    }

    fun movePage(from: Int, to: Int) {
        val pages = _state.value.pages.toMutableList()
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val moved = pages.removeAt(from)
        pages.add(to, moved)
        _state.value = _state.value.copy(pages = pages)
    }

    fun deletePage(pageId: String) {
        val page = _state.value.pages.firstOrNull { it.id == pageId } ?: return
        page.file.delete()
        _state.value = _state.value.copy(pages = _state.value.pages.filterNot { it.id == pageId })
    }

    fun clearPages() {
        compileJob?.cancel()
        // Captured JPGs are persistent user files; this only clears transient review state.
        _state.value = _state.value.copy(pages = emptyList(), isCompiling = false, progress = 0f, savedFile = null)
    }

    fun compileBatch(pageRangeInput: String = "All") {
        if (_state.value.isCompiling || _state.value.pages.isEmpty()) return
        val pages = _state.value.pages.toList()
        val pageRanges = runCatching { PageRangeParser.parse(pageRangeInput, pages.size) }.getOrElse {
            _state.value = _state.value.copy(messageRes = R.string.batch_compile_failed)
            return
        }
        compileJob = viewModelScope.launch {
            _state.value = _state.value.copy(isCompiling = true, progress = 0f, messageRes = null, savedFile = null)
            val output = withContext(Dispatchers.IO) {
                if (!batchPdfDirectory.exists() && !batchPdfDirectory.mkdirs()) throw IOException("Could not create scan PDF directory.")
                val baseName = generateSmartFileName("VibeSync_BatchScan", "pdf")
                var file = File(batchPdfDirectory, baseName)
                var suffix = 1
                while (file.exists()) {
                    file = File(batchPdfDirectory, baseName.removeSuffix(".pdf") + "_${suffix++}.pdf")
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
                    onProgress = { progress -> _state.value = _state.value.copy(progress = progress) }
                ).getOrThrow()
                // Keep the original JPEGs. Clear only the transient UI queue after successful compile.
                _state.value = _state.value.copy(
                    pages = emptyList(),
                    isCompiling = false,
                    progress = 1f,
                    savedFile = result.file,
                    fellBack = result.fellBack,
                    messageRes = if (result.fellBack) R.string.resolution_profile_memory_fallback else null
                )
            } catch (cancelled: CancellationException) {
                output.delete()
                _state.value = _state.value.copy(isCompiling = false, progress = 0f, savedFile = null)
                throw cancelled
            } catch (error: Exception) {
                output.delete()
                _state.value = _state.value.copy(
                    isCompiling = false,
                    progress = 0f,
                    messageRes = if (error is BatchMemoryLimitException || error.message == app.getString(R.string.batch_memory_limit)) R.string.batch_memory_limit else R.string.batch_compile_failed
                )
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
        // Persistent batch images survive Activity/ViewModel teardown.
        super.onCleared()
    }

    private class BatchMemoryLimitException : IOException()
    companion object {
        const val KEY_BATCH_ENABLED = "batch_mode_enabled"
        const val KEY_FILTER_ID = "scan_filter_id"
    }
}