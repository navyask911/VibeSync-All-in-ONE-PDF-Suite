package com.vibesync.toolkit.pdf.presentation.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibesync.toolkit.pdf.R
import com.vibesync.toolkit.pdf.core.util.UriUtils
import com.vibesync.toolkit.pdf.data.model.PageMetadata
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import com.vibesync.toolkit.pdf.data.pdf.PdfRendererEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ViewerUiState(
    val fileName: String = "",
    val pageCount: Int = 0,
    val currentPageIndex: Int = 0,
    val zoomScale: Float = 1.0f,
    val isLoading: Boolean = true,
    val isPasswordRequired: Boolean = false,
    val errorMessage: String? = null,
    val pagesMetadata: List<PageMetadata> = emptyList()
)

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val rendererEngine = PdfRendererEngine(application)
    private val toolkitEngine = PdfBoxToolkitEngine(application)
    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var activeUri: Uri? = null
    private var loadJob: Job? = null

    fun loadDocument(uri: Uri, password: String? = null) {
        loadJob?.cancel()
        activeUri = uri
        _uiState.value = _uiState.value.copy(
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: getApplication<Application>().getString(R.string.default_pdf_filename),
            isLoading = true,
            isPasswordRequired = false,
            errorMessage = null,
            pageCount = 0,
            pagesMetadata = emptyList(),
            currentPageIndex = 0
        )

        loadJob = viewModelScope.launch {
            // Providers can block while resolving DISPLAY_NAME; never query them on Main.
            val fileName = withContext(Dispatchers.IO) {
                try {
                    UriUtils.getFileName(getApplication(), uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    uri.lastPathSegment?.substringAfterLast('/') ?: getApplication<Application>().getString(R.string.default_pdf_filename)
                }
            }
            _uiState.value = _uiState.value.copy(fileName = fileName)

            val targetUri = if (!password.isNullOrBlank()) {
                val decryptedFile = UriUtils.createExportCacheFile(getApplication(), "unlocked_doc")
                val decryptResult = toolkitEngine.decryptPdf(uri, password, decryptedFile)
                if (decryptResult.isSuccess) {
                    Uri.fromFile(decryptedFile)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isPasswordRequired = true,
                        errorMessage = getApplication<Application>().getString(R.string.invalid_password)
                    )
                    return@launch
                }
            } else {
                uri
            }

            val result = rendererEngine.openDocument(targetUri)
            result.fold(
                onSuccess = { count ->
                    val metadata = rendererEngine.getPagesMetadata()
                    _uiState.value = _uiState.value.copy(
                        pageCount = count,
                        pagesMetadata = metadata,
                        isLoading = false,
                        isPasswordRequired = false
                    )
                },
                onFailure = { error ->
                    val msg = error.localizedMessage ?: getApplication<Application>().getString(R.string.failed_to_open_pdf)
                    val isEncrypted = msg.contains("password", ignoreCase = true) ||
                            msg.contains("encrypt", ignoreCase = true) ||
                            msg.contains("security", ignoreCase = true)

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isPasswordRequired = isEncrypted,
                        errorMessage = if (isEncrypted) getApplication<Application>().getString(R.string.encrypted_document) else msg
                    )
                }
            )
        }
    }

    fun submitPassword(password: String) {
        activeUri?.let { uri ->
            loadDocument(uri, password)
        }
    }

    fun dismissPasswordDialog() {
        _uiState.value = _uiState.value.copy(isPasswordRequired = false)
    }

    /** Called when the viewer leaves composition; cleanup runs off the main thread. */
    fun closeDocument() {
        loadJob?.cancel()
        loadJob = null
        viewModelScope.launch {
            rendererEngine.closeDocumentSafely()
        }
    }

    suspend fun getPageBitmap(pageIndex: Int, scale: Float = 1.0f): Bitmap? {
        return rendererEngine.renderPage(pageIndex, scale)
    }

    fun onPageScrolled(pageIndex: Int) {
        if (_uiState.value.currentPageIndex != pageIndex) {
            _uiState.value = _uiState.value.copy(currentPageIndex = pageIndex)
        }
    }

    fun setZoom(scale: Float) {
        val clamped = scale.coerceIn(1.0f, 4.0f) // 100% to 400% zoom
        _uiState.value = _uiState.value.copy(zoomScale = clamped)
    }

    override fun onCleared() {
        super.onCleared()
        rendererEngine.closeDocument()
    }
}
