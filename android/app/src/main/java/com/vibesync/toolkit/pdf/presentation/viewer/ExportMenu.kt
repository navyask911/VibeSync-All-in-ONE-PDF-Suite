package com.vibesync.toolkit.pdf.presentation.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.ocr.MlKitOcrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** SAF export menu. OCR and raster operations happen on-device; source text stays on this device. */
@Composable
fun ExportMenu(
    sourceUri: Uri,
    currentPageIndex: Int = 0,
    modifier: Modifier = Modifier,
    testTag: String = "export_menu",
    onExportSelected: (mimeType: String, displayName: String) -> Unit = { _, _ -> },
    onSafLaunch: ((mimeType: String, displayName: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var completedPages by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var passwordDialog by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var pendingTextTarget by remember { mutableStateOf<Uri?>(null) }
    var queuedJpgPages by remember { mutableStateOf<List<Int>>(emptyList()) }

    val baseName = remember(sourceUri) {
        runCatching { UriUtils.getFileName(context, sourceUri) }
            .getOrDefault(sourceUri.lastPathSegment ?: "document.pdf")
            .substringBeforeLast('.')
            .ifBlank { "document" }
    }

    fun requestSaf(mimeType: String, displayName: String, launch: () -> Unit) {
        onExportSelected(mimeType, displayName)
        if (onSafLaunch != null) onSafLaunch(mimeType, displayName) else launch()
    }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { target ->
        if (target != null) scope.launch {
            runExport(context, { copyPdfToSaf(context, sourceUri, target) }, onDone = {
                Toast.makeText(context, context.getString(R.string.export_saved_pdf), Toast.LENGTH_LONG).show()
            }, onError = { showExportError(context, it) })
        }
    }
    var launchJpg by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val jpgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { target ->
        val pageIndex = queuedJpgPages.firstOrNull()
        if (target == null || pageIndex == null) {
            queuedJpgPages = emptyList()
        } else {
            scope.launch {
                runExport(context, { exportPageJpg(context, sourceUri, target, pageIndex, null) }, onDone = {
                    queuedJpgPages = queuedJpgPages.drop(1)
                    val nextPage = queuedJpgPages.firstOrNull()
                    if (nextPage != null) {
                        val nextName = "${baseName}_page_${nextPage + 1}.jpg"
                        requestSaf("image/jpeg", nextName) { launchJpg?.invoke(nextName) }
                    } else {
                        Toast.makeText(context, context.getString(R.string.export_saved_jpg), Toast.LENGTH_LONG).show()
                    }
                }, onError = {
                    queuedJpgPages = emptyList()
                    showExportError(context, it)
                })
            }
        }
    }
    launchJpg = { name -> jpgLauncher.launch(name) }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { target ->
        if (target != null) scope.launch {
            running = true
            progress = 0f
            completedPages = 0
            totalPages = 0
            runExport(context, {
                exportAllPagesZip(context, sourceUri, target) { complete, total ->
                    completedPages = complete
                    totalPages = total
                    progress = if (total > 0) complete.toFloat() / total else 0f
                }
            }, onDone = {
                running = false
                Toast.makeText(context, context.getString(R.string.export_saved_zip), Toast.LENGTH_LONG).show()
            }, onError = {
                running = false
                showExportError(context, it)
            })
        }
    }
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { target ->
        if (target != null) {
            pendingTextTarget = target
            startTextExport(
                context = context,
                sourceUri = sourceUri,
                targetUri = target,
                passwordValue = null,
                onStart = { job -> activeJob = job; running = true },
                onProgress = { complete, total ->
                    completedPages = complete
                    totalPages = total
                    progress = if (total > 0) complete.toFloat() / total else 0f
                },
                onPasswordRequired = { running = false; passwordDialog = true },
                onDone = {
                    running = false
                    pendingTextTarget = null
                    password = ""
                    Toast.makeText(context, context.getString(R.string.export_saved_txt), Toast.LENGTH_LONG).show()
                },
                onError = { running = false; showExportError(context, it) }
            )
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            IconButton(onClick = { expanded = true }, enabled = !running, modifier = Modifier.testTag(testTag)) {
                Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.export_menu_title))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_pdf)) },
                    modifier = Modifier.testTag("export_pdf"),
                    onClick = {
                        expanded = false
                        val name = "$baseName.pdf"
                        requestSaf("application/pdf", name) { pdfLauncher.launch(name) }
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_jpg_current)) },
                    modifier = Modifier.testTag("export_jpg_current"),
                    onClick = {
                        expanded = false
                        val page = currentPageIndex.coerceAtLeast(0)
                        queuedJpgPages = listOf(page)
                        val name = "${baseName}_page_${page + 1}.jpg"
                        requestSaf("image/jpeg", name) { jpgLauncher.launch(name) }
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_jpg_separate)) },
                    modifier = Modifier.testTag("export_jpg_separate"),
                    onClick = {
                        expanded = false
                        scope.launch {
                            try {
                                val count = withContext(Dispatchers.IO) { pageCount(context, sourceUri, null) }
                                queuedJpgPages = (0 until count).toList()
                                val firstPage = queuedJpgPages.firstOrNull() ?: return@launch
                                val name = "${baseName}_page_${firstPage + 1}.jpg"
                                requestSaf("image/jpeg", name) { jpgLauncher.launch(name) }
                            } catch (error: Throwable) {
                                showExportError(context, error)
                            }
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_jpg_zip)) },
                    modifier = Modifier.testTag("export_jpg_zip"),
                    onClick = {
                        expanded = false
                        val name = "${baseName}_pages.zip"
                        requestSaf("application/zip", name) { zipLauncher.launch(name) }
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_txt)) },
                    modifier = Modifier.testTag("export_txt"),
                    onClick = {
                        expanded = false
                        val name = "$baseName.txt"
                        requestSaf("text/plain", name) { txtLauncher.launch(name) }
                    }
                )
            }
        }
        if (running) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("export_progress"))
            Text(
                stringResource(R.string.export_progress_pages, completedPages, totalPages),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("export_progress_count")
            )
            TextButton(onClick = { activeJob?.cancel(); activeJob = null; running = false }, modifier = Modifier.testTag("export_cancel")) {
                Text(stringResource(R.string.cancel))
            }
        }
    }

    if (passwordDialog) {
        AlertDialog(
            onDismissRequest = { passwordDialog = false; password = ""; pendingTextTarget = null },
            title = { Text(stringResource(R.string.export_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.export_password_message))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.compression_password_label)) },
                        modifier = Modifier.fillMaxWidth().testTag("export_password")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotBlank() && pendingTextTarget != null,
                    onClick = {
                        val target = pendingTextTarget ?: return@TextButton
                        passwordDialog = false
                        startTextExport(
                            context, sourceUri, target, password,
                            onStart = { job -> activeJob = job; running = true },
                            onProgress = { complete, total ->
                                completedPages = complete
                                totalPages = total
                                progress = if (total > 0) complete.toFloat() / total else 0f
                            },
                            onPasswordRequired = { running = false; passwordDialog = true },
                            onDone = {
                                running = false
                                pendingTextTarget = null
                                password = ""
                                Toast.makeText(context, context.getString(R.string.export_saved_txt), Toast.LENGTH_LONG).show()
                            },
                            onError = { running = false; showExportError(context, it) }
                        )
                    },
                    modifier = Modifier.testTag("export_password_submit")
                ) { Text(stringResource(R.string.continue_label)) }
            },
            dismissButton = {
                TextButton(onClick = { passwordDialog = false; password = ""; pendingTextTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun startTextExport(
    context: Context,
    sourceUri: Uri,
    targetUri: Uri,
    passwordValue: String?,
    onStart: (Job) -> Unit,
    onProgress: (Int, Int) -> Unit,
    onPasswordRequired: () -> Unit,
    onDone: () -> Unit,
    onError: (Throwable) -> Unit
) {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
    val job = scope.launch {
        try {
            val source = withContext(Dispatchers.IO) { stagePdf(context, sourceUri) }
            try {
                val pageTotal = withContext(Dispatchers.IO) { pageCountFromFile(source, passwordValue) }
                onProgress(0, pageTotal)
                val allText = buildString {
                    MlKitOcrEngine().use { engine ->
                        for (index in 0 until pageTotal) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val bitmap = withContext(Dispatchers.IO) { renderPage(source, index, passwordValue) }
                            try {
                                val result = engine.recognize(bitmap)
                                if (isNotEmpty()) append("\n\n")
                                append(result.text)
                            } finally {
                                bitmap.recycle()
                            }
                            onProgress(index + 1, pageTotal)
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(targetUri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(allText)
                    } ?: throw IOException("The selected destination could not be opened.")
                }
                onDone()
            } finally {
                source.delete()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is ExportPasswordRequiredException || error is InvalidPasswordException) onPasswordRequired()
            else onError(error)
        }
    }
    onStart(job)
}

private suspend fun runExport(
    context: Context,
    action: suspend () -> Unit,
    onDone: () -> Unit,
    onError: (Throwable) -> Unit
) {
    try {
        withContext(Dispatchers.IO) { action() }
        onDone()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        onError(error)
    }
}

private fun showExportError(context: Context, error: Throwable) {
    Toast.makeText(context, error.localizedMessage ?: context.getString(R.string.export_failed), Toast.LENGTH_LONG).show()
}

private fun copyPdfToSaf(context: Context, sourceUri: Uri, targetUri: Uri) {
    val input = if (sourceUri.scheme == "file") {
        File(sourceUri.path ?: throw IOException("The selected file path is missing.")).inputStream()
    } else {
        context.contentResolver.openInputStream(sourceUri) ?: throw IOException("Unable to open the selected PDF.")
    }
    input.use { source ->
        context.contentResolver.openOutputStream(targetUri, "wt")?.use { target -> source.copyTo(target) }
            ?: throw IOException("The selected destination could not be opened.")
    }
}

private fun exportPageJpg(context: Context, sourceUri: Uri, targetUri: Uri, pageIndex: Int, password: String?) {
    val source = stagePdf(context, sourceUri)
    try {
        val bitmap = renderPage(source, pageIndex, password)
        try {
            context.contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw IOException("Could not encode the page as JPG.")
            } ?: throw IOException("The selected destination could not be opened.")
        } finally {
            bitmap.recycle()
        }
    } finally {
        source.delete()
    }
}

private fun exportAllPagesZip(
    context: Context,
    sourceUri: Uri,
    targetUri: Uri,
    onProgress: (Int, Int) -> Unit
) {
    val stagedZip = File.createTempFile("export_pages_", ".zip", context.cacheDir)
    try {
        buildJpgPagesZip(context, sourceUri, stagedZip, onProgress)
        context.contentResolver.openOutputStream(targetUri, "wt")?.use { target ->
            stagedZip.inputStream().use { source -> source.copyTo(target) }
        } ?: throw IOException("The selected destination could not be opened.")
    } finally {
        stagedZip.delete()
    }
}

/** Builds the ZIP before SAF persistence so its entry list and JPEG bytes can be verified. */
internal fun buildJpgPagesZip(
    context: Context,
    sourceUri: Uri,
    targetFile: File,
    onProgress: (Int, Int) -> Unit = { _, _ -> }
) {
    val source = stagePdf(context, sourceUri)
    try {
        val count = pageCountFromFile(source, null)
        ZipOutputStream(FileOutputStream(targetFile)).use { zip ->
            for (index in 0 until count) {
                val bitmap = renderPage(source, index, null)
                try {
                    zip.putNextEntry(ZipEntry(String.format(Locale.ROOT, "page_%03d.jpg", index + 1)))
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, zip)) throw IOException("Could not encode a page as JPG.")
                    zip.closeEntry()
                } finally {
                    bitmap.recycle()
                }
                onProgress(index + 1, count)
            }
        }
    } finally {
        source.delete()
    }
}

private fun pageCount(context: Context, sourceUri: Uri, password: String?): Int {
    val source = stagePdf(context, sourceUri)
    return try {
        pageCountFromFile(source, password)
    } finally {
        source.delete()
    }
}

private fun stagePdf(context: Context, sourceUri: Uri): File {
    val target = File.createTempFile("export_source_", ".pdf", context.cacheDir)
    try {
        val input = if (sourceUri.scheme == "file") {
            File(sourceUri.path ?: throw IOException("The selected file path is missing.")).inputStream()
        } else {
            context.contentResolver.openInputStream(sourceUri) ?: throw IOException("Unable to open the selected PDF.")
        }
        input.use { source -> FileOutputStream(target).use { sink -> source.copyTo(sink) } }
        return target
    } catch (error: Exception) {
        target.delete()
        throw error
    }
}

private fun pageCountFromFile(file: File, password: String?): Int {
    val document = try {
        if (password.isNullOrBlank()) PDDocument.load(file) else PDDocument.load(file, password)
    } catch (error: InvalidPasswordException) {
        if (password.isNullOrBlank()) throw ExportPasswordRequiredException("Enter the PDF password to extract text.", error)
        throw error
    }
    return document.use { it.numberOfPages }
}

private fun renderPage(file: File, pageIndex: Int, password: String?): Bitmap {
    if (password.isNullOrBlank()) {
        try {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { renderer ->
                require(pageIndex in 0 until renderer.pageCount) { "The requested PDF page does not exist." }
                renderer.openPage(pageIndex).use { page ->
                    val scale = minOf(2.0, 2400.0 / page.width.coerceAtLeast(1), 3200.0 / page.height.coerceAtLeast(1))
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                }
            }
        } catch (error: Exception) {
            val probe = try { PDDocument.load(file) } catch (protected: InvalidPasswordException) {
                throw ExportPasswordRequiredException("Enter the PDF password to export this document.", protected)
            }
            probe.close()
        }
    }
    val document = try {
        if (password.isNullOrBlank()) PDDocument.load(file) else PDDocument.load(file, password)
    } catch (error: InvalidPasswordException) {
        if (password.isNullOrBlank()) throw ExportPasswordRequiredException("Enter the PDF password to export this document.", error)
        throw error
    }
    return document.use { PDFRenderer(it).renderImageWithDPI(pageIndex, 180f, ImageType.RGB) }
}

private class ExportPasswordRequiredException(message: String, cause: Throwable) : IOException(message, cause)
