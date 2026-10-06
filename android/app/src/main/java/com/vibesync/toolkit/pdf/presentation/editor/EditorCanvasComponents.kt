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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.layout.onSizeChanged
import com.vibesync.toolkit.pdf.data.model.Point2D
import com.vibesync.toolkit.pdf.data.model.ToolMode
import com.vibesync.toolkit.pdf.data.model.VectorStroke

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
            detectDragGestures(
                onDragStart = { offset ->
                    activePoints = listOf(Point2D(offset.x, offset.y))
                },
                onDrag = { change, _ ->
                    change.consumePositionChange()
                    val position = change.position
                    activePoints = activePoints + Point2D(position.x, position.y)
                },
                onDragEnd = {
                    if (activePoints.size >= 2) {
                        onStrokeAdded(
                            VectorStroke(
                                points = activePoints,
                                colorArgb = strokeColor.toArgb(),
                                strokeWidth = strokeWidth,
                                isHighlighter = toolMode == ToolMode.HIGHLIGHTER
                            )
                        )
                    }
                    activePoints = emptyList()
                },
                onDragCancel = {
                    activePoints = emptyList()
                }
            )
        }
        else -> modifier
    }

    Canvas(modifier = inputModifier.fillMaxSize()) {
        existingStrokes.forEach { stroke ->
            val points = stroke.points
            val width = if (stroke.isHighlighter) stroke.strokeWidth * 2.5f else stroke.strokeWidth
            val color = Color(stroke.colorArgb).copy(alpha = stroke.alpha)
            for (index in 1 until points.size) {
                drawLine(
                    color = color,
                    start = Offset(points[index - 1].x, points[index - 1].y),
                    end = Offset(points[index].x, points[index].y),
                    strokeWidth = width,
                    cap = StrokeCap.Round
                )
            }
        }

        val active = activePoints
        if (active.size >= 2) {
            val color = if (toolMode == ToolMode.HIGHLIGHTER) {
                strokeColor.copy(alpha = 0.35f)
            } else {
                strokeColor
            }
            val width = if (toolMode == ToolMode.HIGHLIGHTER) strokeWidth * 2.5f else strokeWidth
            for (index in 1 until active.size) {
                drawLine(
                    color = color,
                    start = Offset(active[index - 1].x, active[index - 1].y),
                    end = Offset(active[index].x, active[index].y),
                    strokeWidth = width,
                    cap = StrokeCap.Round
                )
            }
        }
    }
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
        title = { Text("Draw your signature") },
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
                Text("Use Signature")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { strokes = emptyList(); activeStroke = emptyList() }) {
                    Text("Clear")
                }
                TextButton(onClick = onDismissRequest) {
                    Text("Cancel")
                }
            }
        }
    )
}
