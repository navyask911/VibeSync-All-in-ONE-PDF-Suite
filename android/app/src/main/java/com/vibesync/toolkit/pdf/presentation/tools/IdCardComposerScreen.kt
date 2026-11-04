package com.vibesync.toolkit.pdf.presentation.tools

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.util.generateSmartFileName
import com.vibesync.toolkit.pdf.data.pdf.IdCardComposer
import com.vibesync.toolkit.pdf.data.pdf.PdfBitmapUtils
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private enum class IdCardSide { FRONT, BACK }

@Composable
fun IdCardComposerScreen(
    onNavigateBack: () -> Unit,
    previewFront: Bitmap? = null,
    previewBack: Bitmap? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var frontUri by remember { mutableStateOf<Uri?>(null) }
    var backUri by remember { mutableStateOf<Uri?>(null) }
    var frontCameraFile by remember { mutableStateOf<File?>(null) }
    var backCameraFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var selectedSide by remember { mutableStateOf<IdCardSide?>(null) }
    var pendingPickerSide by remember { mutableStateOf<IdCardSide?>(null) }
    var pendingCameraSide by remember { mutableStateOf<IdCardSide?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showCameraDialog by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val side = pendingPickerSide
        if (uri != null && side != null) {
            when (side) {
                IdCardSide.FRONT -> {
                    frontCameraFile = null
                    frontUri = uri
                }
                IdCardSide.BACK -> {
                    backCameraFile = null
                    backUri = uri
                }
            }
            message = null
        }
        pendingPickerSide = null
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            showCameraDialog = true
        } else {
            message = R.string.id_card_camera_permission
            pendingCameraSide = null
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destination ->
        val file = pendingFile
        pendingFile = null
        if (destination != null && file?.isFile == true) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        } ?: throw IOException(context.getString(R.string.id_card_save_failed))
                    }
                }
                message = if (saved.isSuccess) R.string.id_card_save_success else R.string.id_card_save_failed
                file.delete()
                busy = false
            }
        } else {
            file?.delete()
            busy = false
        }
    }

    val frontBitmap by produceState<Bitmap?>(
        initialValue = previewFront,
        key1 = frontUri,
        key2 = frontCameraFile,
        key3 = previewFront
    ) {
        value = if (frontUri == null && frontCameraFile == null) {
            previewFront
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    frontCameraFile?.takeIf(File::isFile)?.let { file ->
                        PdfBitmapUtils.decodeFileDownsampled(
                            file.absolutePath,
                            IdCardComposer.MAX_INPUT_DIMENSION_300_DPI
                        )
                    } ?: frontUri?.let { uri ->
                        PdfBitmapUtils.decodeStreamDownsampled(
                            openInputStream = { context.contentResolver.openInputStream(uri) },
                            maxDimension = IdCardComposer.MAX_INPUT_DIMENSION_300_DPI
                        )
                    }
                }.getOrNull()
            }
        }
    }
    val backBitmap by produceState<Bitmap?>(
        initialValue = previewBack,
        key1 = backUri,
        key2 = backCameraFile,
        key3 = previewBack
    ) {
        value = if (backUri == null && backCameraFile == null) {
            previewBack
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    backCameraFile?.takeIf(File::isFile)?.let { file ->
                        PdfBitmapUtils.decodeFileDownsampled(
                            file.absolutePath,
                            IdCardComposer.MAX_INPUT_DIMENSION_300_DPI
                        )
                    } ?: backUri?.let { uri ->
                        PdfBitmapUtils.decodeStreamDownsampled(
                            openInputStream = { context.contentResolver.openInputStream(uri) },
                            maxDimension = IdCardComposer.MAX_INPUT_DIMENSION_300_DPI
                        )
                    }
                }.getOrNull()
            }
        }
    }

    DisposableEffect(frontBitmap, previewFront) {
        val ownedBitmap = frontBitmap
        onDispose {
            if (ownedBitmap != null && ownedBitmap !== previewFront && !ownedBitmap.isRecycled) {
                ownedBitmap.recycle()
            }
        }
    }
    DisposableEffect(backBitmap, previewBack) {
        val ownedBitmap = backBitmap
        onDispose {
            if (ownedBitmap != null && ownedBitmap !== previewBack && !ownedBitmap.isRecycled) {
                ownedBitmap.recycle()
            }
        }
    }
    DisposableEffect(frontCameraFile) {
        val ownedFile = frontCameraFile
        onDispose { ownedFile?.delete() }
    }
    DisposableEffect(backCameraFile) {
        val ownedFile = backCameraFile
        onDispose { ownedFile?.delete() }
    }

    fun chooseCamera() {
        val side = selectedSide ?: return
        pendingCameraSide = side
        selectedSide = null
        showSourceDialog = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showCameraDialog = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun chooseGallery() {
        val side = selectedSide ?: return
        pendingPickerSide = side
        selectedSide = null
        showSourceDialog = false
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.id_card_title)) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.id_card_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("id_card_pick_front")
                        .clickable {
                            selectedSide = IdCardSide.FRONT
                            showSourceDialog = true
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Badge, contentDescription = null)
                        Text(stringResource(R.string.id_card_front_slot), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        if (frontBitmap != null) {
                            Image(
                                frontBitmap!!.asImageBitmap(),
                                contentDescription = stringResource(R.string.id_card_front_slot),
                                modifier = Modifier.fillMaxWidth().height(110.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Spacer(Modifier.height(110.dp))
                            Text(stringResource(R.string.id_card_pick_front), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("id_card_pick_back")
                        .clickable {
                            selectedSide = IdCardSide.BACK
                            showSourceDialog = true
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Flip, contentDescription = null)
                        Text(stringResource(R.string.id_card_back_slot), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        if (backBitmap != null) {
                            Image(
                                backBitmap!!.asImageBitmap(),
                                contentDescription = stringResource(R.string.id_card_back_slot),
                                modifier = Modifier.fillMaxWidth().height(110.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Spacer(Modifier.height(110.dp))
                            Text(stringResource(R.string.id_card_pick_back), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth().aspectRatio(595f / 842f).testTag("id_card_layout_preview"),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (frontBitmap != null) {
                        Image(
                            bitmap = frontBitmap!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.id_card_front_slot),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                    } else Spacer(Modifier.weight(1f).fillMaxWidth())
                    if (backBitmap != null) {
                        Image(
                            bitmap = backBitmap!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.id_card_back_slot),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                    } else Spacer(Modifier.weight(1f).fillMaxWidth())
                }
            }
            Text(
                stringResource(if (frontBitmap == null || backBitmap == null) R.string.id_card_choose_two_guidance else R.string.id_card_preview_ready),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("id_card_guidance")
            )
            message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
            Button(
                enabled = frontBitmap != null && backBitmap != null && !busy,
                onClick = {
                    val front = frontBitmap ?: return@Button
                    val back = backBitmap ?: return@Button
                    busy = true
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                val composed = File.createTempFile("id_card_", ".pdf", context.cacheDir)
                                IdCardComposer.compose(front, back, composed).getOrThrow()
                                val watermarked = File.createTempFile("id_card_watermarked_", ".pdf", context.cacheDir)
                                try {
                                    PdfBoxToolkitEngine(context).watermarkPdf(
                                        sourceUri = Uri.fromFile(composed),
                                        watermarkText = PdfBoxToolkitEngine.DEFAULT_FOOTER_WATERMARK_TEXT,
                                        outputFile = watermarked
                                    ).getOrThrow()
                                } finally {
                                    composed.delete()
                                }
                                watermarked
                            }
                        }
                        if (result.isSuccess) {
                            pendingFile = result.getOrThrow()
                            saveLauncher.launch(generateSmartFileName("VibeSync_IDCard", "pdf"))
                        } else {
                            message = R.string.id_card_save_failed
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("id_card_save")
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text(stringResource(R.string.id_card_save))
                }
            }
        }
    }

    if (showSourceDialog && selectedSide != null) {
        val title = if (selectedSide == IdCardSide.FRONT) R.string.id_card_front_slot else R.string.id_card_back_slot
        AlertDialog(
            onDismissRequest = {
                showSourceDialog = false
                selectedSide = null
            },
            title = { Text(stringResource(title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { chooseCamera() },
                        modifier = Modifier.fillMaxWidth().testTag("id_card_take_photo_option")
                    ) {
                        Text(stringResource(R.string.id_card_take_photo))
                    }
                    TextButton(
                        onClick = { chooseGallery() },
                        modifier = Modifier.fillMaxWidth().testTag("id_card_import_gallery_option")
                    ) {
                        Text(stringResource(R.string.id_card_import_gallery))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSourceDialog = false
                    selectedSide = null
                }) {
                    Text(stringResource(R.string.manual_crop_cancel))
                }
            }
        )
    }

    if (showCameraDialog) {
        IdCardCameraCaptureDialog(
            onImageCaptured = { file ->
                when (pendingCameraSide) {
                    IdCardSide.FRONT -> {
                        frontUri = null
                        frontCameraFile = file
                    }
                    IdCardSide.BACK -> {
                        backUri = null
                        backCameraFile = file
                    }
                    null -> file.delete()
                }
                pendingCameraSide = null
                showCameraDialog = false
                message = null
            },
            onCameraError = { message = R.string.id_card_camera_error },
            onDismiss = {
                showCameraDialog = false
                pendingCameraSide = null
            }
        )
    }
}

/** CameraX capture surface used by the ID Card Composer's Take Photo action. */
@Composable
private fun IdCardCameraCaptureDialog(
    onImageCaptured: (File) -> Unit,
    onCameraError: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val active = remember { AtomicBoolean(true) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraError by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(previewView, lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (active.get()) {
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build().apply {
                            targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                        }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture
                    )
                    providerRef.set(provider)
                    imageCapture = capture
                }.onFailure {
                    cameraError = true
                    onCameraError()
                }
            }
        }, mainExecutor)
    }

    DisposableEffect(Unit) {
        onDispose {
            active.set(false)
            providerRef.getAndSet(null)?.unbindAll()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.id_card_take_photo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                )
                if (cameraError) {
                    Text(stringResource(R.string.id_card_camera_error), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = imageCapture != null,
                onClick = {
                    val capture = imageCapture ?: return@Button
                    val outputFile = runCatching {
                        File.createTempFile("vibesync_id_card_", ".jpg", context.cacheDir)
                    }.getOrElse {
                        cameraError = true
                        onCameraError()
                        return@Button
                    }
                    val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                    capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            if (outputFile.isFile && outputFile.length() > 0L) {
                                onImageCaptured(outputFile)
                            } else {
                                outputFile.delete()
                                cameraError = true
                                onCameraError()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            outputFile.delete()
                            cameraError = true
                            onCameraError()
                        }
                    })
                }
            ) {
                Text(stringResource(R.string.id_card_camera_capture))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.manual_crop_cancel))
            }
        }
    )
}
