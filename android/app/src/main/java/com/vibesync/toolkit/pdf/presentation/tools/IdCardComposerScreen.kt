package com.vibesync.toolkit.pdf.presentation.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.pdf.IdCardComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
fun IdCardComposerScreen(
    onNavigateBack: () -> Unit,
    previewFront: Bitmap? = null,
    previewBack: Bitmap? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var frontUri by remember { mutableStateOf<Uri?>(null) }
    var backUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    val frontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> frontUri = uri }
    val backPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> backUri = uri }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destination ->
        val file = pendingFile
        pendingFile = null
        if (destination != null && file?.isFile == true) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        } ?: throw IOException(context.getString(R.string.id_card_save_failed))
                    }
                }
                message = if (saved.isSuccess) R.string.id_card_save_success else R.string.id_card_save_failed
                file.delete()
                busy = false
            }
        } else {
            file?.delete()
            busy = false
        }
    }
    val frontBitmap by produceState<Bitmap?>(initialValue = previewFront, key1 = frontUri, key2 = previewFront) {
        if (previewFront != null) value = previewFront
        else value = withContext(Dispatchers.IO) {
            frontUri?.let { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull() }
        }
    }
    val backBitmap by produceState<Bitmap?>(initialValue = previewBack, key1 = backUri, key2 = previewBack) {
        if (previewBack != null) value = previewBack
        else value = withContext(Dispatchers.IO) {
            backUri?.let { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull() }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.id_card_title), fontWeight = FontWeight.Bold) },
            navigationIcon = { androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
            } }
        )
    }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.id_card_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.id_card_front), fontWeight = FontWeight.SemiBold)
                        if (frontBitmap != null) Image(frontBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Fit)
                        else Spacer(Modifier.height(110.dp))
                        OutlinedButton(onClick = { frontPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth().testTag("id_card_pick_front")) {
                            Icon(Icons.Default.Badge, contentDescription = null); Text(stringResource(R.string.id_card_pick_front))
                        }
                    }
                }
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.id_card_back), fontWeight = FontWeight.SemiBold)
                        if (backBitmap != null) Image(backBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Fit)
                        else Spacer(Modifier.height(110.dp))
                        OutlinedButton(onClick = { backPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth().testTag("id_card_pick_back")) {
                            Icon(Icons.Default.Flip, contentDescription = null); Text(stringResource(R.string.id_card_pick_back))
                        }
                    }
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth().aspectRatio(595f / 842f).testTag("id_card_layout_preview"),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (frontBitmap != null) {
                        Image(
                            bitmap = frontBitmap!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.id_card_front),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                    } else Spacer(Modifier.weight(1f).fillMaxWidth())
                    if (backBitmap != null) {
                        Image(
                            bitmap = backBitmap!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.id_card_back),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                    } else Spacer(Modifier.weight(1f).fillMaxWidth())
                }
            }
            Text(
                stringResource(if (frontBitmap == null || backBitmap == null) R.string.id_card_choose_two_guidance else R.string.id_card_preview_ready),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("id_card_guidance")
            )
            message?.let { Text(stringResource(it), color = if (it == R.string.id_card_save_success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
            Button(
                enabled = frontBitmap != null && backBitmap != null && !busy,
                onClick = {
                    val front = frontBitmap ?: return@Button
                    val back = backBitmap ?: return@Button
                    busy = true
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) {
                            val output = File.createTempFile("id_card_", ".pdf", context.cacheDir)
                            IdCardComposer.compose(front, back, output).getOrThrow()
                        } }
                        if (result.isSuccess) {
                            pendingFile = result.getOrThrow()
                            saveLauncher.launch("ID_Card_${System.currentTimeMillis()}.pdf")
                        } else {
                            message = R.string.id_card_save_failed
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("id_card_save")
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else { Icon(Icons.Default.Save, contentDescription = null); Text(stringResource(R.string.id_card_save)) }
            }
        }
    }
}
