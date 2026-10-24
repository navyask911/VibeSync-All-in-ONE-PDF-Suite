package com.vibesync.toolkit.pdf

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.review.ReviewManagerFactory
import com.vibesync.toolkit.pdf.BuildConfig
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.core.util.EngagementPolicy
import com.vibesync.toolkit.pdf.core.util.ReviewEligibilityTracker
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardScreen
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardViewModel
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import com.vibesync.toolkit.pdf.presentation.editor.PdfEditorScreen
import com.vibesync.toolkit.pdf.presentation.monetization.AdMobManager
import com.vibesync.toolkit.pdf.presentation.scanner.DocumentScannerManager
import com.vibesync.toolkit.pdf.presentation.scanner.ScanHistoryScreen
import com.vibesync.toolkit.pdf.presentation.scanner.ScannerHistoryRepository
import com.vibesync.toolkit.pdf.presentation.scanner.ScannerHistoryViewModel
import com.vibesync.toolkit.pdf.presentation.tools.ConvertImagesScreen
import com.vibesync.toolkit.pdf.presentation.tools.EncryptScreen
import com.vibesync.toolkit.pdf.presentation.tools.ExtractImagesScreen
import com.vibesync.toolkit.pdf.presentation.tools.MergeScreen
import com.vibesync.toolkit.pdf.presentation.tools.SplitScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

class MainActivity : AppCompatActivity() {

    private val engagementPreferences by lazy { getSharedPreferences("vibesync_engagement", MODE_PRIVATE) }
    private val reviewEligibilityTracker by lazy { ReviewEligibilityTracker(this) }
    private lateinit var appUpdateManager: AppUpdateManager
    private lateinit var updateFlowLauncher: ActivityResultLauncher<IntentSenderRequest>
    private var pendingUpdateInfo: AppUpdateInfo? = null
    private var updatePromptVisible by mutableStateOf(false)
    private var updateDownloaded by mutableStateOf(false)
    private var updateReadyDialogVisible by mutableStateOf(false)
    private var offlinePromptVisible by mutableStateOf(false)
    private var internetAvailable by mutableStateOf(false)
    private var activeRoute by mutableStateOf("dashboard")
    private var reviewEligibilityRevision by mutableStateOf(0)
    private var updateCheckInProgress = false
    private var updateFlowStarted = false
    private var reviewFlowLaunching = false
    private var connectivityCallbackRegistered = false
    private var hasShownOfflinePromptThisSession = false

