package com.vibesync.toolkit.pdf.presentation.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class EditorUiState(
    val fileName: String = "document.pdf",
    val currentPageIndex: Int = 0,
    val totalPages: Int = 1,
    val documentLoadedRevision: Int = 0,
    val toolMode: ToolMode = ToolMode.PEN,
    val selectedColor: Color = Color(0xFF1A237E),
    val strokeWidth: Float = 8f,
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

    companion object {
        /** Marker consumed by the ViewModel for an eraser gesture sent by the canvas. */
        const val ERASER_GESTURE_ID = "__vibesync_whole_stroke_eraser_gesture__"
    }

    private val rendererEngine = PdfRendererEngine(application)
    private val toolkitEngine = PdfBoxToolkitEngine(application)
    private val securityVault by lazy { SecurityVault(getApplication<Application>()) }
    private val temporarySignatureBitmaps = mutableSetOf<Bitmap>()
    private var penStrokeWidth = 8f
    private var highlighterStrokeWidth = 24f

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
            val app = getApplication<Application>()
            val fileName = withContext(Dispatchers.IO) {
                runCatching { UriUtils.getFileName(app, uri) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: app.getString(R.string.default_pdf_filename)

            rendererEngine.openDocument(uri).onSuccess { count ->
                _uiState.value = _uiState.value.copy(
                    fileName = fileName,
                    totalPages = count,
                    documentLoadedRevision = _uiState.value.documentLoadedRevision + 1
                )
                loadPageAnnotations(0)
            }
        }
    }

    suspend fun getPageBitmap(pageIndex: Int): Bitmap? {
        return rendererEngine.renderPage(pageIndex, scale = 1.5f)
    }

    fun selectTool(mode: ToolMode) {
        val current = _uiState.value
        val width = when (mode) {
            ToolMode.PEN -> penStrokeWidth
            ToolMode.HIGHLIGHTER -> highlighterStrokeWidth
            else -> current.strokeWidth
        }
        _uiState.value = current.copy(toolMode = mode, strokeWidth = width)
    }

    fun selectColor(color: Color) {
        _uiState.value = _uiState.value.copy(selectedColor = color)
    }

    fun setStrokeWidth(width: Float) {
        if (!width.isFinite() || width <= 0f) return
        val safeWidth = width.coerceIn(1f, 64f)
        when (_uiState.value.toolMode) {
            ToolMode.PEN -> penStrokeWidth = safeWidth
            ToolMode.HIGHLIGHTER -> highlighterStrokeWidth = safeWidth
            else -> Unit
        }
        _uiState.value = _uiState.value.copy(strokeWidth = safeWidth)
    }

    fun updateFileName(fileName: String) {
        if (fileName.isBlank()) return
        _uiState.value = _uiState.value.copy(fileName = fileName)
    }

    fun addStroke(stroke: VectorStroke) {
        if (stroke.id == ERASER_GESTURE_ID) {
            eraseStrokesAlongPath(stroke.points, stroke.strokeWidth)
            return
        }
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
     * Deletes the nearest complete stroke when a tap falls within the given pixel radius.
     */
    fun eraseStrokeAt(x: Float, y: Float, tolerancePx: Float): Boolean {
        if (tolerancePx < 0f) return false
        val point = Point2D(x, y)
        val closest = _uiState.value.currentStrokes
            .map { stroke -> stroke to pointToPathDistance(point, stroke.points) }
            .minByOrNull { it.second }
            ?: return false
        if (closest.second > tolerancePx) return false
        return removeStrokesById(listOf(closest.first.id)) == 1
    }

    /**
     * Deletes each complete stroke crossed by a drag. Every deletion receives its own
     * snapshot so undo restores the last deleted stroke at its original list/z-order index.
     */
    fun eraseStrokesAlongPath(pathPoints: List<Point2D>, tolerancePx: Float): Int {
        if (pathPoints.isEmpty() || tolerancePx < 0f) return 0
        if (pathPoints.size == 1) {
            return if (eraseStrokeAt(pathPoints[0].x, pathPoints[0].y, tolerancePx)) 1 else 0
        }

        val hitIds = _uiState.value.currentStrokes
            .filter { stroke -> pathsDistance(stroke.points, pathPoints) <= tolerancePx }
            .map { it.id }
        return removeStrokesById(hitIds)
    }

    private fun removeStrokesById(strokeIds: List<String>): Int {
        var strokes = _uiState.value.currentStrokes
        var removedCount = 0
        strokeIds.distinct().forEach { strokeId ->
            val index = strokes.indexOfFirst { it.id == strokeId }
            if (index >= 0) {
                // Keep a snapshot immediately before each deletion to make each whole stroke
                // independently undoable and preserve its original position in the list.
                undoStack.add(currentPageAnnotations(strokes))
                redoStack.clear()
                strokes = strokes.toMutableList().apply { removeAt(index) }
                removedCount += 1
            }
        }
        if (removedCount == 0) return 0

        _uiState.value = _uiState.value.copy(
            currentStrokes = strokes,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        persistCurrentPageAnnotations()
        return removedCount
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

    fun addSignature(
        bitmap: Bitmap,
        x: Float,
        y: Float,
        width: Float = 160f,
        height: Float = 80f,
        rotationDegrees: Float = 0f,
        saveToVault: Boolean = true
    ) {
        // Keep captured signatures discoverable by their stable encrypted identifiers.
        if (saveToVault) {
            securityVault.saveDefaultSignature(bitmap)
            securityVault.saveSignature(
                "signature_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}",
                bitmap
            )
        }
        val normalizedRotation = ((rotationDegrees % 360f) + 360f) % 360f
        val placedBitmap = if (normalizedRotation == 0f) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(normalizedRotation) }, true
            ).also { if (!bitmap.isRecycled) bitmap.recycle() }
        }
        temporarySignatureBitmaps.add(placedBitmap)

        undoStack.add(currentPageAnnotations())
        redoStack.clear()
        val sig = SignatureAnnotation(
            x = x,
            y = y,
            width = width.coerceAtLeast(1f),
            height = height.coerceAtLeast(1f),
            bitmap = placedBitmap
        )
        val updated = _uiState.value.currentSignatures + sig
        _uiState.value = _uiState.value.copy(
            currentSignatures = updated,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        persistCurrentPageAnnotations()
    }

    fun moveSignature(signatureId: String, deltaX: Float, deltaY: Float) {
        if (!deltaX.isFinite() || !deltaY.isFinite() || (deltaX == 0f && deltaY == 0f)) return
        val current = _uiState.value
        val targetIndex = current.currentSignatures.indexOfFirst { it.id == signatureId }
        if (targetIndex < 0) return

        undoStack.add(currentPageAnnotations())
        redoStack.clear()
        val updated = current.currentSignatures.toMutableList()
        val signature = updated[targetIndex]
        updated[targetIndex] = signature.copy(
            x = (signature.x + deltaX).coerceAtLeast(0f),
            y = (signature.y + deltaY).coerceAtLeast(0f)
        )
        _uiState.value = current.copy(
            currentSignatures = updated,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
        persistCurrentPageAnnotations()
    }

    fun listSavedSignatures(): List<SecurityVault.SavedSignature> = securityVault.listSignatures()

    fun renameSavedSignature(oldId: String, newId: String): Boolean =
        securityVault.renameSignature(oldId, newId)

    fun deleteSavedSignature(id: String): Boolean = securityVault.deleteSignature(id)

    /** Releases decoded/placed signature bitmaps once PDF flattening has finished. */
    fun releaseSignatureBitmapsAfterFlatten() {
        temporarySignatureBitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        temporarySignatureBitmaps.clear()
        pageAnnotationsMap.keys.toList().forEach { pageIndex ->
            pageAnnotationsMap[pageIndex]?.let { annotations ->
                pageAnnotationsMap[pageIndex] = annotations.copy(signatures = emptyList())
            }
        }
        _uiState.value = _uiState.value.copy(currentSignatures = emptyList())
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

    private fun currentPageAnnotations(
        strokes: List<VectorStroke> = _uiState.value.currentStrokes
    ): PageAnnotations {
        val current = _uiState.value
        return PageAnnotations(
            pageIndex = current.currentPageIndex,
            strokes = strokes,
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
                    releaseSignatureBitmapsAfterFlatten()
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
        super.onCleared()
        // Rendering and native close share the renderer lock. Never block the main thread
        // waiting for an in-flight PDF page render during lifecycle teardown.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                rendererEngine.closeDocumentSafely()
            } catch (error: Exception) {
                Log.w(
                    "EditorViewModel",
                    "renderer.cleanup_failed category=" + error.javaClass.simpleName
                )
            }
        }
    }
}


private fun pointToPathDistance(point: Point2D, path: List<Point2D>): Float {
    if (path.isEmpty()) return Float.POSITIVE_INFINITY
    if (path.size == 1) return pointDistance(point, path[0])

    var minimum = Float.POSITIVE_INFINITY
    for (index in 1 until path.size) {
        minimum = minOf(minimum, pointToSegmentDistance(point, path[index - 1], path[index]))
    }
    return minimum
}

private fun pathsDistance(first: List<Point2D>, second: List<Point2D>): Float {
    if (first.isEmpty() || second.isEmpty()) return Float.POSITIVE_INFINITY
    if (first.size == 1) return pointToPathDistance(first[0], second)
    if (second.size == 1) return pointToPathDistance(second[0], first)

    var minimum = Float.POSITIVE_INFINITY
    for (firstIndex in 1 until first.size) {
        for (secondIndex in 1 until second.size) {
            minimum = minOf(
                minimum,
                segmentToSegmentDistance(
                    first[firstIndex - 1], first[firstIndex],
                    second[secondIndex - 1], second[secondIndex]
                )
            )
            if (minimum == 0f) return 0f
        }
    }
    return minimum
}

private fun pointDistance(first: Point2D, second: Point2D): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun pointToSegmentDistance(point: Point2D, start: Point2D, end: Point2D): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0f) return pointDistance(point, start)

    val projection = (
        ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared
    ).coerceIn(0f, 1f)
    val nearest = Point2D(start.x + projection * dx, start.y + projection * dy)
    return pointDistance(point, nearest)
}

