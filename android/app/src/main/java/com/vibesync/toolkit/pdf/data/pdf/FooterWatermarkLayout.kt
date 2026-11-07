package com.vibesync.toolkit.pdf.data.pdf

/**
 * Positions a footer in top-left-origin page coordinates. PDF drawing converts topY back to the
 * PDF bottom-left origin; this pure function keeps footer placement easy to verify.
 */
internal data class FooterWatermarkPosition(val x: Float, val topY: Float)

internal object FooterWatermarkLayout {
    fun topLeft(
        pageWidth: Float,
        pageHeight: Float,
        watermarkWidth: Float,
        watermarkHeight: Float,
        margin: Float = 16f,
        bottomMargin: Float = margin
    ): FooterWatermarkPosition {
        require(pageWidth > 0f && pageHeight > 0f)
        require(watermarkWidth >= 0f && watermarkHeight >= 0f && margin >= 0f && bottomMargin >= 0f)
        return FooterWatermarkPosition(
            x = (pageWidth - margin - watermarkWidth).coerceAtLeast(0f),
            topY = (pageHeight - bottomMargin - watermarkHeight).coerceAtLeast(0f)
        )
    }
}
