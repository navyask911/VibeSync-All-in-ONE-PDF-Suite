package com.vibesync.toolkit.pdf.presentation.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import com.vibesync.toolkit.pdf.data.signature.SignatureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun SignatureManagerScreen(
    onNavigateBack: () -> Unit,
    onSelectSignature: (Bitmap, Float, Float, Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    securityVault: SecurityVault? = null,
    signatureRepository: SignatureRepository? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val repository = remember(context, securityVault, signatureRepository) {
        signatureRepository ?: SignatureRepository(context, securityVault ?: SecurityVault(context))
    }
    val scope = rememberCoroutineScope()
    val signatures = remember { mutableStateListOf<SignatureRepository.SavedSignature>() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showCapture by remember { mutableStateOf(false) }
    var isSavingCapture by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var offsetX by remember { mutableStateOf(24f) }
    var offsetY by remember { mutableStateOf(24f) }
    var placementZoom by remember { mutableStateOf(1f) }
    var placementPan by remember { mutableStateOf(Offset.Zero) }
    var sizeScale by remember { mutableStateOf(1f) }
    var rotationDegrees by remember { mutableStateOf(0f) }

    fun refreshSignatures() {
        signatures.clear()
        signatures.addAll(repository.listSignatures())
        if (selectedId != null && signatures.none { it.id == selectedId }) {
            selectedId = null
            previewBitmap = null
        }
    }

    fun selectSignature(id: String) {
        selectedId = id
        previewBitmap = null
        offsetX = 24f
        offsetY = 24f
        sizeScale = 1f
        rotationDegrees = 0f
        placementZoom = 1f
        placementPan = Offset.Zero
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { repository.getSignature(id) }
            if (selectedId == id) {
                previewBitmap = loaded
            } else {
                loaded?.let { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    DisposableEffect(Unit) {
        refreshSignatures()
        onDispose { }
    }
    DisposableEffect(previewBitmap) {
        val bitmapToDispose = previewBitmap
        onDispose {
            bitmapToDispose?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.signature_manager_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (signatures.isEmpty()) {
                Text(stringResource(R.string.signature_manager_empty), modifier = Modifier.testTag("signature_manager_empty"))
            }
            signatures.forEach { signature ->
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("signature_item_${signature.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(signature.id, modifier = Modifier.weight(1f))
                        IconButton(onClick = { selectSignature(signature.id) }, modifier = Modifier.testTag("signature_select_${signature.id}")) {
                            Icon(Icons.Default.Draw, contentDescription = stringResource(R.string.signature_manager_select))
                        }
                        IconButton(
                            onClick = { selectedId = signature.id; renameValue = signature.id; showRename = true },
                            modifier = Modifier.testTag("signature_rename_${signature.id}")
                        ) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.signature_manager_rename)) }
                        IconButton(
                            onClick = { selectedId = signature.id; showDelete = true },
                            modifier = Modifier.testTag("signature_delete_${signature.id}")
                        ) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.signature_manager_delete)) }
                    }
                }
            }
            val preview = previewBitmap
            val previewImageBitmap = remember(preview) { preview?.asImageBitmap() }
            if (preview != null && previewImageBitmap != null && !preview.isRecycled) {
                Text(stringResource(R.string.signature_manager_drag_hint), style = MaterialTheme.typography.bodySmall)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag("signature_placement_canvas")
                        .pointerInput(preview) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var previousCentroid: Offset? = null
                                var previousDistance = 0f
                                var previousPointerCount = 0
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    val centroid = Offset(
                                        pressed.map { it.position.x }.average().toFloat(),
                                        pressed.map { it.position.y }.average().toFloat()
                                    )
                                    val distance = if (pressed.size > 1) {
                                        sqrt(pressed.sumOf { change ->
                                            val dx = (change.position.x - centroid.x).toDouble()
                                            val dy = (change.position.y - centroid.y).toDouble()
                                            dx * dx + dy * dy
                                        } / pressed.size).toFloat()
                                    } else 0f

                                    val priorCentroid = previousCentroid
                                    if (priorCentroid != null &&
                                        previousPointerCount == pressed.size &&
                                        (pressed.size > 1 || placementZoom > 1f)
                                    ) {
                                        val pan = centroid - priorCentroid
                                        val zoomFactor = if (pressed.size > 1 && previousDistance > 0f) {
                                            distance / previousDistance
                                        } else 1f
                                        val previousScale = placementZoom
                                        val nextScale = (previousScale * zoomFactor).coerceIn(1f, 5f)
                                        val scaleFactor = nextScale / previousScale
                                        placementZoom = nextScale
                                        if (nextScale > 1f) {
                                            val focusX = centroid.x - size.width / 2f
                                            val focusY = centroid.y - size.height / 2f
                                            val maxOffsetX = size.width * (nextScale - 1f) / 2f
                                            val maxOffsetY = size.height * (nextScale - 1f) / 2f
                                            placementPan = Offset(
                                                ((placementPan.x - focusX) * scaleFactor + focusX + pan.x)
                                                    .coerceIn(-maxOffsetX, maxOffsetX),
                                                ((placementPan.y - focusY) * scaleFactor + focusY + pan.y)
                                                    .coerceIn(-maxOffsetY, maxOffsetY)
                                            )
                                        } else {
                                            placementPan = Offset.Zero
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    previousCentroid = centroid
                                    previousDistance = distance
                                    previousPointerCount = pressed.size
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = placementZoom,
                                scaleY = placementZoom,
                                translationX = placementPan.x,
                                translationY = placementPan.y
                            )
                    ) {
                        Image(
                            bitmap = previewImageBitmap,
                            contentDescription = stringResource(R.string.signature_manager_select),
                            modifier = Modifier
                                .offset(x = offsetX.dp, y = offsetY.dp)
                                .size(width = (160f * sizeScale).dp, height = (80f * sizeScale).dp)
                                .graphicsLayer(rotationZ = rotationDegrees)
                                .pointerInput(preview, density) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        offsetX = (offsetX + dragAmount.x / density).coerceIn(0f, 220f)
                                        offsetY = (offsetY + dragAmount.y / density).coerceIn(0f, 95f)
                                    }
                                }
                                .testTag("signature_placement_preview")
                        )
                    }
                }
                Text(stringResource(R.string.signature_manager_size), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = sizeScale,
                    onValueChange = { sizeScale = it },
                    valueRange = 0.5f..2f,
                    modifier = Modifier.testTag("signature_size_slider")
                )
                Text(stringResource(R.string.signature_manager_rotation, rotationDegrees.roundToInt()), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = rotationDegrees,
                    onValueChange = { rotationDegrees = it },
                    valueRange = -180f..180f,
                    modifier = Modifier.testTag("signature_rotation_slider")
                )
                Button(
                    onClick = {
                        val selected = previewBitmap
                        if (selected != null && !selected.isRecycled) {
                            onSelectSignature(
                                selected.copy(Bitmap.Config.ARGB_8888, true),
                                offsetX * density,
                                offsetY * density,
                                160f * sizeScale * density,
                                80f * sizeScale * density,
                                rotationDegrees
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("signature_place_button")
                ) { Text(stringResource(R.string.signature_manager_place)) }
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        }
        FloatingActionButton(
            onClick = { showCapture = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).testTag("signature_create_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.signature_capture_title))
        }
    }

    if (showCapture) {
        SignatureCaptureDialog(
            isSaving = isSavingCapture,
            onDismiss = { if (!isSavingCapture) showCapture = false },
            onSave = { id, bitmap ->
                isSavingCapture = true
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { repository.saveSignature(id, bitmap) }
                    }
                    if (!bitmap.isRecycled) bitmap.recycle()
                    isSavingCapture = false
                    saved.onSuccess { file ->
                        showCapture = false
                        errorMessage = null
                        refreshSignatures()
                        selectSignature(file.name.removeSuffix(".sig"))
                    }.onFailure {
                        errorMessage = it.localizedMessage ?: context.getString(R.string.signature_capture_save_failed)
                    }
                }
            }
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.signature_manager_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text(stringResource(R.string.signature_manager_enter_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val oldId = selectedId
                    if (oldId != null && repository.renameSignature(oldId, renameValue)) {
                        val changedId = renameValue.trim()
                        showRename = false
                        refreshSignatures()
                        if (previewBitmap != null) selectSignature(changedId)
                    } else {
                        errorMessage = context.getString(R.string.signature_manager_name_required)
                    }
                }) { Text(stringResource(R.string.signature_manager_rename)) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.manual_crop_cancel)) } }
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.signature_manager_delete_title)) },
            confirmButton = {
                TextButton(onClick = {
                    selectedId?.let { repository.deleteSignature(it) }
                    showDelete = false
                    refreshSignatures()
                }) { Text(stringResource(R.string.signature_manager_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.manual_crop_cancel)) } }
        )
    }
}


