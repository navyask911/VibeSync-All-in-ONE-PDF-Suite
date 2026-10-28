package com.vibesync.toolkit.pdf.presentation.scanner

import android.graphics.Bitmap
import com.vibesync.toolkit.pdf.data.pdf.PdfBitmapUtils
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.pdf.BookSplitter
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfilePrefs
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
internal fun rememberBookSplitPhotoPickerLauncher(
    onImagePicked: (Uri?) -> Unit
): ActivityResultLauncher<PickVisualMediaRequest> =
    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> onImagePicked(uri) }

@Composable
fun BookSplitPreview(
    source: Bitmap,
    initialGutterPx: Int = BookSplitter.detectGutter(source),
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var gutter by remember(source) { mutableStateOf(initialGutterPx.coerceIn(1, source.width.coerceAtLeast(2) - 1)) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.book_split_preview_title), style = MaterialTheme.typography.titleMedium)
        Box(
            modifier = Modifier.fillMaxWidth().height(260.dp).testTag("book_split_preview"),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = source.asImageBitmap(),
                contentDescription = stringResource(R.string.book_split_source_image),
                modifier = Modifier.fillMaxSize().testTag("book_split_source"),
                contentScale = ContentScale.Fit
            )
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(source.width) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val delta = (dragAmount.x / size.width.toFloat() * source.width).toInt()
                        gutter = (gutter + delta).coerceIn(1, source.width - 1)
                    }
                }
            )
            val dividerColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize()) {
                val x = size.width * gutter / source.width.toFloat()
                drawLine(
                    color = dividerColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 3.dp.toPx()
                )
            }
        }
        Text(stringResource(R.string.book_split_gutter, gutter), modifier = Modifier.testTag("book_split_gutter"))
        Slider(
            value = gutter.toFloat(),
            onValueChange = { gutter = it.toInt().coerceIn(1, source.width - 1) },
            valueRange = 1f..(source.width - 1).coerceAtLeast(2).toFloat(),
            modifier = Modifier.testTag("book_split_gutter_slider")
        )
        Button(onClick = { onCommit(gutter) }, enabled = source.width > source.height, modifier = Modifier.fillMaxWidth().testTag("book_split_commit")) {
            Text(stringResource(R.string.book_split_commit))
        }
    }
}

@Composable
fun BookSplitEntryScreen(
    onNavigateBack: () -> Unit,
    onChooseImage: (Uri) -> Unit
) {
    val launcher = rememberBookSplitPhotoPickerLauncher { uri ->
        uri?.let(onChooseImage)
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.book_split_title)) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.book_split_choose_description))
            Button(onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth().testTag("book_split_choose_image")) {
                Text(stringResource(R.string.book_split_choose_image))
            }
        }
    }
}

@Composable
fun BookSplitScreen(
    imageUri: Uri,
    onNavigateBack: () -> Unit,
    onSuccess: (File) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source by produceState<Bitmap?>(initialValue = null, key1 = imageUri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (imageUri.scheme == "file") imageUri.path?.let { PdfBitmapUtils.decodeFileDownsampled(it) }
                else PdfBitmapUtils.decodeStreamDownsampled(openInputStream = { context.contentResolver.openInputStream(imageUri) })
            }.getOrNull()
        }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.book_split_title)) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val bitmap = source
            when {
                bitmap == null -> CircularProgressIndicator()
                bitmap.width <= bitmap.height -> Text(stringResource(R.string.book_split_portrait_error), color = MaterialTheme.colorScheme.error)
                else -> BookSplitPreview(source = bitmap, onCommit = { gutter ->
                    if (busy) return@BookSplitPreview
                    busy = true
                    error = null
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) {
                            val split = BookSplitter.split(bitmap, gutter)
                            val work = File(context.cacheDir, "book_split_${System.nanoTime()}").apply { mkdirs() }
                            val inputFiles = mutableListOf<File>()
                            try {
                                split.pages.forEachIndexed { index, page ->
                                    val file = File(work, "half_${index + 1}.jpg")
                                    FileOutputStream(file).use { output ->
                                        if (!page.compress(Bitmap.CompressFormat.JPEG, 95, output)) throw java.io.IOException("Could not encode split page.")
                                    }
                                    inputFiles.add(file)
                                    page.recycle()
                                }
                                val scans = File(context.filesDir, "scans").apply { mkdirs() }
                                val output = File(scans, "BookSplit_${System.currentTimeMillis()}.pdf")
                                val prefs = ResolutionProfilePrefs(context)
                                val selectedFilter = context.getSharedPreferences(ResolutionProfilePrefs.PREFERENCES_FILE, 0)
                                    .getString(BatchScanViewModel.KEY_FILTER_ID, null)?.let { runCatching { FilterId.valueOf(it) }.getOrNull() }
                                PdfBoxToolkitEngine(context).assembleBatchPages(inputFiles, output, prefs.read(), selectedFilter).getOrThrow()
                                output
                            } finally {
                                work.deleteRecursively()
                            }
                        } }
                        if (result.isSuccess) onSuccess(result.getOrThrow())
                        else { error = R.string.book_split_failed; busy = false }
                    }
                })
            }
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if (busy) CircularProgressIndicator()
        }
    }
}
