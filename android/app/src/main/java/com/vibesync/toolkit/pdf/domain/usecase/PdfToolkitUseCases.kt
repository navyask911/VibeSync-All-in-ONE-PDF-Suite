package com.vibesync.toolkit.pdf.domain.usecase

import android.net.Uri
import com.vibesync.toolkit.pdf.data.model.FormFieldItem
import com.vibesync.toolkit.pdf.data.pdf.PdfBoxToolkitEngine
import java.io.File

class SplitPdfUseCase(private val toolkitEngine: PdfBoxToolkitEngine) {
    suspend operator fun invoke(sourceUri: Uri, pageIntervals: List<IntRange>): Result<List<File>> {
        return toolkitEngine.splitPdf(sourceUri, pageIntervals)
    }
}

class MergePdfsUseCase(private val toolkitEngine: PdfBoxToolkitEngine) {
    suspend operator fun invoke(sourceUris: List<Uri>, outputFile: File): Result<File> {
        return toolkitEngine.mergePdfs(sourceUris, outputFile)
    }
}

class ExtractImagesUseCase(private val toolkitEngine: PdfBoxToolkitEngine) {
    suspend operator fun invoke(sourceUri: Uri): Result<List<File>> {
        return toolkitEngine.extractEmbeddedImages(sourceUri)
    }
}

class ConvertImagesToPdfUseCase(private val toolkitEngine: PdfBoxToolkitEngine) {
    suspend operator fun invoke(imageUris: List<Uri>, outputFile: File): Result<File> {
        return toolkitEngine.convertImagesToPdf(imageUris, outputFile)
    }
}

class EncryptPdfUseCase(private val toolkitEngine: PdfBoxToolkitEngine) {
    suspend operator fun invoke(
        sourceUri: Uri,
        userPassword: String,
        ownerPassword: String,
        allowPrinting: Boolean,
        allowCopying: Boolean,
        outputFile: File
    ): Result<File> {
        return toolkitEngine.encryptPdf(sourceUri, userPassword, ownerPassword, allowPrinting, allowCopying, outputFile)
    }
}

class DecryptPdfUseCase(private val toolkitEngine: PdfBoxToolkitEngine) {
    suspend operator fun invoke(sourceUri: Uri, password: String, outputFile: File): Result<File> {
        return toolkitEngine.decryptPdf(sourceUri, password, outputFile)
    }
}

class WatermarkPdfUseCase(private val toolkitEngine: PdfBoxToolkitEngine) {
    suspend operator fun invoke(sourceUri: Uri, text: String, outputFile: File): Result<File> {
        return toolkitEngine.watermarkPdf(sourceUri, text, outputFile)
    }
}

class FillAcroFormUseCase(private val toolkitEngine: PdfBoxToolkitEngine) {
    suspend fun getFields(sourceUri: Uri): Result<List<FormFieldItem>> {
        return toolkitEngine.getAcroFormFields(sourceUri)
    }

    suspend fun updateFields(sourceUri: Uri, values: Map<String, String>, flatten: Boolean, outputFile: File): Result<File> {
        return toolkitEngine.updateAcroForm(sourceUri, values, flatten, outputFile)
    }
}
