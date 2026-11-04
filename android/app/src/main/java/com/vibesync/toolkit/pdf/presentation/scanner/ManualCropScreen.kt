package com.vibesync.toolkit.pdf.presentation.scanner

import android.graphics.Bitmap
import com.vibesync.toolkit.pdf.data.pdf.PdfBitmapUtils
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.pdf.PerspectiveTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.sqrt

@Composable
fun ManualCropScreen(
    onNavigateBack: () -> Unit,
    onCommit: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    initialBitmap: Bitmap? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val previewMaxDimension = remember(context) {
        maxOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
            .coerceIn(1024, 2048)
    }
    var sourceBitmap by remember(initialBitmap) { mutableStateOf(initialBitmap) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var loading by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    val corners = remember(sourceBitmap) {
        mutableStateListOf<Offset>().apply {
            sourceBitmap?.let { bitmap ->
                addAll(
                    listOf(
                        Offset(0f, 0f),
                        Offset(bitmap.width.toFloat(), 0f),
                        Offset(bitmap.width.toFloat(), bitmap.height.toFloat()),
                        Offset(0f, bitmap.height.toFloat())
                    )
                )
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedUri = uri
    }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        loading = true
        loadFailed = false
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                PdfBitmapUtils.decodeStreamDownsampled(
                    openInputStream = { context.contentResolver.openInputStream(uri) },
                    maxDimension = previewMaxDimension
                )
            }.getOrNull()
        }
        loading = false
        if (decoded == null) {
            loadFailed = true
        } else {
            sourceBitmap = decoded
        }
    }

    DisposableEffect(sourceBitmap, initialBitmap) {
        val ownedBitmap = sourceBitmap
        onDispose {
            if (ownedBitmap != null && ownedBitmap !== initialBitmap && !ownedBitmap.isRecycled) {
                ownedBitmap.recycle()
            }
        }
    }

    Scaffold(modifier = modifier.fillMaxSize(), topBar = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.manual_crop_cancel))
            }
            Text(stringResource(R.string.manual_crop_title), style = MaterialTheme.typography.titleLarge)
        }
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.manual_crop_instructions),
                style = MaterialTheme.typography.bodySmall
            )
            val bitmap = sourceBitmap
            if (loading) {
                CircularProgressIndicator()
            } else if (bitmap == null) {
                Button(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth().testTag("manual_crop_choose_image")
                ) {
                    Icon(Icons.Default.Crop, contentDescription = null)
                    Spacer(Modifier.height(1.dp))
                    Text(stringResource(R.string.manual_crop_choose_image))
                }
                if (loadFailed) Text(stringResource(R.string.manual_crop_error), color = MaterialTheme.colorScheme.error)
            } else {
                val handleColor = MaterialTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("manual_crop_handles")
                        .semantics { contentDescription = context.getString(R.string.manual_crop_instructions) }
                        .pointerInput(bitmap) {
                            // Handle indices 0..3 resize a corner; 4 translates the full
                            // quadrilateral without changing its shape.
                            var selectedHandle = -1
                            fun scaleAndOffset(): Triple<Float, Float, Float> {
                                val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                                return Triple(scale, (size.width - bitmap.width * scale) / 2f, (size.height - bitmap.height * scale) / 2f)
                            }
                            detectDragGestures(
                                onDragStart = { start ->
                                    val (scale, left, top) = scaleAndOffset()
                                    val displayCorners = corners.map { point ->
                                        Offset(left + point.x * scale, top + point.y * scale)
                                    }
                                    val nearestCorner = displayCorners.indices.minByOrNull { index ->
                                        val dx = displayCorners[index].x - start.x
                                        val dy = displayCorners[index].y - start.y
                                        sqrt(dx * dx + dy * dy)
                                    }
                                    selectedHandle = nearestCorner?.takeIf { index ->
                                        val dx = displayCorners[index].x - start.x
                                        val dy = displayCorners[index].y - start.y
                                        sqrt(dx * dx + dy * dy) <= 56f
                                    } ?: if (isPointInsideCrop(start, displayCorners)) 4 else -1
                                },
                                onDrag = { change: PointerInputChange, dragAmount: Offset ->
                                    if (selectedHandle >= 0) {
                                        change.consume()
                                        val (scale, _, _) = scaleAndOffset()
                                        val deltaX = dragAmount.x / scale
                                        val deltaY = dragAmount.y / scale
                                        if (selectedHandle == 4) {
                                            // Clamp the translation as a unit so no crop corner
                                            // can leave the source bitmap.
                                            val minDeltaX = -corners.minOf { it.x }
                                            val maxDeltaX = bitmap.width.toFloat() - corners.maxOf { it.x }
                                            val minDeltaY = -corners.minOf { it.y }
                                            val maxDeltaY = bitmap.height.toFloat() - corners.maxOf { it.y }
                                            val clampedX = deltaX.coerceIn(minDeltaX, maxDeltaX)
                                            val clampedY = deltaY.coerceIn(minDeltaY, maxDeltaY)
                                            corners.indices.forEach { index ->
                                                val old = corners[index]
                                                corners[index] = Offset(old.x + clampedX, old.y + clampedY)
                                            }
                                        } else {
                                            val old = corners[selectedHandle]
                                            corners[selectedHandle] = Offset(
                                                (old.x + deltaX).coerceIn(0f, bitmap.width.toFloat()),
                                                (old.y + deltaY).coerceIn(0f, bitmap.height.toFloat())
                                            )
                                        }
                                    }
                                },
                                onDragEnd = { selectedHandle = -1 },
                                onDragCancel = { selectedHandle = -1 }
                            )
                        }
                ) {
                    drawRect(Color(0xFF242424))
                    val scale = min(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
                    val left = (size.width - bitmap.width * scale) / 2f
                    val top = (size.height - bitmap.height * scale) / 2f
                    drawContext.canvas.nativeCanvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    )
                    val points = corners.map { Offset(left + it.x * scale, top + it.y * scale) }
                    for (index in points.indices) {
                        drawLine(handleColor, points[index], points[(index + 1) % points.size], strokeWidth = 2.dp.toPx())
                    }
                    points.forEach { point ->
                        drawCircle(Color.White, radius = 13.dp.toPx(), center = point)
                        drawCircle(handleColor, radius = 10.dp.toPx(), center = point)
                    }
                    // A separate center grip makes the whole crop area draggable.
                    val center = points.reduce { total, point -> total + point } / points.size.toFloat()
                    drawCircle(Color.White, radius = 17.dp.toPx(), center = center)
                    drawCircle(handleColor, radius = 13.dp.toPx(), center = center)
                    val grip = 5.dp.toPx()
                    drawLine(Color.White, center + Offset(-grip, 0f), center + Offset(grip, 0f), strokeWidth = 2.dp.toPx())
                    drawLine(Color.White, center + Offset(0f, -grip), center + Offset(0f, grip), strokeWidth = 2.dp.toPx())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.manual_crop_choose_image))
                    }
                    Button(
                        enabled = !cropping,
                        onClick = {
                            val selectedCorners = corners.map { PointF(it.x, it.y) }
                            cropping = true
                            scope.launch {
                                try {
                                    val cropped = withContext(Dispatchers.Default) {
                                        PerspectiveTransform.crop(bitmap, selectedCorners)
                                    }
                                    onCommit(cropped)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    loadFailed = true
                                } finally {
                                    cropping = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("manual_crop_commit")
                    ) {
                        if (cropping) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.manual_crop_commit))
                    }
                }
                if (loadFailed) Text(stringResource(R.string.manual_crop_error), color = MaterialTheme.colorScheme.error)
            }
            Text(
                text = stringResource(R.string.manual_crop_cancel),
                modifier = Modifier.align(Alignment.End).testTag("manual_crop_cancel")
            )
        }
    }
}


/** Ray-casting hit test in Canvas coordinates for the currently displayed crop quadrilateral. */
private fun isPointInsideCrop(point: Offset, polygon: List<Offset>): Boolean {
    if (polygon.size != 4) return false
    var inside = false
    var previous = polygon.lastIndex
    for (current in polygon.indices) {
        val first = polygon[current]
        val second = polygon[previous]
        val crosses = (first.y > point.y) != (second.y > point.y) &&
            point.x < (second.x - first.x) * (point.y - first.y) /
                (second.y - first.y) + first.x
        if (crosses) inside = !inside
        previous = current
    }
    return inside
}
