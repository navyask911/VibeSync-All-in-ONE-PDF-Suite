package com.vibesync.toolkit.pdf.presentation.viewer

import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.vibesync.toolkit.pdf.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    documentUri: Uri,
    viewModel: PdfViewerViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToEditor: (Uri) -> Unit,
    onShareDocument: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(documentUri) {
        // A new document must not inherit the previous document's zoom or pan.
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        listState.scrollToItem(0)
        viewModel.loadDocument(documentUri)
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
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.fileName, maxLines = 1, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (uiState.pageCount > 0) {
                            Text(
                                stringResource(R.string.page_position, uiState.currentPageIndex + 1, uiState.pageCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scale = (scale / 1.25f).coerceAtLeast(1f)
                            if (scale <= 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        },
                        enabled = scale > 1f
                    ) {
                        Icon(Icons.Default.ZoomOut, contentDescription = stringResource(R.string.reset_zoom))
                    }
                    IconButton(onClick = {
                        scale = (scale * 1.25f).coerceAtMost(4f)
                    }) {
                        Icon(Icons.Default.ZoomIn, contentDescription = stringResource(R.string.reset_zoom))
                    }
                    if (scale > 1.05f) {
                        IconButton(onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }) {
                            Icon(Icons.Default.ZoomOutMap, contentDescription = stringResource(R.string.reset_zoom))
                        }
                    }
                    // Jump to Editor / Signer
                    IconButton(onClick = onShareDocument) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_pdf), tint = VibeSyncCrimsonPrimary)
                    }
                    IconButton(onClick = { onNavigateToEditor(documentUri) }) {
                        Icon(Icons.Default.Draw, contentDescription = stringResource(R.string.edit_sign_action), tint = VibeSyncCrimsonPrimary)
                    }
                }
            )
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
                                    val nextScale = (previousScale * zoomFactor).coerceIn(1f, 4f)
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
                        .then(Modifier)
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
                Image(
                    bitmap = checkNotNull(bitmap).asImageBitmap(),
                    contentDescription = stringResource(R.string.pdf_page_description, pageIndex + 1),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
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