@Composable
private fun SignatureCaptureDialog(
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Bitmap) -> Unit
) {
    var signatureName by remember { mutableStateOf("") }
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var canvasWidth by remember { mutableStateOf(0f) }
    var canvasHeight by remember { mutableStateOf(0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.signature_capture_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = signatureName,
                    onValueChange = { signatureName = it },
                    label = { Text(stringResource(R.string.signature_capture_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("signature_capture_name")
                )
                Text(stringResource(R.string.signature_capture_hint), style = MaterialTheme.typography.bodySmall)
                Canvas(
                    modifier = Modifier.fillMaxWidth()
                        .height(170.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .onSizeChanged {
                            canvasWidth = it.width.toFloat()
                            canvasHeight = it.height.toFloat()
                        }
                        .testTag("signature_capture_canvas")
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { point -> strokes = strokes + listOf(listOf(point)) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    if (strokes.isNotEmpty()) {
                                        strokes = strokes.dropLast(1) + listOf(strokes.last() + change.position)
                                    }
                                }
                            )
                        }
                ) {
                    strokes.forEach { points ->
                        if (points.isNotEmpty()) {
                            val path = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(
                                path = path,
                                color = Color.Black,
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { strokes = emptyList() },
                        enabled = !isSaving
                    ) { Text(stringResource(R.string.signature_capture_clear)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving && signatureName.isNotBlank() && strokes.any { it.size > 0 } &&
                    canvasWidth > 0f && canvasHeight > 0f,
                modifier = Modifier.testTag("signature_capture_save"),
                onClick = {
                    val bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    val scaleX = bitmap.width / canvasWidth
                    val scaleY = bitmap.height / canvasHeight
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.BLACK
                        style = android.graphics.Paint.Style.STROKE
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                        strokeWidth = 12f
                    }
                    strokes.forEach { points ->
                        if (points.size == 1) {
                            canvas.drawCircle(points.single().x * scaleX, points.single().y * scaleY, 6f, paint)
                        } else if (points.size > 1) {
                            val path = android.graphics.Path().apply {
                                moveTo(points.first().x * scaleX, points.first().y * scaleY)
                                points.drop(1).forEach { lineTo(it.x * scaleX, it.y * scaleY) }
                            }
                            canvas.drawPath(path, paint)
                        }
                    }
                    onSave(signatureName.trim(), bitmap)
                }
            ) {
                if (isSaving) Text("Saving…") else Text(stringResource(R.string.signature_capture_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
