package com.vibesync.toolkit.pdf.presentation.export

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.vibesync.toolkit.pdf.core.util.ScopedFileExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shared post-export action sheet for generated files and published MediaStore documents. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExportBottomSheet(
    file: File? = null,
    sourceUri: Uri? = null,
    mimeType: String = "application/pdf",
    displayName: String = file?.name ?: "VibeSync_Export",
    onDismiss: () -> Unit
) {
    if (file == null && sourceUri == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun saveToDownloads() {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ScopedFileExport.saveToDownloads(context, file, sourceUri, displayName, mimeType)
                }
            }
            if (result.isSuccess) {
                Toast.makeText(context, "Saved to Downloads/VibeSync", Toast.LENGTH_LONG).show()
                onDismiss()
            } else {
                Toast.makeText(context, result.exceptionOrNull()?.localizedMessage ?: "Could not save file to Downloads/VibeSync.", Toast.LENGTH_LONG).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveToDownloads()
        else Toast.makeText(context, "Storage permission is needed to save to Downloads on this Android version.", Toast.LENGTH_LONG).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("file_export_bottom_sheet")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Export ready", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Text(displayName, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else saveToDownloads()
                },
                modifier = Modifier.fillMaxWidth().testTag("file_export_save_downloads")
            ) { Text("Save to Downloads") }
            OutlinedButton(
                onClick = {
                    runCatching {
                        val uri = file?.let {
                            require(it.isFile && it.length() > 0L) { "The generated file is no longer available." }
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
                        } ?: requireNotNull(sourceUri)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = ClipData.newUri(context.contentResolver, displayName, uri)
                        }
                        context.startActivity(Intent.createChooser(send, "Share ${displayName}"))
                    }.onSuccess { onDismiss() }
                        .onFailure { Toast.makeText(context, it.localizedMessage ?: "Could not share file.", Toast.LENGTH_LONG).show() }
                },
                modifier = Modifier.fillMaxWidth().testTag("file_export_share")
            ) { Text("Share") }
        }
    }
}
