package com.vibesync.toolkit.pdf.presentation.scanner

import com.vibesync.toolkit.pdf.data.pdf.PdfBitmapUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId
import com.vibesync.toolkit.pdf.data.pdf.PageRangeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BatchReviewSheet(
    pages: List<BatchScanPage>,
    onMovePage: (Int, Int) -> Unit,
    onDeletePage: (String) -> Unit,
    onAddPages: () -> Unit,
    onCompile: (String) -> Unit,
    isCompiling: Boolean = false,
    progress: Float = 0f,
    modifier: Modifier = Modifier
) {
    var showPageRangeDialog by remember { androidx.compose.runtime.mutableStateOf(false) }
    var pageRangeInput by remember { androidx.compose.runtime.mutableStateOf("All") }
    var pageRangeError by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxWidth().testTag("batch_review_sheet")) {
        Text(
            text = stringResource(R.string.batch_page_count, pages.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("batch_page_count")
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            pages.forEachIndexed { index, page ->
                // Keep each thumbnail's composition attached to the page while its index changes.
                key(page.id) {
                    BatchPageThumbnail(
                        page = page,
                        index = index,
                        onDelete = { onDeletePage(page.id) },
                        onMove = onMovePage,
                        pageCount = pages.size
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onAddPages, enabled = !isCompiling, modifier = Modifier.weight(1f).testTag("batch_add_pages")) {
                Text(stringResource(R.string.batch_add_pages))
            }
            Button(
                onClick = {
                    pageRangeInput = "All"
                    pageRangeError = null
                    showPageRangeDialog = true
                },
                enabled = pages.isNotEmpty() && !isCompiling,
                modifier = Modifier.weight(1f).testTag("batch_compile")
            ) {
                if (isCompiling) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.batch_compile_pdf))
            }
        }
        if (showPageRangeDialog) {
            AlertDialog(
                onDismissRequest = { showPageRangeDialog = false },
                title = { Text("Page Range") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Use All or a comma-separated range such as 1-10, 15, 20-25.")
                        OutlinedTextField(
                            value = pageRangeInput,
                            onValueChange = { pageRangeInput = it; pageRangeError = null },
                            label = { Text("Page Range") },
                            singleLine = true,
                            modifier = Modifier.testTag("batch_page_range_input")
                        )
                        pageRangeError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { PageRangeParser.parse(pageRangeInput, pages.size) }
                            .onSuccess {
                                showPageRangeDialog = false
                                onCompile(pageRangeInput.trim())
                            }
                            .onFailure {
                                pageRangeError = "Enter All or valid page numbers from 1 to ${pages.size}."
                            }
                    }, modifier = Modifier.testTag("batch_page_range_confirm")) { Text("Compile") }
                },
                dismissButton = {
                    TextButton(onClick = { showPageRangeDialog = false }) { Text("Cancel") }
                }
            )
        }
        if (isCompiling) {
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().testTag("batch_compile_progress"))
        }
    }
}

