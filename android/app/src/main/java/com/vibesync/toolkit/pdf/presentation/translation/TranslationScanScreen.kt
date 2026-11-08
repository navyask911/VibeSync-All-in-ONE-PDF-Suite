package com.vibesync.toolkit.pdf.presentation.translation

import android.content.Context
import android.graphics.Bitmap
import com.vibesync.toolkit.pdf.data.pdf.PdfBitmapUtils
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.data.ocr.MlKitOcrEngine
import com.vibesync.toolkit.pdf.data.ocr.TranslationEngine
import com.vibesync.toolkit.pdf.data.ocr.TranslationProcessor
import com.google.mlkit.nl.translate.TranslateLanguage
import com.vibesync.toolkit.pdf.data.pdf.PdfPasswordRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

private data class TranslationLanguage(val code: String, val labelRes: Int)

private val translationLanguages = listOf(
    TranslationLanguage("en", R.string.language_english),
    TranslationLanguage("es", R.string.language_spanish),
    TranslationLanguage("de", R.string.language_german),
    TranslationLanguage("fr", R.string.language_french),
    TranslationLanguage("it", R.string.language_italian),
    TranslationLanguage("pt", R.string.language_portuguese),
    TranslationLanguage("ru", R.string.language_russian),
    TranslationLanguage("zh", R.string.language_chinese),
    TranslationLanguage("ja", R.string.language_japanese),
    TranslationLanguage("ko", R.string.language_korean),
    TranslationLanguage("ar", R.string.language_arabic),
    TranslationLanguage("iw", R.string.language_hebrew),
    TranslationLanguage("hi", R.string.language_hindi),
    TranslationLanguage("bn", R.string.language_bengali),
    TranslationLanguage("ur", R.string.language_urdu),
    TranslationLanguage("tr", R.string.language_turkish),
    TranslationLanguage("vi", R.string.language_vietnamese),
    TranslationLanguage("th", R.string.language_thai),
    TranslationLanguage("id", R.string.language_indonesian),
    TranslationLanguage("nl", R.string.language_dutch),
    TranslationLanguage("pl", R.string.language_polish),
    TranslationLanguage("fa", R.string.language_persian),
    TranslationLanguage("el", R.string.language_greek),
    TranslationLanguage("cs", R.string.language_czech),
    TranslationLanguage("sv", R.string.language_swedish),
    TranslationLanguage("ta", R.string.language_tamil),
    TranslationLanguage("te", R.string.language_telugu),
    TranslationLanguage("mr", R.string.language_marathi),
    TranslationLanguage("gu", R.string.language_gujarati),
    TranslationLanguage("kn", R.string.language_kannada),
    TranslationLanguage("ro", R.string.language_romanian),
    TranslationLanguage("uk", R.string.language_ukrainian),
    TranslationLanguage("sw", R.string.language_swahili),
    TranslationLanguage("tl", R.string.language_filipino)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationScanScreen(
    onNavigateBack: () -> Unit,
    processor: TranslationProcessor? = null,
    initialText: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val translator = remember(processor, context) { processor ?: TranslationEngine(context.applicationContext) }
    val supportedLanguages = remember { translationLanguages.filter { it.code in TranslateLanguage.getAllLanguages() } }
    // A document change must not restore an empty saved state from an older composition.
    val initialTextStateKey = "${initialText?.length ?: -1}_${initialText?.hashCode() ?: 0}"
    var originalText by rememberSaveable(initialText, key = "translation_original_$initialTextStateKey") {
        mutableStateOf(initialText.orEmpty())
    }
    var translatedText by rememberSaveable(initialText, key = "translation_result_$initialTextStateKey") {
        mutableStateOf("")
    }
    var sourceLanguage by rememberSaveable(initialText, key = "translation_source_$initialTextStateKey") {
        mutableStateOf("auto")
    }
    var targetLanguage by rememberSaveable(initialText, key = "translation_target_$initialTextStateKey") {
        mutableStateOf("es")
    }
    var sourcePicker by remember { mutableStateOf(false) }
    var targetPicker by remember { mutableStateOf(false) }
    var modelConsentDialog by remember { mutableStateOf(false) }
    var passwordDialog by remember { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf("") }
    var pendingPdfUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("") }
    var processing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var completedPages by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var requestedSource by remember { mutableStateOf("en") }
    var requestedTarget by remember { mutableStateOf("es") }
    var translatedExportText by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingPdfUri = uri
            activeJob = scope.launch {
                processing = true
                progress = 0f
                completedPages = 0
                totalPages = 0
                status = ""
                try {
                    originalText = recognizeUri(context, uri, null) { completed, total ->
                        completedPages = completed
                        totalPages = total
                        progress = if (total > 0) completed.toFloat() / total else 0f
                    }
                    translatedText = ""
                    status = context.getString(R.string.translation_ocr_complete)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (error is PdfPasswordRequiredException || error is InvalidPasswordException) {
                        passwordDialog = true
                    } else {
                        status = error.localizedMessage ?: context.getString(R.string.translation_failed)
                    }
                } finally {
                    processing = false
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { capturedBitmap ->
        if (capturedBitmap != null) {
            activeJob = scope.launch {
                processing = true
                progress = 0f
                completedPages = 0
                totalPages = 1
                status = ""
                try {
                    val result = MlKitOcrEngine(context.applicationContext).use { it.recognize(capturedBitmap) }
                    originalText = result.text
                    translatedText = ""
                    completedPages = 1
                    progress = 1f
                    status = context.getString(R.string.translation_ocr_complete)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    status = error.localizedMessage ?: context.getString(R.string.translation_failed)
                } finally {
                    capturedBitmap.recycle()
                    processing = false
                }
            }
        }
    }

    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(translatedText)
                    } ?: throw IOException("Unable to write the translated text.")
                }
            }.onSuccess { status = context.getString(R.string.translation_export_saved) }
                .onFailure { status = it.localizedMessage ?: context.getString(R.string.translation_failed) }
        }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { writeTranslatedPdf(context, uri, translatedText) }
            }.onSuccess { status = context.getString(R.string.translation_export_saved) }
                .onFailure { status = it.localizedMessage ?: context.getString(R.string.translation_failed) }
        }
    }

    fun beginTranslation(afterConsent: Boolean) {
        val source = if (sourceLanguage == "auto") detectSourceLanguage(originalText) else sourceLanguage
        requestedSource = source
        requestedTarget = targetLanguage
        activeJob = scope.launch {
            processing = true
            status = ""
            try {
                if (!afterConsent && !translator.isModelReady(source, targetLanguage)) {
                    modelConsentDialog = true
                    return@launch
                }
                translatedText = translator.translate(originalText, source, targetLanguage)
                status = context.getString(R.string.translation_complete)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                status = error.localizedMessage ?: context.getString(R.string.translation_failed)
            } finally {
                processing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.translation_scan_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.translation_scan_description), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { cameraLauncher.launch(null) },
                    enabled = !processing,
                    modifier = Modifier.weight(1f).testTag("translation_capture")
                ) { Text(stringResource(R.string.scan_new)) }
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/pdf", "image/*")) },
                    enabled = !processing,
                    modifier = Modifier.weight(1f).testTag("translation_import")
                ) { Text(stringResource(R.string.translation_import)) }
            }
            if (processing) {
                androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("translation_progress"))
                if (totalPages > 0) Text(stringResource(R.string.export_progress_pages, completedPages, totalPages))
                OutlinedButton(onClick = { activeJob?.cancel(); activeJob = null; processing = false }, modifier = Modifier.fillMaxWidth().testTag("translation_cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.translation_source_language), style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { sourcePicker = true }, modifier = Modifier.fillMaxWidth().testTag("translation_source_picker")) {
                            Text(
                                if (sourceLanguage == "auto") stringResource(R.string.translation_language_auto)
                                else stringResource(supportedLanguages.first { it.code == sourceLanguage }.labelRes),
                                maxLines = 1
                            )
                        }
                        DropdownMenu(expanded = sourcePicker, onDismissRequest = { sourcePicker = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.translation_language_auto)) },
                                onClick = { sourceLanguage = "auto"; sourcePicker = false },
                                modifier = Modifier.testTag("translation_source_auto")
                            )
                            supportedLanguages.forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(language.labelRes)) },
                                    onClick = { sourceLanguage = language.code; sourcePicker = false },
                                    modifier = Modifier.testTag("translation_source_${language.code}")
                                )
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.translation_target_language), style = MaterialTheme.typography.labelLarge)
                    Box {
                        OutlinedButton(onClick = { targetPicker = true }, modifier = Modifier.fillMaxWidth().testTag("translation_target_picker")) {
                            Text(stringResource(supportedLanguages.first { it.code == targetLanguage }.labelRes), maxLines = 1)
                        }
                        DropdownMenu(expanded = targetPicker, onDismissRequest = { targetPicker = false }) {
                            supportedLanguages.forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(language.labelRes)) },
                                    onClick = { targetLanguage = language.code; targetPicker = false },
                                    modifier = Modifier.testTag("translation_target_${language.code}")
                                )
                            }
                        }
                    }
                }
            }
            Button(
                enabled = !processing && originalText.isNotBlank() && sourceLanguage != targetLanguage,
                onClick = { beginTranslation(afterConsent = false) },
                modifier = Modifier.fillMaxWidth().testTag("translation_run")
            ) {
                if (processing) CircularProgressIndicator()
                else {
                    Icon(Icons.Default.Translate, contentDescription = null)
                    Text(stringResource(R.string.translation_translate_action), modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (originalText.isNotBlank() || translatedText.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f).heightIn(min = 120.dp)) {
                        Text(stringResource(R.string.translation_original), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(originalText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("translation_original_text"))
                    }
                    val rtl = targetLanguage == "ar" || targetLanguage == "iw" || targetLanguage == "fa" || targetLanguage == "ur"
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Column(Modifier.weight(1f).heightIn(min = 120.dp)) {
                            Text(stringResource(R.string.translation_result), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                translatedText,
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = if (rtl) TextDirection.ContentOrRtl else TextDirection.Content),
                                textAlign = if (rtl) TextAlign.Start else TextAlign.Start,
                                modifier = Modifier.testTag("translation_result_text")
                            )
                        }
                    }
                }
            }
            if (translatedText.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { txtLauncher.launch("translated_text.txt") }, modifier = Modifier.weight(1f).testTag("translation_export_txt")) {
                        Text(stringResource(R.string.translation_export_txt))
                    }
                    OutlinedButton(onClick = { pdfLauncher.launch("translated_text.pdf") }, modifier = Modifier.weight(1f).testTag("translation_export_pdf")) {
                        Text(stringResource(R.string.translation_export_pdf))
                    }
                }
            }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("translation_status"))
        }
    }

    if (modelConsentDialog) {
        val targetName = context.getString(supportedLanguages.first { it.code == requestedTarget }.labelRes)
        AlertDialog(
            onDismissRequest = {
                modelConsentDialog = false
                status = context.getString(R.string.translation_model_download_declined)
            },
            title = { Text(stringResource(R.string.translation_model_download_title)) },
            text = { Text(stringResource(R.string.translation_model_download_consent, targetName, 30)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        modelConsentDialog = false
                        beginTranslation(afterConsent = true)
                    },
                    modifier = Modifier.testTag("translation_model_accept")
                ) { Text(stringResource(R.string.continue_label)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        modelConsentDialog = false
                        status = context.getString(R.string.translation_model_download_declined)
                        translatedText = ""
                    },
                    modifier = Modifier.testTag("translation_model_decline")
                ) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (passwordDialog) {
        AlertDialog(
            onDismissRequest = { passwordDialog = false; password = "" },
            title = { Text(stringResource(R.string.compression_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.compression_password_message))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.compression_password_label)) },
                        modifier = Modifier.fillMaxWidth().testTag("translation_password")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotBlank() && pendingPdfUri != null,
                    onClick = {
                        val uri = pendingPdfUri ?: return@TextButton
                        passwordDialog = false
                        activeJob = scope.launch {
                            processing = true
                            try {
                                originalText = recognizeUri(context, uri, password) { completed, total ->
                                    completedPages = completed
                                    totalPages = total
                                    progress = if (total > 0) completed.toFloat() / total else 0f
                                }
                                status = context.getString(R.string.translation_ocr_complete)
                                password = ""
                            } catch (error: Throwable) {
                                if (error is CancellationException) throw error
                                status = error.localizedMessage ?: context.getString(R.string.translation_failed)
                            } finally {
                                processing = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("translation_password_submit")
                ) { Text(stringResource(R.string.continue_label)) }
            },
            dismissButton = {
                TextButton(onClick = { passwordDialog = false; password = ""; pendingPdfUri = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private suspend fun recognizeUri(
    context: Context,
    uri: Uri,
    password: String?,
    onProgress: (Int, Int) -> Unit
): String {
    val mimeType = withContext(Dispatchers.IO) { context.contentResolver.getType(uri).orEmpty() }
    if (mimeType.startsWith("image/")) {
        val bitmap = withContext(Dispatchers.IO) {
            PdfBitmapUtils.decodeStreamDownsampled(openInputStream = {
                if (uri.scheme == "file") File(uri.path ?: "").inputStream()
                else context.contentResolver.openInputStream(uri)
            }) ?: throw IOException("Unable to read the selected image.")
        }
        try {
            onProgress(0, 1)
            val text = MlKitOcrEngine(context.applicationContext).use { it.recognize(bitmap).text }
            onProgress(1, 1)
            return text
        } finally {
            bitmap.recycle()
        }
    }

    val source = stageUri(context, uri)
    try {
        val count = withContext(Dispatchers.IO) { pdfPageCount(source, password) }
        val builder = StringBuilder()
        MlKitOcrEngine(context).use { engine ->
            for (index in 0 until count) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val bitmap = withContext(Dispatchers.IO) { pdfPageBitmap(source, index, password) }
                try {
                    val result = engine.recognize(bitmap)
                    if (builder.isNotEmpty()) builder.append("\n\n")
                    builder.append(result.text)
                } finally {
                    bitmap.recycle()
                }
                onProgress(index + 1, count)
            }
        }
        return builder.toString()
    } finally {
        source.delete()
    }
}

private fun stageUri(context: Context, uri: Uri): File {
    val file = File.createTempFile("translation_input_", ".pdf", context.cacheDir)
    try {
        val input = if (uri.scheme == "file") File(uri.path ?: "").inputStream()
        else context.contentResolver.openInputStream(uri) ?: throw IOException("Unable to open the selected PDF.")
        input.use { source -> FileOutputStream(file).use { sink -> source.copyTo(sink) } }
        return file
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}

private fun pdfPageCount(file: File, password: String?): Int {
    val document = try {
        if (password.isNullOrBlank()) PDDocument.load(file) else PDDocument.load(file, password)
    } catch (error: InvalidPasswordException) {
        if (password.isNullOrBlank()) throw PdfPasswordRequiredException("Enter the PDF password before OCR.", error)
        throw error
    }
    return document.use { it.numberOfPages }
}

private fun pdfPageBitmap(file: File, index: Int, password: String?): Bitmap {
    var decryptedFile: File? = null
    val renderFile = if (password.isNullOrBlank()) file else {
        val decrypted = File.createTempFile("translation_decrypted_", ".pdf", file.parentFile)
        try {
            PDDocument.load(file, password).use { document ->
                document.setAllSecurityToBeRemoved(true)
                document.save(decrypted)
            }
            decryptedFile = decrypted
            decrypted
        } catch (error: Exception) {
            decrypted.delete()
            throw error
        }
    }
    try {
        val descriptor = ParcelFileDescriptor.open(renderFile, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            require(index in 0 until renderer.pageCount) { "The requested PDF page does not exist." }
            renderer.openPage(index).use { page ->
                val scale = minOf(2.5, 2400.0 / page.width.coerceAtLeast(1), 3200.0 / page.height.coerceAtLeast(1))
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                try {
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    return bitmap
                } catch (error: Exception) {
                    bitmap.recycle()
                    throw error
                }
            }
        }
    } catch (error: Exception) {
        if (password.isNullOrBlank()) {
            try {
                PDDocument.load(file).use { /* verify whether this PDF requires a password */ }
            } catch (protected: InvalidPasswordException) {
                throw PdfPasswordRequiredException("Enter the PDF password before OCR.", protected)
            }
        }
        throw error
    } finally {
        decryptedFile?.delete()
    }
}

