package com.vibesync.toolkit.pdf.presentation.editor

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncCrimsonPrimary
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncRubyDeep
import com.vibesync.toolkit.pdf.data.model.ToolMode
import com.vibesync.toolkit.pdf.presentation.monetization.AdMobManager
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfEditorScreen(
    documentUri: Uri,
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit,
    onExportComplete: (File) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val uiState by viewModel.uiState.collectAsState()

    var showSignatureDialog by remember { mutableStateOf(false) }
    var showSignatureManagerDialog by remember { mutableStateOf(false) }
    var showTextInputDialog by remember { mutableStateOf(false) }
    var pendingTapOffset by remember { mutableStateOf(Offset.Zero) }
    var textInputState by remember { mutableStateOf("") }
    var screenWidthPx by remember { mutableFloatStateOf(1080f) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(documentUri) {
        viewModel.initEditor(documentUri)
        AdMobManager.preloadInterstitial(context)
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.editor_annotate_sign),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.undo() }, enabled = uiState.canUndo) {
                        Icon(Icons.Default.Undo, contentDescription = stringResource(R.string.editor_undo))
                    }
                    IconButton(onClick = { viewModel.redo() }, enabled = uiState.canRedo) {
                        Icon(Icons.Default.Redo, contentDescription = stringResource(R.string.editor_redo))
                    }
                    IconButton(onClick = { showSignatureManagerDialog = true }, modifier = Modifier.testTag("editor_saved_signatures")) {
                        Icon(Icons.Default.Draw, contentDescription = stringResource(R.string.signature_manager_title))
                    }
                    Button(
                        onClick = {
                            viewModel.saveFlattenedDocument(screenWidthPx) { savedFile ->
                                (context as? Activity)?.let { activity ->
                                    AdMobManager.showInterstitial(activity) {
                                        onExportComplete(savedFile)
                                    }
                                } ?: onExportComplete(savedFile)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.editor_flatten_save))
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().shadow(8.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Tool Palette Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ToolIconButton(
                            icon = Icons.Default.Edit,
                            label = stringResource(R.string.editor_pen),
                            testTag = "Pen",
                            isSelected = uiState.toolMode == ToolMode.PEN,
                            onClick = { viewModel.selectTool(ToolMode.PEN) }
                        )
                        ToolIconButton(
                            icon = Icons.Default.BorderColor,
                            label = stringResource(R.string.editor_highlighter),
                            testTag = "Highlighter",
                            isSelected = uiState.toolMode == ToolMode.HIGHLIGHTER,
                            onClick = { viewModel.selectTool(ToolMode.HIGHLIGHTER) }
                        )
                        ToolIconButton(
                            icon = Icons.Default.TextFields,
                            label = stringResource(R.string.editor_text),
                            testTag = "Text",
                            isSelected = uiState.toolMode == ToolMode.TEXT,
                            onClick = { viewModel.selectTool(ToolMode.TEXT) }
                        )
                        ToolIconButton(
                            icon = Icons.Default.Draw,
                            label = stringResource(R.string.editor_sign),
                            testTag = "Sign",
                            isSelected = uiState.toolMode == ToolMode.SIGNATURE,
                            onClick = {
                                viewModel.selectTool(ToolMode.SIGNATURE)
                                showSignatureDialog = true
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Color Palette Selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val palette = listOf(
                            Color(0xFF8F00FF), // Violet
                            Color(0xFF4B0082), // Indigo
                            Color(0xFF0000FF), // Blue
                            Color(0xFF008000), // Green
                            Color(0xFFFFFF00), // Yellow
                            Color(0xFFFFA500), // Orange
                            Color(0xFFFF0000), // Red
                            Color(0xFF000000)  // Black
                        )

                        palette.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (uiState.selectedColor == color) 3.dp else 1.dp,
                                        color = if (uiState.selectedColor == color) VibeSyncCrimsonPrimary else Color.Gray,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.selectColor(color) }
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFCBD5E1)),
            contentAlignment = Alignment.Center
        ) {
            val pageBitmapState = produceState<Bitmap?>(
                initialValue = null,
                key1 = uiState.currentPageIndex,
                key2 = uiState.documentLoadedRevision
            ) {
                value = viewModel.getPageBitmap(uiState.currentPageIndex)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .aspectRatio(595f / 842f)
                    .testTag("editor_pdf_page") // Standard A4 Aspect Ratio
                    .shadow(8.dp, RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .onGloballyPositioned { coordinates ->
                        screenWidthPx = coordinates.size.width.toFloat()
                    }
            ) {
                val bitmap = pageBitmapState.value
                if (bitmap != null && !bitmap.isRecycled) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.editor_page_canvas),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }

                // Render placed text annotations
                for (textItem in uiState.currentTextAnnotations) {
                    Text(
                        text = textItem.text,
                        color = Color(textItem.colorArgb),
                        fontSize = textItem.fontSizeSp.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.offset { IntOffset(textItem.x.roundToInt(), textItem.y.roundToInt()) }
                    )
                }

                // Render placed signatures
                for (sig in uiState.currentSignatures) {
                    sig.bitmap?.let { sigBmp ->
                        val dragOffset = remember(sig.id) { mutableStateOf(Offset.Zero) }
                        Image(
                            bitmap = sigBmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.editor_placed_signature),
                            modifier = Modifier
                                .zIndex(1f)
                                .offset {
                                    IntOffset(
                                        (sig.x + dragOffset.value.x).roundToInt(),
                                        (sig.y + dragOffset.value.y).roundToInt()
                                    )
                                }
                                .size(
                                    width = with(density) { sig.width.toDp() },
                                    height = with(density) { sig.height.toDp() }
                                )
                                .pointerInput(sig.id) {
                                    detectDragGestures(
                                        onDragEnd = {
                                            val delta = dragOffset.value
                                            viewModel.moveSignature(sig.id, delta.x, delta.y)
                                            dragOffset.value = Offset.Zero
                                        },
                                        onDragCancel = { dragOffset.value = Offset.Zero },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffset.value += dragAmount
                                        }
                                    )
                                }
                        )
                    }
                }

                Text(
                    text = "VibeSync All in ONE PDF Suite",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    color = Color(0xFF8E0000).copy(alpha = 0.55f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold
                )

                // Interactive Vector Canvas Overlay
                VectorCanvasOverlay(
                    modifier = Modifier.testTag("editor_canvas_overlay"),
                    toolMode = uiState.toolMode,
                    strokeColor = uiState.selectedColor,
                    strokeWidth = uiState.strokeWidth,
                    existingStrokes = uiState.currentStrokes,
                    onStrokeAdded = { stroke -> viewModel.addStroke(stroke) },
                    onTap = { offset ->
                        if (uiState.toolMode == ToolMode.TEXT) {
                            pendingTapOffset = offset
                            showTextInputDialog = true
                        }
                    }
                )
            }
        }
    }

    // Signature Pad Modal Dialog
    if (showSignatureDialog) {
        SignaturePadDialog(
            onDismissRequest = { showSignatureDialog = false },
            onSignatureCaptured = { bitmap ->
                showSignatureDialog = false
                val signatureWidthPx = 160f
                val signatureHeightPx = 80f
                val pageHeightPx = screenWidthPx * 842f / 595f
                viewModel.addSignature(
                    bitmap,
                    x = ((screenWidthPx - signatureWidthPx) / 2f).coerceAtLeast(0f),
                    y = ((pageHeightPx - signatureHeightPx) / 2f).coerceAtLeast(0f),
                    width = signatureWidthPx,
                    height = signatureHeightPx
                )
            }
        )
    }

    if (showSignatureManagerDialog) {
        Dialog(onDismissRequest = { showSignatureManagerDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp)
            ) {
                SignatureManagerScreen(
                    onNavigateBack = { showSignatureManagerDialog = false },
                    onSelectSignature = { bitmap, x, y, width, height, rotation ->
                        viewModel.addSignature(bitmap, x, y, width, height, rotation, saveToVault = false)
                        showSignatureManagerDialog = false
                    }
                )
            }
        }
    }

    // Text Input Modal Dialog
    if (showTextInputDialog) {
        AlertDialog(
            onDismissRequest = { showTextInputDialog = false },
            title = { Text(stringResource(R.string.editor_add_text_note)) },
            text = {
                OutlinedTextField(
                    value = textInputState,
                    onValueChange = { textInputState = it },
                    placeholder = { Text(stringResource(R.string.editor_enter_annotation)) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (textInputState.isNotBlank()) {
                        viewModel.addTextAnnotation(textInputState, pendingTapOffset.x, pendingTapOffset.y)
                        textInputState = ""
                    }
                    showTextInputDialog = false
                }) {
                    Text(stringResource(R.string.editor_place_text))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextInputDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ToolIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    testTag: String = label,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) VibeSyncCrimsonPrimary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onClick() }
            .testTag("editor_tool_$testTag")
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (isSelected) VibeSyncCrimsonPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) VibeSyncCrimsonPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