@Composable
private fun BatchPageThumbnail(
    page: BatchScanPage,
    index: Int,
    onDelete: () -> Unit,
    onMove: (Int, Int) -> Unit,
    pageCount: Int
) {
    var showFullPreview by remember(page.id) { androidx.compose.runtime.mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = page.id, key2 = page.file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            if (page.file.exists() && page.file.isFile) {
                PdfBitmapUtils.decodeFileDownsampled(page.file.absolutePath)
            } else {
                null
            }
        }
    }
    Card(
        // Three Material IconButtons require 144 dp; include 16 dp of horizontal card padding.
        modifier = Modifier.width(160.dp).testTag("batch_page_${page.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier.size(88.dp, 112.dp)
                    .testTag("batch_drag_${page.id}")
                    .pointerInput(page.id, index, pageCount) {
                        var accumulatedDrag = 0f
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            accumulatedDrag += dragAmount.x
                            val threshold = size.width * 0.25f
                            if (accumulatedDrag >= threshold) {
                                onMove(index, (index + 1).coerceAtMost(pageCount - 1))
                                accumulatedDrag = 0f
                            } else if (accumulatedDrag <= -threshold) {
                                onMove(index, (index - 1).coerceAtLeast(0))
                                accumulatedDrag = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val preview = bitmap
                if (preview != null && !preview.isRecycled) {
                    Image(
                        preview.asImageBitmap(),
                        contentDescription = stringResource(R.string.batch_page_number, index + 1),
                        modifier = Modifier.fillMaxSize().clickable { showFullPreview = true },
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Image unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(stringResource(R.string.batch_page_number, index + 1), style = MaterialTheme.typography.labelMedium)
            Row {
                IconButton(onClick = { onMove(index, (index - 1).coerceAtLeast(0)) }, enabled = index > 0, modifier = Modifier.testTag("batch_move_left_${page.id}")) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = stringResource(R.string.batch_move_left))
                }
                IconButton(onClick = { onMove(index, (index + 1).coerceAtMost(pageCount - 1)) }, enabled = index < pageCount - 1, modifier = Modifier.testTag("batch_move_right_${page.id}")) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = stringResource(R.string.batch_move_right))
                }
                IconButton(onClick = onDelete, modifier = Modifier.testTag("batch_delete_${page.id}")) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.batch_delete_page))
                }
            }
        }
    }

    if (showFullPreview && bitmap != null && !bitmap!!.isRecycled) {
        Dialog(onDismissRequest = { showFullPreview = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.batch_page_number, index + 1),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(onClick = { showFullPreview = false }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                    Image(
                        bitmap!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.batch_page_number, index + 1),
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("batch_page_preview_${page.id}"),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}

@Composable
fun BatchScanScreen(
    viewModel: BatchScanViewModel,
    onNavigateBack: () -> Unit,
    onStartCapture: () -> Unit,
    onOpenPdf: (File) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("scan_settings_prefs", 0) }
    var selectedFilterName by remember { androidx.compose.runtime.mutableStateOf(prefs.getString(BatchScanViewModel.KEY_FILTER_ID, "") ?: "") }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val selectedFilter = selectedFilterName.takeIf { it.isNotBlank() }?.let {
        runCatching { FilterId.valueOf(it) }.getOrNull()
    }

    LaunchedEffect(state.savedFile) {
        state.savedFile?.let { file ->
            onOpenPdf(file)
            viewModel.clearCompletion()
        }
    }
    LaunchedEffect(state.messageRes) {
        state.messageRes?.let { snackbarHostState.showSnackbar(context.getString(it)) }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.batch_scan_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.batch_mode_title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.batch_mode_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.batchModeEnabled,
                    onCheckedChange = viewModel::setBatchModeEnabled,
                    modifier = Modifier.testTag("batch_mode_toggle")
                )
            }
            Text(stringResource(R.string.scan_filter_title), style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterId.values().forEach { filterId ->
                    FilterChip(
                        selected = selectedFilter == filterId,
                        onClick = {
                            selectedFilterName = filterId.name
                            prefs.edit().putString(BatchScanViewModel.KEY_FILTER_ID, filterId.name).apply()
                        },
                        label = { Text(stringResource(filterId.labelRes)) },
                        modifier = Modifier.testTag("batch_filter_" + filterId.name)
                    )
                }
                if (selectedFilter != null) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            selectedFilterName = ""
                            prefs.edit().remove(BatchScanViewModel.KEY_FILTER_ID).apply()
                        },
                        label = { Text(stringResource(R.string.signature_clear_action)) },
                        modifier = Modifier.testTag("batch_filter_clear")
                    )
                }
            }
            androidx.compose.material3.OutlinedButton(
                onClick = { viewModel.requestCapture(); onStartCapture() },
                enabled = state.batchModeEnabled && !state.isCompiling,
                modifier = Modifier.fillMaxWidth().testTag("batch_capture")
            ) { Text(stringResource(if (state.pages.isEmpty()) R.string.batch_start_capture else R.string.batch_add_capture)) }
            state.messageRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("batch_error")) }
            if (state.pages.isNotEmpty()) {
                BatchReviewSheet(
                    pages = state.pages,
                    onMovePage = viewModel::movePage,
                    onDeletePage = viewModel::deletePage,
                    onAddPages = { viewModel.requestCapture(); onStartCapture() },
                    onCompile = viewModel::compileBatch,
                    isCompiling = state.isCompiling,
                    progress = state.progress
                )
            }
            if (state.fellBack) Text(stringResource(R.string.resolution_profile_memory_fallback), color = MaterialTheme.colorScheme.error)
            if (state.isCompiling) OutlinedButton(onClick = viewModel::cancelCompile, modifier = Modifier.testTag("batch_cancel_compile")) { Text(stringResource(R.string.cancel)) }
        }
    }
}