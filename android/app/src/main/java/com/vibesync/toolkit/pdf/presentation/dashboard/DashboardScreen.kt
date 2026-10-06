package com.vibesync.toolkit.pdf.presentation.dashboard

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.platform.testTag
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncCrimsonPrimary
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncRubyAccent
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncRubyDeep
import com.vibesync.toolkit.pdf.presentation.monetization.BannerAd

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
    onRequestStoragePermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showPrivacyDialog by remember { mutableStateOf(false) }

    // SAF Document Pickers: keep View & Read and Edit & Sign as separate actions.
    val viewerPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.onDocumentSelected(it, onNavigateToViewer) }
    }

    val editorPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onNavigateToEditor(it) }
    }

    val tools = remember {
        listOf(
            ToolItem("View & Read", "Continuous scroll & 400% zoom", Icons.Default.MenuBook, "viewer_picker", VibeSyncCrimsonPrimary),
            ToolItem("Edit & Sign", "Vector pen, highlighter, e-sign", Icons.Default.Draw, "editor_picker", VibeSyncRubyAccent),
            ToolItem("Document Scanner", "AI perspective warp & auto PDF", Icons.Default.DocumentScanner, "scanner", Color(0xFFD32F2F)),
            ToolItem("Split Document", "Extract custom page ranges", Icons.Default.CallSplit, "split", Color(0xFFC62828)),
            ToolItem("Merge Documents", "Drag-and-drop file combine", Icons.Default.MergeType, "merge", Color(0xFF8E0000)),
            ToolItem("Images to PDF", "Compile gallery photos to A4", Icons.Default.AddPhotoAlternate, "convert", Color(0xFFB71C1C)),
            ToolItem("Extract Images", "Dump embedded PNG/JPEGs", Icons.Default.Image, "extract", Color(0xFFE53935)),
            ToolItem("Encrypt & Protect", "AES-128 standard encryption", Icons.Default.Lock, "encrypt", Color(0xFF5F0000))
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VibeSync All in ONE PDF Suite", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Professional On-Device PDF Architecture", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = { showPrivacyDialog = true }) {
                        Icon(Icons.Default.Security, contentDescription = "Privacy & Terms", tint = Color(0xFF10B981))
                    }
                    IconButton(onClick = { viewModel.triggerScanRequest() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan Device PDFs", tint = VibeSyncCrimsonPrimary)
                    }
                    IconButton(onClick = { viewerPickerLauncher.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Open Document", tint = VibeSyncCrimsonPrimary)
                    }
                }
            )
        },
        bottomBar = {
            BannerAd(modifier = Modifier.fillMaxWidth())
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewerPickerLauncher.launch(arrayOf("application/pdf")) },
                containerColor = VibeSyncCrimsonPrimary,
                contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Open PDF") }
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
                                contentDescription = "Zero Cloud Upload",
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "🛡️ All files are stored strictly on your device only. Zero cloud upload.",
                                color = Color(0xFFECFDF5),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Text(
                                "Hardware-backed offline encryption. Your PDFs never leave this phone.",
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
                                "100% On-Device PDF Suite",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Local PDFBox execution, ML Kit document perspective warping, and MasterKey encryption.",
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
                    "CORE TOOLKIT",
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
                                        viewerPickerLauncher.launch(arrayOf("application/pdf"))
                                    } else if (tool.route == "editor_picker") {
                                        editorPickerLauncher.launch(arrayOf("application/pdf"))
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

            // 4. "All PDFs on Device" Automatic Discovery Section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "ALL PDFS ON DEVICE",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${uiState.discoveredPdfs.size} documents indexed via MediaStore",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (uiState.isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = VibeSyncCrimsonPrimary)
                    } else {
                        IconButton(onClick = { viewModel.refreshDevicePdfs() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Discovered PDFs List or Discovery Invitation
            if (uiState.discoveredPdfs.isEmpty() && !uiState.isScanning) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.FindInPage, contentDescription = null, tint = VibeSyncCrimsonPrimary, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Automatic PDF Discovery", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                "Scan device storage to list all your PDFs in one place.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.triggerScanRequest() },
                                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
                            ) {
                                Text("Scan Device Storage")
                            }
                        }
                    }
                }
            } else {
                items(uiState.discoveredPdfs) { pdf ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToViewer(pdf.uri) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = VibeSyncCrimsonPrimary, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pdf.name, fontWeight = FontWeight.SemiBold, maxLines = 1, fontSize = 13.sp)
                                Text(
                                    "${pdf.sizeFormatted} • Modified ${pdf.formattedDate}",
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

            // 5. Recent Documents Section
            if (uiState.recentDocuments.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "RECENTLY OPENED",
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
                Text("Allow Device PDF Access", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "VibeSync scans your local storage solely to index and list your PDF documents for offline viewing and editing. Your files never leave your device.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        Text("100% on-device local query • Zero network transmit", fontSize = 11.sp, color = Color(0xFF10B981), fontWeight = FontWeight.SemiBold)
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
                    Text("Grant Access")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        viewModel.dismissPermissionRationale()
                        viewerPickerLauncher.launch(arrayOf("application/pdf"))
                    }
                ) {
                    Text("Select Files Manually (SAF)")
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
                Text("Privacy & Data Safety Guarantee", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
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
                            Text("100% On-Device • Zero Cloud Transit", fontWeight = FontWeight.Bold, color = Color(0xFFECFDF5), fontSize = 12.sp)
                        }
                    }

                    Text(
                        "• Document Processing: All PDF rendering, splitting, merging, watermarking, and OCR camera scanning execute entirely on your device hardware.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        "• Vaulted Signatures: Stored locally in hardware-backed Android Keystore with AES-256-GCM encryption. Never uploaded to remote servers.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        "• Advertising (Google AdMob): Integrates Google Mobile Ads SDK (App ID: ca-app-pub-4783826505860771~4377815936). AdMob may collect device advertising IDs (AAID) and diagnostic telemetry subject to Google Play policies.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/navyask911/VibeSync-All-in-ONE-PDF-Suite/blob/main/PRIVACY_POLICY.md")
                        )
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback if browser not configured
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Full Policy (Web)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
