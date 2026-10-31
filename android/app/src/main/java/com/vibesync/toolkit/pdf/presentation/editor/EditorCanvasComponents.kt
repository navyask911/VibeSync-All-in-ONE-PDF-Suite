package com.vibesync.toolkit.pdf.presentation.editor

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncCrimsonPrimary
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
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
    modifier: Modifier = Modifier,
    bitmapSize: IntSize = IntSize.Zero,
    zoomScale: Float = 1f,
    panOffset: Offset = Offset.Zero
) {
    var activePoints by remember { mutableStateOf<List<Point2D>>(emptyList()) }
    var eraserEnabled by remember { mutableStateOf(toolMode == ToolMode.ERASER) }
    LaunchedEffect(toolMode) {
        eraserEnabled = toolMode == ToolMode.ERASER
    }
    val activeToolMode = when {
        eraserEnabled -> ToolMode.ERASER
        toolMode == ToolMode.ERASER -> ToolMode.PEN
        else -> toolMode
    }
    val eraserRadiusPx = with(LocalDensity.current) { 8.dp.toPx() }

    val inputModifier = when (activeToolMode) {
        // The pointer node is inside the transformed page layer. Compose already
        // maps taps into this canvas's unscaled local coordinate space.
        ToolMode.TEXT -> modifier.pointerInput(bitmapSize) {
            detectTapGestures { touchOffset -> onTap(touchOffset) }
        }
        ToolMode.PEN, ToolMode.HIGHLIGHTER -> modifier.pointerInput(
            activeToolMode, strokeColor, strokeWidth, bitmapSize
        ) {
            val containerSize = Size(size.width.toFloat(), size.height.toFloat())
            val pageRect = ZoomCoordinateMapping.calculatePageRect(containerSize, bitmapSize)
            val normalizedStrokeWidth = strokeWidth / pageRect.width.coerceAtLeast(1f)
            fun toNormalizedPoint(touch: Offset): Point2D {
                val point = ZoomCoordinateMapping.toNormalizedPagePoint(touch, containerSize, bitmapSize)
                Log.d(
                    "PdfEditorCoordinates",
                    "localCanvasSize=${containerSize.width}x${containerSize.height}, " +
                        "bitmapSize=${bitmapSize.width}x${bitmapSize.height}, " +
                        "zoomScale=${zoomScale}, panOffset=${panOffset}, " +
                        "touch=${touch}, normalized=${point}"
                )
                return Point2D(point.x, point.y)
            }
            detectDragGestures(
                onDragStart = { touchOffset ->
                    activePoints = listOf(toNormalizedPoint(touchOffset))
                },
                onDrag = { change, _ ->
                    change.consume()
                    activePoints = activePoints + toNormalizedPoint(change.position)
                },
                onDragEnd = {
                    if (activePoints.size >= 2) {
                        onStrokeAdded(
                            VectorStroke(
                                points = activePoints,
                                colorArgb = strokeColor.toArgb(),
                                strokeWidth = normalizedStrokeWidth,
                                isHighlighter = activeToolMode == ToolMode.HIGHLIGHTER,
                                alpha = if (activeToolMode == ToolMode.HIGHLIGHTER) 0.35f else 1.0f
                            )
                        )
                    }
                    activePoints = emptyList()
                },
                onDragCancel = { activePoints = emptyList() }
            )
        }
        ToolMode.ERASER -> modifier.pointerInput(
            activeToolMode, eraserRadiusPx, onStrokeAdded, bitmapSize
        ) {
            val containerSize = Size(size.width.toFloat(), size.height.toFloat())
            val pageRect = ZoomCoordinateMapping.calculatePageRect(containerSize, bitmapSize)
            val normalizedRadius = eraserRadiusPx / pageRect.width.coerceAtLeast(1f)
            fun toNormalizedPoint(touch: Offset): Point2D {
                val point = ZoomCoordinateMapping.toNormalizedPagePoint(touch, containerSize, bitmapSize)
                return Point2D(point.x, point.y)
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pathPoints = mutableListOf(toNormalizedPoint(down.position))
                var lastPosition = down.position
                down.consume()
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change != null) {
                        if (change.position != lastPosition) {
                            pathPoints.add(toNormalizedPoint(change.position))
                            lastPosition = change.position
                        }
                        if (change.pressed) change.consume()
                    }
                } while (event.changes.any { it.pressed })
                onStrokeAdded(
                    VectorStroke(
                        id = EditorViewModel.ERASER_GESTURE_ID,
                        points = pathPoints,
                        colorArgb = Color.Transparent.toArgb(),
                        strokeWidth = normalizedRadius
                    )
                )
            }
        }
        else -> modifier
    }

    // Pointer input and preview Canvas are descendants of the fitted page graphicsLayer.
    // Compose resolves zoom/pan exactly once before coordinates reach the page-local pointer node.
    Box(modifier = inputModifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pageRect = ZoomCoordinateMapping.calculatePageRect(size, bitmapSize)
            fun toCanvasPoint(point: Point2D): Offset = Offset(
                pageRect.left + point.x * pageRect.width,
                pageRect.top + point.y * pageRect.height
            )
            existingStrokes.forEach { stroke ->
                if (stroke.points.size >= 2) {
                    val path = Path().apply {
                        val first = toCanvasPoint(stroke.points.first())
                        moveTo(first.x, first.y)
                        stroke.points.drop(1).forEach { point ->
                            val mapped = toCanvasPoint(point)
                            lineTo(mapped.x, mapped.y)
                        }
                    }
                    val isHighlighter = stroke.isHighlighter
                    val color = Color(stroke.colorArgb).copy(alpha = if (isHighlighter) 0.35f else stroke.alpha)
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = stroke.strokeWidth * pageRect.width,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        ),
                        blendMode = if (isHighlighter) BlendMode.Multiply else BlendMode.SrcOver
                    )
                }
            }
            val active = activePoints
            if (active.size >= 2) {
                val isHighlighter = activeToolMode == ToolMode.HIGHLIGHTER
                val color = if (isHighlighter) strokeColor.copy(alpha = 0.35f) else strokeColor
                val path = Path().apply {
                    val first = toCanvasPoint(active.first())
                    moveTo(first.x, first.y)
                    active.drop(1).forEach { point ->
                        val mapped = toCanvasPoint(point)
                        lineTo(mapped.x, mapped.y)
                    }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    blendMode = if (isHighlighter) BlendMode.Multiply else BlendMode.SrcOver
                )
            }
        }

        TextButton(
            onClick = { eraserEnabled = !eraserEnabled },
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
        ) {
            Text(if (eraserEnabled) "Eraser on" else "Erase")
        }
    }
}

