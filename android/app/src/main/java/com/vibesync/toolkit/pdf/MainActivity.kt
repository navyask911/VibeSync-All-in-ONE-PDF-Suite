package com.vibesync.toolkit.pdf

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vibesync.toolkit.pdf.core.designsystem.VibeSyncPdfTheme
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardScreen
import com.vibesync.toolkit.pdf.presentation.dashboard.DashboardViewModel
import com.vibesync.toolkit.pdf.presentation.editor.EditorViewModel
import com.vibesync.toolkit.pdf.presentation.editor.PdfEditorScreen
import com.vibesync.toolkit.pdf.presentation.monetization.AdMobManager
import com.vibesync.toolkit.pdf.presentation.scanner.DocumentScannerManager
import com.vibesync.toolkit.pdf.presentation.tools.ConvertImagesScreen
import com.vibesync.toolkit.pdf.presentation.tools.EncryptScreen
import com.vibesync.toolkit.pdf.presentation.tools.ExtractImagesScreen
import com.vibesync.toolkit.pdf.presentation.tools.MergeScreen
import com.vibesync.toolkit.pdf.presentation.tools.SplitScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerScreen
import com.vibesync.toolkit.pdf.presentation.viewer.PdfViewerViewModel
import java.net.URLDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

    private val pendingPdfUriState = MutableStateFlow<Uri?>(null)

    private val dashboardViewModel: DashboardViewModel by viewModels()
    private val viewerViewModel: PdfViewerViewModel by viewModels()
    private val editorViewModel: EditorViewModel by viewModels()

    private lateinit var scannerManager: DocumentScannerManager
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>

    // Permission launcher for Android 14+ photo and media access
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (!granted) {
            Toast.makeText(this, "Limited storage permissions granted.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initialize ML Kit Document Scanner
        scannerManager = DocumentScannerManager(this)
        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            val scanResult = scannerManager.parseResult(result.resultCode, result.data)
            scanResult?.pdfUri?.let { scannedPdfUri ->
                Toast.makeText(this, "Document scanned: ${scanResult.pageCount} pages", Toast.LENGTH_LONG).show()
                // Forward the generated PDF into the same viewer navigation path used by
                // picked/shared documents.
                pendingPdfUriState.value = scannedPdfUri
            }
        }

        // 2. Request appropriate runtime storage/media permissions
        checkAndRequestPermissions()

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
                    val pendingPdfUri by pendingPdfUriState.collectAsState()

                    // Automatically forward ACTION_VIEW, ACTION_SEND, and scanner results to Viewer.
                    LaunchedEffect(pendingPdfUri) {
                        pendingPdfUri?.let { uri ->
                            val encoded = URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8.toString())
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
                                    val encoded = URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8.toString())
                                    navController.navigate("viewer/$encoded")
                                },
                                onNavigateToTool = { route ->
                                    navController.navigate(route)
                                },
                                onLaunchScanner = {
                                    scannerManager.startScan(scannerLauncher) { error ->
                                        Toast.makeText(this@MainActivity, "Scanner Error: ${error.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRequestStoragePermission = {
                                    requestStoragePermissionFlow()
                                }
                            )
                        }

                        // Full-Screen System PDF Viewer
                        composable(
                            route = "viewer/{encodedUri}",
                            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val encodedUri = backStackEntry.arguments?.getString("encodedUri") ?: ""
                            val decodedUri = Uri.parse(URLDecoder.decode(encodedUri, StandardCharsets.UTF_8.toString()))

                            PdfViewerScreen(
                                documentUri = decodedUri,
                                viewModel = viewerViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToEditor = { uri ->
                                    val enc = URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8.toString())
                                    navController.navigate("editor/$enc")
                                }
                            )
                        }

                        // Annotation & Digital Signature Editor
                        composable(
                            route = "editor/{encodedUri}",
                            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val encodedUri = backStackEntry.arguments?.getString("encodedUri") ?: ""
                            val decodedUri = Uri.parse(URLDecoder.decode(encodedUri, StandardCharsets.UTF_8.toString()))

                            PdfEditorScreen(
                                documentUri = decodedUri,
                                viewModel = editorViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onExportComplete = { savedFile ->
                                    Toast.makeText(this@MainActivity, "Saved to ${savedFile.name}", Toast.LENGTH_LONG).show()
                                    navController.popBackStack()
                                }
                            )
                        }

                        // Toolkit Screen: Split
                        composable("split") {
                            SplitScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { files ->
                                    Toast.makeText(this@MainActivity, "Split into ${files.size} documents", Toast.LENGTH_LONG).show()
                                    navController.popBackStack()
                                }
                            )
                        }

                        // Toolkit Screen: Merge
                        composable("merge") {
                            MergeScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { mergedFile ->
                                    Toast.makeText(this@MainActivity, "Merged into ${mergedFile.name}", Toast.LENGTH_LONG).show()
                                    navController.popBackStack()
                                }
                            )
                        }

                        // Toolkit Screen: Encrypt
                        composable("encrypt") {
                            EncryptScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { encryptedFile ->
                                    Toast.makeText(this@MainActivity, "Document encrypted & locked", Toast.LENGTH_LONG).show()
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("convert") {
                            ConvertImagesScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { outputFile ->
                                    Toast.makeText(this@MainActivity, "PDF created: " + outputFile.name, Toast.LENGTH_LONG).show()
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("extract") {
                            ExtractImagesScreen(
                                onNavigateBack = { navController.popBackStack() },
                                onSuccess = { files ->
                                    Toast.makeText(this@MainActivity, "Extracted " + files.size + " images", Toast.LENGTH_LONG).show()
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
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

        if ((Intent.ACTION_VIEW == action || Intent.ACTION_SEND == action) && type != null) {
            if ("application/pdf" == type) {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
        }
        return intent.data
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            // Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        dashboardViewModel.checkPermissionAndScan()
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
}
