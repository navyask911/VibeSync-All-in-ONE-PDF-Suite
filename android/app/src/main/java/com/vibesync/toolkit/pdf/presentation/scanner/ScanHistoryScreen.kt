package com.vibesync.toolkit.pdf.presentation.scanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.presentation.common.PdfDocumentActionsSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ScanHistoryScreen(
    viewModel: ScannerHistoryViewModel,
    onNavigateBack: () -> Unit,
    onNewScan: () -> Unit,
    onOpenScan: (Uri) -> Unit,
    onSharePdf: (File) -> Unit,
    onShareImages: (File) -> Unit,
    onSaveToDownloads: (File) -> Unit,
    onNavigateToEditor: (Uri) -> Unit = {},
    onNavigateToPdfTool: (String, Uri) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val repository = remember(context) {
        ScannerHistoryRepository(File(context.filesDir, "scans"))
    }

    LaunchedEffect(viewModel) { viewModel.refresh() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.scan_history_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.scan_history_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onNewScan, modifier = Modifier.testTag("scan_history_new_scan_top")) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = stringResource(R.string.scan_new))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewScan,
                icon = { Icon(Icons.Default.AddAPhoto, contentDescription = null) },
                text = { Text(stringResource(R.string.scan_new)) },
                modifier = Modifier.testTag("scan_history_new_scan_fab")
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading && state.scans.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.scans.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(28.dp).testTag("scan_history_empty"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.DocumentScanner, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.scan_empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.scan_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onNewScan, modifier = Modifier.testTag("scan_history_empty_new_scan")) {
                        Icon(Icons.Default.AddAPhoto, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.scan_new))
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    modifier = Modifier.fillMaxSize().testTag("scan_history_grid"),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.scans, key = { it.file.absolutePath }) { scan ->
                        ScanHistoryCard(
                            entry = scan,
                            repository = repository,
                            onOpen = { onOpenScan(Uri.fromFile(scan.file)) },
                            onSharePdf = { onSharePdf(scan.file) },
                            onShareImages = { onShareImages(scan.file) },
                            onSaveToDownloads = { onSaveToDownloads(scan.file) },
                            onEdit = { onNavigateToEditor(Uri.fromFile(scan.file)) },
                            onNavigateToPdfTool = { route -> onNavigateToPdfTool(route, Uri.fromFile(scan.file)) },
                            onRename = { file, name ->
                                viewModel.renameScan(file, name) { result ->
                                    scope.launch {
                                        val message = result.fold(
                                            onSuccess = { context.getString(R.string.scan_rename_success) },
                                            onFailure = { it.localizedMessage ?: context.getString(R.string.scan_operation_failed) }
                                        )
                                        snackbarHostState.showSnackbar(message)
                                    }
                                }
                            },
                            onDelete = { file ->
                                viewModel.deleteScan(file) { result ->
                                    scope.launch {
                                        val message = result.fold(
                                            onSuccess = { context.getString(R.string.scan_delete_success) },
                                            onFailure = { it.localizedMessage ?: context.getString(R.string.scan_operation_failed) }
                                        )
                                        snackbarHostState.showSnackbar(message)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanHistoryCard(
    entry: ScanHistoryEntry,
    repository: ScannerHistoryRepository,
    onOpen: () -> Unit,
    onSharePdf: () -> Unit,
    onShareImages: () -> Unit,
    onSaveToDownloads: () -> Unit,
    onEdit: () -> Unit,
    onNavigateToPdfTool: (String) -> Unit,
    onRename: (File, String) -> Unit,
    onDelete: (File) -> Unit
) {
    var showActionsSheet by rememberSaveable(entry.file.absolutePath) { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable(entry.file.absolutePath) { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable(entry.file.absolutePath) { mutableStateOf(false) }
    var requestedName by rememberSaveable(entry.file.absolutePath) { mutableStateOf(entry.file.nameWithoutExtension) }
    val preview by produceState<Bitmap?>(initialValue = null, key1 = entry.file.absolutePath, key2 = entry.file.lastModified()) {
        value = withContext(Dispatchers.IO) { repository.loadThumbnail(entry.file) }
    }
    // Never recycle a preview from composition disposal: Compose can still reference its ImageBitmap.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { showActionsSheet = true })
            .testTag("scan_item_" + entry.file.name),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.78f)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .combinedClickable(onClick = onOpen, onLongClick = { showActionsSheet = true })
                        .testTag("scan_thumbnail_" + entry.file.name),
                    contentAlignment = Alignment.Center
                ) {
                    val safePreview = preview?.takeUnless { it.isRecycled }
                    if (safePreview != null && !safePreview.isRecycled) {
                        Image(
                            bitmap = safePreview.asImageBitmap(),
                            contentDescription = entry.file.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(
                    onClick = { showActionsSheet = true },
                    modifier = Modifier.align(Alignment.TopEnd).testTag("scan_actions_" + entry.file.name)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.scan_action_menu))
                }

            }

            Column(Modifier.padding(12.dp)) {
                Text(
                    entry.file.nameWithoutExtension,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = { showActionsSheet = true }).testTag("scan_name_" + entry.file.name)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.scan_page_count, entry.pageCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.scan_scanned_at, DateFormat.getDateTimeInstance().format(Date(entry.scannedAtMillis))),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showActionsSheet) {
        PdfDocumentActionsSheet(
            documentName = entry.file.name,
            onDismissRequest = { showActionsSheet = false },
            onOpen = onOpen,
            onEdit = onEdit,
            onRename = {
                requestedName = entry.file.nameWithoutExtension
                showRenameDialog = true
            },
            onSharePdf = onSharePdf,
            onShareImages = onShareImages,
            onSaveToDownloads = onSaveToDownloads,
            onSplit = { onNavigateToPdfTool("split") },
            onCompress = { onNavigateToPdfTool("compress") },
            onWatermark = { onNavigateToPdfTool("watermark") },
            onEncrypt = { onNavigateToPdfTool("encrypt") },
            onDelete = { showDeleteDialog = true }
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.scan_rename_title)) },
            text = {
                OutlinedTextField(
                    value = requestedName,
                    onValueChange = { requestedName = it },
                    label = { Text(stringResource(R.string.scan_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("scan_rename_field")
                )
            },
            confirmButton = {
                TextButton(
                    enabled = requestedName.isNotBlank(),
                    onClick = { showRenameDialog = false; onRename(entry.file, requestedName.trim()) }
                ) { Text(stringResource(R.string.continue_label)) }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.scan_delete_title)) },
            text = { Text(stringResource(R.string.scan_delete_message, entry.file.name)) },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteDialog = false; onDelete(entry.file) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.scan_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}
