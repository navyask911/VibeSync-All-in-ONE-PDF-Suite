package com.vibesync.toolkit.pdf.presentation.tools

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncCrimsonPrimary
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.presentation.common.rememberUriDisplayName
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.presentation.settings.ResolutionProfilePreferencePanel
import com.vibesync.toolkit.pdf.presentation.monetization.AdMobManager
import kotlinx.coroutines.launch
import java.io.File

// ==========================================
// 1. SPLIT PDF SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitScreen(
    onNavigateBack: () -> Unit,
    onSuccess: (List<File>) -> Unit,
    initialUri: Uri? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { PdfBoxToolkitEngine(context) }

    var selectedUri by remember(initialUri) { mutableStateOf(initialUri) }
    var intervalsInput by remember { mutableStateOf("1-2, 3") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            UriUtils.persistReadPermission(context, it)
            selectedUri = it
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tool_split_document), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.tool_select_pdf_to_split), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (selectedUri != null) {
                        Text(stringResource(R.string.selected_file_name, rememberUriDisplayName(selectedUri)), fontSize = 13.sp)
                    } else {
                        Button(
                            onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
                            colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
                        ) {
                            Text(stringResource(R.string.tool_choose_pdf_document))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = intervalsInput,
                onValueChange = { intervalsInput = it },
                label = { Text(stringResource(R.string.tool_page_intervals_hint)) },
                modifier = Modifier.fillMaxWidth()
            )

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    val uri = selectedUri ?: return@Button
                    isProcessing = true
                    errorMessage = null
                    coroutineScope.launch {
                        // Parse interval string
                        val ranges = intervalsInput.split(",").mapNotNull { part ->
                            val clean = part.trim()
                            if (clean.contains("-")) {
                                val split = clean.split("-")
                                val start = split[0].toIntOrNull() ?: 1
                                val end = split[1].toIntOrNull() ?: start
                                IntRange(start, end)
                            } else {
                                clean.toIntOrNull()?.let { IntRange(it, it) }
                            }
                        }

                        val result = engine.splitPdf(uri, ranges)
                        isProcessing = false
                        result.fold(
                            onSuccess = { files ->
                                (context as? Activity)?.let { act ->
                                    AdMobManager.showInterstitial(act) { onSuccess(files) }
                                } ?: onSuccess(files)
                            },
                            onFailure = { errorMessage = it.localizedMessage }
                        )
                    }
                },
                enabled = selectedUri != null && !isProcessing,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.tool_split_and_save_files))
                }
            }
        }
    }
}

// ==========================================
// 2. MERGE PDFS SCREEN (Reorderable List)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(
    onNavigateBack: () -> Unit,
    onSuccess: (File) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { PdfBoxToolkitEngine(context) }

    val selectedUris = remember { mutableStateListOf<Uri>() }
    var isMerging by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val pickMultiplePdfsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { UriUtils.persistReadPermission(context, it) }
        selectedUris.addAll(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tool_merge_documents), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Button(
                onClick = { pickMultiplePdfsLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.tool_add_pdf_files_to_merge))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(stringResource(R.string.tool_document_assembly_order), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(selectedUris) { index, uri ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${index + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                            Text(
                                rememberUriDisplayName(uri),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                fontSize = 13.sp
                            )
                            // Up Arrow
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val temp = selectedUris[index]
                                        selectedUris[index] = selectedUris[index - 1]
                                        selectedUris[index - 1] = temp
                                    }
                                },
                                enabled = index > 0
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.tool_move_up))
                            }
                            // Down Arrow
                            IconButton(
                                onClick = {
                                    if (index < selectedUris.size - 1) {
                                        val temp = selectedUris[index]
                                        selectedUris[index] = selectedUris[index + 1]
                                        selectedUris[index + 1] = temp
                                    }
                                },
                                enabled = index < selectedUris.size - 1
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.tool_move_down))
                            }
                            // Delete
                            IconButton(onClick = { selectedUris.removeAt(index) }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.tool_remove), tint = Color.Red)
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (selectedUris.size >= 2) {
                        isMerging = true
                        errorMessage = null
                        coroutineScope.launch {
                            val output = UriUtils.createExportCacheFile(context, "merged_output")
                            val result = engine.mergePdfs(selectedUris, output)
                            isMerging = false
                            result.fold(
                                onSuccess = { mergedFile ->
                                    (context as? Activity)?.let { act ->
                                        AdMobManager.showInterstitial(act) { onSuccess(mergedFile) }
                                    } ?: onSuccess(mergedFile)
                                },
                                onFailure = { errorMessage = it.localizedMessage ?: context.getString(R.string.tool_failed_merge) }
                            )
                        }
                    }
                },
                enabled = selectedUris.size >= 2 && !isMerging,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
            ) {
                if (isMerging) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.tool_merge_button, selectedUris.size))
                }
            }
        }
    }
}