private fun segmentToSegmentDistance(
    firstStart: Point2D,
    firstEnd: Point2D,
    secondStart: Point2D,
    secondEnd: Point2D
): Float {
    if (segmentsIntersect(firstStart, firstEnd, secondStart, secondEnd)) return 0f
    return minOf(
        pointToSegmentDistance(firstStart, secondStart, secondEnd),
        pointToSegmentDistance(firstEnd, secondStart, secondEnd),
        pointToSegmentDistance(secondStart, firstStart, firstEnd),
        pointToSegmentDistance(secondEnd, firstStart, firstEnd)
    )
}

private fun segmentsIntersect(a: Point2D, b: Point2D, c: Point2D, d: Point2D): Boolean {
    fun orientation(p: Point2D, q: Point2D, r: Point2D): Float =
        (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)

    fun liesOnSegment(point: Point2D, start: Point2D, end: Point2D): Boolean =
        point.x >= minOf(start.x, end.x) - 0.0001f &&
            point.x <= maxOf(start.x, end.x) + 0.0001f &&
            point.y >= minOf(start.y, end.y) - 0.0001f &&
            point.y <= maxOf(start.y, end.y) + 0.0001f

    val first = orientation(a, b, c)
    val second = orientation(a, b, d)
    val third = orientation(c, d, a)
    val fourth = orientation(c, d, b)

    if (((first > 0f && second < 0f) || (first < 0f && second > 0f)) &&
        ((third > 0f && fourth < 0f) || (third < 0f && fourth > 0f))
    ) return true

    return (kotlin.math.abs(first) <= 0.0001f && liesOnSegment(c, a, b)) ||
        (kotlin.math.abs(second) <= 0.0001f && liesOnSegment(d, a, b)) ||
        (kotlin.math.abs(third) <= 0.0001f && liesOnSegment(a, c, d)) ||
        (kotlin.math.abs(fourth) <= 0.0001f && liesOnSegment(b, c, d))
}
