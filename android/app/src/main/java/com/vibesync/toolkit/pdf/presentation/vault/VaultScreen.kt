package com.vibesync.toolkit.pdf.presentation.vault

import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.vault.VaultStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

@Composable
fun VaultScreen(
    onNavigateBack: () -> Unit,
    onAuthenticate: (((Boolean) -> Unit) -> Unit),
    storage: VaultStorage,
    modifier: Modifier = Modifier,
    onOpenPdf: (Uri) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authenticated by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var vaultFiles by remember { mutableStateOf(emptyList<File>()) }
    var movableScans by remember { mutableStateOf(emptyList<File>()) }
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }

    fun refreshFiles() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { storage.listVaultFiles() to storage.listMovableDocuments() }
            }
            result.onSuccess { (vault, scans) ->
                vaultFiles = vault
                movableScans = scans
                errorMessage = null
            }.onFailure { errorMessage = it.localizedMessage ?: "" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { sourceUri ->
        if (sourceUri != null && authenticated && !isImporting) {
            scope.launch {
                isImporting = true
                importProgress = 0f
                errorMessage = null
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val metadata = readPdfSourceMetadata(context, sourceUri)
                        val mimeType = context.contentResolver.getType(sourceUri)
                        if (!mimeType.equals("application/pdf", ignoreCase = true) &&
                            !metadata.first.endsWith(".pdf", ignoreCase = true)
                        ) {
                            throw IOException("Select a PDF document to import.")
                        }
                        val input = context.contentResolver.openInputStream(sourceUri)
                            ?: throw IOException("The selected PDF could not be opened.")
                        storage.importPdf(
                            inputStream = input,
                            displayName = metadata.first,
                            expectedBytes = metadata.second
                        ) { fraction ->
                            importProgress = fraction.coerceIn(0f, 1f)
                        }
                    }
                }
                result.onSuccess {
                    errorMessage = null
                    refreshFiles()
                }.onFailure {
                    errorMessage = it.localizedMessage ?: context.getString(R.string.vault_import_failed)
                }
                isImporting = false
            }
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
                        if (success) refreshFiles()
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("vault_unlock_button")
            ) {
                Text(stringResource(R.string.vault_unlock))
            }
        } else {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/pdf")) },
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth().testTag("vault_import_external_pdf")
            ) {
                Text(stringResource(R.string.vault_import_external_pdf))
            }
            if (isImporting) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = importProgress, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.vault_import_progress, (importProgress * 100f).roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("vault_import_progress")
                    )
                }
            }
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
                                        scope.launch {
                                            val decryptedResult = withContext(Dispatchers.IO) {
                                                runCatching { storage.decryptToCache(file) }
                                            }
                                            decryptedResult.onSuccess { decrypted ->
                                                runCatching {
                                                    onOpenPdf(Uri.fromFile(decrypted))
                                                }.onFailure {
                                                    decrypted.delete()
                                                    errorMessage = it.localizedMessage ?: context.getString(R.string.failed_to_open_pdf)
                                                }
                                            }.onFailure {
                                                errorMessage = it.localizedMessage ?: context.getString(R.string.failed_to_open_pdf)
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("vault_open_${file.name}")
                                ) {
                                    Text(stringResource(R.string.open_pdf))
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runCatching { storage.decryptToCache(file) }
                                            }
                                            result.onSuccess { decrypted ->
                                                pendingExportFile = decrypted
                                                exportLauncher.launch(storage.displayName(file))
                                            }.onFailure {
                                                pendingExportFile?.delete()
                                                pendingExportFile = null
                                                errorMessage = it.localizedMessage ?: context.getString(R.string.export_failed)
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("vault_export_${file.name}")
                                ) {
                                    Text(stringResource(R.string.export_pdf))
                                }
                            }
                        }
                    }
                }
                item { Text(stringResource(R.string.vault_saved_scans), style = MaterialTheme.typography.titleMedium) }
                items(movableScans, key = { "scan_${it.name}" }) { file ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { storage.moveIntoVault(file) }
                                    }
                                    result.onSuccess { errorMessage = null; refreshFiles() }
                                        .onFailure { errorMessage = it.localizedMessage ?: "" }
                                }
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
            errorMessage?.let { message ->
                Text(
                    if (message.isBlank()) stringResource(R.string.vault_move_failed) else message,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                enabled = !isImporting,
                onClick = {
                    pendingExportFile?.delete()
                    pendingExportFile = null
                    storage.clearDecryptedCache()
                    authenticated = false
                    denied = false
                    vaultFiles = emptyList()
                    movableScans = emptyList()
                },
                modifier = Modifier.fillMaxWidth().testTag("vault_lock_button")
            ) { Text(stringResource(R.string.vault_lock_action)) }
        }
    }
}


private fun readPdfSourceMetadata(context: android.content.Context, uri: android.net.Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Imported_Document.pdf"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).coerceAtLeast(0L)
            }
        }
    if (!name.endsWith(".pdf", ignoreCase = true)) name += ".pdf"
    return name to size
}
