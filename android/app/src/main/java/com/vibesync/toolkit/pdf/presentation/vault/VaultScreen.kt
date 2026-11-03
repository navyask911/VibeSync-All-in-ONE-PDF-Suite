package com.vibesync.toolkit.pdf.presentation.vault

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.vault.VaultStorage
import com.vibesync.toolkit.pdf.data.vault.DevicePdfDocument
import com.vibesync.toolkit.pdf.data.vault.DevicePdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
fun VaultScreen(
    onNavigateBack: () -> Unit,
    onAuthenticate: (((Boolean) -> Unit) -> Unit),
    storage: VaultStorage,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authenticated by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var vaultFiles by remember { mutableStateOf(emptyList<File>()) }
    var movableScans by remember { mutableStateOf(emptyList<File>()) }
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val preferences = remember(context) { context.getSharedPreferences("vault_preferences", 0) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var deviceTreeUri by remember {
        mutableStateOf(preferences.getString("device_pdf_tree", null)?.let(Uri::parse))
    }
    var devicePdfFiles by remember { mutableStateOf(emptyList<DevicePdfDocument>()) }

    fun refreshFiles() {
        runCatching { storage.listVaultFiles() to storage.listMovableScans() }
            .onSuccess { (vault, scans) ->
                vaultFiles = vault
                movableScans = scans
                errorMessage = null
            }
            .onFailure { errorMessage = it.localizedMessage ?: "" }
    }

    fun refreshDevicePdfs(treeUri: Uri?) {
        if (treeUri == null) {
            devicePdfFiles = emptyList()
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { DevicePdfRepository.listPdfDocuments(context, treeUri) }
            }.onSuccess {
                devicePdfFiles = it
                errorMessage = null
            }.onFailure {
                errorMessage = it.localizedMessage ?: "Could not read PDFs in the selected folder."
            }
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { selectedTree ->
        if (selectedTree != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    selectedTree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                preferences.edit().putString("device_pdf_tree", selectedTree.toString()).apply()
                deviceTreeUri = selectedTree
                selectedTab = 1
                refreshDevicePdfs(selectedTree)
            }.onFailure {
                errorMessage = it.localizedMessage ?: "Could not access the selected folder."
            }
        }
    }

    fun openDevicePdf(document: DevicePdfDocument) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(document.uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_pdf)))
        }.onFailure {
            errorMessage = it.localizedMessage ?: context.getString(R.string.failed_to_open_pdf)
        }
    }

    fun moveDevicePdfToVault(document: DevicePdfDocument) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val temporary = File.createTempFile("vault_device_import_", ".pdf", context.cacheDir)
                    try {
                        context.contentResolver.openInputStream(document.uri)?.use { input ->
                            temporary.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw IOException("Could not read the selected device PDF.")
                        storage.moveIntoVault(temporary)
                    } finally {
                        temporary.delete()
                    }
                }
            }
            result.onSuccess { refreshFiles(); errorMessage = null }
                .onFailure { errorMessage = it.localizedMessage ?: context.getString(R.string.vault_move_failed) }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destination ->
        val decrypted = pendingExportFile
        pendingExportFile = null
        if (destination == null || decrypted?.isFile != true) {
            decrypted?.delete()
        } else {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(destination, "w")
                            ?: throw IOException("The selected export destination could not be opened.")
                        output.use { stream ->
                            decrypted.inputStream().use { input -> input.copyTo(stream) }
                        }
                    }
                }
                decrypted.delete()
                result.onFailure { errorMessage = it.localizedMessage ?: context.getString(R.string.export_failed) }
            }
        }
    }

    // Plaintext cache copies exist only while the vault route is active/authenticated.
    DisposableEffect(storage) {
        onDispose { storage.clearDecryptedCache() }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = {
                storage.clearDecryptedCache()
                onNavigateBack()
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp))
        }
        if (!authenticated) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.vault_locked_message),
                modifier = Modifier.fillMaxWidth().testTag("vault_lock_message")
            )
            if (denied) {
                Text(stringResource(R.string.vault_auth_denied), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onAuthenticate { success ->
                        authenticated = success
                        denied = !success
                        if (success) {
                            refreshFiles()
                            refreshDevicePdfs(deviceTreeUri)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("vault_unlock_button")
            ) {
                Text(stringResource(R.string.vault_unlock))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("App Scanned") },
                    modifier = Modifier.testTag("vault_tab_app_scanned")
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        if (deviceTreeUri == null) treePicker.launch(null)
                        else refreshDevicePdfs(deviceTreeUri)
                    },
                    label = { Text("All Device PDFs") },
                    modifier = Modifier.testTag("vault_tab_all_device")
                )
            }

            if (selectedTab == 0) {
                Text(stringResource(R.string.vault_files), style = MaterialTheme.typography.titleMedium)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("vault_file_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (vaultFiles.isEmpty()) {
                        item { Text(stringResource(R.string.vault_empty), modifier = Modifier.testTag("vault_empty")) }
                    }
                    items(vaultFiles, key = { it.name }) { file ->
                        Card(modifier = Modifier.fillMaxWidth().testTag("vault_file_${file.name}")) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Folder, contentDescription = null)
                                    Text(storage.displayName(file), modifier = Modifier.padding(start = 10.dp))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = {
                                            runCatching {
                                                val decrypted = storage.decryptToCache(file)
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    decrypted
                                                )
                                                val viewIntent = Intent(Intent.ACTION_VIEW)
                                                    .setDataAndType(uri, "application/pdf")
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                context.startActivity(Intent.createChooser(viewIntent, context.getString(R.string.open_pdf)))
                                            }.onFailure {
                                                errorMessage = it.localizedMessage ?: context.getString(R.string.failed_to_open_pdf)
                                            }
                                        },
                                        modifier = Modifier.testTag("vault_open_${file.name}")
                                    ) { Text(stringResource(R.string.open_pdf)) }
                                    TextButton(
                                        onClick = {
                                            runCatching {
                                                pendingExportFile = storage.decryptToCache(file)
                                                exportLauncher.launch(storage.displayName(file))
                                            }.onFailure {
                                                pendingExportFile?.delete()
                                                pendingExportFile = null
                                                errorMessage = it.localizedMessage ?: context.getString(R.string.export_failed)
                                            }
                                        },
                                        modifier = Modifier.testTag("vault_export_${file.name}")
                                    ) { Text(stringResource(R.string.export_pdf)) }
                                }
                            }
                        }
                    }
                    item { Text(stringResource(R.string.vault_saved_scans), style = MaterialTheme.typography.titleMedium) }
                    items(movableScans, key = { "scan_${it.name}" }) { file ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    runCatching { storage.moveIntoVault(file) }
                                        .onSuccess { errorMessage = null; refreshFiles() }
                                        .onFailure { errorMessage = it.localizedMessage ?: "" }
                                }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null)
                                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(file.name)
                                    Text(stringResource(R.string.vault_move_to_vault), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Choose a folder on your device. PDFs in that folder and its subfolders are indexed using Android's Storage Access Framework, without broad storage permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { treePicker.launch(deviceTreeUri) },
                        modifier = Modifier.fillMaxWidth().testTag("vault_choose_device_folder")
                    ) { Text(if (deviceTreeUri == null) "Choose device folder" else "Change device folder") }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("vault_device_pdf_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (deviceTreeUri != null && devicePdfFiles.isEmpty()) {
                            item { Text("No PDFs found in the selected folder.", modifier = Modifier.testTag("vault_device_pdf_empty")) }
                        }
                        items(devicePdfFiles, key = { it.uri.toString() }) { document ->
                            Card(modifier = Modifier.fillMaxWidth().testTag("vault_device_pdf_${document.displayName}")) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(document.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { openDevicePdf(document) }) {
                                            Text(stringResource(R.string.open_pdf))
                                        }
                                        TextButton(onClick = { moveDevicePdfToVault(document) }) {
                                            Text(stringResource(R.string.vault_move_to_vault))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            errorMessage?.let { message ->
                Text(
                    if (message.isBlank()) stringResource(R.string.vault_move_failed) else message,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = {
                    pendingExportFile?.delete()
                    pendingExportFile = null
                    storage.clearDecryptedCache()
                    authenticated = false
                    denied = false
                    vaultFiles = emptyList()
                    movableScans = emptyList()
                    devicePdfFiles = emptyList()
                    selectedTab = 0
                },
                modifier = Modifier.fillMaxWidth().testTag("vault_lock_button")
            ) { Text(stringResource(R.string.vault_lock_action)) }
        }
    }
}
