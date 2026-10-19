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
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.data.pdf.PdfRendererEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val PAGE_METADATA_BATCH_SIZE = 16
private const val DOCUMENT_LOAD_WATCHDOG_MS = 30_000L
private const val FIRST_PAGE_VALIDATION_SCALE = 1.5f

data class ViewerUiState(
    val fileName: String = "",
    val pageCount: Int = 0,
    val currentPageIndex: Int = 0,
    val zoomScale: Float = 1.0f,
    val isLoading: Boolean = true,
    val isPasswordRequired: Boolean = false,
    val errorMessage: String? = null,
    val pagesMetadata: List<PageMetadata> = emptyList()
)

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val rendererEngine = PdfRendererEngine(application)
    private val toolkitEngine = PdfBoxToolkitEngine(application)
    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var activeUri: Uri? = null
    private var loadJob: Job? = null
    private var pageMetadataJob: Job? = null
    private var loadWatchdogJob: Job? = null
    private var timedOutRequestId: Long? = null
    private var closeJob: Job? = null
    private var loadSequence: Long = 0L
    private var unlockedDocumentFile: File? = null

    /**
     * Every request owns one sequence number. Only the newest request may publish UI state;
     * cancellation and a request's finally block must never overwrite a later document.
     */
    fun loadDocument(uri: Uri, password: String? = null) {
        val requestId = ++loadSequence
        val previousLoad = loadJob
        pageMetadataJob?.cancel()
        pageMetadataJob = null
        loadWatchdogJob?.cancel()
        loadWatchdogJob = null
        timedOutRequestId = null
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
            zoomScale = 1.0f
        )

        loadWatchdogJob = viewModelScope.launch {
            delay(DOCUMENT_LOAD_WATCHDOG_MS)
            if (requestId == loadSequence && _uiState.value.isLoading) {
                timedOutRequestId = requestId
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isPasswordRequired = false,
                    errorMessage = app.getString(R.string.document_load_error)
                )
                Log.e(
                    TAG,
                    "load.watchdog_timeout request=$requestId timeoutMs=$DOCUMENT_LOAD_WATCHDOG_MS"
                )
                // This is cooperative; the terminal UI above is independent of a provider/native
                // call that might not return promptly when its coroutine is cancelled.
                loadJob?.cancel()
            }
        }

        loadJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var pendingUnlockedFile: File? = null
            Log.i(TAG, "load.start request=" + requestId + " scheme=" + (uri.scheme ?: "unknown"))
            try {
                // Do not join a previous provider operation: a provider read can ignore coroutine
                // cancellation. Source staging now happens outside the shared renderer mutex.
                previousClose?.join()
                currentCoroutineContext().ensureActive()
                if (requestId != loadSequence || requestId == timedOutRequestId) return@launch

                unlockedDocumentFile?.delete()
                unlockedDocumentFile = null

                // Never block the document-opening path on an optional provider display-name query.
                val fileName = fallbackName
                Log.d(TAG, "load.stage request=$requestId stage=filename_fallback status=success")

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
                            closeRendererNonCancellable()
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
                if (requestId != loadSequence) return@launch
                if (requestId == timedOutRequestId) {
                    closeRendererNonCancellable()
                    return@launch
                }
                val count = openResult.getOrElse { throw it }
                if (count <= 0) {
                    throw IllegalStateException(app.getString(R.string.document_load_error))
                }

                Log.i(TAG, "load.stage request=$requestId stage=renderer_open status=success pages=$count")
                val firstPageMetadata = rendererEngine.getPagesMetadata(0, 1).firstOrNull()
                    ?: throw IllegalStateException(app.getString(R.string.document_load_error))
                currentCoroutineContext().ensureActive()
                if (requestId != loadSequence) return@launch
                if (requestId == timedOutRequestId) {
                    closeRendererNonCancellable()
                    return@launch
                }

                Log.i(TAG, "load.stage request=$requestId stage=first_page_metadata status=success")
                val firstPageRenderStartedAt = SystemClock.elapsedRealtime()
                val firstPageLease = rendererEngine.renderPageLease(
                    pageIndex = 0,
                    scale = FIRST_PAGE_VALIDATION_SCALE
                ) ?: throw IllegalStateException("The first PDF page could not be rendered.")
                try {
                    val firstBitmap = firstPageLease.bitmap
                    if (firstBitmap.isRecycled || firstBitmap.width <= 0 || firstBitmap.height <= 0) {
                        throw IllegalStateException("The first PDF page produced an invalid bitmap.")
                    }
                    currentCoroutineContext().ensureActive()
                } finally {
                    firstPageLease.close()
                }
                Log.i(
                    TAG,
                    "load.stage request=$requestId stage=first_page_render status=success " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - firstPageRenderStartedAt}"
                )
                if (requestId != loadSequence) return@launch
                if (requestId == timedOutRequestId) {
                    closeRendererNonCancellable()
                    return@launch
                }

                pendingUnlockedFile?.let { decryptedFile ->
                    unlockedDocumentFile?.delete()
                    unlockedDocumentFile = decryptedFile
                    pendingUnlockedFile = null
                }
                _uiState.value = _uiState.value.copy(
                    fileName = fileName,
                    pageCount = count,
                    pagesMetadata = listOf(firstPageMetadata),
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
                startRemainingPageMetadataLoad(requestId, count)
                resolveDisplayNameAsync(requestId, uri, fallbackName)
            } catch (cancelled: CancellationException) {
                Log.d(TAG, "load.cancelled request=" + requestId)
                if (requestId == loadSequence) closeRendererNonCancellable()
                throw cancelled
            } catch (error: OutOfMemoryError) {
                Log.e(TAG, "load.failed request=" + requestId + " category=OutOfMemoryError", error)
                if (requestId == loadSequence) closeRendererNonCancellable()
                if (requestId == loadSequence) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isPasswordRequired = false,
                        errorMessage = app.getString(R.string.document_load_error)
                    )
                }
            } catch (error: Exception) {
                val passwordFailure = isPasswordFailure(error)
                Log.e(
                    TAG,
                    "load.failed request=" + requestId +
                        " category=" + error.javaClass.simpleName,
                    error
                )
                if (requestId == loadSequence) closeRendererNonCancellable()
                if (requestId == loadSequence) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isPasswordRequired = passwordFailure,
                        errorMessage = if (passwordFailure) {
                            app.getString(R.string.encrypted_document)
                        } else {
                            app.getString(R.string.document_load_error)
                        }
                    )
                }
            } finally {
                pendingUnlockedFile?.delete()
                if (requestId == loadSequence) {
                    loadWatchdogJob?.cancel()
                    loadWatchdogJob = null
                }
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

    /**
     * Loads page dimensions in small batches after the first page is already displayable.
     * Request IDs and renderer document identity prevent stale metadata from updating a new PDF.
     */
    private fun startRemainingPageMetadataLoad(requestId: Long, pageCount: Int) {
        pageMetadataJob?.cancel()
        if (pageCount <= 1) {
            pageMetadataJob = null
            return
        }

        pageMetadataJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var start = 1
            try {
                while (start < pageCount) {
                    currentCoroutineContext().ensureActive()
                    if (requestId != loadSequence) return@launch

                    val end = minOf(start + PAGE_METADATA_BATCH_SIZE, pageCount)
                    val batch = rendererEngine.getPagesMetadata(start, end)
                    currentCoroutineContext().ensureActive()
                    if (requestId != loadSequence) return@launch
                    if (batch.isEmpty()) {
                        Log.w(TAG, "metadata.batch_empty request=$requestId start=$start end=$end")
                        break
                    }

                    val current = _uiState.value
                    if (current.pageCount != pageCount || current.errorMessage != null) return@launch
                    val updated = current.pagesMetadata.toMutableList()
                    while (updated.size < end) updated.add(defaultPageMetadata(updated.size))
                    batch.forEach { metadata ->
                        if (metadata.pageIndex in updated.indices) {
                            updated[metadata.pageIndex] = metadata
                        }
                    }
                    _uiState.value = current.copy(pagesMetadata = updated)
                    start = end
                }
                Log.i(
                    TAG,
                    "metadata.background_complete request=$requestId pagesPrepared=$start/$pageCount " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "metadata.background_failed request=$requestId", error)
            } finally {
                if (requestId == loadSequence) pageMetadataJob = null
            }
        }
    }

    /**
     * Provider display-name lookup is best-effort and deliberately runs after first-page rendering.
     * A slow metadata provider must not prevent the PDF reader from becoming usable.
     */
    private fun resolveDisplayNameAsync(requestId: Long, uri: Uri, fallbackName: String) {
        if (uri.scheme != "content") return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nameStartedAt = SystemClock.elapsedRealtime()
                Log.d(TAG, "load.stage request=$requestId stage=display_name_query status=start")
                val resolvedName = UriUtils.getFileName(
                    getApplication<Application>(),
                    uri
                ).takeIf { it.isNotBlank() && it != fallbackName }
                Log.d(
                    TAG,
                    "load.stage request=$requestId stage=display_name_query status=" +
                        (if (resolvedName != null) "success" else "fallback") +
                        " elapsedMs=${SystemClock.elapsedRealtime() - nameStartedAt}"
                )
                if (resolvedName != null) {
                    withContext(Dispatchers.Main.immediate) {
                        if (
                            requestId == loadSequence &&
                            !_uiState.value.isLoading &&
                            _uiState.value.pageCount > 0
                        ) {
                            _uiState.value = _uiState.value.copy(fileName = resolvedName)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "load.stage request=$requestId stage=display_name_query status=fallback", error)
            }
        }
    }

    private fun defaultPageMetadata(pageIndex: Int): PageMetadata =
        PageMetadata(pageIndex = pageIndex, widthPt = 595f, heightPt = 842f)

    /** Called when the viewer leaves composition; cleanup runs off the main thread. */
    fun closeDocument() {
        loadSequence += 1
        val previousLoad = loadJob
        previousLoad?.cancel()
        pageMetadataJob?.cancel()
        pageMetadataJob = null
        loadJob = null
        loadWatchdogJob?.cancel()
        loadWatchdogJob = null
        timedOutRequestId = null
        val previousClose = closeJob
        closeJob = viewModelScope.launch {
            previousClose?.join()
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

    fun setZoom(scale: Float) {
        val clamped = scale.coerceIn(1.0f, 4.0f)
        _uiState.value = _uiState.value.copy(zoomScale = clamped)
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
        pageMetadataJob?.cancel()
        pageMetadataJob = null
        loadWatchdogJob?.cancel()
        loadWatchdogJob = null
        timedOutRequestId = null
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
    }
}
