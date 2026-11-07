package com.vibesync.toolkit.pdf.presentation.viewer

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.util.BitmapCache
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.model.PageMetadata
import com.vibesync.toolkit.pdf.data.ocr.MlKitOcrEngine
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.data.pdf.PdfSearchHighlight
import com.vibesync.toolkit.pdf.data.pdf.PdfSearchMatch
import com.vibesync.toolkit.pdf.data.pdf.PdfTextExtractor
import com.vibesync.toolkit.pdf.data.pdf.PdfUnicodeSearch
import com.vibesync.toolkit.pdf.data.pdf.PdfRendererEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

data class ViewerUiState(
    val fileName: String = "",
    val pageCount: Int = 0,
    val currentPageIndex: Int = 0,
    val zoomScale: Float = 1.0f,
    val isLoading: Boolean = true,
    val isPasswordRequired: Boolean = false,
    val errorMessage: String? = null,
    val pagesMetadata: List<PageMetadata> = emptyList(),
    val isSearchRunning: Boolean = false,
    val isSearchOcrRunning: Boolean = false,
    val searchMatches: List<PdfSearchMatch> = emptyList(),
    val currentSearchMatchIndex: Int = -1,
    val searchErrorMessage: String? = null
)

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val rendererEngine = PdfRendererEngine(application)
    private val toolkitEngine = PdfBoxToolkitEngine(application)
    private val textExtractor = PdfTextExtractor(application.applicationContext)
    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var activeUri: Uri? = null
    private var loadJob: Job? = null
    private var closeJob: Job? = null
    private var loadSequence: Long = 0L
    private var unlockedDocumentFile: File? = null
    private var searchJob: Job? = null
    private var searchSequence: Long = 0L

    /**
     * Every request owns one sequence number. Only the newest request may publish UI state;
     * cancellation and a request's finally block must never overwrite a later document.
     */
    fun loadDocument(uri: Uri, password: String? = null) {
        cancelSearch()
        val requestId = ++loadSequence
        val previousLoad = loadJob
        previousLoad?.cancel()
        val previousClose = closeJob
        activeUri = uri
        val app = getApplication<Application>()
        val fallbackName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: app.getString(R.string.default_pdf_filename)

        _uiState.value = _uiState.value.copy(
            fileName = fallbackName,
            isLoading = true,
            isPasswordRequired = false,
            errorMessage = null,
            pageCount = 0,
            pagesMetadata = emptyList(),
            currentPageIndex = 0,
            zoomScale = 1.0f,
            isSearchRunning = false,
            isSearchOcrRunning = false,
            searchMatches = emptyList(),
            currentSearchMatchIndex = -1,
            searchErrorMessage = null
        )

        loadJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var pendingUnlockedFile: File? = null
            Log.i(TAG, "load.start request=" + requestId + " scheme=" + (uri.scheme ?: "unknown"))
            try {
                // A canceled operation is joined before a replacement touches renderer resources.
                previousLoad?.join()
                previousClose?.join()
                currentCoroutineContext().ensureActive()
                if (requestId != loadSequence) return@launch

                unlockedDocumentFile?.delete()
                unlockedDocumentFile = null
                rendererEngine.closeDocumentSafely()

                // ContentResolver metadata queries can block on provider I/O, so keep them off Main.
                val fileName = withContext(Dispatchers.IO) {
                    try {
                        UriUtils.getFileName(app, uri).takeIf { it.isNotBlank() } ?: fallbackName
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        fallbackName
                    }
                }
                currentCoroutineContext().ensureActive()
                if (requestId != loadSequence) return@launch
                _uiState.value = _uiState.value.copy(fileName = fileName)

                var targetUri = uri
                if (!password.isNullOrBlank()) {
                    val outputFile = UriUtils.createExportCacheFile(app, "unlocked_doc")
                    pendingUnlockedFile = outputFile
                    val decryptResult = toolkitEngine.decryptPdf(uri, password, outputFile)
                    currentCoroutineContext().ensureActive()
                    if (decryptResult.isFailure) {
                        val decryptError = decryptResult.exceptionOrNull()
                        val passwordFailure = isPasswordFailure(decryptError)
                        Log.w(
                            TAG,
                            "load.decrypt_failed request=" + requestId +
                                " category=" + (decryptError?.javaClass?.simpleName ?: "Unknown")
                        )
                        if (requestId == loadSequence) {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                isPasswordRequired = passwordFailure,
                                errorMessage = if (passwordFailure) {
                                    app.getString(R.string.invalid_password)
                                } else {
                                    app.getString(R.string.document_load_error)
                                }
                            )
                        }
                        return@launch
                    }
                    val decryptedFile = decryptResult.getOrThrow()
                    pendingUnlockedFile = decryptedFile
                    targetUri = Uri.fromFile(decryptedFile)
                }

                Log.d(TAG, "load.renderer_open_start request=" + requestId)
                val openResult = rendererEngine.openDocument(targetUri)
                currentCoroutineContext().ensureActive()
                val count = openResult.getOrElse { throw it }
                if (count <= 0) {
                    throw IllegalStateException(app.getString(R.string.document_load_error))
                }

                val metadata = rendererEngine.getPagesMetadata()
                currentCoroutineContext().ensureActive()
                if (requestId != loadSequence) return@launch
                if (metadata.isEmpty()) {
                    throw IllegalStateException(app.getString(R.string.document_load_error))
                }

                pendingUnlockedFile?.let { decryptedFile ->
                    unlockedDocumentFile?.delete()
                    unlockedDocumentFile = decryptedFile
                    pendingUnlockedFile = null
                }
                _uiState.value = _uiState.value.copy(
                    fileName = fileName,
                    pageCount = count,
                    pagesMetadata = metadata,
                    isLoading = false,
                    isPasswordRequired = false,
                    errorMessage = null,
                    currentPageIndex = 0
                )
                Log.i(
                    TAG,
                    "load.success request=" + requestId +
                        " pages=" + count +
                        " elapsedMs=" + (SystemClock.elapsedRealtime() - startedAt)
                )
            } catch (cancelled: CancellationException) {
                Log.d(TAG, "load.cancelled request=" + requestId)
                if (requestId == loadSequence && _uiState.value.isLoading) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isPasswordRequired = false,
                        errorMessage = app.getString(R.string.document_load_error)
                    )
                }
                closeRendererNonCancellable()
                throw cancelled
            } catch (error: FileNotFoundException) {
                publishLoadFailure(requestId, app, error)
            } catch (error: SecurityException) {
                publishLoadFailure(requestId, app, error)
            } catch (error: IOException) {
                publishLoadFailure(requestId, app, error)
            } catch (error: OutOfMemoryError) {
                publishLoadFailure(requestId, app, error)
            } catch (error: Exception) {
                publishLoadFailure(requestId, app, error)
            } finally {
                pendingUnlockedFile?.delete()
                // Safety net: any active request that exits without publishing success or failure
                // must become terminal. A stale request is never allowed to touch current UI state.
                if (requestId == loadSequence && _uiState.value.isLoading) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isPasswordRequired = false,
                        errorMessage = app.getString(R.string.document_load_error)
                    )
                    Log.w(TAG, "load.terminal_fallback request=" + requestId)
                }
            }
        }
    }

    fun retryLastDocument() {
        activeUri?.let { loadDocument(it) }
    }

    fun submitPassword(password: String) {
        activeUri?.let { uri -> loadDocument(uri, password) }
    }

    fun dismissPasswordDialog() {
        _uiState.value = _uiState.value.copy(isPasswordRequired = false)
    }

    /** Called when the viewer leaves composition; cleanup runs off the main thread. */
    fun closeDocument() {
        cancelSearch()
        loadSequence += 1
        val previousLoad = loadJob
        previousLoad?.cancel()
        loadJob = null
        val previousClose = closeJob
        closeJob = viewModelScope.launch {
            previousClose?.join()
            previousLoad?.join()
            rendererEngine.closeDocumentSafely()
            unlockedDocumentFile?.delete()
            unlockedDocumentFile = null
        }
    }

    /**
     * Returns a cache lease for a page. The caller closes it when that page leaves composition,
     * keeping its bitmap alive across cache eviction and low-memory cache clears.
     */
    suspend fun acquirePageBitmap(
        pageIndex: Int,
        scale: Float = 1.0f
    ): BitmapCache.BitmapLease? = rendererEngine.renderPageLease(pageIndex, scale)

    fun cacheKeyForPage(pageIndex: Int, scale: Float): String? =
        rendererEngine.cacheKeyForPage(pageIndex, scale)

    fun onPageScrolled(pageIndex: Int) {
        if (_uiState.value.currentPageIndex != pageIndex) {
            _uiState.value = _uiState.value.copy(currentPageIndex = pageIndex)
        }
    }

    /**
     * Searches every page with PDFBox first. Pages with no usable text layer are rendered through
     * the existing PdfRenderer and passed to ML Kit OCR. Only one page bitmap is leased at a time.
     */
    fun searchDocument(query: String) {
        val normalizedQuery = query.trim()
        val uri = activeUri
        val requestId = ++searchSequence
        searchJob?.cancel()
        searchJob = null

        if (normalizedQuery.isBlank() || uri == null) {
            _uiState.value = _uiState.value.copy(
                isSearchRunning = false,
                isSearchOcrRunning = false,
                searchMatches = emptyList(),
                currentSearchMatchIndex = -1,
                searchErrorMessage = null
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isSearchRunning = true,
            isSearchOcrRunning = false,
            searchMatches = emptyList(),
            currentSearchMatchIndex = -1,
            searchErrorMessage = null
        )

        searchJob = viewModelScope.launch {
            val app = getApplication<Application>()
            var ocrEngine: MlKitOcrEngine? = null
            try {
                // Password-protected documents are searched through the same decrypted cache file
                // already opened by PdfRenderer, never by reopening the encrypted source URI.
                val searchableUri = unlockedDocumentFile?.let { Uri.fromFile(it) } ?: uri
                val pages = textExtractor.extractPages(searchableUri)
                val matches = mutableListOf<PdfSearchMatch>()

                for (page in pages) {
                    currentCoroutineContext().ensureActive()
                    if (page.characterBounds.isEmpty()) {
                        if (requestId == searchSequence) {
                            _uiState.value = _uiState.value.copy(isSearchOcrRunning = true)
                        }
                        val lease = rendererEngine.renderPageLease(
                            page.pageIndex,
                            scale = SEARCH_OCR_RENDER_SCALE
                        )
                        if (lease != null) {
                            try {
                                val bitmap = lease.bitmap
                                if (!bitmap.isRecycled) {
                                    if (ocrEngine == null) {
                                        ocrEngine = MlKitOcrEngine(app.applicationContext)
                                    }
                                    val recognized = ocrEngine.recognize(bitmap)
                                    if (recognized.blocks.isEmpty()) {
                                        matches += PdfUnicodeSearch.findMatches(
                                            pageIndex = page.pageIndex,
                                            text = recognized.text,
                                            query = normalizedQuery
                                        )
                                    } else {
                                        recognized.blocks.forEach { block ->
                                            val box = block.boundingBox?.let {
                                                PdfSearchHighlight(
                                                    left = (it.left.toFloat() / bitmap.width * PdfSearchHighlight.COORDINATE_SCALE)
                                                        .toInt().coerceIn(0, PdfSearchHighlight.COORDINATE_SCALE),
                                                    top = (it.top.toFloat() / bitmap.height * PdfSearchHighlight.COORDINATE_SCALE)
                                                        .toInt().coerceIn(0, PdfSearchHighlight.COORDINATE_SCALE),
                                                    right = (it.right.toFloat() / bitmap.width * PdfSearchHighlight.COORDINATE_SCALE)
                                                        .toInt().coerceIn(0, PdfSearchHighlight.COORDINATE_SCALE),
                                                    bottom = (it.bottom.toFloat() / bitmap.height * PdfSearchHighlight.COORDINATE_SCALE)
                                                        .toInt().coerceIn(0, PdfSearchHighlight.COORDINATE_SCALE)
                                                )
                                            }
                                            matches += PdfUnicodeSearch.findMatches(
                                                pageIndex = page.pageIndex,
                                                text = block.text,
                                                query = normalizedQuery,
                                                highlight = box
                                            )
                                        }
                                    }
                                }
                            } finally {
                                lease.close()
                            }
                        }
                    } else {
                        matches += PdfUnicodeSearch.findMatches(
                            pageIndex = page.pageIndex,
                            text = page.text,
                            query = normalizedQuery,
                            characterBounds = page.characterBounds
                        )
                    }
                }

                if (requestId == searchSequence) {
                    val orderedMatches = matches.sortedWith(
                        compareBy<PdfSearchMatch> { it.pageIndex }.thenBy { it.startOffset }
                    )
                    _uiState.value = _uiState.value.copy(
                        searchMatches = orderedMatches,
                        currentSearchMatchIndex = if (orderedMatches.isEmpty()) -1 else 0,
                        searchErrorMessage = null
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "search.failed category=" + error.javaClass.simpleName, error)
                if (requestId == searchSequence) {
                    _uiState.value = _uiState.value.copy(
                        searchMatches = emptyList(),
                        currentSearchMatchIndex = -1,
                        searchErrorMessage = app.getString(R.string.viewer_search_failed)
                    )
                }
            } finally {
                ocrEngine?.close()
                if (requestId == searchSequence) {
                    _uiState.value = _uiState.value.copy(
                        isSearchRunning = false,
                        isSearchOcrRunning = false
                    )
                }
            }
        }
    }

    fun clearSearch() {
        cancelSearch()
        _uiState.value = _uiState.value.copy(
            isSearchRunning = false,
            isSearchOcrRunning = false,
            searchMatches = emptyList(),
            currentSearchMatchIndex = -1,
            searchErrorMessage = null
        )
    }

    fun showNextSearchMatch() = moveSearchMatch(1)

    fun showPreviousSearchMatch() = moveSearchMatch(-1)

    private fun moveSearchMatch(delta: Int) {
        val count = _uiState.value.searchMatches.size
        if (count == 0) return
        val current = _uiState.value.currentSearchMatchIndex.coerceIn(0, count - 1)
        val next = (current + delta + count) % count
        _uiState.value = _uiState.value.copy(currentSearchMatchIndex = next)
    }

    private fun cancelSearch() {
        searchSequence += 1L
        searchJob?.cancel()
        searchJob = null
    }

    fun setZoom(scale: Float) {
        val clamped = scale.coerceIn(1.0f, 4.0f)
        _uiState.value = _uiState.value.copy(zoomScale = clamped)
    }

    private suspend fun publishLoadFailure(
        requestId: Long,
        app: Application,
        error: Throwable
    ) {
        // Keep the exception and stack trace in Logcat so provider/permission/IO failures
        // can be diagnosed instead of appearing as a silent, permanent loading spinner.
        Log.e("PdfViewer", "Failed to render PDF", error)
        closeRendererNonCancellable()

        if (requestId == loadSequence) {
            val passwordFailure = isPasswordFailure(error)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isPasswordRequired = passwordFailure,
                errorMessage = when {
                    passwordFailure -> app.getString(R.string.encrypted_document)
                    error is FileNotFoundException -> app.getString(R.string.document_load_error)
                    error is SecurityException -> app.getString(R.string.document_load_error)
                    error is IOException -> app.getString(R.string.document_load_error)
                    else -> app.getString(R.string.document_load_error)
                }
            )
        }
    }

    private suspend fun closeRendererNonCancellable() {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                rendererEngine.closeDocumentSafely()
            } catch (error: Exception) {
                Log.w(TAG, "renderer.cleanup_failed category=" + error.javaClass.simpleName)
            }
        }
    }

    private fun isPasswordFailure(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current.javaClass.simpleName.contains("InvalidPassword", ignoreCase = true)) {
                return true
            }
            val message = current.message?.lowercase().orEmpty()
            if (
                message.contains("password required") ||
                message.contains("incorrect password") ||
                message.contains("invalid password") ||
                message.contains("document is encrypted")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    override fun onCleared() {
        loadSequence += 1
        loadJob?.cancel()
        searchJob?.cancel()
        val fileToDelete = unlockedDocumentFile
        unlockedDocumentFile = null
        super.onCleared()

        // Never wait for PdfRenderer's native lock on the main thread during lifecycle teardown.
        // This short-lived, standalone scope survives ViewModel scope cancellation and exits after
        // renderer/page descriptors are closed and the decrypted cache source is deleted.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                rendererEngine.closeDocumentSafely()
            } catch (error: Exception) {
                Log.w(TAG, "renderer.final_cleanup_failed category=" + error.javaClass.simpleName)
            } finally {
                fileToDelete?.delete()
            }
        }
    }

    companion object {
        private const val TAG = "PdfViewerViewModel"
        private const val MIN_NATIVE_TEXT_CHARACTERS_PER_PAGE = 32
        private const val SEARCH_OCR_RENDER_SCALE = 2.0f
    }
}
