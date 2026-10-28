package com.vibesync.toolkit.pdf.presentation.viewer

import android.app.Activity
import android.content.ContextWrapper
import android.content.Context
import android.print.PrintAttributes
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncCrimsonPrimary
import com.vibesync.toolkit.pdf.core.util.BitmapCache
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.presentation.common.launchSystemPdfPrint
import com.vibesync.toolkit.pdf.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import java.io.File
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    documentUri: Uri,
    viewModel: PdfViewerViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEditor: (Uri) -> Unit,
    onShareDocument: () -> Unit,
    isManagedScan: Boolean = false,
    onRenameScan: (String) -> Unit = {},
    onShareScanPdf: () -> Unit = {},
    onShareScanImages: () -> Unit = {},
    onSaveScanToDownloads: () -> Unit = {},
    onDeleteScan: () -> Unit = {},
    onLaunchPrint: (Context, Uri, String) -> Unit = { printContext, uri, jobName ->
        launchSystemPdfPrint(printContext, uri, jobName)
    },
    onRenameClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var isPreparingPrint by remember(documentUri) { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var scanMenuExpanded by remember(documentUri) { mutableStateOf(false) }
    var showRenameScanDialog by remember(documentUri) { mutableStateOf(false) }
    var showDeleteScanDialog by remember(documentUri) { mutableStateOf(false) }
    var renamedScanName by rememberSaveable(documentUri) { mutableStateOf("") }

    LaunchedEffect(uiState.fileName, documentUri) {
        renamedScanName = uiState.fileName.removeSuffix(".pdf").removeSuffix(".PDF")
    }

    LaunchedEffect(documentUri) {
        // A new document must not inherit the previous document's zoom or pan.
        scale = 1f
        offsetX = 0f
        offsetY = 0f

        // Load first. While isLoading is true, this screen intentionally does not compose the
        // LazyColumn, so scrolling its unattached LazyListState here can suspend indefinitely.
        viewModel.loadDocument(documentUri)
    }

    // Scroll only after a successful load has caused the LazyColumn to enter composition.
    LaunchedEffect(documentUri, uiState.isLoading, uiState.pageCount) {
        if (!uiState.isLoading && uiState.pageCount > 0) {
            listState.scrollToItem(0)
        }
    }

    // The renderer's openDocument() serializes and closes previous native renderer state itself.
    // Closing on every URI key change can race the subsequent load and close the new document.
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.closeDocument()
        }
    }

    // Monitor visible page for scrub bar sync
    val firstVisibleIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    LaunchedEffect(firstVisibleIndex) {
        viewModel.onPageScrolled(firstVisibleIndex)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.fileName,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .basicMarquee(iterations = Int.MAX_VALUE)
                                .clickable { onRenameClick(uiState.fileName) }
                                .testTag("viewer_rename_title"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {}
                )
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            scale = (scale / 1.25f).coerceAtLeast(1f)
                            if (scale <= 1f) { offsetX = 0f; offsetY = 0f }
                        },
                        enabled = scale > 1f
                    ) { Icon(Icons.Default.ZoomOut, contentDescription = stringResource(R.string.reset_zoom)) }
                    IconButton(onClick = { scale = (scale * 1.25f).coerceAtMost(5f) }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = stringResource(R.string.reset_zoom))
                    }
                    if (scale > 1.05f) {
                        IconButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                            Icon(Icons.Default.ZoomOutMap, contentDescription = stringResource(R.string.reset_zoom))
                        }
                    }
                    IconButton(
                        onClick = {
                            val target = (uiState.currentPageIndex - 1).coerceAtLeast(0)
                            viewModel.onPageScrolled(target)
                            coroutineScope.launch { listState.scrollToItem(target) }
                        },
                        enabled = uiState.currentPageIndex > 0
                    ) { Icon(Icons.Default.NavigateBefore, contentDescription = "Previous page") }
                    Text(
                        stringResource(R.string.page_position, uiState.currentPageIndex + 1, uiState.pageCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = {
                            val target = (uiState.currentPageIndex + 1).coerceAtMost(uiState.pageCount - 1)
                            viewModel.onPageScrolled(target)
                            coroutineScope.launch { listState.scrollToItem(target) }
                        },
                        enabled = uiState.pageCount > 0 && uiState.currentPageIndex < uiState.pageCount - 1
                    ) { Icon(Icons.Default.NavigateNext, contentDescription = "Next page") }
                    if (!isManagedScan) {
                        IconButton(onClick = onShareDocument) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_pdf), tint = VibeSyncCrimsonPrimary)
                        }
                    }
                    IconButton(onClick = { onNavigateToEditor(documentUri) }) {
                        Icon(Icons.Default.Draw, contentDescription = stringResource(R.string.edit_sign_action), tint = VibeSyncCrimsonPrimary)
                    }
                    IconButton(
                        onClick = {
                            if (isPreparingPrint) return@IconButton
                            isPreparingPrint = true
                            val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                                .filterIsInstance<Activity>()
                                .firstOrNull()
                            if (activity == null) {
                                isPreparingPrint = false
                                return@IconButton
                            }
                            val jobName = context.getString(R.string.print_job_name, uiState.fileName)
                            val attributes = PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .setResolution(PrintAttributes.Resolution("pdf_print", "PDF print", 300, 300))
                                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                                .build()
                            val sourceFile = if (documentUri.scheme == "file") documentUri.path?.let(::File) else null
                            if (sourceFile != null && sourceFile.isFile && sourceFile.canRead() && sourceFile.length() > 0L) {
                                var preparedFile: File? = null
                                try {
                                    preparedFile = UriUtils.copyUriToCacheFile(context, documentUri, "print_ready").also { file ->
                                        if (!file.isFile || !file.canRead() || file.length() <= 0L) {
                                            file.delete()
                                            throw IOException("The selected PDF is not ready for printing.")
                                        }
                                    }
                                    viewModel.setPreparedPrintFile(preparedFile)
                                    viewModel.printDocument(activity, jobName, attributes)
                                    preparedFile = null
                                } catch (error: Exception) {
                                    preparedFile?.delete()
                                    android.widget.Toast.makeText(
                                        context,
                                        error.localizedMessage ?: context.getString(R.string.print_failed),
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } finally {
                                    isPreparingPrint = false
                                }
                            } else {
                                coroutineScope.launch {
                                    var preparedFile: File? = null
                                    try {
                                        preparedFile = withContext(Dispatchers.IO) {
                                            UriUtils.copyUriToCacheFile(context, documentUri, "print_ready").also { file ->
                                                if (!file.isFile || !file.canRead() || file.length() <= 0L) {
                                                    file.delete()
                                                    throw IOException("The selected PDF is not ready for printing.")
                                                }
                                            }
                                        }
                                        viewModel.setPreparedPrintFile(requireNotNull(preparedFile))
                                        viewModel.printDocument(activity, jobName, attributes)
                                        preparedFile = null
                                    } catch (error: Exception) {
                                        preparedFile?.delete()
                                        android.widget.Toast.makeText(
                                            context,
                                            error.localizedMessage ?: context.getString(R.string.print_failed),
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        isPreparingPrint = false
                                    }
                                }
                            }
                        },
                        enabled = !isPreparingPrint,
                        modifier = Modifier.testTag("viewer_print")
                    ) {
                        if (isPreparingPrint) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Print, contentDescription = stringResource(R.string.print_action))
                    }
                    ExportMenu(sourceUri = documentUri, currentPageIndex = uiState.currentPageIndex, testTag = "viewer_export_menu")
                    if (isManagedScan) {
                        Box {
                            IconButton(onClick = { scanMenuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.scan_action_menu))
                            }
                            DropdownMenu(expanded = scanMenuExpanded, onDismissRequest = { scanMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_pdf)) },
                                    leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                                    onClick = { scanMenuExpanded = false; onShareScanPdf() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.scan_share_images)) },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                    onClick = { scanMenuExpanded = false; onShareScanImages() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.scan_save_downloads)) },
                                    leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                                    onClick = { scanMenuExpanded = false; onSaveScanToDownloads() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.scan_rename)) },
                                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                                    onClick = {
                                        scanMenuExpanded = false
                                        renamedScanName = uiState.fileName.removeSuffix(".pdf").removeSuffix(".PDF")
                                        showRenameScanDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.scan_delete)) },
                                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                                    onClick = { scanMenuExpanded = false; showDeleteScanDialog = true }
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (uiState.pageCount > 1) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("1", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.page_position, uiState.currentPageIndex + 1, uiState.pageCount), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text("${uiState.pageCount}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = uiState.currentPageIndex.toFloat(),
                            onValueChange = { targetPage ->
                                val pageInt = targetPage.toInt()
                                viewModel.onPageScrolled(pageInt)
                                coroutineScope.launch {
                                    listState.scrollToItem(pageInt)
                                }
                            },
                            valueRange = 0f..(uiState.pageCount - 1).toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = VibeSyncCrimsonPrimary,
                                activeTrackColor = VibeSyncCrimsonPrimary
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFE2E8F0))
                .pointerInput(documentUri) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var previousCentroid: Offset? = null
                        var previousDistance = 0f
                        var previousPointerCount = 0

                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            val centroid = Offset(
                                pressed.map { it.position.x }.average().toFloat(),
                                pressed.map { it.position.y }.average().toFloat()
                            )
                            val distance = if (pressed.size > 1) {
                                sqrt(
                                    pressed.sumOf { change ->
                                        val dx = (change.position.x - centroid.x).toDouble()
                                        val dy = (change.position.y - centroid.y).toDouble()
                                        dx * dx + dy * dy
                                    } / pressed.size
                                ).toFloat()
                            } else 0f

                            val priorCentroid = previousCentroid
                            if (priorCentroid != null && previousPointerCount == pressed.size) {
                                val pan = centroid - priorCentroid
                                val zoomFactor = if (pressed.size > 1 && previousDistance > 0f) {
                                    distance / previousDistance
                                } else 1f
                                val shouldHandleGesture = scale > 1f || pressed.size > 1
                                if (shouldHandleGesture) {
                                    val previousScale = scale
                                    val nextScale = (previousScale * zoomFactor).coerceIn(1f, 5f)
                                    val scaleFactor = nextScale / previousScale
                                    scale = nextScale
                                    if (nextScale > 1f) {
                                        // Keep the content beneath the pinch centroid stationary.
                                        val focusX = centroid.x - size.width / 2f
                                        val focusY = centroid.y - size.height / 2f
                                        val maxOffsetX = size.width * (nextScale - 1f) / 2f
                                        val maxOffsetY = size.height * (nextScale - 1f) / 2f
                                        offsetX = ((offsetX - focusX) * scaleFactor + focusX + pan.x)
                                            .coerceIn(-maxOffsetX, maxOffsetX)
                                        offsetY = ((offsetY - focusY) * scaleFactor + focusY + pan.y)
                                            .coerceIn(-maxOffsetY, maxOffsetY)
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }

                            previousCentroid = centroid
                            previousDistance = distance
                            previousPointerCount = pressed.size
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(documentUri) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2f
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    )
                }
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = VibeSyncCrimsonPrimary
                )
            } else if (uiState.errorMessage != null) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(stringResource(R.string.error_opening_pdf), fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(uiState.errorMessage ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!uiState.isPasswordRequired) {
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = { viewModel.retryLastDocument() }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("pdf_page_list")
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        ),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    userScrollEnabled = scale <= 1f
                ) {
                    items(
                        count = uiState.pageCount,
                        key = { pageIndex -> "${documentUri}#${pageIndex}" }
                    ) { pageIndex ->
                        val metadata = uiState.pagesMetadata.getOrNull(pageIndex)
                        val aspectRatio = metadata?.aspectRatio ?: (595f / 842f)

                        PdfPageItem(
                            pageIndex = pageIndex,
                            aspectRatio = aspectRatio,
                            viewModel = viewModel,
                            documentKey = documentUri.toString()
                        )
                    }
                }
            }
        }
    }

    if (isPreparingPrint) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = VibeSyncCrimsonPrimary)
                    Text("Preparing PDF for printing…", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (isManagedScan && showRenameScanDialog) {
        AlertDialog(
            onDismissRequest = { showRenameScanDialog = false },
            title = { Text(stringResource(R.string.scan_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renamedScanName,
                    onValueChange = { renamedScanName = it },
                    label = { Text(stringResource(R.string.scan_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("viewer_rename_field")
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renamedScanName.isNotBlank(),
                    onClick = {
                        showRenameScanDialog = false
                        onRenameScan(renamedScanName.trim())
                    }
                ) { Text(stringResource(R.string.continue_label)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameScanDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (isManagedScan && showDeleteScanDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteScanDialog = false },
            title = { Text(stringResource(R.string.scan_delete_title)) },
            text = { Text(stringResource(R.string.scan_delete_message, uiState.fileName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteScanDialog = false
                        onDeleteScan()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.scan_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteScanDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Password Protected PDF Unlock Dialog
    if (uiState.isPasswordRequired) {
        var enteredPassword by rememberSaveable { mutableStateOf("") }
        var passwordVisible by rememberSaveable { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { viewModel.dismissPasswordDialog() },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = VibeSyncCrimsonPrimary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(stringResource(R.string.password_protected_pdf), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.password_protected_message),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = enteredPassword,
                        onValueChange = { enteredPassword = it },
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                            val description = if (passwordVisible) stringResource(R.string.hide_password) else stringResource(R.string.show_password)
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(imageVector = image, contentDescription = description)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.submitPassword(enteredPassword) },
                    enabled = enteredPassword.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
                ) {
                    Text(stringResource(R.string.unlock))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissPasswordDialog()
                    onNavigateBack()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * Explicit rendering states ensure a cache miss is a single loading attempt, not an implicit loop.
 * The lease is retained independently of recompositions and released only when the item is disposed
 * or a deliberate retry replaces the previous attempt.
 */
sealed interface PageRenderState {
    data object Loading : PageRenderState
    data class Success(val lease: BitmapCache.BitmapLease) : PageRenderState
    data class Error(val cause: Throwable? = null) : PageRenderState
}

private class PageBitmapLeaseHolder {
    private var lease: BitmapCache.BitmapLease? = null
    private var disposed = false

    @Synchronized
    fun replace(next: BitmapCache.BitmapLease?): Boolean {
        if (disposed) {
            next?.close()
            return false
        }
        val previous = lease
        lease = next
        if (previous !== next) previous?.close()
        return true
    }

    @Synchronized
    fun close() {
        if (disposed) return
        disposed = true
        lease?.close()
        lease = null
    }
}

@Composable
fun PdfPageItem(
    pageIndex: Int,
    aspectRatio: Float,
    viewModel: PdfViewerViewModel,
    documentKey: String
) {
    // LazyColumn item identity + explicit effect keys make scrolling/recomposition alone unable
    // to restart a render. The retry counter changes only after a user presses Retry.
    var retryAttempt by remember(documentKey, pageIndex) { mutableIntStateOf(0) }
    var pageState by remember(documentKey, pageIndex) {
        mutableStateOf<PageRenderState>(PageRenderState.Loading)
    }
    val leaseHolder = remember(documentKey, pageIndex) { PageBitmapLeaseHolder() }

    DisposableEffect(leaseHolder) {
        onDispose { leaseHolder.close() }
    }

    LaunchedEffect(documentKey, pageIndex, retryAttempt) {
        leaseHolder.replace(null)
        pageState = PageRenderState.Loading
        try {
            val lease = viewModel.acquirePageBitmap(pageIndex, scale = PAGE_RENDER_SCALE)
            if (lease == null) {
                pageState = PageRenderState.Error()
            } else if (lease.bitmap.isRecycled) {
                lease.close()
                pageState = PageRenderState.Error()
            } else if (leaseHolder.replace(lease)) {
                pageState = PageRenderState.Success(lease)
            } else {
                pageState = PageRenderState.Error()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: OutOfMemoryError) {
            pageState = PageRenderState.Error(error)
        } catch (error: Exception) {
            pageState = PageRenderState.Error(error)
        }
    }

    val bitmap = (pageState as? PageRenderState.Success)?.lease?.bitmap
    val bitmapReady = bitmap != null && !bitmap.isRecycled
    val renderFailed = pageState is PageRenderState.Error ||
        (pageState is PageRenderState.Success && !bitmapReady)
    val safeAspectRatio = aspectRatio
        .takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(0.1f, 10f)
        ?: (595f / 842f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(safeAspectRatio)
            .shadow(4.dp, RoundedCornerShape(4.dp)),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        when {
            bitmapReady -> {
                val bitmapForCompose = bitmap?.takeUnless { it.isRecycled }
                if (bitmapForCompose != null && !bitmapForCompose.isRecycled) {
                    Image(
                        bitmap = bitmapForCompose.asImageBitmap(),
                        contentDescription = stringResource(R.string.pdf_page_description, pageIndex + 1),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.page_render_failed), fontSize = 12.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 2.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        text = stringResource(R.string.pdf_brand_watermark),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        color = Color(0xFF8E0000).copy(alpha = 0.55f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            renderFailed -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.page_render_failed),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { retryAttempt += 1 }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = VibeSyncCrimsonPrimary
                    )
                }
            }
        }
    }
}

private const val PAGE_RENDER_SCALE = 2.0f
