package com.omnidoc.toolkit.pdf.data.model

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.io.Serializable

enum class ToolMode {
    VIEW,
    PEN,
    HIGHLIGHTER,
    TEXT,
    SIGNATURE,
    ERASER
}

data class Point2D(val x: Float, val y: Float) : Serializable

data class VectorStroke(
    val id: String = java.util.UUID.randomUUID().toString(),
    val points: List<Point2D>,
    val colorArgb: Int,
    val strokeWidth: Float,
    val isHighlighter: Boolean = false,
    val alpha: Float = if (isHighlighter) 0.35f else 1.0f
) : Serializable

data class TextAnnotation(
    val id: String = java.util.UUID.randomUUID().toString(),
    val x: Float,
    val y: Float,
    val text: String,
    val fontSizeSp: Float = 14f,
    val colorArgb: Int = 0xFF000000.toInt()
) : Serializable

data class SignatureAnnotation(
    val id: String = java.util.UUID.randomUUID().toString(),
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    @Transient val bitmap: Bitmap? = null
)

data class PageAnnotations(
    val pageIndex: Int,
    val strokes: List<VectorStroke> = emptyList(),
    val textAnnotations: List<TextAnnotation> = emptyList(),
    val signatures: List<SignatureAnnotation> = emptyList()
) {
    val isEmpty: Boolean
        get() = strokes.isEmpty() && textAnnotations.isEmpty() && signatures.isEmpty()
}
