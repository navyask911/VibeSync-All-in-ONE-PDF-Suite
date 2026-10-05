package com.omnidoc.toolkit.pdf.presentation.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.omnidoc.toolkit.pdf.data.model.Point2D
import com.omnidoc.toolkit.pdf.data.model.ToolMode
import com.omnidoc.toolkit.pdf.data.model.VectorStroke

@Composable
fun VectorCanvasOverlay(
    toolMode: ToolMode,
    strokeColor: Color,
    strokeWidth: Float,
    existingStrokes: List<VectorStroke>,
    onStrokeAdded: (VectorStroke) -> Unit,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPoints = remember { mutableStateListOf<Point2D>() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(toolMode, strokeColor, strokeWidth) {
                if (toolMode == ToolMode.PEN || toolMode == ToolMode.HIGHLIGHTER) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentPoints.clear()
                            currentPoints.add(Point2D(offset.x, offset.y))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentPoints.add(Point2D(change.position.x, change.position.y))
                        },
                        onDragEnd = {
                            if (currentPoints.size >= 2) {
                                val isHighlighter = toolMode == ToolMode.HIGHLIGHTER
                                val stroke = VectorStroke(
                                    points = currentPoints.toList(),
                                    colorArgb = strokeColor.toArgb(),
                                    strokeWidth = if (isHighlighter) strokeWidth * 3.5f else strokeWidth,
                                    isHighlighter = isHighlighter,
                                    alpha = if (isHighlighter) 0.35f else 1.0f
                                )
                                onStrokeAdded(stroke)
                            }
                            currentPoints.clear()
                        },
                        onDragCancel = {
                            currentPoints.clear()
                        }
                    )
                } else if (toolMode == ToolMode.TEXT || toolMode == ToolMode.SIGNATURE) {
                    detectTapGestures { offset ->
                        onTap(offset)
                    }
                }
            }
    ) {
        // 1. Draw all committed existing strokes
        for (stroke in existingStrokes) {
            drawVectorStroke(stroke)
        }

        // 2. Draw currently active in-progress stroke
        if (currentPoints.size >= 2) {
            val isHighlighter = toolMode == ToolMode.HIGHLIGHTER
            val activeStroke = VectorStroke(
                points = currentPoints.toList(),
                colorArgb = strokeColor.toArgb(),
                strokeWidth = if (isHighlighter) strokeWidth * 3.5f else strokeWidth,
                isHighlighter = isHighlighter,
                alpha = if (isHighlighter) 0.35f else 1.0f
            )
            drawVectorStroke(activeStroke)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVectorStroke(stroke: VectorStroke) {
    if (stroke.points.size < 2) return

    val path = Path().apply {
        val first = stroke.points[0]
        moveTo(first.x, first.y)
        for (i in 1 until stroke.points.size) {
            val prev = stroke.points[i - 1]
            val curr = stroke.points[i]
            // Quadratic bezier interpolation for smooth curves
            quadraticBezierTo(
                prev.x,
                prev.y,
                (prev.x + curr.x) / 2f,
                (prev.y + curr.y) / 2f
            )
        }
    }

    drawPath(
        path = path,
        color = Color(stroke.colorArgb).copy(alpha = stroke.alpha),
        style = Stroke(
            width = stroke.strokeWidth,
            cap = if (stroke.isHighlighter) StrokeCap.Square else StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}
