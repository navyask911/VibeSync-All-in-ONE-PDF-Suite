package com.vibesync.toolkit.pdf.presentation.scanner

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ScanHistoryUiState(
    val isLoading: Boolean = false,
    val scans: List<ScanHistoryEntry> = emptyList(),
    val errorMessage: String? = null
)

class ScannerHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ScannerHistoryRepository(File(application.filesDir, "scans"))
    private val _uiState = MutableStateFlow(ScanHistoryUiState())
    val uiState: StateFlow<ScanHistoryUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val scans = withContext(Dispatchers.IO) { repository.listEntries() }
            _uiState.value = ScanHistoryUiState(isLoading = false, scans = scans)
        }
    }

    fun isManagedScanUri(uri: Uri): Boolean = repository.isManagedScanUri(uri)

    fun renameScan(file: File, requestedName: String, onComplete: (Result<File>) -> Unit = {}) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.renameScan(file, requestedName) }
            if (result.isSuccess) {
                val scans = withContext(Dispatchers.IO) { repository.listEntries() }
                _uiState.value = _uiState.value.copy(scans = scans, isLoading = false)
            }
            onComplete(result)
        }
    }

    fun deleteScan(file: File, onComplete: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.deleteScan(file) }
            if (result.isSuccess) {
                val scans = withContext(Dispatchers.IO) { repository.listEntries() }
                _uiState.value = _uiState.value.copy(scans = scans, isLoading = false)
            }
            onComplete(result)
        }
    }
}
