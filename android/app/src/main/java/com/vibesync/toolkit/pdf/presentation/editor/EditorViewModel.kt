package com.vibesync.toolkit.pdf.presentation.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.model.*
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.data.pdf.PdfRendererEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class EditorUiState(
    val currentPageIndex: Int = 0,
    val totalPages: Int = 1,
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
    private val pageAnnotationsMap = mutableMapOf<Int, PageAnnotations>()

    // Undo / Redo history stores complete page annotation snapshots so text,
    // signatures, and vector strokes all participate consistently.
    private val undoStack = mutableListOf<PageAnnotations>()
    private val redoStack = mutableListOf<PageAnnotations>()

    fun initEditor(uri: Uri) {
        activeDocumentUri = uri
        viewModelScope.launch {
            rendererEngine.openDocument(uri).onSuccess { count ->
                _uiState.value = _uiState.value.copy(totalPages = count)
                loadPageAnnotations(0)
            }
        }
    }

    suspend fun getPageBitmap(pageIndex: Int): Bitmap? {
        return rendererEngine.renderPage(pageIndex, scale = 1.5f)
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
        _uiState.value = _uiState.value.copy(currentTextAnnotations = updated)
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
        val sourceUri = activeDocumentUri ?: return
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
                        errorMessage = error.localizedMessage ?: "Failed to flatten PDF"
                    )
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        rendererEngine.closeDocument()
    }
}
