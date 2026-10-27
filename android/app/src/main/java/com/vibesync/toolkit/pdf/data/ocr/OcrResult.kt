package com.vibesync.toolkit.pdf.data.ocr

import android.graphics.Rect

data class OcrResult(
    val text: String,
    val blocks: List<OcrBlock>
)

data class OcrBlock(
    val boundingBox: Rect?,
    val text: String,
    val language: String?
)
