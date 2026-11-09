package com.vibesync.toolkit.pdf.presentation.scanner

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncCrimsonPrimary
import com.vibesync.toolkit.pdf.data.pdf.DiscoveredPdf
import com.vibesync.toolkit.pdf.data.pdf.PdfScannerGroup
import com.vibesync.toolkit.pdf.data.pdf.PdfScannerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScannerScreen(
    onNavigateBack: () -> Unit,
    onOpenPdf: (Uri) -> Unit,
    autoLaunchFolderPicker: Boolean = true
) {
    val context = LocalContext.current
    val repository = remember(context) { PdfScannerRepository(context) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var groups by remember { mutableStateOf(emptyList<PdfScannerGroup>()) }
    var isScanning by remember { mutableStateOf(false) }
    var hasRequestedInitialFolder by rememberSaveable { mutableStateOf(false) }

    fun refreshFolders() {
        coroutineScope.launch {
            isScanning = true
            try {
                groups = withContext(Dispatchers.IO) { repository.scanGrantedFolders() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbarHostState.showSnackbar(context.getString(R.string.smart_scan_scan_failed))
            } finally {
                isScanning = false
            }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { selectedTree: Uri? ->
        selectedTree?.let { treeUri ->
            coroutineScope.launch {
                val persisted = withContext(Dispatchers.IO) { repository.addFolder(treeUri) }
                if (persisted) {
                    refreshFolders()
                } else {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.smart_scan_permission_failed)
                    )
                }
            }
        }
    }

    LaunchedEffect(repository) {
        if (repository.getGrantedFolderUris().isEmpty()) {
            if (autoLaunchFolderPicker && !hasRequestedInitialFolder) {
                hasRequestedInitialFolder = true
                folderPickerLauncher.launch(null)
            }
        } else {
            refreshFolders()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.smart_scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = ::refreshFolders, modifier = Modifier.testTag("smart_scan_refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.smart_scan_scan_again))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.smart_scan_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth().testTag("smart_scan_add_folder")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.smart_scan_add_folder))
                    }
                }
            }

            when {
                isScanning -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = VibeSyncCrimsonPrimary)
                }
                groups.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("smart_scan_empty_state"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(
                            if (repository.getGrantedFolderUris().isEmpty()) {
                                R.string.smart_scan_no_folders
                            } else {
                                R.string.smart_scan_no_pdfs
                            }
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("smart_scan_groups"),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(groups, key = { group -> "folder_${group.parentFolderName}" }) { group ->
                        PdfScannerFolderGroup(group = group, onOpenPdf = onOpenPdf)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfScannerFolderGroup(
    group: PdfScannerGroup,
    onOpenPdf: (Uri) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = VibeSyncCrimsonPrimary)
            Spacer(Modifier.size(8.dp))
            Text(
                group.parentFolderName,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.smart_scan_folder_count, group.pdfs.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        group.pdfs.forEach { pdf ->
            PdfScannerFileCard(pdf = pdf, onClick = { onOpenPdf(pdf.uri) })
        }
    }
}

@Composable
private fun PdfScannerFileCard(
    pdf: DiscoveredPdf,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .testTag("smart_scan_pdf_${pdf.uri.toString().hashCode()}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = VibeSyncCrimsonPrimary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(pdf.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.modified_date, pdf.sizeFormatted, pdf.formattedDate),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
