package com.vibesync.toolkit.pdf.presentation.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
    var sourceBitmap by remember(initialBitmap) { mutableStateOf(initialBitmap) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var loading by remember { mutableStateOf(false) }
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
                context.contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input) }
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.manual_crop_instructions),
                style = MaterialTheme.typography.bodyMedium
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
                        .pointerInput(bitmap, corners) {
                            var selectedHandle by mutableIntStateOf(-1)
                            fun scaleAndOffset(): Triple<Float, Float, Float> {
                                val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                                return Triple(scale, (size.width - bitmap.width * scale) / 2f, (size.height - bitmap.height * scale) / 2f)
                            }
                            androidx.compose.foundation.gestures.detectDragGestures(
                                onDragStart = { start ->
                                    val (scale, left, top) = scaleAndOffset()
                                    selectedHandle = corners.indices.minByOrNull { index ->
                                        val point = corners[index]
                                        val dx = left + point.x * scale - start.x
                                        val dy = top + point.y * scale - start.y
                                        sqrt(dx * dx + dy * dy)
                                    }?.takeIf { index ->
                                        val point = corners[index]
                                        val dx = left + point.x * scale - start.x
                                        val dy = top + point.y * scale - start.y
                                        sqrt(dx * dx + dy * dy) <= 48f
                                    } ?: -1
                                },
                                onDrag = { change, dragAmount ->
                                    if (selectedHandle >= 0) {
                                        change.consume()
                                        val (scale, _, _) = scaleAndOffset()
                                        val old = corners[selectedHandle]
                                        corners[selectedHandle] = Offset(
                                            (old.x + dragAmount.x / scale).coerceIn(0f, bitmap.width.toFloat()),
                                            (old.y + dragAmount.y / scale).coerceIn(0f, bitmap.height.toFloat())
                                        )
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
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.manual_crop_choose_image))
                    }
                    Button(
                        onClick = {
                            runCatching {
                                PerspectiveTransform.crop(
                                    bitmap,
                                    corners.map { PointF(it.x, it.y) }
                                )
                            }.onSuccess(onCommit).onFailure { loadFailed = true }
                        },
                        modifier = Modifier.weight(1f).testTag("manual_crop_commit")
                    ) {
                        Text(stringResource(R.string.manual_crop_commit))
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
