package com.vibesync.toolkit.pdf.presentation.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vibesync.toolkit.pdf.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.vibesync.toolkit.pdf.data.model.Point2D
import com.vibesync.toolkit.pdf.data.model.ToolMode
import com.vibesync.toolkit.pdf.data.model.VectorStroke

/**
 * Pastel VIBGYOR swatches are opaque in the palette; the drawing layer applies controlled
 * transparency so original PDF text remains crisp beneath the marker.
 */
data class HighlighterSwatch(val name: String, val color: Color)

object HighlighterPalette {
    val colors = listOf(
        HighlighterSwatch("Violet", Color(0xFFDCC6F4)),
        HighlighterSwatch("Indigo", Color(0xFFC8CCF8)),
        HighlighterSwatch("Blue", Color(0xFFBFE0FA)),
        HighlighterSwatch("Green", Color(0xFFC8EBCB)),
        HighlighterSwatch("Yellow", Color(0xFFFFF0A8)),
        HighlighterSwatch("Orange", Color(0xFFFFD7B0)),
        HighlighterSwatch("Red", Color(0xFFF8C4C8))
    )
    val defaultColor: Color
        get() = colors[2].color
}

private const val HIGHLIGHTER_ALPHA = 0.35f

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
    var activePoints by remember { mutableStateOf<List<Point2D>>(emptyList()) }

    val inputModifier = when (toolMode) {
        ToolMode.TEXT -> modifier.pointerInput(Unit) {
            detectTapGestures { offset -> onTap(offset) }
        }
        ToolMode.PEN, ToolMode.HIGHLIGHTER -> modifier.pointerInput(toolMode, strokeColor, strokeWidth) {
            awaitEachGesture {
                // Keep the actual DOWN point. Drag detectors commonly wait for touch slop and can
                // skip this segment, which contributes to a dotted-looking slow highlighter path.
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val points = mutableListOf(Point2D(down.position.x, down.position.y))
                activePoints = points.toList()
                var lastPosition = down.position

                try {
                    var isPressed = true
                    while (isPressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val position = change.position
                        if ((position - lastPosition).getDistance() >= 0.25f) {
                            points.add(Point2D(position.x, position.y))
                            activePoints = points.toList()
                            lastPosition = position
                        }
                        change.consume()
                        isPressed = change.pressed
                    }

                    if (points.size >= 2) {
                        onStrokeAdded(
                            VectorStroke(
                                points = points.toList(),
                                colorArgb = strokeColor.toArgb(),
                                strokeWidth = strokeWidth,
                                isHighlighter = toolMode == ToolMode.HIGHLIGHTER,
                                alpha = if (toolMode == ToolMode.HIGHLIGHTER) HIGHLIGHTER_ALPHA else 1f
                            )
                        )
                    }
                } finally {
                    activePoints = emptyList()
                }
            }
        }
        else -> modifier
    }

    Canvas(modifier = inputModifier.fillMaxSize()) {
        // Non-destructive sibling overlay above the PDF bitmap. Multiply blends pastel color
        // with page pixels while preserving dark glyphs; neither the source bitmap nor its text
        // layer is ever erased or mutated by the highlighter.
        existingStrokes.forEach { stroke ->
            if (stroke.points.size >= 2) {
                val highlighter = stroke.isHighlighter
                val color = if (highlighter) {
                    Color(stroke.colorArgb).copy(alpha = stroke.alpha.coerceIn(0.30f, 0.40f))
                } else Color(stroke.colorArgb)
                drawPath(
                    path = createSmoothedStrokePath(stroke.points),
                    color = color,
                    style = Stroke(
                        width = if (highlighter) stroke.strokeWidth * 2.5f else stroke.strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    ),
                    blendMode = if (highlighter) BlendMode.Multiply else BlendMode.SrcOver
                )
            }
        }

        if (activePoints.size >= 2) {
            val highlighter = toolMode == ToolMode.HIGHLIGHTER
            drawPath(
                path = createSmoothedStrokePath(activePoints),
                color = if (highlighter) strokeColor.copy(alpha = HIGHLIGHTER_ALPHA) else strokeColor,
                style = Stroke(
                    width = if (highlighter) strokeWidth * 2.5f else strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                ),
                blendMode = if (highlighter) BlendMode.Multiply else BlendMode.SrcOver
            )
        }
    }
}

/** Draw one smooth path to avoid the darker dot artifacts caused by overlapping line segments. */
internal fun createSmoothedStrokePath(points: List<Point2D>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size == 1) return path
    if (points.size == 2) {
        path.lineTo(points.last().x, points.last().y)
        return path
    }
    for (index in 1 until points.lastIndex) {
        val current = points[index]
        val next = points[index + 1]
        path.quadraticTo(
            current.x,
            current.y,
            (current.x + next.x) / 2f,
            (current.y + next.y) / 2f
        )
    }
    path.lineTo(points.last().x, points.last().y)
    return path
}

@Composable
fun SignaturePadDialog(
    onDismissRequest: () -> Unit,
    onSignatureCaptured: (Bitmap) -> Unit
) {
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.signature_draw_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color.White)
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { padSize = it }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        activeStroke = listOf(offset)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        activeStroke = activeStroke + change.position
                                    },
                                    onDragEnd = {
                                        if (activeStroke.size >= 2) {
                                            strokes = strokes + listOf(activeStroke)
                                        }
                                        activeStroke = emptyList()
                                    },
                                    onDragCancel = {
                                        activeStroke = emptyList()
                                    }
                                )
                            }
                    ) {
                        val paintWidth = 4.dp.toPx()
                        val allStrokes = strokes + listOf(activeStroke)
                        allStrokes.forEach { points ->
                            for (index in 1 until points.size) {
                                drawLine(
                                    color = Color.Black,
                                    start = points[index - 1],
                                    end = points[index],
                                    strokeWidth = paintWidth,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = strokes.isNotEmpty() && padSize.width > 0 && padSize.height > 0,
                onClick = {
                    val width = padSize.width
                    val height = padSize.height
                    val bitmap = Bitmap.createBitmap(
                        width,
                        height,
                        Bitmap.Config.ARGB_8888
                    )

                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.TRANSPARENT)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.BLACK
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 4f
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                    }

                    val allStrokes = strokes
                    allStrokes.forEach { points ->
                        if (points.size >= 2) {
                            val path = android.graphics.Path()
                            path.moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { point ->
                                path.lineTo(point.x, point.y)
                            }
                            canvas.drawPath(path, paint)
                        }
                    }

                    onSignatureCaptured(bitmap)
                }
            ) {
                Text(stringResource(R.string.signature_use_action))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { strokes = emptyList(); activeStroke = emptyList() }) {
                    Text(stringResource(R.string.signature_clear_action))
                }
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
