package com.vibesync.toolkit.pdf.presentation.dashboard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.testTag
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncCrimsonPrimary
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncRubyAccent
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncRubyDeep
import com.vibesync.toolkit.pdf.presentation.monetization.BannerAd
import com.vibesync.toolkit.pdf.core.util.UriUtils
import kotlinx.coroutines.launch

private const val VIBESYNC_PUBLIC_INFO_URL = "https://github.com/navyask911/VibeSync-All-in-ONE-PDF-Suite-Read-Me"
private const val VIBESYNC_PRIVACY_URL = "https://github.com/navyask911/VibeSync-All-in-ONE-PDF-Suite-Read-Me/blob/main/PRIVACY_POLICY.md"
private const val VIBESYNC_LICENSE_URL = "https://github.com/navyask911/VibeSync-All-in-ONE-PDF-Suite-Read-Me/blob/main/LICENSE"
private const val VIBESYNC_TERMS_URL = "https://github.com/navyask911/VibeSync-All-in-ONE-PDF-Suite-Read-Me/blob/main/TERMS.md"
private const val PDF_SECTION_PREFERENCES = "dashboard_pdf_section_preferences"
private const val KEY_PDF_SECTION_VISIBLE = "pdf_section_visible"

data class ToolItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val route: String,
    val color: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToViewer: (Uri) -> Unit,
    onNavigateToEditor: (Uri) -> Unit,
    onNavigateToTool: (String) -> Unit,
    onLaunchScanner: () -> Unit,
    onRequestStoragePermission: () -> Unit = {},
    onSelectLanguage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAllPdfs by remember { mutableStateOf(false) }
    var isPdfSectionVisible by remember(context) { mutableStateOf(context.getSharedPreferences(PDF_SECTION_PREFERENCES, android.content.Context.MODE_PRIVATE).getBoolean(KEY_PDF_SECTION_VISIBLE, true)) }
    val visiblePdfs = remember(uiState.discoveredPdfs, showAllPdfs) { if (showAllPdfs) uiState.discoveredPdfs else uiState.discoveredPdfs.take(10) }

    fun launchPickerSafely(action: () -> Unit) {
        try {
            action()
        } catch (_: ActivityNotFoundException) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.picker_unavailable))
            }
        } catch (_: SecurityException) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.folder_access_denied))
            }
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    // SAF document pickers return content URIs; persist read access and do provider work off Main.
    val viewerPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val persisted = UriUtils.persistReadPermission(context, it)
            if (!persisted) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.folder_permission_not_persisted))
                }
            }
            viewModel.onDocumentSelected(it, onNavigateToViewer)
        }
    }

    val editorPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val persisted = UriUtils.persistReadPermission(context, it)
            if (!persisted) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.folder_permission_not_persisted))
                }
            }
            onNavigateToEditor(it)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let {
            val persisted = UriUtils.persistReadPermission(context, it)
            if (!persisted) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(context.getString(R.string.folder_permission_not_persisted))
                }
            }
            viewModel.scanSelectedFolder(it)
        }
    }

    val tools = remember {
        listOf(
            ToolItem(context.getString(R.string.tool_view_read), context.getString(R.string.tool_view_read_subtitle), Icons.Default.MenuBook, "viewer_picker", VibeSyncCrimsonPrimary),
            ToolItem(context.getString(R.string.tool_edit_sign), context.getString(R.string.tool_edit_sign_subtitle), Icons.Default.Draw, "editor_picker", VibeSyncRubyAccent),
            ToolItem(context.getString(R.string.tool_document_scanner), context.getString(R.string.tool_document_scanner_subtitle), Icons.Default.DocumentScanner, "scanner", Color(0xFFD32F2F)),
            ToolItem(context.getString(R.string.tool_split_document), context.getString(R.string.tool_split_document_subtitle), Icons.Default.CallSplit, "split", Color(0xFFC62828)),
            ToolItem(context.getString(R.string.tool_merge_documents), context.getString(R.string.tool_merge_documents_subtitle), Icons.Default.MergeType, "merge", Color(0xFF8E0000)),
            ToolItem(context.getString(R.string.tool_images_to_pdf), context.getString(R.string.tool_images_to_pdf_subtitle), Icons.Default.AddPhotoAlternate, "convert", Color(0xFFB71C1C)),
            ToolItem(context.getString(R.string.tool_extract_images), context.getString(R.string.tool_extract_images_subtitle), Icons.Default.Image, "extract", Color(0xFFE53935)),
            ToolItem(context.getString(R.string.tool_encrypt_protect), context.getString(R.string.tool_encrypt_protect_subtitle), Icons.Default.Lock, "encrypt", Color(0xFF5F0000))
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 20.sp,
                                lineHeight = 22.sp,
                                letterSpacing = (-0.2).sp
                            ),
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(R.string.dashboard_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            lineHeight = 15.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { showPrivacyDialog = true }) {
                        Icon(Icons.Default.Security, contentDescription = stringResource(R.string.privacy_terms), tint = Color(0xFF10B981))
                    }
                    IconButton(onClick = { showLanguageDialog = true }) {
                        Icon(Icons.Default.Language, contentDescription = stringResource(R.string.language_settings), tint = VibeSyncCrimsonPrimary)
                    }
                    IconButton(onClick = { launchPickerSafely { folderPickerLauncher.launch(null) } }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = stringResource(R.string.choose_pdf_folder), tint = VibeSyncCrimsonPrimary)
                    }
                    IconButton(onClick = { viewModel.triggerScanRequest() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.scan_device_pdfs), tint = VibeSyncCrimsonPrimary)
                    }
                    IconButton(onClick = { launchPickerSafely { viewerPickerLauncher.launch(arrayOf("application/pdf")) } }) {
                        Icon(Icons.Default.FileOpen, contentDescription = stringResource(R.string.open_document), tint = VibeSyncCrimsonPrimary)
                    }
                }
            )
        },
        bottomBar = {
            BannerAd(modifier = Modifier.fillMaxWidth())
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { launchPickerSafely { viewerPickerLauncher.launch(arrayOf("application/pdf")) } },
                containerColor = VibeSyncCrimsonPrimary,
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.open_pdf)) }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("dashboard_list")
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Prominent Top Privacy & Data Safety Banner
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    color = Color(0xFF042117).copy(alpha = 0.9f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = stringResource(R.string.zero_cloud_upload_banner),
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.zero_cloud_upload_banner),
                                color = Color(0xFFECFDF5),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Text(
                                stringResource(R.string.zero_cloud_upload_body),
                                color = Color(0xFFA7F3D0),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // 2. Retail Hero Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.hero_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.hero_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }

            // 3. Core Toolkit Section
            item {
                Text(
                    stringResource(R.string.core_toolkit),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            // Grid of Tools
            items(tools.chunked(2)) { rowTools ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (tool in rowTools) {
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (tool.route == "scanner") {
                                        onLaunchScanner()
                                    } else if (tool.route == "viewer_picker") {
                                        launchPickerSafely { viewerPickerLauncher.launch(arrayOf("application/pdf")) }
                                    } else if (tool.route == "editor_picker") {
                                        launchPickerSafely { editorPickerLauncher.launch(arrayOf("application/pdf")) }
                                    } else {
                                        onNavigateToTool(tool.route)
                                    }
                                }
                                .testTag("tool_" + tool.route),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(tool.color.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(tool.icon, contentDescription = null, tint = tool.color, modifier = Modifier.size(24.dp))
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(tool.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    tool.subtitle,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 4. Compact, persistent PDF discovery section. Hiding this section never
            // changes permissions, the scanner index, or the underlying documents.
            if (!isPdfSectionVisible) {
                item(key = "show_pdfs_section") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = {
                                isPdfSectionVisible = true
                                showAllPdfs = false
                                context.getSharedPreferences(PDF_SECTION_PREFERENCES, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_PDF_SECTION_VISIBLE, true).apply()
                            },
                            modifier = Modifier.testTag("pdf_show_section")
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.pdfs_show_section))
                        }
                    }
                }
            } else {
                item(key = "pdf_section_header") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(if (uiState.isFolderScanResult) R.string.pdfs_in_selected_folder else R.string.pdf_recent_heading),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (uiState.discoveredPdfs.isEmpty()) stringResource(R.string.pdfs_indexed_count, 0)
                                else stringResource(R.string.pdfs_showing_count, visiblePdfs.size, uiState.discoveredPdfs.size),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (uiState.isScanning) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = VibeSyncCrimsonPrimary)
                        } else {
                            IconButton(onClick = { viewModel.refreshCurrentSource() }) {
                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.rescan), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(
                            onClick = {
                                isPdfSectionVisible = false
                                showAllPdfs = false
                                context.getSharedPreferences(PDF_SECTION_PREFERENCES, android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY_PDF_SECTION_VISIBLE, false).apply()
                            },
                            modifier = Modifier.testTag("pdf_section_close")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.pdfs_hide_section), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (uiState.discoveredPdfs.isEmpty() && !uiState.isScanning) {
                    item(key = "pdf_discovery_empty_state") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FindInPage, contentDescription = null, tint = VibeSyncCrimsonPrimary, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.automatic_pdf_discovery), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(stringResource(R.string.scan_storage_invitation), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { viewModel.triggerScanRequest() }, colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)) {
                                    Text(stringResource(R.string.scan_device_storage))
                                }
                            }
                        }
                    }
                } else {
                    items(items = visiblePdfs, key = { pdf -> pdf.uri.toString() }) { pdf ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onNavigateToViewer(pdf.uri) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = VibeSyncCrimsonPrimary, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pdf.name, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 13.sp)
                                    Text(stringResource(R.string.modified_date, pdf.sizeFormatted, pdf.formattedDate), style = MaterialTheme.typography.bodyMedium, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (uiState.discoveredPdfs.size > 10) {
                        item(key = "pdf_show_all_toggle") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                TextButton(onClick = { showAllPdfs = !showAllPdfs }, modifier = Modifier.testTag("pdf_show_all_toggle")) {
                                    Text(if (showAllPdfs) stringResource(R.string.pdfs_show_less) else stringResource(R.string.pdfs_show_all, uiState.discoveredPdfs.size))
                                }
                            }
                        }
                    }
                }
            }

            // 5. Recent Documents Section
            if (uiState.recentDocuments.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.recently_opened),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                items(uiState.recentDocuments) { doc ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToViewer(Uri.parse(doc.uriString)) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, tint = VibeSyncCrimsonPrimary, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(doc.fileName, fontWeight = FontWeight.Medium, maxLines = 1, fontSize = 13.sp)
                                Text(
                                    "${(doc.fileSize / 1024)} KB",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(60.dp))
            }
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.select_language)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val languageOptions = listOf(
                        "" to stringResource(R.string.language_system),
                        "en" to stringResource(R.string.language_english),
                        "es" to stringResource(R.string.language_spanish),
                        "fr" to stringResource(R.string.language_french),
                        "de" to stringResource(R.string.language_german),
                        "it" to stringResource(R.string.language_italian),
                        "pt" to stringResource(R.string.language_portuguese),
                        "ru" to stringResource(R.string.language_russian),
                        "ar" to stringResource(R.string.language_arabic),
                        "ja" to stringResource(R.string.language_japanese),
                        "ko" to stringResource(R.string.language_korean),
                        "zh-CN" to stringResource(R.string.language_chinese),
                        "zh-TW" to stringResource(R.string.language_chinese_traditional),
                        "id" to stringResource(R.string.language_indonesian),
                        "tr" to stringResource(R.string.language_turkish),
                        "hi" to stringResource(R.string.language_hindi),
                        "bn" to stringResource(R.string.language_bengali),
                        "ur" to stringResource(R.string.language_urdu),
                        "vi" to stringResource(R.string.language_vietnamese),
                        "ta" to stringResource(R.string.language_tamil),
                        "te" to stringResource(R.string.language_telugu),
                        "kn" to stringResource(R.string.language_kannada),
                        "mr" to stringResource(R.string.language_marathi),
                        "gu" to stringResource(R.string.language_gujarati),
                        "he" to stringResource(R.string.language_hebrew),
                        "th" to stringResource(R.string.language_thai),
                        "fil" to stringResource(R.string.language_filipino),
                        "nl" to stringResource(R.string.language_dutch),
                        "pl" to stringResource(R.string.language_polish),
                        "uk" to stringResource(R.string.language_ukrainian),
                        "fa" to stringResource(R.string.language_persian),
                        "sw" to stringResource(R.string.language_swahili),
                        "pa" to stringResource(R.string.language_punjabi),
                        "ml" to stringResource(R.string.language_malayalam),
                        "ne" to stringResource(R.string.language_nepali),
                        "si" to stringResource(R.string.language_sinhala),
                        "ro" to stringResource(R.string.language_romanian),
                        "el" to stringResource(R.string.language_greek),
                        "cs" to stringResource(R.string.language_czech),
                        "hu" to stringResource(R.string.language_hungarian),
                        "sv" to stringResource(R.string.language_swedish)
                    )
                    languageOptions.forEach { (languageTag, label) ->
                        TextButton(
                            onClick = {
                                onSelectLanguage(languageTag)
                                showLanguageDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Transparent Permission Request Explanatory Rationale Dialog
    if (uiState.showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPermissionRationale() },
            icon = {
                Icon(
                    imageVector = Icons.Default.FolderSpecial,
                    contentDescription = null,
                    tint = VibeSyncCrimsonPrimary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(stringResource(R.string.allow_device_pdf_access), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.device_access_rationale),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.local_query_guarantee), fontSize = 11.sp, color = Color(0xFF10B981), fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissPermissionRationale()
                        onRequestStoragePermission()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
                ) {
                    Text(stringResource(R.string.grant_access))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        viewModel.dismissPermissionRationale()
                        launchPickerSafely { viewerPickerLauncher.launch(arrayOf("application/pdf")) }
                    }
                ) {
                    Text(stringResource(R.string.select_files_manually))
                }
            }
        )
    }

    // In-App Privacy & Terms Dialog
    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(stringResource(R.string.privacy_data_safety_guarantee), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = Color(0xFF042117),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.device_zero_cloud_transit), fontWeight = FontWeight.Bold, color = Color(0xFFECFDF5), fontSize = 12.sp)
                        }
                    }
                    Text(
                        stringResource(R.string.document_processing_policy),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.signature_storage_policy),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.ads_collection_policy),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("privacy_legal_links"),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VIBESYNC_PUBLIC_INFO_URL))) }
                            },
                            modifier = Modifier.testTag("privacy_public_info")
                        ) { Text(stringResource(R.string.public_info)) }
                        TextButton(
                            onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VIBESYNC_PRIVACY_URL))) }
                            },
                            modifier = Modifier.testTag("privacy_policy_link")
                        ) { Text(stringResource(R.string.privacy_policy)) }
                        TextButton(
                            onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VIBESYNC_LICENSE_URL))) }
                            },
                            modifier = Modifier.testTag("privacy_license_link")
                        ) { Text(stringResource(R.string.license_copyright)) }
                        TextButton(
                            onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VIBESYNC_TERMS_URL))) }
                            },
                            modifier = Modifier.testTag("privacy_terms_link")
                        ) { Text(stringResource(R.string.terms_of_use)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showPrivacyDialog = false },
                    modifier = Modifier.testTag("privacy_close_button")
                ) { Text(stringResource(R.string.close)) }
            }
        )
    }
}