    private val updateInstallStateListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> runOnUiThread {
                updateDownloaded = true
                updatePromptVisible = false
                updateReadyDialogVisible = true
                updateFlowStarted = false
            }
            InstallStatus.FAILED -> {
                Log.w(TAG, "Google Play in-app update download failed.")
                runOnUiThread { updateFlowStarted = false }
            }
            else -> Unit
        }
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshInternetStatus()
        override fun onLost(network: Network) = refreshInternetStatus()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            runOnUiThread { applyConnectivityState(validated) }
        }
    }

    private val pendingPdfUriState = MutableStateFlow<Uri?>(null)

    private val dashboardViewModel: DashboardViewModel by viewModels()
    private val viewerViewModel: PdfViewerViewModel by viewModels()
    private val editorViewModel: EditorViewModel by viewModels()
    private val scannerHistoryViewModel: ScannerHistoryViewModel by viewModels()
    private val scannerHistoryRepository by lazy { ScannerHistoryRepository(File(filesDir, "scans")) }
    private val toolkitEngine by lazy { PdfBoxToolkitEngine(applicationContext) }

    private lateinit var scannerManager: DocumentScannerManager
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var saveScanLauncher: ActivityResultLauncher<String>
    private var pendingScanExportFile: File? = null

    // Permission launcher for Android 14+ photo and media access
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (!granted) {
            Toast.makeText(this, getString(R.string.storage_permissions_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateFlowLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            updateFlowStarted = false
            if (result.resultCode != Activity.RESULT_OK) {
                engagementPreferences.edit()
                    .putBoolean(KEY_IMMEDIATE_UPDATE_IN_PROGRESS, false)
                    .putLong(KEY_UPDATE_SNOOZE_UNTIL, System.currentTimeMillis() + UPDATE_PROMPT_COOLDOWN_MS)
                    .apply()
                pendingUpdateInfo = null
                updatePromptVisible = false
            }
        }
        appUpdateManager = AppUpdateManagerFactory.create(this)
        runCatching { appUpdateManager.registerListener(updateInstallStateListener) }
            .onFailure { Log.w(TAG, "Could not register Play update listener.", it) }

        // 1. Initialize ML Kit Document Scanner
        scannerManager = DocumentScannerManager(this)
        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val scanResult = scannerManager.parseResult(result.resultCode, result.data)
            scanResult?.let { completedScan ->
                completedScan.pdfUri?.let { scannedPdfUri ->
                Toast.makeText(this, getString(R.string.document_scanned_page_count, completedScan.pageCount), Toast.LENGTH_LONG).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    // Scans are user documents, not disposable exports: keep them in app-private
                    // persistent storage rather than cacheDir so they survive cache eviction.
                    val scanDirectory = File(filesDir, "scans")
                    val savedFile: File? = try {
                        if (!scanDirectory.exists() && !scanDirectory.mkdirs()) {
                            throw java.io.IOException("Unable to create the saved scans directory.")
                        }
                        if (!scanDirectory.isDirectory) {
                            throw java.io.IOException("The saved scans path is not a directory.")
                        }

                        val timestamp = System.currentTimeMillis()
                        val baseName = "Scan_$timestamp"
                        var finalFile = File(scanDirectory, "$baseName.pdf")
                        var suffix = 1
                        while (finalFile.exists()) {
                            finalFile = File(scanDirectory, "${baseName}_${suffix++}.pdf")
                        }
                        val temporaryFile = File.createTempFile("scan_work_", ".pdf", scanDirectory)
                        val watermarkResult = toolkitEngine.watermarkPdf(
                            sourceUri = scannedPdfUri,
                            watermarkText = getString(R.string.pdf_watermark_text),
                            outputFile = temporaryFile
                        )
                        watermarkResult.fold(
                            onSuccess = { output ->
                                if (output.isFile && output.length() > 0L && output.renameTo(finalFile)) {
                                    // Preserve ML Kit's original page images alongside the PDF. They
                                    // are copied byte-for-byte (not resized or recompressed) for future
                                    // page export/reprocessing workflows.
                                    val copiedPageFiles = mutableListOf<File>()
                                    try {
                                        completedScan.pageImageUris.forEachIndexed { index, pageUri ->
                                            val pageFile = File(
                                                scanDirectory,
                                                "${baseName}_page_${index + 1}.jpg"
                                            )
                                            copiedPageFiles.add(pageFile)
                                            contentResolver.openInputStream(pageUri)?.use { input ->
                                                pageFile.outputStream().use { outputStream ->
                                                    input.copyTo(outputStream)
                                                }
                                            } ?: throw java.io.IOException("Unable to read scanned page ${index + 1}.")
                                            if (!pageFile.isFile || pageFile.length() == 0L) {
                                                throw java.io.IOException("Scanned page ${index + 1} was empty.")
                                            }
                                        }
                                    } catch (pageError: Exception) {
                                        copiedPageFiles.forEach { it.delete() }
                                        Log.w(TAG, "PDF saved, but original page images could not all be retained.", pageError)
                                    }
                                    finalFile
                                } else {
                                    output.delete()
                                    null
                                }
                            },
                            onFailure = {
                                temporaryFile.delete()
                                null
                            }
                        )
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to save scanned PDF to persistent storage", error)
                        null
                    }

                    launch(Dispatchers.Main) {
                        if (savedFile != null) {
                            pendingPdfUriState.value = Uri.fromFile(savedFile)
                            dashboardViewModel.refreshRecentDocuments()
                            scannerHistoryViewModel.refresh()
                            recordSuccessfulPdfOperation()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.error_watermark_scanned_pdf),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                }
            }
        }

        saveScanLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { destinationUri ->
            val sourceFile = pendingScanExportFile
            pendingScanExportFile = null
            if (destinationUri != null && sourceFile != null && sourceFile.isFile) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                            sourceFile.inputStream().use { input -> input.copyTo(output) }
                        } ?: throw java.io.IOException("The selected download location could not be opened.")
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            if (result.isSuccess) getString(R.string.scan_save_success) else getString(R.string.scan_save_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // SAF is the default document access path and does not require broad storage
        // permissions. Legacy/storage-discovery access is only requested after the user
        // explicitly asks to scan the entire device.

        // 3. Preload AdMob interstitial
        AdMobManager.preloadInterstitial(this)

        // 4. Inspect incoming Intent (ACTION_VIEW handler for PDF files)
        pendingPdfUriState.value = handleIncomingIntent(intent)

        setContent {
            VibeSyncPdfTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val currentBackStackEntry by navController.currentBackStackEntryAsState()
                    val pendingPdfUri by pendingPdfUriState.collectAsState()

                    LaunchedEffect(currentBackStackEntry?.destination?.route) {
                        activeRoute = currentBackStackEntry?.destination?.route ?: "dashboard"
                    }
                    LaunchedEffect(activeRoute, internetAvailable, reviewEligibilityRevision) {
                        if (internetAvailable) {
                            offlinePromptVisible = false
                            return@LaunchedEffect
                        }
                        if (activeRoute != "dashboard" || hasShownOfflinePromptThisSession) return@LaunchedEffect
                        val lastShown = engagementPreferences.getLong(KEY_OFFLINE_PROMPT_LAST_SHOWN, 0L)
                        if (!EngagementPolicy.shouldShowOfflinePrompt(false, System.currentTimeMillis(), lastShown)) {
                            return@LaunchedEffect
                        }
                        delay(1_600L)
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            !internetAvailable && activeRoute == "dashboard" && !hasShownOfflinePromptThisSession
                        ) {
                            hasShownOfflinePromptThisSession = true
                            offlinePromptVisible = true
                            engagementPreferences.edit().putLong(KEY_OFFLINE_PROMPT_LAST_SHOWN, System.currentTimeMillis()).apply()
                        }
                    }
                    LaunchedEffect(activeRoute, internetAvailable, reviewEligibilityRevision, updatePromptVisible,
                        offlinePromptVisible, updateDownloaded, updateReadyDialogVisible) {
                        if (activeRoute == "dashboard" && internetAvailable) {
                            showPendingUpdatePrompt()
                            if (!updatePromptVisible && !offlinePromptVisible && !updateDownloaded &&
                                !updateReadyDialogVisible && reviewEligibilityTracker.isEligible()
                            ) {
                                delay(30_000L)
                                maybeLaunchReviewIfEligible()
                            }
                        }
                    }

                    // Automatically forward ACTION_VIEW, ACTION_SEND, and scanner results to Viewer.
                    LaunchedEffect(pendingPdfUri) {
                        pendingPdfUri?.let { uri ->
                            val encoded = encodeUriRoute(uri)
                            navController.navigate("viewer/$encoded") {
                                launchSingleTop = true
                            }
                            pendingPdfUriState.value = null
                        }
                    }

                    NavHost(navController = navController, startDestination = "dashboard") {
                        // Dashboard Tool Hub
                        composable("dashboard") {
                            DashboardScreen(
                                viewModel = dashboardViewModel,
                                onNavigateToViewer = { uri ->
                                    val encoded = encodeUriRoute(uri)
                                    navController.navigate("viewer/$encoded")
                                },
                                onNavigateToEditor = { uri ->
                                    val encoded = encodeUriRoute(uri)
                                    navController.navigate("editor/$encoded")
                                },
                                onNavigateToTool = { route ->
                                    navController.navigate(route)
                                },
                                onLaunchScanner = {
                                    scannerManager.startScan(scannerLauncher) { error ->
                                        Toast.makeText(this@MainActivity, getString(R.string.error_scanner, error.message ?: ""), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRequestStoragePermission = {
                                    requestStoragePermissionFlow()
                                },
                                onSelectLanguage = { languageTag ->
                                    val locales = if (languageTag.isBlank()) {
                                        LocaleListCompat.getEmptyLocaleList()
                                    } else {
                                        LocaleListCompat.forLanguageTags(languageTag)
                                    }
                                    AppCompatDelegate.setApplicationLocales(locales)
                                }
                            )
                        }

                        // Saved scans are browsed and managed before the user launches the camera.
                        composable("scan_history") {
                            ScanHistoryScreen(
                                viewModel = scannerHistoryViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onNewScan = {
                                    scannerManager.startScan(scannerLauncher) { error ->
                                        Toast.makeText(
                                            this@MainActivity,
                                            getString(R.string.error_scanner, error.message ?: ""),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onOpenScan = { uri ->
                                    navController.navigate("viewer/" + encodeUriRoute(uri))
                                },
                                onSharePdf = { file -> shareScanPdf(file) },
                                onShareImages = { file -> shareScanImages(file) },
                                onSaveToDownloads = { file -> saveScanToDownloads(file) }
                            )
                        }

                        // Full-Screen System PDF Viewer
                        composable(
                            route = "viewer/{encodedUri}",
                            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val encodedUri = backStackEntry.arguments?.getString("encodedUri") ?: ""
                            val decodedUri = decodeUriRoute(encodedUri)

                            val isManagedScan = scannerHistoryViewModel.isManagedScanUri(decodedUri)
                            val managedScanFile = scannerHistoryRepository.fileFromUri(decodedUri)
                            PdfViewerScreen(
                                documentUri = decodedUri,
                                viewModel = viewerViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToEditor = { uri ->
                                    val enc = encodeUriRoute(uri)
                                    navController.navigate("editor/$enc")
                                },
                                onShareDocument = {
                                    if (isManagedScan && managedScanFile != null) shareScanPdf(managedScanFile)
                                    else shareViewedDocument(decodedUri)
                                },
                                isManagedScan = isManagedScan && managedScanFile != null,
                                onRenameScan = { requestedName ->
                                    if (managedScanFile != null) {
                                        scannerHistoryViewModel.renameScan(managedScanFile, requestedName) { result ->
                                            result.onSuccess { renamedFile ->
                                                val oldRoute = "viewer/" + encodeUriRoute(decodedUri)
                                                val newRoute = "viewer/" + encodeUriRoute(Uri.fromFile(renamedFile))
                                                // Remove the old URI route before opening the renamed file;
                                                // otherwise Back could reopen the now-missing original path.
                                                navController.popBackStack()
                                                navController.navigate(newRoute) {
                                                    launchSingleTop = true
                                                }
                                            }.onFailure { error ->
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    error.localizedMessage ?: getString(R.string.scan_operation_failed),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                onShareScanPdf = { managedScanFile?.let { shareScanPdf(it) } },
                                onShareScanImages = { managedScanFile?.let { shareScanImages(it) } },
                                onSaveScanToDownloads = { managedScanFile?.let { saveScanToDownloads(it) } },
                                onDeleteScan = {
                                    if (managedScanFile != null) {
                                        scannerHistoryViewModel.deleteScan(managedScanFile) { result ->
                                            result.onSuccess { navController.popBackStack() }
                                                .onFailure { error ->
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        error.localizedMessage ?: getString(R.string.scan_operation_failed),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                        }
                                    }
                                }
                            )
                        }

                        // Annotation & Digital Signature Editor
                        composable(
                            route = "editor/{encodedUri}",
                            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val encodedUri = backStackEntry.arguments?.getString("encodedUri") ?: ""
                            val decodedUri = decodeUriRoute(encodedUri)

                            PdfEditorScreen(
                                documentUri = decodedUri,
                                viewModel = editorViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onExportComplete = { savedFile ->
                                    recordSuccessfulPdfOperation()
                                    shareFiles(listOf(savedFile), "application/pdf", "VibeSync_Edit_Signed")
                                    navController.popBackStack()
                                }
                            )
                        }

                        // Toolkit Screen: Split
                        composable("split") {
                            SplitScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { files ->
                                    recordSuccessfulPdfOperation()
                                    shareFiles(files, "application/pdf", "VibeSync_Split_Document")
                                    navController.popBackStack()
                                }
                            )
                        }

                        // Toolkit Screen: Merge
                        composable("merge") {
                            MergeScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { mergedFile ->
                                    recordSuccessfulPdfOperation()
                                    shareFiles(listOf(mergedFile), "application/pdf", "VibeSync_Merged_Document")
                                    navController.popBackStack()
                                }
                            )
                        }

                        // Toolkit Screen: Encrypt
                        composable("encrypt") {
                            EncryptScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { encryptedFile ->
                                    recordSuccessfulPdfOperation()
                                    shareFiles(listOf(encryptedFile), "application/pdf", "VibeSync_Protected_Document")
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("convert") {
                            ConvertImagesScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { outputFile ->
                                    recordSuccessfulPdfOperation()
                                    shareFiles(listOf(outputFile), "application/pdf", "VibeSync_Images_To_PDF")
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("extract") {
                            ExtractImagesScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { files ->
                                    recordSuccessfulPdfOperation()
                                    shareFiles(files, "image/png", "VibeSync_Extracted_Images")
                                    navController.popBackStack()
                                }
                            )
                        }
                    }

                    if (updatePromptVisible && activeRoute == "dashboard" && !updateDownloaded) {
                        AlertDialog(
                            onDismissRequest = { deferUpdatePrompt() },
                            title = { Text(getString(R.string.update_available_title)) },
                            text = { Text(getString(R.string.update_available_message)) },
                            confirmButton = { TextButton(onClick = { beginUpdateFlow() }) { Text(getString(R.string.update_now)) } },
                            dismissButton = { TextButton(onClick = { deferUpdatePrompt() }) { Text(getString(R.string.update_later)) } }
                        )
                    }
                    if (updateReadyDialogVisible && updateDownloaded && activeRoute == "dashboard") {
                        AlertDialog(
                            onDismissRequest = { updateReadyDialogVisible = false },
                            title = { Text(getString(R.string.update_ready_title)) },
                            text = { Text(getString(R.string.update_ready_message)) },
                            confirmButton = { TextButton(onClick = { completeDownloadedUpdate() }) { Text(getString(R.string.restart_to_update)) } },
                            dismissButton = { TextButton(onClick = { updateReadyDialogVisible = false }) { Text(getString(R.string.update_later)) } }
                        )
                    }
                    if (offlinePromptVisible && !internetAvailable && activeRoute == "dashboard") {
                        AlertDialog(
                            onDismissRequest = { dismissOfflinePrompt() },
                            title = { Text(getString(R.string.offline_support_title)) },
                            text = { Text(getString(R.string.offline_support_message)) },
                            confirmButton = {
                                TextButton(onClick = { dismissOfflinePrompt(); openInternetSettings() }) {
                                    Text(getString(R.string.connect_to_internet))
                                }
                            },
                            dismissButton = { TextButton(onClick = { dismissOfflinePrompt() }) { Text(getString(R.string.not_now)) } }
                        )
                    }
                }
            }
        }
    }

    private fun saveScanToDownloads(scanFile: File) {
        if (!scanFile.isFile || scanFile.length() <= 0L) {
            Toast.makeText(this, getString(R.string.scan_operation_failed), Toast.LENGTH_LONG).show()
            return
        }
        pendingScanExportFile = scanFile
        saveScanLauncher.launch(scanFile.name)
    }

    private fun shareScanPdf(scanFile: File) {
        if (!scanFile.isFile || scanFile.length() <= 0L) {
            Toast.makeText(this, getString(R.string.scan_operation_failed), Toast.LENGTH_LONG).show()
            return
        }
        try {
            val uri = UriUtils.getShareableUri(this, scanFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, scanFile.name, uri)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_pdf)))
        } catch (error: Exception) {
            Toast.makeText(this, error.localizedMessage ?: getString(R.string.scan_operation_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun shareScanImages(scanFile: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = scannerHistoryRepository.exportPageImagesForSharing(
                scanFile,
                File(cacheDir, "pdf_cache")
            )
            withContext(Dispatchers.Main) {
                result.onSuccess { imageFiles ->
                    try {
                        val uris = imageFiles.map { UriUtils.getShareableUri(this@MainActivity, it) }
                        if (uris.isEmpty()) {
                            Toast.makeText(this@MainActivity, getString(R.string.scan_images_unavailable), Toast.LENGTH_LONG).show()
                            return@onSuccess
                        }
                        val clip = ClipData.newUri(contentResolver, "Scanned pages", uris.first())
                        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/jpeg"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = clip
                        }
                        startActivity(Intent.createChooser(intent, getString(R.string.scan_share_images)))
                    } catch (error: Exception) {
                        Toast.makeText(this@MainActivity, error.localizedMessage ?: getString(R.string.scan_images_unavailable), Toast.LENGTH_LONG).show()
                    }
                }.onFailure { error ->
                    Toast.makeText(
                        this@MainActivity,
                        error.localizedMessage ?: getString(R.string.scan_images_unavailable),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun shareViewedDocument(sourceUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val output = UriUtils.createExportCacheFile(this@MainActivity, "VibeSync_Viewed_Document")
            val result = toolkitEngine.watermarkPdf(
                sourceUri = sourceUri,
                watermarkText = getString(R.string.pdf_watermark_text),
                outputFile = output
            )
            launch(Dispatchers.Main) {
                result.onSuccess {
                    recordSuccessfulPdfOperation()
                    shareFiles(listOf(output), "application/pdf", "VibeSync_Viewed_Document")
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, error.localizedMessage ?: getString(R.string.error_prepare_pdf_sharing), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun shareFiles(files: List<File>, mimeType: String, defaultBaseName: String) {
        val validFiles = files.filter { it.exists() && it.length() > 0L }
        if (validFiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_output_files), Toast.LENGTH_LONG).show()
            return
        }

        val extension = if (mimeType == "image/png") ".png" else ".pdf"
        val defaultName = defaultBaseName.removeSuffix(".pdf").removeSuffix(".png")
        val input = EditText(this).apply {
            setText(defaultName)
            selectAll()
            hint = getString(R.string.file_name)
            setSingleLine(true)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.output_name_dialog_title))
            .setMessage(getString(R.string.output_name_dialog_message))
            .setView(input)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.continue_label)) { _, _ ->
                val requested = sanitizeOutputBaseName(input.text?.toString().orEmpty(), defaultName)
                lifecycleScope.launch(Dispatchers.IO) {
                    val renamedFiles = renameOutputFiles(validFiles, requested, extension)
                    launch(Dispatchers.Main) {
                        if (renamedFiles.isEmpty()) {
                            Toast.makeText(this@MainActivity, getString(R.string.error_rename_output_files), Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        shareRenamedFiles(renamedFiles, mimeType)
                    }
                }
            }
            .show()
    }

    private fun shareRenamedFiles(files: List<File>, mimeType: String) {
        val uris = files.map { UriUtils.getShareableUri(this, it) }
        val shareIntent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "VibeSync output", uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, "VibeSync output", uris.first())
            }
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_vibesync_output)))
    }

    private fun renameOutputFiles(files: List<File>, baseName: String, extension: String): List<File> {
        return files.mapIndexedNotNull { index, source ->
            val targetName = if (files.size == 1) {
                baseName + extension
            } else {
                baseName + "_" + (index + 1) + extension
            }
            val target = File(source.parentFile ?: cacheDir, targetName)
            if (source.absolutePath == target.absolutePath) {
                source
            } else if (source.renameTo(target)) {
                target
            } else {
                try {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                    target
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun sanitizeOutputBaseName(raw: String, fallback: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(".pdf")
            .removeSuffix(".png")
        return cleaned.ifBlank { fallback }.take(80)
    }

    private fun encodeUriRoute(uri: Uri): String =
        Base64.encodeToString(
            uri.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

    private fun decodeUriRoute(encoded: String): Uri {
        val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
        return Uri.parse(String(bytes, StandardCharsets.UTF_8))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingPdfUriState.value = handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?): Uri? {
        if (intent == null) return null
        val action = intent.action
        val type = intent.type

        val incomingUri = if (
            (Intent.ACTION_VIEW == action || Intent.ACTION_SEND == action) &&
            type == "application/pdf"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else {
            intent.data
        }

        // ACTION_VIEW/ACTION_SEND usually grant temporary access. Retain it only when the sender
        // explicitly marked the content URI grant as persistable; otherwise the renderer promptly
        // copies the stream into app-private cache while the temporary grant is active.
        if (
            incomingUri?.scheme == "content" &&
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        ) {
            val persisted = UriUtils.persistReadPermission(this, incomingUri)
            if (!persisted) {
                Log.w(TAG, "Incoming PDF URI did not permit persistent read access; staging it from the active grant.")
            } else {
                Log.i(TAG, "Persisted read access for incoming PDF content URI.")
            }
        }
        return incomingUri
    }

    private fun checkAndRequestPermissions() {
        // This legacy path is reached only on Android 10 and earlier, where the
        // device-wide PDF discovery feature still uses READ_EXTERNAL_STORAGE.
        // Image selection must use the system Photo Picker and never requests
        // broad photo/video library access.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    override fun onStart() {
        super.onStart()
        registerConnectivityCallback()
    }

    override fun onStop() {
        unregisterConnectivityCallback()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        reviewEligibilityTracker.recordSessionIfDue()
        reviewEligibilityRevision += 1
        dashboardViewModel.checkPermissionAndScan()
        refreshInternetStatus()
        maybeCheckForUpdate()
    }

    override fun onDestroy() {
        if (::appUpdateManager.isInitialized) runCatching { appUpdateManager.unregisterListener(updateInstallStateListener) }
        super.onDestroy()
    }

    private fun registerConnectivityCallback() {
        if (connectivityCallbackRegistered) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            manager.registerDefaultNetworkCallback(connectivityCallback)
            connectivityCallbackRegistered = true
        } catch (error: Exception) {
            Log.w(TAG, "Could not register connectivity callback.", error)
        }
        refreshInternetStatus()
    }

    private fun unregisterConnectivityCallback() {
        if (!connectivityCallbackRegistered) return
        getSystemService(ConnectivityManager::class.java)?.let {
            runCatching { it.unregisterNetworkCallback(connectivityCallback) }
        }
        connectivityCallbackRegistered = false
    }

    private fun refreshInternetStatus() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val validated = caps?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
        runOnUiThread { applyConnectivityState(validated) }
    }

    private fun applyConnectivityState(available: Boolean) {
        val wasAvailable = internetAvailable
        internetAvailable = available
        AdMobManager.onConnectivityChanged(applicationContext, available)
        if (available) {
            offlinePromptVisible = false
            if (!wasAvailable) maybeCheckForUpdate(force = true)
            reviewEligibilityRevision += 1
        } else updatePromptVisible = false
    }

    private fun maybeCheckForUpdate(force: Boolean = false) {
        if (!::appUpdateManager.isInitialized || !internetAvailable || isFinishing || isDestroyed || updateCheckInProgress) return
        val now = System.currentTimeMillis()
        val lastCheck = engagementPreferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        val immediateInProgress = engagementPreferences.getBoolean(KEY_IMMEDIATE_UPDATE_IN_PROGRESS, false)
        if (!EngagementPolicy.shouldCheckForUpdate(
                isInternetValidated = internetAvailable,
                nowMs = now,
                lastCheckMs = lastCheck,
                force = force,
                immediateUpdateInProgress = immediateInProgress
            )
        ) {
            showPendingUpdatePrompt()
            return
        }
        updateCheckInProgress = true
        engagementPreferences.edit().putLong(KEY_LAST_UPDATE_CHECK, now).apply()
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info ->
                updateCheckInProgress = false
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    updateDownloaded = true
                    updateReadyDialogVisible = true
                    updatePromptVisible = false
                    return@addOnSuccessListener
                }
                when (info.updateAvailability()) {
                    UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                        if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) beginUpdateFlow(info, AppUpdateType.IMMEDIATE)
                    }
                    UpdateAvailability.UPDATE_AVAILABLE -> {
                        // Show the confirmed availability even if an update flow type cannot be started;
                        // beginUpdateFlow() will safely fall back to the Play Store listing in that case.
                        pendingUpdateInfo = info
                        showPendingUpdatePrompt()
                    }
                    else -> {
                        pendingUpdateInfo = null
                        updatePromptVisible = false
                    }
                }
            }
            .addOnFailureListener { error ->
                updateCheckInProgress = false
                Log.w(TAG, "Google Play update availability check failed.", error)
            }
    }

    private fun showPendingUpdatePrompt() {
        val shouldPrompt = EngagementPolicy.shouldPromptForUpdate(
            updateAvailable = pendingUpdateInfo != null,
            isInternetValidated = internetAvailable,
            nowMs = System.currentTimeMillis(),
            snoozeUntilMs = engagementPreferences.getLong(KEY_UPDATE_SNOOZE_UNTIL, 0L)
        )
        if (shouldPrompt && activeRoute == "dashboard" && !updateDownloaded) {
            updatePromptVisible = true
        }
    }

    private fun deferUpdatePrompt() {
        updatePromptVisible = false
        engagementPreferences.edit()
            .putBoolean(KEY_IMMEDIATE_UPDATE_IN_PROGRESS, false)
            .putLong(KEY_UPDATE_SNOOZE_UNTIL, System.currentTimeMillis() + UPDATE_PROMPT_COOLDOWN_MS)
            .apply()
    }

    private fun beginUpdateFlow() {
        if (!internetAvailable) {
            updatePromptVisible = false
            return
        }
        val info = pendingUpdateInfo ?: run {
            updatePromptVisible = false
            maybeCheckForUpdate(force = true)
            return
        }
        val type = when {
            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
            else -> null
        }
        if (type == null) fallbackToPlayStoreAndSnooze() else beginUpdateFlow(info, type)
    }

    private fun beginUpdateFlow(info: AppUpdateInfo, type: Int) {
        if (updateFlowStarted) return
        updateFlowStarted = true
        updatePromptVisible = false
        if (type == AppUpdateType.IMMEDIATE) engagementPreferences.edit().putBoolean(KEY_IMMEDIATE_UPDATE_IN_PROGRESS, true).apply()
        try {
            val started = appUpdateManager.startUpdateFlowForResult(info, updateFlowLauncher, AppUpdateOptions.newBuilder(type).build())
            if (!started) {
                updateFlowStarted = false
                fallbackToPlayStoreAndSnooze()
            } else {
                // Play documents AppUpdateInfo as single-use for starting an update flow.
                pendingUpdateInfo = null
            }
        } catch (error: Exception) {
            updateFlowStarted = false
            Log.w(TAG, "Could not start Google Play in-app update.", error)
            fallbackToPlayStoreAndSnooze()
        }
    }

    private fun completeDownloadedUpdate() {
        appUpdateManager.completeUpdate()
            .addOnSuccessListener {
                updateReadyDialogVisible = false
                updateDownloaded = false
                pendingUpdateInfo = null
                engagementPreferences.edit()
                    .putBoolean(KEY_IMMEDIATE_UPDATE_IN_PROGRESS, false)
                    .putLong(KEY_UPDATE_SNOOZE_UNTIL, 0L)
                    .apply()
            }
            .addOnFailureListener { error -> Log.w(TAG, "Could not complete the downloaded update.", error) }
    }

    private fun fallbackToPlayStoreAndSnooze() {
        pendingUpdateInfo = null
        updatePromptVisible = false
        engagementPreferences.edit()
            .putBoolean(KEY_IMMEDIATE_UPDATE_IN_PROGRESS, false)
            .putLong(KEY_UPDATE_SNOOZE_UNTIL, System.currentTimeMillis() + UPDATE_PROMPT_COOLDOWN_MS)
            .apply()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${BuildConfig.APPLICATION_ID}")))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the HTTPS Play listing.
        } catch (error: Exception) {
            Log.w(TAG, "Could not open Play Store.", error)
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}")))
        } catch (error: Exception) {
            Log.w(TAG, "Could not open Play listing.", error)
            Toast.makeText(this, getString(R.string.update_store_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    private fun openInternetSettings() {
        try {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (error: Exception) {
                Log.w(TAG, "Could not open network settings.", error)
                Toast.makeText(this, getString(R.string.internet_settings_unavailable), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dismissOfflinePrompt() {
        offlinePromptVisible = false
        hasShownOfflinePromptThisSession = true
        engagementPreferences.edit().putLong(KEY_OFFLINE_PROMPT_LAST_SHOWN, System.currentTimeMillis()).apply()
    }

    private fun recordSuccessfulPdfOperation() {
        reviewEligibilityTracker.recordSuccessfulOperation()
        reviewEligibilityRevision += 1
    }

    private fun maybeLaunchReviewIfEligible() {
        if (reviewFlowLaunching || !internetAvailable || activeRoute != "dashboard" || updatePromptVisible ||
            offlinePromptVisible || updateDownloaded || updateReadyDialogVisible
        ) return
        if (pendingUpdateInfo != null) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val now = System.currentTimeMillis()
        if (!reviewEligibilityTracker.isEligible(now)) return
        reviewFlowLaunching = true
        try {
            val manager = ReviewManagerFactory.create(this)
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (request.isSuccessful &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !isFinishing && !isDestroyed
                ) {
                    // Start the cooldown only when Play accepted the request and the Activity
                    // is still safe to present UI. Play never reports whether a review was submitted.
                    reviewEligibilityTracker.markAttempt()
                    manager.launchReviewFlow(this, request.result).addOnCompleteListener {
                        reviewFlowLaunching = false
                    }
                } else {
                    if (!request.isSuccessful) {
                        Log.w(TAG, "Google Play review request failed.", request.exception)
                    }
                    reviewFlowLaunching = false
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Google Play in-app review is unavailable.", error)
            reviewFlowLaunching = false
        }
    }

    private fun requestStoragePermissionFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            checkAndRequestPermissions()
        }
    }

    private companion object {
        const val TAG = "VibeSyncMainActivity"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_ms"
        const val KEY_UPDATE_SNOOZE_UNTIL = "update_snooze_until_ms"
        const val KEY_IMMEDIATE_UPDATE_IN_PROGRESS = "immediate_update_in_progress"
        const val KEY_OFFLINE_PROMPT_LAST_SHOWN = "offline_prompt_last_shown_ms"
        const val UPDATE_PROMPT_COOLDOWN_MS = 24L * 60L * 60L * 1000L
    }
}