private fun detectSourceLanguage(text: String): String {
    if (text.any { it.code in 0x0600..0x06FF }) return "ar"
    if (text.any { it.code in 0x0590..0x05FF }) return "iw"
    if (text.any { it.code in 0x0900..0x097F }) return "hi"
    if (text.any { it.code in 0x3040..0x30FF }) return "ja"
    if (text.any { it.code in 0xAC00..0xD7AF }) return "ko"
    if (text.any { it.code in 0x4E00..0x9FFF }) return "zh"
    return "en"
}

private fun writeTranslatedPdf(context: Context, target: Uri, text: String) {
    val document = PdfDocument()
    try {
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val maxWidth = pageWidth - margin * 2
        val lines = mutableListOf<String>()
        text.lines().forEach { paragraph ->
            if (paragraph.isBlank()) {
                lines.add("")
            } else {
                var current = ""
                paragraph.split(Regex("\\s+")).forEach { word ->
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                        lines.add(current)
                        current = word
                    } else current = candidate
                }
                lines.add(current)
            }
        }
        val lineHeight = 18f
        val linesPerPage = ((pageHeight - margin * 2) / lineHeight).toInt().coerceAtLeast(1)
        val batches = lines.chunked(linesPerPage).ifEmpty { listOf(emptyList()) }
        batches.forEachIndexed { index, batch ->
            val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
            val page = document.startPage(info)
            var y = margin + lineHeight
            batch.forEach { line ->
                page.canvas.drawText(line, margin, y, paint)
                y += lineHeight
            }
            document.finishPage(page)
        }
        context.contentResolver.openOutputStream(target, "wt")?.use { document.writeTo(it) }
            ?: throw IOException("Unable to write the translated PDF.")
    } finally {
        document.close()
    }
}
