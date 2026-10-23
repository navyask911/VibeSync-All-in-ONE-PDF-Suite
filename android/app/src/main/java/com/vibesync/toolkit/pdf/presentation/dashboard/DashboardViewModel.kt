package com.vibesync.toolkit.pdf.presentation.dashboard

import android.app.Application
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.model.DocumentMetadata
import com.vibesync.toolkit.pdf.data.pdf.DevicePdfScanner
import com.vibesync.toolkit.pdf.data.pdf.SafPdfFolderScanner
import com.vibesync.toolkit.pdf.data.pdf.DiscoveredPdf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardUiState(
    val recentDocuments: List<DocumentMetadata> = emptyList(),
    val discoveredPdfs: List<DiscoveredPdf> = emptyList(),
    val isScanning: Boolean = false,
    val showPermissionRationale: Boolean = false,
    val hasStoragePermission: Boolean = false,
    val isProcessing: Boolean = false,
    val message: String? = null,
    val isFolderScanResult: Boolean = false
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = DevicePdfScanner(application)
    private val folderScanner = SafPdfFolderScanner(application)
    private var scanJob: Job? = null
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        checkPermissionAndScan()
        loadRecentDocuments()
    }

    fun checkPermissionAndScan() {
        val hasPermission = scanner.hasStoragePermission()
        _uiState.value = _uiState.value.copy(hasStoragePermission = hasPermission)
        if (_uiState.value.isFolderScanResult) {
            refreshCurrentSource()
        } else if (hasPermission) {
            refreshDevicePdfs()
        }
    }

    fun triggerScanRequest() {
        if (scanner.hasStoragePermission()) {
            refreshDevicePdfs()
        } else {
            _uiState.value = _uiState.value.copy(showPermissionRationale = true)
        }
    }

    fun dismissPermissionRationale() {
        _uiState.value = _uiState.value.copy(showPermissionRationale = false)
    }

    /** Re-scans whichever source is currently displayed without changing its scope. */
    fun refreshCurrentSource() {
        if (!_uiState.value.isFolderScanResult) {
            refreshDevicePdfs()
            return
        }

        val savedTreeUri = getApplication<Application>()
            .getSharedPreferences(PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_PDF_TREE_URI, null)

        if (savedTreeUri.isNullOrBlank()) {
            refreshDevicePdfs()
        } else {
            scanSelectedFolder(Uri.parse(savedTreeUri))
        }
    }

    fun refreshDevicePdfs() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true, isFolderScanResult = false)
            try {
                val pdfs = scanner.scanDevicePdfs()
                _uiState.value = _uiState.value.copy(
                    discoveredPdfs = pdfs,
                    isScanning = false,
                    isFolderScanResult = false
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    message = getApplication<Application>().getString(com.vibesync.toolkit.pdf.R.string.folder_scan_failed)
                )
            }
        }
    }

    /**
     * Scans only the folder explicitly selected by the user through ACTION_OPEN_DOCUMENT_TREE.
     * Persisting the tree URI lets the app retain access without broad storage permissions.
     */
    fun scanSelectedFolder(treeUri: Uri) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isScanning = true,
                isFolderScanResult = true,
                message = null
            )
            getApplication<Application>()
                .getSharedPreferences(PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SELECTED_PDF_TREE_URI, treeUri.toString())
                .apply()

            try {
                val pdfs = folderScanner.scanFolder(treeUri)
                _uiState.value = _uiState.value.copy(
                    discoveredPdfs = pdfs,
                    isScanning = false,
                    isFolderScanResult = true,
                    message = getApplication<Application>().getString(
                        com.vibesync.toolkit.pdf.R.string.folder_scan_complete,
                        pdfs.size
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    isFolderScanResult = true,
                    message = getApplication<Application>().getString(
                        com.vibesync.toolkit.pdf.R.string.folder_access_denied
                    )
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isScanning = false,
                    isFolderScanResult = true,
                    message = getApplication<Application>().getString(
                        com.vibesync.toolkit.pdf.R.string.folder_scan_failed
                    )
                )
            }
        }
    }

    /** Refreshes the recent list after a scan is committed to persistent storage. */
    fun refreshRecentDocuments() = loadRecentDocuments()

    private fun loadRecentDocuments() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val persistentScanDir = java.io.File(app.filesDir, "scans")
                val exportCacheDir = java.io.File(app.cacheDir, "pdf_cache")
                val savedScans = persistentScanDir.listFiles { _, name ->
                    name.endsWith(".pdf", ignoreCase = true)
                } ?: emptyArray()
                val cachedPdfs = exportCacheDir.listFiles { _, name ->
                    name.endsWith(".pdf", ignoreCase = true)
                } ?: emptyArray()

                (savedScans.asList() + cachedPdfs.asList())
                    .filter { it.isFile && it.length() > 0L }
                    .distinctBy { it.absolutePath }
                    .sortedByDescending { it.lastModified() }
                    .take(5)
                    .map { file ->
                        val pageCount = runCatching {
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                                PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
                            }
                        }.getOrDefault(1)
                        DocumentMetadata(
                            fileName = file.name,
                            uriString = Uri.fromFile(file).toString(),
                            pageCount = pageCount,
                            fileSize = file.length()
                        )
                    }
            }
            _uiState.value = _uiState.value.copy(recentDocuments = list)
        }
    }

    fun onDocumentSelected(uri: Uri, onNavigateToViewer: (Uri) -> Unit) {
        // Navigate immediately so a slow document provider cannot delay the loading screen.
        onNavigateToViewer(uri)
        viewModelScope.launch {
            // Activity Result callbacks run on Main. ContentResolver metadata queries must not.
            val metadata = withContext(Dispatchers.IO) {
                try {
                    UriUtils.getFileName(getApplication(), uri) to UriUtils.getFileSize(getApplication(), uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    getApplication<Application>().getString(com.vibesync.toolkit.pdf.R.string.default_pdf_filename) to 0L
                }
            }
            val doc = DocumentMetadata(
                fileName = metadata.first,
                uriString = uri.toString(),
                pageCount = 1,
                fileSize = metadata.second
            )
            val updated = listOf(doc) + _uiState.value.recentDocuments.filter { it.uriString != uri.toString() }
            _uiState.value = _uiState.value.copy(recentDocuments = updated.take(5))
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private companion object {
        const val PREFERENCES_NAME = "document_picker_preferences"
        const val KEY_SELECTED_PDF_TREE_URI = "selected_pdf_tree_uri"
    }
}
