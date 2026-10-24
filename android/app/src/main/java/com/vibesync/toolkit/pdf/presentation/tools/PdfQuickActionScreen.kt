package com.vibesync.toolkit.pdf.presentation.tools

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.presentation.settings.ResolutionProfilePreferencePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfQuickActionScreen(
    mode: String,
    initialUri: Uri,
    onNavigateBack: () -> Unit,
    onSuccess: (File) -> Unit
) {
    val context = LocalContext.current
    val engine = remember { PdfBoxToolkitEngine(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var processing by remember { mutableStateOf(false) }
    val isWatermark = mode.equals("watermark", ignoreCase = true)
    val title = stringResource(if (isWatermark) R.string.action_watermark_pdf else R.string.action_compress_pdf)
    val documentName = remember(initialUri) {
        runCatching { UriUtils.getFileName(context, initialUri) }.getOrDefault(initialUri.lastPathSegment ?: "document.pdf")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (isWatermark) Icons.Default.Brush else Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(documentName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.selected_file_name, documentName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (!isWatermark) {
                ResolutionProfilePreferencePanel(
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "resolution_profile_compression_selector"
                )
            }
            Button(
                enabled = !processing,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        processing = true
                        val output = withContext(Dispatchers.IO) {
                            UriUtils.createExportCacheFile(context, if (isWatermark) "watermarked_pdf" else "compressed_pdf")
                        }
                        val result = if (isWatermark) {
                            engine.watermarkPdf(initialUri, context.getString(R.string.pdf_watermark_text), output)
                        } else {
                            engine.compressPdf(initialUri, output)
                        }
                        processing = false
                        result.fold(
                            onSuccess = onSuccess,
                            onFailure = {
                                output.delete()
                                Toast.makeText(context, it.localizedMessage ?: context.getString(R.string.document_action_failed), Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }
            ) {
                if (processing) {
                    CircularProgressIndicator()
                } else {
                    Text(title)
                }
            }
        }
    }
}
