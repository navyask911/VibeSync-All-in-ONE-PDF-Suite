package com.vibesync.toolkit.pdf.presentation.viewer

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // Start loading without waiting for the LazyColumn to be attached. During initial
    // loading the list is not composed, so scrollToItem() here can suspend forever and prevent
    // loadDocument() from ever running.
    LaunchedEffect(documentUri) {
        Log.i(
            "PdfDebug",
            "Viewer load effect reached: scheme=${documentUri.scheme ?: "unknown"}, " +
                "authorityPresent=${documentUri.authority != null}"
        )
        viewModel.loadDocument(documentUri)
    }

    // Reset scroll only after the loaded document has caused the LazyColumn to be composed.
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
                    // Zoom reset
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
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1.0f, 4.0f) // 100% to 400% zoom
                        if (scale > 1f) {
                            val maxOffsetX = (size.width * (scale - 1)) / 2
                            val maxOffsetY = (size.height * (scale - 1)) / 2
                            offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("pdf_document_loading"),
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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
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
    var renderTimedOut by remember(documentKey, pageIndex) { mutableStateOf(false) }

    DisposableEffect(leaseHolder) {
        onDispose { leaseHolder.close() }
    }

    LaunchedEffect(documentKey, pageIndex, retryAttempt) {
        renderTimedOut = false
        leaseHolder.replace(null)
        pageState = PageRenderState.Loading
        try {
            val lease = viewModel.acquirePageBitmap(pageIndex, scale = PAGE_RENDER_SCALE)
            // A watchdog may have already published a terminal error while native rendering was
            // blocked. Never let a late completion overwrite that state or leak its bitmap lease.
            if (renderTimedOut || pageState !is PageRenderState.Loading) {
                lease?.close()
                return@LaunchedEffect
            }
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
            if (pageState is PageRenderState.Loading) pageState = PageRenderState.Error(error)
        } catch (error: Exception) {
            if (pageState is PageRenderState.Loading) pageState = PageRenderState.Error(error)
        }
    }

    LaunchedEffect(documentKey, pageIndex, retryAttempt, pageState is PageRenderState.Loading) {
        if (pageState !is PageRenderState.Loading) return@LaunchedEffect
        delay(PAGE_RENDER_WATCHDOG_MS)
        if (pageState is PageRenderState.Loading) {
            renderTimedOut = true
            leaseHolder.replace(null)
            pageState = PageRenderState.Error()
            Log.e(
                "PdfViewerScreen",
                "page.render_watchdog_timeout documentKeyHash=${documentKey.hashCode()} " +
                    "page=$pageIndex timeoutMs=$PAGE_RENDER_WATCHDOG_MS"
            )
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
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("pdf_page_loading"),
                        strokeWidth = 2.dp,
                        color = VibeSyncCrimsonPrimary
                    )
                }
            }
        }
    }
}

private const val PAGE_RENDER_SCALE = 1.5f
private const val PAGE_RENDER_WATCHDOG_MS = 90_000L
