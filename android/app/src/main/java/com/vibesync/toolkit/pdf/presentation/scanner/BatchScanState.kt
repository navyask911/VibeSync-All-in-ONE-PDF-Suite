package com.vibesync.toolkit.pdf.presentation.scanner

import java.io.File

data class BatchScanPage(
    val id: String,
    val file: File,
    val width: Int,
    val height: Int,
    val estimatedBytes: Long
)

data class BatchScanFolder(
    val id: String,
    val name: String,
    val directory: File,
    val pageCount: Int,
    val lastModified: Long
)

data class BatchScanState(
    val batchModeEnabled: Boolean = false,
    val awaitingCapture: Boolean = false,
    val isCompiling: Boolean = false,
    val progress: Float = 0f,
    val pages: List<BatchScanPage> = emptyList(),
    val batchFolders: List<BatchScanFolder> = emptyList(),
    val activeBatchId: String? = null,
    val messageRes: Int? = null,
    val savedFile: File? = null,
    val fellBack: Boolean = false
)
