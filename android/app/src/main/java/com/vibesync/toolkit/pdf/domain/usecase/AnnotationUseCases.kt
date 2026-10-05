package com.vibesync.toolkit.pdf.domain.usecase

import android.graphics.Bitmap
import android.net.Uri
import com.vibesync.toolkit.pdf.core.security.SecurityVault
import com.vibesync.toolkit.pdf.data.model.PageAnnotations
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import java.io.File

class FlattenAnnotationsUseCase(private val toolkitEngine: PdfBoxToolkitEngine) {
    suspend operator fun invoke(
        sourceUri: Uri,
        annotationsMap: Map<Int, PageAnnotations>,
        screenWidthPx: Float,
        outputFile: File
    ): Result<File> {
        return toolkitEngine.flattenAnnotations(sourceUri, annotationsMap, screenWidthPx, outputFile)
    }
}

class SaveSignatureToVaultUseCase(private val securityVault: SecurityVault) {
    operator fun invoke(bitmap: Bitmap, signatureId: String = "default") {
        securityVault.saveSignature(signatureId, bitmap)
    }
}

class GetVaultSignatureUseCase(private val securityVault: SecurityVault) {
    operator fun invoke(signatureId: String = "default"): Bitmap? {
        return securityVault.getSignature(signatureId)
    }
}
