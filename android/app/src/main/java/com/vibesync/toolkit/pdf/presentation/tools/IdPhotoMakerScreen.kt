package com.vibesync.toolkit.pdf.presentation.tools

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.pdf.IdPhotoExporter
import com.vibesync.toolkit.pdf.data.pdf.IdPhotoPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun IdPhotoMakerScreen(
    onNavigateBack: () -> Unit,
    previewBitmap: Bitmap? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pickedUri = uri }
    var selected by remember { mutableStateOf(IdPhotoPresets.STANDARD_35x45) }
    var zoom by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val source by produceState<Bitmap?>(initialValue = previewBitmap, key1 = pickedUri, key2 = previewBitmap) {
        if (previewBitmap != null) value = previewBitmap
        else value = withContext(Dispatchers.IO) {
            pickedUri?.let { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull() }
        }
    }
    val cropAspect = selected.widthPx.toFloat() / selected.heightPx.toFloat()
    val cropGuideColor = MaterialTheme.colorScheme.primary
    val savedPreview by produceState<Bitmap?>(initialValue = null, key1 = savedUri) {
        value = withContext(Dispatchers.IO) {
            savedUri?.let { uri ->
                runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
        }
    }
    val openOrShare = { share: Boolean ->
        savedUri?.let { uri ->
            val intent = if (share) Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching {
                context.startActivity(if (share) Intent.createChooser(intent, context.getString(R.string.id_photo_share)) else intent)
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.id_photo_title)) }, navigationIcon = {
            androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        })
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.id_photo_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth().testTag("id_photo_pick")) {
                Text(stringResource(R.string.id_photo_pick))
            }
            Text(stringResource(R.string.id_photo_presets), style = MaterialTheme.typography.titleSmall)
            IdPhotoPresets.values().forEach { preset ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("id_photo_preset_${preset.name}")) {
                    RadioButton(selected = selected == preset, onClick = { selected = preset; zoom = 1f; panX = 0f; panY = 0f }, modifier = Modifier.testTag("id_photo_radio_" + preset.name))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(preset.labelRes))
                        Text(stringResource(R.string.id_photo_resolution_size, preset.widthPx, preset.heightPx), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth().testTag("id_photo_crop_card")) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(280.dp).pointerInput(selected) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                            panX = (panX + pan.x / size.width.toFloat()).coerceIn(-1f, 1f)
                            panY = (panY + pan.y / size.height.toFloat()).coerceIn(-1f, 1f)
                        }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    val image = source
                    if (image != null) {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = stringResource(R.string.id_photo_crop_guide),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = panX * 90f, translationY = panY * 90f)
                        )
                    } else {
                        Text(stringResource(R.string.id_photo_pick_first))
                    }
                    Canvas(Modifier.fillMaxSize().testTag("id_photo_crop_guide_${selected.name}")) {
                        val target = cropAspect
                        val rectWidth: Float
                        val rectHeight: Float
                        if (size.width / size.height > target) {
                            rectHeight = size.height * 0.86f
                            rectWidth = rectHeight * target
                        } else {
                            rectWidth = size.width * 0.86f
                            rectHeight = rectWidth / target
                        }
                        val left = (size.width - rectWidth) / 2f
                        val top = (size.height - rectHeight) / 2f
                        val shade = Color.Black.copy(alpha = 0.48f)
                        drawRect(shade, size = androidx.compose.ui.geometry.Size(size.width, top))
                        drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(0f, top + rectHeight), size = androidx.compose.ui.geometry.Size(size.width, size.height - top - rectHeight))
                        drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(left, rectHeight))
                        drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(left + rectWidth, top), size = androidx.compose.ui.geometry.Size(size.width - left - rectWidth, rectHeight))
                        drawRect(
                            color = cropGuideColor,
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(rectWidth, rectHeight),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                        )
                    }
                }
            }
            Text(stringResource(R.string.id_photo_crop_hint), style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            savedUri?.let {
                Card(modifier = Modifier.fillMaxWidth().testTag("id_photo_saved_confirmation")) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        savedPreview?.let { thumbnail ->
                            Image(
                                bitmap = thumbnail.asImageBitmap(),
                                contentDescription = stringResource(R.string.id_photo_saved),
                                modifier = Modifier.size(104.dp),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.id_photo_saved), modifier = Modifier.weight(1f))
                            androidx.compose.material3.IconButton(onClick = { openOrShare(false) }, modifier = Modifier.testTag("id_photo_open")) {
                                Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.id_photo_open))
                            }
                            androidx.compose.material3.IconButton(onClick = { openOrShare(true) }, modifier = Modifier.testTag("id_photo_share")) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.id_photo_share))
                            }
                        }
                    }
                }
            }
            Button(
                enabled = source != null && !saving,
                onClick = {
                    val bitmap = source ?: return@Button
                    saving = true
                    savedUri = null
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val cropped = IdPhotoExporter.cropForPreset(bitmap, selected, zoom, panX, panY)
                                try { IdPhotoExporter.save(context, cropped, selected).getOrThrow() }
                                finally { if (!cropped.isRecycled && cropped !== bitmap) cropped.recycle() }
                            }
                        }
                        result.fold(onSuccess = { savedUri = it }, onFailure = { error = it.localizedMessage ?: context.getString(R.string.id_photo_save_failed) })
                        saving = false
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("id_photo_save")
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.id_photo_save))
            }
        }
    }
}
