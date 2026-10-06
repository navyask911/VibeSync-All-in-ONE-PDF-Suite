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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncCrimsonPrimary
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
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
    onSuccess: (List<File>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { PdfBoxToolkitEngine(context) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var intervalsInput by remember { mutableStateOf("1-2, 3") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> selectedUri = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split Document", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                    Text("Select PDF to Split", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (selectedUri != null) {
                        Text("Selected: ${UriUtils.getFileName(context, selectedUri!!)}", fontSize = 13.sp)
                    } else {
                        Button(
                            onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
                            colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
                        ) {
                            Text("Choose PDF Document")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = intervalsInput,
                onValueChange = { intervalsInput = it },
                label = { Text("Page Intervals (e.g. 1-2, 3, 4-5)") },
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
                    Text("Split and Save Files")
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
        selectedUris.addAll(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Merge Documents", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                Text("Add PDF Files to Merge")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Document Assembly Order:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                                UriUtils.getFileName(context, uri),
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
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
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
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                            }
                            // Delete
                            IconButton(onClick = { selectedUris.removeAt(index) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Red)
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
                                onFailure = { errorMessage = it.localizedMessage ?: "Failed to merge PDFs" }
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
                    Text("Merge (${selectedUris.size}) Documents")
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
    onSuccess: (File) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { PdfBoxToolkitEngine(context) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var password by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var allowPrinting by remember { mutableStateOf(true) }
    var allowCopying by remember { mutableStateOf(false) }
    var isEncrypting by remember { mutableStateOf(false) }

    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> selectedUri = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Encrypt & Protect", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                Text(if (selectedUri != null) "Change PDF (${UriUtils.getFileName(context, selectedUri!!)})" else "Select PDF to Protect")
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Document Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    val description = if (passwordVisible) "Hide password" else "Show password"
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
                Text("Allow Printing Document", fontSize = 14.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowCopying, onCheckedChange = { allowCopying = it })
                Text("Allow Content Copying / Extraction", fontSize = 14.sp)
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
                            onFailure = { errorMessage = it.localizedMessage ?: "Failed to encrypt PDF" }
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
                    Text("Apply AES-128 Encryption")
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
        selectedUris.clear()
        selectedUris.addAll(uris)
        errorMessage = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Images to PDF", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                onClick = { imagePicker.launch(arrayOf("image/*")) },
                enabled = !isConverting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = VibeSyncCrimsonPrimary)
            ) {
                Text(if (selectedUris.isEmpty()) "Choose Images" else "Change Images (" + selectedUris.size + ")")
            }

            if (selectedUris.isEmpty()) {
                Text(
                    "Select one or more photos. They will be placed into an A4 PDF in selection order.",
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
                                    UriUtils.getFileName(context, uri),
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
                            onFailure = { errorMessage = it.localizedMessage ?: "Failed to create PDF" }
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
                    Text("Create PDF")
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
                title = { Text("Extract Images", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                    if (selectedUri == null) "Choose PDF"
                    else "Change PDF (" + UriUtils.getFileName(context, selectedUri!!) + ")"
                )
            }

            Text(
                "Embedded raster images will be extracted as PNG files. Vector-only artwork is not extracted as a bitmap.",
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
                            onFailure = { errorMessage = it.localizedMessage ?: "Failed to extract images" }
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
                    Text("Extract Images")
                }
            }
        }
    }
}