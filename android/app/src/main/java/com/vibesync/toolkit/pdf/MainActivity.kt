package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.data.pdf.PdfBitmapUtils
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.widget.Toast
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.vibesync.toolkit.pdf.data.pdf.createWhiteBackgroundBitmap
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardScreen
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardViewModel
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import com.vibesync.toolkit.pdf.presentation.editor.PdfEditorScreen
import com.vibesync.toolkit.pdf.presentation.editor.SignatureManagerScreen
import com.vibesync.toolkit.pdf.presentation.scanner.ManualCropScreen
import com.vibesync.toolkit.pdf.presentation.vault.VaultGate
import com.vibesync.toolkit.pdf.presentation.vault.VaultScreen
import com.vibesync.toolkit.pdf.data.vault.VaultStorage
import com.vibesync.toolkit.pdf.presentation.monetization.AdMobManager
import com.vibesync.toolkit.pdf.presentation.scanner.DocumentScannerManager
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanViewModel
import com.vibesync.toolkit.pdf.presentation.scanner.BatchScanScreen
import com.vibesync.toolkit.pdf.presentation.scanner.BookSplitEntryScreen
import com.vibesync.toolkit.pdf.presentation.scanner.BookSplitScreen
import com.vibesync.toolkit.pdf.presentation.tools.IdCardComposerScreen
import com.vibesync.toolkit.pdf.presentation.tools.IdPhotoMakerScreen
import com.vibesync.toolkit.pdf.presentation.scanner.ScanHistoryScreen
import com.vibesync.toolkit.pdf.presentation.scanner.ScannerHistoryRepository
import com.vibesync.toolkit.pdf.presentation.scanner.ScannerHistoryViewModel
import com.vibesync.toolkit.pdf.presentation.tools.ConvertImagesScreen
import com.vibesync.toolkit.pdf.presentation.tools.EncryptScreen
import com.vibesync.toolkit.pdf.presentation.tools.ExtractImagesScreen
import com.vibesync.toolkit.pdf.presentation.tools.MergeScreen
import com.vibesync.toolkit.pdf.presentation.tools.PdfQuickActionScreen
import com.vibesync.toolkit.pdf.presentation.tools.SplitScreen
import com.vibesync.toolkit.pdf.presentation.translation.TranslationScanScreen
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
    private var pendingShareFiles by mutableStateOf<List<File>>(emptyList())
    private var showRenameDialog by mutableStateOf(false)
    private var pendingShareMimeType by mutableStateOf("application/pdf")
    private var pendingShareExtension by mutableStateOf(".pdf")
    private var pendingShareDefaultBaseName by mutableStateOf("document")
    private var pendingShareForceMultiple by mutableStateOf(false)
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
    private val batchScanViewModel: BatchScanViewModel by viewModels()
    private val scannerHistoryRepository by lazy { ScannerHistoryRepository(File(filesDir, "scans")) }
    private val toolkitEngine by lazy { PdfBoxToolkitEngine(applicationContext) }

    private lateinit var scannerManager: DocumentScannerManager
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var saveScanLauncher: ActivityResultLauncher<String>
    private var pendingScanExportFile: File? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
            if (batchScanViewModel.handleCaptureResult(result.resultCode, scanResult?.pageImageUris.orEmpty())) {
                return@registerForActivityResult
            }
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
                        val requestedBaseName = "Scan_$timestamp"
                        var finalFile = File(scanDirectory, "$requestedBaseName.pdf")
                        var suffix = 1
                        while (finalFile.exists() || scanDirectory.listFiles { _, name ->
                                name.startsWith("${finalFile.nameWithoutExtension}_page_")
                            }?.isNotEmpty() == true
                        ) {
                            finalFile = File(scanDirectory, "${requestedBaseName}_${suffix++}.pdf")
                        }
                        // Use the unique PDF stem for all page images, including rare timestamp collisions.
                        val baseName = finalFile.nameWithoutExtension
                        val temporaryFile = File.createTempFile("scan_work_", ".pdf", scanDirectory)
                        val watermarkResult = toolkitEngine.watermarkPdf(
                            sourceUri = scannedPdfUri,
                            watermarkText = getString(R.string.pdf_watermark_text),
                            outputFile = temporaryFile
                        )
                        watermarkResult.fold(
                            onSuccess = { output ->
                                if (output.isFile && output.length() > 0L && output.renameTo(finalFile)) {
                                    // ML Kit's PDF is loaded and saved with a vector footer watermark;
                                    // its embedded page images are not rasterized/recompressed here.
                                    // Keep the original JPEG page bytes at their captured resolution.
                                    val copiedPageFiles = mutableListOf<File>()
                                    try {
                                        completedScan.pageImageUris.forEachIndexed { index, pageUri ->
                                            val pageFile = File(
                                                scanDirectory,
                                                "${baseName}_page_${index + 1}.jpg"
                                            )
                                            val temporaryPageFile = File.createTempFile(
                                                "scan_page_work_", ".jpg", scanDirectory
                                            )
                                            try {
                                                contentResolver.openInputStream(pageUri)?.use { input ->
                                                    temporaryPageFile.outputStream().use { outputStream ->
                                                        input.copyTo(outputStream)
                                                    }
                                                } ?: throw java.io.IOException("Unable to read scanned page ${index + 1}.")
                                                if (!temporaryPageFile.isFile || temporaryPageFile.length() == 0L) {
                                                    throw java.io.IOException("Scanned page ${index + 1} was empty.")
                                                }
                                                if (!temporaryPageFile.renameTo(pageFile)) {
                                                    throw java.io.IOException("Could not persist scanned page ${index + 1}.")
                                                }
                                                copiedPageFiles.add(pageFile)
                                            } finally {
                                                temporaryPageFile.delete()
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

                    NavHost(
                        navController = navController,
                        startDestination = "dashboard",
                        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars)
                    ) {
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
                                onSelectLanguage = { languageTag ->
                                    val locales = if (languageTag.isBlank()) {
                                        LocaleListCompat.getEmptyLocaleList()
                                    } else {
                                        LocaleListCompat.forLanguageTags(languageTag)
                                    }
                                    AppCompatDelegate.setApplicationLocales(locales)
                                },
                                onSharePdfDocument = { uri -> shareDocumentPdf(uri) },
                                onShareJpgImages = { uri -> shareDocumentAsJpgImages(uri) },
                                onRenameDocument = { uri, name -> renamePdfDocument(uri, name) },
                                onDeleteDocument = { uri -> deletePdfDocument(uri) },
                                onNavigateToPdfTool = { route, uri ->
                                    if (route == "watermark") getSharedPreferences("pdf_watermark_options", MODE_PRIVATE).edit().putBoolean("watermark_custom_enabled", true).apply()
                                    navController.navigate("$route/${encodeUriRoute(uri)}")
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
                                onSaveToDownloads = { file -> saveScanToDownloads(file) },
                                onNavigateToEditor = { uri -> navController.navigate("editor/${encodeUriRoute(uri)}") },
                                onNavigateToPdfTool = { route, uri ->
                                    if (route == "watermark") getSharedPreferences("pdf_watermark_options", MODE_PRIVATE).edit().putBoolean("watermark_custom_enabled", true).apply()
                                    navController.navigate("$route/${encodeUriRoute(uri)}")
                                },
                                onSplitBook = { imageUri -> navController.navigate("book_split/${encodeUriRoute(imageUri)}") }
                            )
                        }

                        composable("batch_scan") {
                            BatchScanScreen(
                                viewModel = batchScanViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onStartCapture = {
                                    scannerManager.startBatchScan(scannerLauncher) { error ->
                                        Toast.makeText(this@MainActivity, getString(R.string.error_scanner, error.message ?: ""), Toast.LENGTH_LONG).show()
                                    }
                                },
                                onOpenPdf = { file ->
                                    scannerHistoryViewModel.refresh()
                                    dashboardViewModel.refreshRecentDocuments()
                                    navController.navigate("viewer/${encodeUriRoute(Uri.fromFile(file))}")
                                }
                            )
                        }

                        composable("book_split") {
                            BookSplitEntryScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onChooseImage = { uri -> navController.navigate("book_split/${encodeUriRoute(uri)}") }
                            )
                        }

                        composable(
                            route = "book_split/{encodedUri}",
                            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType })
                        ) { entry ->
                            val imageUri = decodeUriRoute(entry.arguments?.getString("encodedUri") ?: "")
                            BookSplitScreen(
                                imageUri = imageUri,
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { file ->
                                    scannerHistoryViewModel.refresh()
                                    navController.navigate("viewer/${encodeUriRoute(Uri.fromFile(file))}")
                                }
                            )
                        }

                        composable("id_card") {
                            IdCardComposerScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable("id_photo") {
                            IdPhotoMakerScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable("translation_scan") {
                            TranslationScanScreen(onNavigateBack = { navController.popBackStack() })
                        }

                        composable("manual_crop") {
                            val cropScope = rememberCoroutineScope()
                            ManualCropScreen(
                                onNavigateBack = {
                                    if (!navController.popBackStack("scan_history", false)) {
                                        navController.navigate("scan_history") {
                                            popUpTo("dashboard") { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                onCommit = { croppedBitmap ->
                                    cropScope.launch {
                                        try {
                                            val saved = withContext(Dispatchers.IO) {
                                                val scansDirectory = File(filesDir, "scans")
                                                if (!scansDirectory.exists() && !scansDirectory.mkdirs()) {
                                                    throw java.io.IOException("Unable to create cropped image storage.")
                                                }
                                                val temporaryPng = File.createTempFile("manual_crop_", ".png", cacheDir)
                                                try {
                                                    temporaryPng.outputStream().use { stream ->
                                                        if (!croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                                                            throw java.io.IOException("Unable to encode the cropped image.")
                                                        }
                                                    }
                                                    val output = File(scansDirectory, "Manual_Crop_${System.currentTimeMillis()}.pdf")
                                                    PdfBoxToolkitEngine(applicationContext)
                                                        .convertImagesToPdf(listOf(Uri.fromFile(temporaryPng)), output)
                                                        .getOrThrow()
                                                    output
                                                } finally {
                                                    temporaryPng.delete()
                                                }
                                            }
                                            scannerHistoryViewModel.refresh()
                                            Toast.makeText(this@MainActivity, getString(R.string.manual_crop_saved, saved.name), Toast.LENGTH_LONG).show()
                                        } catch (error: Exception) {
                                            Toast.makeText(this@MainActivity, error.localizedMessage ?: getString(R.string.manual_crop_error), Toast.LENGTH_LONG).show()
                                        } finally {
                                            if (!croppedBitmap.isRecycled) croppedBitmap.recycle()
                                            navController.popBackStack()
                                        }
                                    }
                                }
                            )
                        }

                        composable("signature_manager") {
                            SignatureManagerScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSelectSignature = { bitmap, _, _, _, _, _ ->
                                    Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.signature_manager_open_editor_hint),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                }
                            )
                        }

                        composable("vault") {
                            val vaultStorage = remember { VaultStorage(applicationContext) }
                            VaultScreen(
                                storage = vaultStorage,
                                onNavigateBack = { navController.popBackStack() },
                                onAuthenticate = { result ->
                                    val host = this@MainActivity as? FragmentActivity
                                    if (host == null) {
                                        result(false)
                                    } else {
                                        VaultGate().authenticate(host, result)
                                    }
                                }
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

                        // Quick-launch Split with the selected PDF pre-populated.
                        composable(
                            route = "split/{encodedUri}",
                            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType })
                        ) { entry ->
                            val initialUri = decodeUriRoute(entry.arguments?.getString("encodedUri") ?: "")
                            SplitScreen(
                                onNavigateBack = { navController.popBackStack() },
                                initialUri = initialUri,
                                onSuccess = { files ->
                                    recordSuccessfulPdfOperation()
                                    shareFiles(files, "application/pdf", "VibeSync_Split_Document")
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

                        // Quick-launch Encrypt with the selected PDF pre-populated.
                        composable(
                            route = "encrypt/{encodedUri}",
                            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType })
                        ) { entry ->
                            val initialUri = decodeUriRoute(entry.arguments?.getString("encodedUri") ?: "")
                            EncryptScreen(
                                onNavigateBack = { navController.popBackStack() },
                                initialUri = initialUri,
                                onSuccess = { encryptedFile ->
                                    recordSuccessfulPdfOperation()
                                    shareFiles(listOf(encryptedFile), "application/pdf", "VibeSync_Protected_Document")
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

                        composable(
                            route = "compress/{encodedUri}",
                            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType })
                        ) { entry ->
                            PdfQuickActionScreen(
                                mode = "compress",
                                initialUri = decodeUriRoute(entry.arguments?.getString("encodedUri") ?: ""),
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { output ->
                                    recordSuccessfulPdfOperation()
                                    shareFiles(listOf(output), "application/pdf", "VibeSync_Compressed_Document")
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable(
                            route = "watermark/{encodedUri}",
                            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType })
                        ) { entry ->
                            PdfQuickActionScreen(
                                mode = "watermark",
                                initialUri = decodeUriRoute(entry.arguments?.getString("encodedUri") ?: ""),
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { output ->
                                    recordSuccessfulPdfOperation()
                                    shareFiles(listOf(output), "application/pdf", "VibeSync_Watermarked_Document")
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
                    if (showRenameDialog) {
                        var newName by remember { mutableStateOf(pendingShareDefaultBaseName) }
                        AlertDialog(
                            onDismissRequest = { showRenameDialog = false },
                            title = { Text("Name your output file") },
                            text = {
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    label = { Text("File name") },
                                    singleLine = true
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showRenameDialog = false
                                    val requested = sanitizeOutputBaseName(newName, pendingShareDefaultBaseName)
                                    val filesToRename = pendingShareFiles
                                    val extension = pendingShareExtension
                                    val mimeType = pendingShareMimeType
                                    val forceMultiple = pendingShareForceMultiple
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val renamedFiles = renameOutputFiles(filesToRename, requested, extension)
                                        withContext(Dispatchers.Main) {
                                            if (isFinishing || isDestroyed) return@withContext
                                            if (renamedFiles.isEmpty()) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    getString(R.string.error_rename_output_files),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                return@withContext
                                            }
                                            shareRenamedFiles(renamedFiles, mimeType, forceMultiple)
                                        }
                                    }
                                }) { Text("Continue") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                            }
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

    private fun shareDocumentPdf(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val staged = runCatching {
                UriUtils.copyUriToCacheFile(this@MainActivity, uri, "share_pdf")
            }
            withContext(Dispatchers.Main) {
                staged.onSuccess { file -> shareFiles(listOf(file), "application/pdf", "VibeSync_Shared_Document") }
                    .onFailure { error ->
                        Toast.makeText(this@MainActivity, error.localizedMessage ?: getString(R.string.document_action_failed), Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    /** Render regular PDFs to print-quality JPEGs; these temporary bitmaps are never exposed to Compose. */
    private fun shareDocumentAsJpgImages(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val generated = mutableListOf<File>()
            var staged: File? = null
            val result = runCatching {
                staged = UriUtils.copyUriToCacheFile(this@MainActivity, uri, "share_jpg")
                val exportDir = File(cacheDir, "pdf_cache/document_jpg_exports")
                if (!exportDir.exists() && !exportDir.mkdirs()) throw java.io.IOException("Could not prepare JPG exports.")
                ParcelFileDescriptor.open(requireNotNull(staged), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount > 0) { "The PDF contains no pages." }
                        for (pageIndex in 0 until renderer.pageCount) {
                            renderer.openPage(pageIndex).use { page ->
                                val pointsArea = page.width.toDouble() * page.height.toDouble()
                                val scale = minOf(
                                    300f / 72f,
                                    6000f / maxOf(page.width, page.height).toFloat(),
                                    kotlin.math.sqrt(16_000_000.0 / pointsArea).toFloat()
                                )
                                val width = (page.width * scale).toInt().coerceAtLeast(1)
                                val height = (page.height * scale).toInt().coerceAtLeast(1)
                                val bitmap = createWhiteBackgroundBitmap(width, height)
                                try {
                                    val matrix = Matrix().apply {
                                        postScale(width.toFloat() / page.width.toFloat().coerceAtLeast(1f), height.toFloat() / page.height.toFloat().coerceAtLeast(1f))
                                    }
                                    page.render(bitmap, Rect(0, 0, width, height), matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    val output = UriUtils.createExportCacheFile(this@MainActivity, "document_page_${pageIndex + 1}", ".jpg")
                                    val flattenedBitmap = PdfBitmapUtils.ensureWhiteBackgroundForJpeg(bitmap)
                                    try {
                                        java.io.FileOutputStream(output).use { stream ->
                                            if (!flattenedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw java.io.IOException("Could not encode page JPEG.")
                                        }
                                    } finally {
                                        if (flattenedBitmap !== bitmap && !flattenedBitmap.isRecycled) flattenedBitmap.recycle()
                                    }
                                    generated += output
                                } finally {
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                }
                            }
                        }
                    }
                }
                if (generated.isEmpty()) throw java.io.IOException("No page images were created.")
                generated
            }
            staged?.delete()
            withContext(Dispatchers.Main) {
                result.onSuccess { shareFiles(it, "image/jpeg", "VibeSync_PDF_Pages", forceMultiple = true) }
                    .onFailure { error ->
                        generated.forEach { it.delete() }
                        Toast.makeText(this@MainActivity, error.localizedMessage ?: getString(R.string.document_action_failed), Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun renamePdfDocument(uri: Uri, requestedName: String) {
        if (scannerHistoryViewModel.isManagedScanUri(uri)) {
            val file = scannerHistoryRepository.fileFromUri(uri)
            if (file == null) {
                Toast.makeText(this, getString(R.string.document_action_failed), Toast.LENGTH_LONG).show()
                return
            }
            scannerHistoryViewModel.renameScan(file, requestedName) { result ->
                runOnUiThread {
                    result.onSuccess {
                        Toast.makeText(this, getString(R.string.document_rename_success), Toast.LENGTH_SHORT).show()
                        dashboardViewModel.refreshCurrentSource()
                        dashboardViewModel.refreshRecentDocuments()
                    }.onFailure { error ->
                        Toast.makeText(this, error.localizedMessage ?: getString(R.string.document_action_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val newName = requestedName.trim().removeSuffix(".pdf").removeSuffix(".PDF")
                .filter { it.isLetterOrDigit() || it.isWhitespace() || it in "-_()." }
                .replace(Regex("\\s+"), " ").trim(' ', '.', '_').take(80) + ".pdf"
            val result = runCatching {
                require(newName != ".pdf") { getString(R.string.rename_document_hint) }
                when (uri.scheme) {
                    "file" -> {
                        val source = File(requireNotNull(uri.path))
                        val destination = File(source.parentFile ?: filesDir, newName)
                        require(source.isFile && (source.canonicalFile == destination.canonicalFile || !destination.exists())) {
                            "A document with that name already exists."
                        }
                        if (source.canonicalFile != destination.canonicalFile && !source.renameTo(destination)) {
                            throw java.io.IOException("Could not rename the PDF.")
                        }
                        Uri.fromFile(destination)
                    }
                    "content" -> {
                        val updated = runCatching {
                            contentResolver.update(uri, ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                            }, null, null)
                        }.getOrDefault(0)
                        if (updated > 0) {
                            uri
                        } else {
                            DocumentsContract.renameDocument(contentResolver, uri, newName)
                                ?: throw java.io.IOException("This document provider does not permit renaming this PDF.")
                        }
                    }
                    else -> throw java.io.IOException("Unsupported document URI.")
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { renamedUri ->
                    Toast.makeText(this@MainActivity, getString(R.string.document_rename_success), Toast.LENGTH_SHORT).show()
                    dashboardViewModel.updateRecentDocument(uri, renamedUri, newName)
                    dashboardViewModel.refreshCurrentSource()
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, error.localizedMessage ?: getString(R.string.document_action_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun deletePdfDocument(uri: Uri) {
        if (scannerHistoryViewModel.isManagedScanUri(uri)) {
            val file = scannerHistoryRepository.fileFromUri(uri)
            if (file == null) {
                Toast.makeText(this, getString(R.string.document_action_failed), Toast.LENGTH_LONG).show()
                return
            }
            scannerHistoryViewModel.deleteScan(file) { result ->
                runOnUiThread {
                    result.onSuccess {
                        Toast.makeText(this, getString(R.string.document_delete_success), Toast.LENGTH_SHORT).show()
                        dashboardViewModel.removeRecentDocument(uri)
                        dashboardViewModel.refreshCurrentSource()
                        dashboardViewModel.refreshRecentDocuments()
                    }.onFailure { error ->
                        Toast.makeText(this, error.localizedMessage ?: getString(R.string.document_action_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                when (uri.scheme) {
                    "file" -> {
                        val file = File(requireNotNull(uri.path))
                        if (!file.isFile || !file.delete()) throw java.io.IOException("Could not delete the PDF.")
                    }
                    "content" -> {
                        val deleted = runCatching { contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
                        if (!deleted && !runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false)) {
                            throw java.io.IOException("This document provider does not permit deleting this PDF.")
                        }
                    }
                    else -> throw java.io.IOException("Unsupported document URI.")
                }
            }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    Toast.makeText(this@MainActivity, getString(R.string.document_delete_success), Toast.LENGTH_SHORT).show()
                    dashboardViewModel.removeRecentDocument(uri)
                    dashboardViewModel.refreshCurrentSource()
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, error.localizedMessage ?: getString(R.string.document_action_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
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
    private fun shareFiles(
        files: List<File>,
        mimeType: String,
        defaultBaseName: String,
        forceMultiple: Boolean = false
    ) {
        val validFiles = files.filter { it.exists() && it.length() > 0L }
        if (validFiles.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_output_files), Toast.LENGTH_LONG).show()
            return
        }

        pendingShareFiles = validFiles
        pendingShareMimeType = mimeType
        pendingShareExtension = when (mimeType) {
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            else -> ".pdf"
        }
        pendingShareDefaultBaseName = defaultBaseName
            .removeSuffix(".pdf")
            .removeSuffix(".png")
            .removeSuffix(".jpg")
            .removeSuffix(".jpeg")
        pendingShareForceMultiple = forceMultiple
        showRenameDialog = true
    }

    private fun shareRenamedFiles(
        files: List<File>,
        mimeType: String,
        forceMultiple: Boolean = false
    ) {
        if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val uris = files.map { UriUtils.getShareableUri(this, it) }
        val clipData = ClipData.newUri(contentResolver, "VibeSync output", uris.first())
        uris.drop(1).forEach { clipData.addItem(ClipData.Item(it)) }
        val shareIntent = if (uris.size == 1 && !forceMultiple) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                this.clipData = clipData
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                this.clipData = clipData
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
            .removeSuffix(".jpg")
            .removeSuffix(".jpeg")
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
        dashboardViewModel.refreshCurrentSource()
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

    private companion object {
        const val TAG = "VibeSyncMainActivity"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_ms"
        const val KEY_UPDATE_SNOOZE_UNTIL = "update_snooze_until_ms"
        const val KEY_IMMEDIATE_UPDATE_IN_PROGRESS = "immediate_update_in_progress"
        const val KEY_OFFLINE_PROMPT_LAST_SHOWN = "offline_prompt_last_shown_ms"
        const val UPDATE_PROMPT_COOLDOWN_MS = 24L * 60L * 60L * 1000L
    }
}
