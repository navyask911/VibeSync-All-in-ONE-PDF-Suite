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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.pdf.CompressionReport
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.data.pdf.PdfCompressor
import com.vibesync.toolkit.pdf.data.pdf.PdfPasswordRequiredException
import com.vibesync.toolkit.pdf.data.pdf.ResolutionProfilePrefs
import com.vibesync.toolkit.pdf.presentation.settings.ResolutionProfilePreferencePanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfQuickActionScreen(
    mode: String,
    initialUri: Uri,
    onNavigateBack: () -> Unit,
    onSuccess: (File) -> Unit,
    onCompressionReport: (CompressionReport, Locale) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val formatLocale = context.resources.configuration.locales.get(0)
    val engine = remember { PdfBoxToolkitEngine(context.applicationContext) }
    val compressor = remember { PdfCompressor(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var completedPages by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }
    var compressionReport by remember { mutableStateOf<CompressionReport?>(null) }
    var pendingOutput by remember { mutableStateOf<File?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var password by rememberSaveable(initialUri) { mutableStateOf("") }
    var pendingPasswordRetry by remember { mutableStateOf(false) }
    val isWatermark = mode.equals("watermark", ignoreCase = true)
    val title = stringResource(if (isWatermark) R.string.action_watermark_pdf else R.string.action_compress_pdf)
    val documentName = remember(initialUri) {
        runCatching { UriUtils.getFileName(context, initialUri) }.getOrDefault(initialUri.lastPathSegment ?: "document.pdf")
    }

    fun startCompression(passwordValue: String? = null) {
        activeJob = scope.launch {
            processing = true
            progress = 0f
            completedPages = 0
            totalPages = 0
            compressionReport = null
            pendingOutput?.delete()
            val output = withContext(kotlinx.coroutines.Dispatchers.IO) {
                UriUtils.createExportCacheFile(context, "compressed_pdf")
            }
            pendingOutput = output
            val result = compressor.compress(
                sourceUri = initialUri,
                outputFile = output,
                profile = ResolutionProfilePrefs(context).read(),
                password = passwordValue,
                onProgress = { completed, total ->
                    completedPages = completed
                    totalPages = total
                    progress = if (total > 0) completed.toFloat() / total else 0f
                }
            )
            processing = false
            result.fold(
                onSuccess = { report ->
                    compressionReport = report
                    onCompressionReport(report, formatLocale)
                    password = ""
                    pendingPasswordRetry = false
                },
                onFailure = { error ->
                    output.delete()
                    pendingOutput = null
                    if (error is PdfPasswordRequiredException || error is InvalidPasswordException) {
                        pendingPasswordRetry = passwordValue != null
                        showPasswordPrompt = true
                    } else if (error !is CancellationException) {
                        Toast.makeText(
                            context,
                            error.localizedMessage ?: context.getString(R.string.document_action_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }
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
            if (processing && !isWatermark) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("compression_progress"))
                Text(
                    stringResource(R.string.compression_progress_pages, completedPages, totalPages),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("compression_progress_count")
                )
                OutlinedButton(onClick = { activeJob?.cancel(); activeJob = null; processing = false }, modifier = Modifier.fillMaxWidth().testTag("compression_cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            } else if (compressionReport != null) {
                val report = compressionReport!!
                Text(stringResource(R.string.compression_before_size, formatBytesForDisplay(report.beforeBytes, formatLocale)), modifier = Modifier.testTag("compression_before_size"))
                Text(stringResource(R.string.compression_after_size, formatBytesForDisplay(report.afterBytes, formatLocale)), modifier = Modifier.testTag("compression_after_size"))
                Text(stringResource(R.string.compression_size_change, String.format(formatLocale, "%.1f", report.reductionPercent)), modifier = Modifier.testTag("compression_size_change"))
                Text(stringResource(R.string.compression_page_count, report.pageCount))
                Button(onClick = { onSuccess(report.outputFile) }, modifier = Modifier.fillMaxWidth().testTag("compression_use_output")) {
                    Text(stringResource(R.string.compression_use_output))
                }
                OutlinedButton(onClick = { startCompression() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.compression_try_again))
                }
            } else {
                Button(
                    enabled = !processing,
                    modifier = Modifier.fillMaxWidth().testTag(if (isWatermark) "watermark_run" else "compression_run"),
                    onClick = {
                        if (isWatermark) {
                            scope.launch {
                                processing = true
                                val output = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    UriUtils.createExportCacheFile(context, "watermarked_pdf")
                                }
                                val result = engine.watermarkPdf(initialUri, context.getString(R.string.pdf_watermark_text), output)
                                processing = false
                                result.fold(
                                    onSuccess = onSuccess,
                                    onFailure = {
                                        output.delete()
                                        Toast.makeText(context, it.localizedMessage ?: context.getString(R.string.document_action_failed), Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        } else startCompression()
                    }
                ) {
                    if (processing) CircularProgressIndicator()
                    else Text(title)
                }
            }
        }
    }

    if (showPasswordPrompt) {
        AlertDialog(
            onDismissRequest = { showPasswordPrompt = false; pendingPasswordRetry = false; password = "" },
            title = { Text(stringResource(R.string.compression_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (pendingPasswordRetry) R.string.compression_password_incorrect else R.string.compression_password_message))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(stringResource(R.string.compression_password_label)) },
                        modifier = Modifier.fillMaxWidth().testTag("compression_password")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotBlank(),
                    onClick = { showPasswordPrompt = false; startCompression(password) },
                    modifier = Modifier.testTag("compression_password_submit")
                ) { Text(stringResource(R.string.continue_label)) }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordPrompt = false; pendingPasswordRetry = false; password = "" }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

fun formatBytesForDisplay(bytes: Long, locale: Locale): String {
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return String.format(locale, "%.1f KB", kib)
    return String.format(locale, "%.2f MB", kib / 1024.0)
}