private data class SignaturePadStroke(
    val points: List<Offset>,
    val color: Color,
    val strokeWidthPx: Float
)

@Composable
fun SignaturePadDialog(
    onDismissRequest: () -> Unit,
    onSignatureCaptured: (Bitmap, Float) -> Unit
) {
    var strokes by remember { mutableStateOf<List<SignaturePadStroke>>(emptyList()) }
    var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var activeStrokeColor by remember { mutableStateOf(Color.Black) }
    var activeStrokeWidthPx by remember { mutableStateOf(0f) }
    var selectedColor by remember { mutableStateOf(Color.Black) }
    var selectedWidthDp by remember { mutableStateOf(8f) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val selectedWidthPx = with(density) { selectedWidthDp.dp.toPx() }
    val signatureColors = listOf(Color.Black, Color.Blue, Color.Red)
    val thicknesses = listOf("Thin" to 4f, "Medium" to 8f, "Thick" to 16f)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.signature_draw_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    signatureColors.forEachIndexed { index, color ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(color, CircleShape)
                                .border(
                                    width = if (selectedColor == color) 3.dp else 1.dp,
                                    color = if (selectedColor == color) VibeSyncCrimsonPrimary else Color.Gray,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = color }
                                .testTag("signature_color_$index")
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    thicknesses.forEachIndexed { index, (label, widthDp) ->
                        Column(
                            modifier = Modifier
                                .clickable { selectedWidthDp = widthDp }
                                .testTag("signature_thickness_${label.lowercase()}"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(32.dp)
                                    .height(listOf(3.dp, 7.dp, 12.dp)[index])
                                    .background(
                                        if (selectedWidthDp == widthDp) VibeSyncCrimsonPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        RoundedCornerShape(4.dp)
                                    )
                            )
                            Text(label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { padSize = it }
                            .pointerInput(selectedColor, selectedWidthPx) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        activeStroke = listOf(start)
                                        activeStrokeColor = selectedColor
                                        activeStrokeWidthPx = selectedWidthPx
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        activeStroke = activeStroke + change.position
                                    },
                                    onDragEnd = {
                                        if (activeStroke.size >= 2) {
                                            strokes = strokes + SignaturePadStroke(
                                                points = activeStroke,
                                                color = activeStrokeColor,
                                                strokeWidthPx = activeStrokeWidthPx.coerceAtLeast(1f)
                                            )
                                        }
                                        activeStroke = emptyList()
                                    },
                                    onDragCancel = { activeStroke = emptyList() }
                                )
                            }
                    ) {
                        fun drawSignatureStroke(points: List<Offset>, color: Color, width: Float) {
                            if (points.size < 2) return
                            val path = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(
                                path,
                                color,
                                style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                        strokes.forEach { drawSignatureStroke(it.points, it.color, it.strokeWidthPx) }
                        drawSignatureStroke(activeStroke, activeStrokeColor, activeStrokeWidthPx)
                    }
                }
                Text(
                    "Choose ink colour and line thickness before signing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                enabled = strokes.isNotEmpty() && padSize.width > 0 && padSize.height > 0,
                onClick = {
                    val sourceWidth = padSize.width
                    val sourceHeight = padSize.height
                    val targetWidth = SignatureStrokeMetrics.DEFAULT_TARGET_WIDTH_PX
                    val targetHeight = SignatureStrokeMetrics.DEFAULT_TARGET_HEIGHT_PX
                    val rasterScale = SignatureStrokeMetrics.RASTER_SCALE
                    val outputWidth = (targetWidth * rasterScale).toInt()
                    val outputHeight = (targetHeight * rasterScale).toInt()
                    val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.TRANSPARENT)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style = android.graphics.Paint.Style.STROKE
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    strokes.forEach { stroke ->
                        val relativeStrokeWidth = SignatureStrokeMetrics.relativeStrokeWidth(
                            stroke.strokeWidthPx,
                            sourceWidth.toFloat()
                        )
                        val scaledStrokeWidth = SignatureStrokeMetrics.scaledStrokeWidth(
                            relativeStrokeWidth,
                            targetWidth
                        )
                        paint.color = stroke.color.toArgb()
                        paint.strokeWidth = scaledStrokeWidth * rasterScale
                        val path = android.graphics.Path()
                        path.moveTo(
                            stroke.points.first().x / sourceWidth.toFloat() * outputWidth,
                            stroke.points.first().y / sourceHeight.toFloat() * outputHeight
                        )
                        stroke.points.drop(1).forEach { point ->
                            path.lineTo(
                                point.x / sourceWidth.toFloat() * outputWidth,
                                point.y / sourceHeight.toFloat() * outputHeight
                            )
                        }
                        canvas.drawPath(path, paint)
                    }
                    val selectedRelativeWidth = strokes.maxOfOrNull { stroke ->
                        SignatureStrokeMetrics.relativeStrokeWidth(stroke.strokeWidthPx, sourceWidth.toFloat())
                    } ?: SignatureStrokeMetrics.relativeStrokeWidth(selectedWidthPx, sourceWidth.toFloat())
                    onSignatureCaptured(bitmap, selectedRelativeWidth)
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

