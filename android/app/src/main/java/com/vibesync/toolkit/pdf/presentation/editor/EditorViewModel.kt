package com.vibesync.toolkit.pdf.presentation.editor

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.model.*
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
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

data class EditorUiState(
    val currentPageIndex: Int = 0,
    val totalPages: Int = 1,
    val isDocumentLoading: Boolean = true,
    val isDocumentLoaded: Boolean = false,
    val toolMode: ToolMode = ToolMode.PEN,
    val selectedColor: Color = Color(0xFF1A237E),
    val strokeWidth: Float = 6f,
    val currentStrokes: List<VectorStroke> = emptyList(),
    val currentTextAnnotations: List<TextAnnotation> = emptyList(),
    val currentSignatures: List<SignatureAnnotation> = emptyList(),
    val isSaving: Boolean = false,
    val exportSuccessFile: File? = null,
    val errorMessage: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val rendererEngine = PdfRendererEngine(application)
    private val toolkitEngine = PdfBoxToolkitEngine(application)
    private val securityVault by lazy { SecurityVault(getApplication<Application>()) }

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var activeDocumentUri: Uri? = null
    private var requestedDocumentUri: Uri? = null
    @Volatile private var stagedSourceFile: File? = null
    private var loadDocumentJob: Job? = null
    private val pageAnnotationsMap = mutableMapOf<Int, PageAnnotations>()

    // Undo / Redo history stores complete page annotation snapshots so text,
    // signatures, and vector strokes all participate consistently.
    private val undoStack = mutableListOf<PageAnnotations>()
    private val redoStack = mutableListOf<PageAnnotations>()

    fun initEditor(uri: Uri) {
        if (
            requestedDocumentUri == uri &&
            (_uiState.value.isDocumentLoaded ||
                (_uiState.value.isDocumentLoading && loadDocumentJob?.isActive == true))
        ) {
            return
        }

        requestedDocumentUri = uri
        loadDocumentJob?.cancel()
        loadDocumentJob = viewModelScope.launch {
            val operationContext = currentCoroutineContext()
            var newSourceFile: File? = null

            // The source scratch file remains available for the editor's export operation.
            // PdfRendererEngine independently stages its own renderer-owned copy.
            val previousSourceFile = stagedSourceFile
            stagedSourceFile = null
            activeDocumentUri = null
            pageAnnotationsMap.clear()
            undoStack.clear()
            redoStack.clear()
            _uiState.value = _uiState.value.copy(
                currentPageIndex = 0,
                totalPages = 0,
                isDocumentLoading = true,
                isDocumentLoaded = false,
                currentStrokes = emptyList(),
                currentTextAnnotations = emptyList(),
                currentSignatures = emptyList(),
                canUndo = false,
                canRedo = false,
                errorMessage = null
            )

            try {
                withContext(Dispatchers.IO) {
                    previousSourceFile?.let { file ->
                        if (file.exists() && !file.delete()) {
                            Log.w(TAG, "editor.source_cleanup_failed")
                        }
                    }
                }

                Log.i(TAG, "editor.open.start scheme=${uri.scheme ?: "unknown"}")
                // URI grants can be provider-specific or short-lived. Stage content:// and file://
                // sources into private, seekable app cache before initializing the edit workflow.
                newSourceFile = withContext(Dispatchers.IO) {
                    UriUtils.copyUriToCacheFile(
                        context = getApplication<Application>(),
                        uri = uri,
                        prefix = "edit_source",
                        cancellationCheck = { operationContext.ensureActive() }
                    )
                }
                val sourceFile = requireNotNull(newSourceFile)
                Log.i(TAG, "editor.source_cache.success bytes=${sourceFile.length()}")

                val openResult = rendererEngine.openDocument(Uri.fromFile(sourceFile))
                val openError = openResult.exceptionOrNull()
                if (openError != null) {
                    Log.e(
                        TAG,
                        "editor.open.failed stage=renderer_open category=${openError.javaClass.simpleName}",
                        openError
                    )
                    _uiState.value = _uiState.value.copy(
                        isDocumentLoading = false,
                        isDocumentLoaded = false,
                        errorMessage = getApplication<Application>().getString(R.string.document_load_error)
                    )
                    return@launch
                }

                val count = openResult.getOrThrow()
                stagedSourceFile = sourceFile
                activeDocumentUri = Uri.fromFile(sourceFile)
                newSourceFile = null
                _uiState.value = _uiState.value.copy(
                    currentPageIndex = 0,
                    totalPages = count,
                    isDocumentLoading = false,
                    isDocumentLoaded = true,
                    errorMessage = null
                )
                loadPageAnnotations(0)
                Log.i(TAG, "editor.open.success pages=$count")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "editor.open.failed stage=source_cache category=${error.javaClass.simpleName}",
                    error
                )
                _uiState.value = _uiState.value.copy(
                    isDocumentLoading = false,
                    isDocumentLoaded = false,
                    errorMessage = getApplication<Application>().getString(R.string.document_load_error)
                )
            } finally {
                newSourceFile?.let { file ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (file.exists() && !file.delete()) {
                            Log.w(TAG, "editor.source_cleanup_failed")
                        }
                    }
                }
            }
        }
    }

    suspend fun getPageBitmap(pageIndex: Int): Bitmap? {
        if (!_uiState.value.isDocumentLoaded) return null
        return try {
            val bitmap = rendererEngine.renderPage(pageIndex, scale = 1.5f)
            if (bitmap == null) {
                Log.e(TAG, "editor.page_render_failed page=$pageIndex")
                _uiState.value = _uiState.value.copy(
                    errorMessage = getApplication<Application>().getString(R.string.page_render_failed)
                )
            } else if (_uiState.value.errorMessage ==
                getApplication<Application>().getString(R.string.page_render_failed)
            ) {
                _uiState.value = _uiState.value.copy(errorMessage = null)
            }
            bitmap
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(
                TAG,
                "editor.page_render_failed page=$pageIndex category=${error.javaClass.simpleName}",
                error
            )
            _uiState.value = _uiState.value.copy(
                errorMessage = getApplication<Application>().getString(R.string.page_render_failed)
            )
            null
        }
    }

    fun selectTool(mode: ToolMode) {
        _uiState.value = _uiState.value.copy(toolMode = mode)
    }

    fun selectColor(color: Color) {
        _uiState.value = _uiState.value.copy(selectedColor = color)
    }

    fun setStrokeWidth(width: Float) {
        _uiState.value = _uiState.value.copy(strokeWidth = width)
    }

    fun addStroke(stroke: VectorStroke) {
        undoStack.add(currentPageAnnotations())
        redoStack.clear()
        val updated = _uiState.value.currentStrokes + stroke
        _uiState.value = _uiState.value.copy(
            currentStrokes = updated,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        persistCurrentPageAnnotations()
    }

    /**
     * Erases only user-created overlay strokes near the finger. The PDF bitmap/source is read-only,
     * and original text, images, form content, text annotations, and signatures are not touched.
     * Erasing is recorded in undo history like any other annotation edit.
     */
    fun eraseStrokeAt(x: Float, y: Float, radius: Float = 24f) {
        val current = _uiState.value
        if (!x.isFinite() || !y.isFinite() || !radius.isFinite()) return
        val safeRadius = radius.coerceAtLeast(0f)
        val strokesToRemove = current.currentStrokes.filter { stroke ->
            val points = stroke.points
            if (points.isEmpty()) {
                false
            } else {
                val hitRadius = safeRadius + stroke.strokeWidth.coerceAtLeast(0f) / 2f
                val hitRadiusSquared = hitRadius * hitRadius
                if (points.size == 1) {
                    distanceSquaredToPoint(x, y, points.first().x, points.first().y) <= hitRadiusSquared
                } else {
                    points.zipWithNext().any { (start, end) ->
                        distanceSquaredToSegmentSquared(x, y, start.x, start.y, end.x, end.y) <= hitRadiusSquared
                    }
                }
            }
        }
        if (strokesToRemove.isEmpty()) return

        undoStack.add(currentPageAnnotations())
        redoStack.clear()
        val updatedStrokes = current.currentStrokes.filterNot { stroke -> stroke in strokesToRemove }
        _uiState.value = current.copy(
            currentStrokes = updatedStrokes,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        persistCurrentPageAnnotations()
    }

    private fun distanceSquaredToPoint(x: Float, y: Float, pointX: Float, pointY: Float): Float {
        val dx = x - pointX
        val dy = y - pointY
        return dx * dx + dy * dy
    }

    private fun distanceSquaredToSegmentSquared(
        x: Float,
        y: Float,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Float {
        val dx = endX - startX
        val dy = endY - startY
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared <= 0f) {
            0f
        } else {
            (((x - startX) * dx + (y - startY) * dy) / lengthSquared).coerceIn(0f, 1f)
        }
        val nearestX = startX + t * dx
        val nearestY = startY + t * dy
        return distanceSquaredToPoint(x, y, nearestX, nearestY)
    }

    fun addTextAnnotation(text: String, x: Float, y: Float) {
        val annotation = TextAnnotation(
            x = x,
            y = y,
            text = text,
            fontSizeSp = 16f,
            colorArgb = _uiState.value.selectedColor.toArgb()
        )
        undoStack.add(currentPageAnnotations())
        redoStack.clear()
        val updated = _uiState.value.currentTextAnnotations + annotation
        _uiState.value = _uiState.value.copy(
            currentTextAnnotations = updated,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        persistCurrentPageAnnotations()
    }

    fun addSignature(bitmap: Bitmap, x: Float, y: Float) {
        // Automatically save to Encrypted Vault
        securityVault.saveDefaultSignature(bitmap)

        undoStack.add(currentPageAnnotations())
        redoStack.clear()

        val sig = SignatureAnnotation(
            x = x,
            y = y,
            width = 160f,
            height = 80f,
            bitmap = bitmap
        )
        val updated = _uiState.value.currentSignatures + sig
        _uiState.value = _uiState.value.copy(currentSignatures = updated)
        persistCurrentPageAnnotations()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(currentPageAnnotations())
            val previous = undoStack.removeAt(undoStack.lastIndex)
            applyAnnotations(previous)
            updateHistoryFlags()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(currentPageAnnotations())
            val restored = redoStack.removeAt(redoStack.lastIndex)
            applyAnnotations(restored)
            updateHistoryFlags()
        }
    }

    fun setPage(index: Int) {
        persistCurrentPageAnnotations()
        _uiState.value = _uiState.value.copy(currentPageIndex = index)
        loadPageAnnotations(index)
    }

    private fun loadPageAnnotations(pageIndex: Int) {
        val annotations = pageAnnotationsMap[pageIndex] ?: PageAnnotations(pageIndex)
        undoStack.clear()
        redoStack.clear()

        applyAnnotations(annotations)
        updateHistoryFlags()
    }

    private fun persistCurrentPageAnnotations() {
        val current = _uiState.value
        pageAnnotationsMap[current.currentPageIndex] = PageAnnotations(
            pageIndex = current.currentPageIndex,
            strokes = current.currentStrokes,
            textAnnotations = current.currentTextAnnotations,
            signatures = current.currentSignatures
        )
    }

    private fun currentPageAnnotations(): PageAnnotations {
        val current = _uiState.value
        return PageAnnotations(
            pageIndex = current.currentPageIndex,
            strokes = current.currentStrokes,
            textAnnotations = current.currentTextAnnotations,
            signatures = current.currentSignatures
        )
    }

    private fun applyAnnotations(annotations: PageAnnotations) {
        _uiState.value = _uiState.value.copy(
            currentStrokes = annotations.strokes,
            currentTextAnnotations = annotations.textAnnotations,
            currentSignatures = annotations.signatures
        )
        persistCurrentPageAnnotations()
    }

    private fun updateHistoryFlags() {
        _uiState.value = _uiState.value.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }


    /**
     * Bakes all vector strokes, highlighters, and signatures onto the PDF stream
     * using PdfBox at 72 DPI.
     */
    fun saveFlattenedDocument(screenWidthPx: Float, onComplete: (File) -> Unit) {
        persistCurrentPageAnnotations()
        val sourceUri = activeDocumentUri
        if (!_uiState.value.isDocumentLoaded || sourceUri == null) {
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                errorMessage = getApplication<Application>().getString(R.string.document_load_error)
            )
            return
        }
        _uiState.value = _uiState.value.copy(isSaving = true)

        viewModelScope.launch {
            val outputFile = UriUtils.createExportCacheFile(getApplication(), "signed_annotated")
            val result = toolkitEngine.flattenAnnotations(
                sourceUri = sourceUri,
                pageAnnotationsMap = pageAnnotationsMap,
                screenWidthPx = screenWidthPx,
                outputFile = outputFile
            )

            result.fold(
                onSuccess = { file ->
                    _uiState.value = _uiState.value.copy(isSaving = false, exportSuccessFile = file)
                    onComplete(file)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        errorMessage = error.localizedMessage ?: getApplication<Application>().getString(R.string.editor_failed_flatten)
                    )
                }
            )
        }
    }

    override fun onCleared() {
        loadDocumentJob?.cancel()
        activeDocumentUri = null
        val sourceFileToDelete = stagedSourceFile
        stagedSourceFile = null
        super.onCleared()

        // Renderer/native cleanup and scratch-file removal must not block the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                rendererEngine.closeDocumentSafely()
            } catch (error: Exception) {
                Log.w(TAG, "renderer.cleanup_failed category=${error.javaClass.simpleName}", error)
            } finally {
                sourceFileToDelete?.let { file ->
                    if (file.exists() && !file.delete()) {
                        Log.w(TAG, "editor.source_cleanup_failed")
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "EditorViewModel"
    }
}
