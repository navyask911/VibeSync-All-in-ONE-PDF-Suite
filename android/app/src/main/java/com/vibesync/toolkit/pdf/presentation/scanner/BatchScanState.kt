package com.vibesync.toolkit.pdf.presentation.scanner

import java.io.File

data class BatchScanPage(
    val id: String,
    val file: File,
    val width: Int,
    val height: Int,
    val estimatedBytes: Long
)

data class BatchScanState(
    val batchModeEnabled: Boolean = false,
    val awaitingCapture: Boolean = false,
    val isCompiling: Boolean = false,
    val progress: Float = 0f,
    val pages: List<BatchScanPage> = emptyList(),
    val messageRes: Int? = null,
    val savedFile: File? = null,
    val fellBack: Boolean = false
)