package com.vibesync.toolkit.pdf.domain.usecase

import android.graphics.Bitmap
import android.net.Uri
import com.vibesync.toolkit.pdf.data.model.PageMetadata
import com.vibesync.toolkit.pdf.data.pdf.PdfRendererEngine

class OpenDocumentUseCase(private val rendererEngine: PdfRendererEngine) {
    suspend operator fun invoke(uri: Uri): Result<Int> {
        return rendererEngine.openDocument(uri)
    }
}

class RenderPageUseCase(private val rendererEngine: PdfRendererEngine) {
    suspend operator fun invoke(pageIndex: Int, scale: Float = 1.0f): Bitmap? {
        return rendererEngine.renderPage(pageIndex, scale)
    }
}

class GetDocumentMetadataUseCase(private val rendererEngine: PdfRendererEngine) {
    suspend operator fun invoke(): List<PageMetadata> {
        return rendererEngine.getPagesMetadata()
    }
}

class CloseDocumentUseCase(private val rendererEngine: PdfRendererEngine) {
    operator fun invoke() {
        rendererEngine.closeDocument()
    }
}
