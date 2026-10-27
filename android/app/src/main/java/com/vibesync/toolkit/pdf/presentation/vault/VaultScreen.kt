package com.vibesync.toolkit.pdf.presentation.vault

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.vault.VaultStorage

@Composable
fun VaultScreen(
    onNavigateBack: () -> Unit,
    onAuthenticate: (((Boolean) -> Unit) -> Unit),
    storage: VaultStorage,
    modifier: Modifier = Modifier
) {
    var authenticated by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var vaultFiles by remember { mutableStateOf(emptyList<java.io.File>()) }
    var movableScans by remember { mutableStateOf(emptyList<java.io.File>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshFiles() {
        vaultFiles = storage.listVaultFiles()
        movableScans = storage.listMovableScans()
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back)) }
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
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Text(file.name, modifier = Modifier.padding(start = 10.dp))
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
            errorMessage?.let { message ->
                Text(
                    if (message.isBlank()) stringResource(R.string.vault_move_failed) else message,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = {
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
