package com.omnidoc.toolkit.pdf.presentation.dashboard

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omnidoc.toolkit.pdf.core.util.UriUtils
import com.omnidoc.toolkit.pdf.data.model.DocumentMetadata
import com.omnidoc.toolkit.pdf.data.pdf.DevicePdfScanner
import com.omnidoc.toolkit.pdf.data.pdf.DiscoveredPdf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val recentDocuments: List<DocumentMetadata> = emptyList(),
    val discoveredPdfs: List<DiscoveredPdf> = emptyList(),
    val isScanning: Boolean = false,
    val showPermissionRationale: Boolean = false,
    val hasStoragePermission: Boolean = false,
    val isProcessing: Boolean = false,
    val message: String? = null
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = DevicePdfScanner(application)
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        checkPermissionAndScan()
        loadRecentDocuments()
    }

    fun checkPermissionAndScan() {
        val hasPermission = scanner.hasStoragePermission()
        _uiState.value = _uiState.value.copy(hasStoragePermission = hasPermission)
        if (hasPermission) {
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

    fun refreshDevicePdfs() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            val pdfs = scanner.scanDevicePdfs()
            _uiState.value = _uiState.value.copy(
                discoveredPdfs = pdfs,
                isScanning = false
            )
        }
    }

    private fun loadRecentDocuments() {
        viewModelScope.launch {
            val cacheDir = getApplication<Application>().cacheDir
            val pdfDir = java.io.File(cacheDir, "pdf_cache")
            if (pdfDir.exists()) {
                val files = pdfDir.listFiles { _, name -> name.endsWith(".pdf") } ?: emptyArray()
                val list = files.take(5).map { file ->
                    DocumentMetadata(
                        fileName = file.name,
                        uriString = Uri.fromFile(file).toString(),
                        pageCount = 1,
                        fileSize = file.length()
                    )
                }
                _uiState.value = _uiState.value.copy(recentDocuments = list)
            }
        }
    }

    fun onDocumentSelected(uri: Uri, onNavigateToViewer: (Uri) -> Unit) {
        val fileName = UriUtils.getFileName(getApplication(), uri)
        val fileSize = UriUtils.getFileSize(getApplication(), uri)
        val doc = DocumentMetadata(
            fileName = fileName,
            uriString = uri.toString(),
            pageCount = 1,
            fileSize = fileSize
        )
        val updated = listOf(doc) + _uiState.value.recentDocuments.filter { it.uriString != uri.toString() }
        _uiState.value = _uiState.value.copy(recentDocuments = updated.take(5))
        onNavigateToViewer(uri)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