// ==========================================
// 3. ENCRYPT & PROTECT SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptScreen(
    onNavigateBack: () -> Unit,
    onSuccess: (File) -> Unit,
    initialUri: Uri? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { PdfBoxToolkitEngine(context) }

    var selectedUri by remember(initialUri) { mutableStateOf(initialUri) }
    var password by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var allowPrinting by remember { mutableStateOf(true) }
    var allowCopying by remember { mutableStateOf(false) }
    var isEncrypting by remember { mutableStateOf(false) }

    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            UriUtils.persistReadPermission(context, it)
            selectedUri = it
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tool_encrypt_protect), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
            ) {
                Text(if (selectedUri != null) stringResource(R.string.tool_change_pdf, rememberUriDisplayName(selectedUri)) else stringResource(R.string.tool_select_pdf_to_protect))
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.tool_document_password)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    val description = if (passwordVisible) context.getString(R.string.tool_hide_password) else context.getString(R.string.tool_show_password)
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(imageVector = image, contentDescription = description)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowPrinting, onCheckedChange = { allowPrinting = it })
                Text(stringResource(R.string.tool_allow_printing), fontSize = 14.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowCopying, onCheckedChange = { allowCopying = it })
                Text(stringResource(R.string.tool_allow_copying), fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    val uri = selectedUri ?: return@Button
                    isEncrypting = true
                    errorMessage = null
                    coroutineScope.launch {
                        val output = UriUtils.createExportCacheFile(context, "encrypted_protected")
                        val result = engine.encryptPdf(
                            sourceUri = uri,
                            userPassword = password,
                            ownerPassword = password + "_admin",
                            allowPrinting = allowPrinting,
                            allowCopying = allowCopying,
                            outputFile = output
                        )
                        isEncrypting = false
                        result.fold(
                            onSuccess = { file ->
                                (context as? Activity)?.let { act ->
                                    AdMobManager.showInterstitial(act) { onSuccess(file) }
                                } ?: onSuccess(file)
                            },
                            onFailure = { errorMessage = it.localizedMessage ?: context.getString(R.string.tool_failed_encrypt) }
                        )
                    }
                },
                enabled = selectedUri != null && password.isNotBlank() && !isEncrypting,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
            ) {
                if (isEncrypting) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.tool_apply_aes128))
                }
            }
        }
    }
}

// ==========================================
// 4. IMAGES TO PDF SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertImagesScreen(
    onNavigateBack: () -> Unit,
    onSuccess: (File) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { PdfBoxToolkitEngine(context) }
    val selectedUris = remember { mutableStateListOf<Uri>() }
    var isConverting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { UriUtils.persistReadPermission(context, it) }
        selectedUris.clear()
        selectedUris.addAll(uris)
        errorMessage = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tool_images_to_pdf), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ResolutionProfilePreferencePanel(
                modifier = Modifier.fillMaxWidth(),
                testTag = "resolution_profile_images_to_pdf_selector"
            )

            Button(
                onClick = { imagePicker.launch(arrayOf("image/*")) },
                enabled = !isConverting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
            ) {
                Text(if (selectedUris.isEmpty()) stringResource(R.string.tool_choose_images) else stringResource(R.string.tool_change_images, selectedUris.size))
            }

            if (selectedUris.isEmpty()) {
                Text(
                    stringResource(R.string.tool_images_to_pdf_subtitle),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(selectedUris) { index, uri ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text((index + 1).toString() + ".", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    rememberUriDisplayName(uri),
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Button(
                onClick = {
                    if (selectedUris.isEmpty()) return@Button
                    isConverting = true
                    errorMessage = null
                    coroutineScope.launch {
                        val output = UriUtils.createExportCacheFile(context, "images_to_pdf")
                        val result = engine.convertImagesToPdf(selectedUris.toList(), output)
                        isConverting = false
                        result.fold(
                            onSuccess = onSuccess,
                            onFailure = { errorMessage = it.localizedMessage ?: context.getString(R.string.tool_failed_create_pdf) }
                        )
                    }
                },
                enabled = selectedUris.isNotEmpty() && !isConverting,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
            ) {
                if (isConverting) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.tool_create_pdf))
                }
            }
        }
    }
}

// ==========================================
// 5. EXTRACT IMAGES SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractImagesScreen(
    onNavigateBack: () -> Unit,
    onSuccess: (List<File>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { PdfBoxToolkitEngine(context) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var isExtracting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        selectedUri = uri
        errorMessage = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tool_extract_images), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
                enabled = !isExtracting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
            ) {
                Text(
                    if (selectedUri == null) stringResource(R.string.tool_choose_pdf)
                    else stringResource(R.string.tool_change_pdf, rememberUriDisplayName(selectedUri))
                )
            }

            Text(
                stringResource(R.string.tool_embedded_images_note),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.weight(1f))

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Button(
                onClick = {
                    val uri = selectedUri ?: return@Button
                    isExtracting = true
                    errorMessage = null
                    coroutineScope.launch {
                        val result = engine.extractEmbeddedImages(uri)
                        isExtracting = false
                        result.fold(
                            onSuccess = onSuccess,
                            onFailure = { errorMessage = it.localizedMessage ?: context.getString(R.string.tool_failed_extract_images) }
                        )
                    }
                },
                enabled = selectedUri != null && !isExtracting,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
            ) {
                if (isExtracting) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.tool_extract_images))
                }
            }
        }
    }
}