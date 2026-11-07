package com.vibesync.toolkit.pdf.presentation.editor

import kotlin.math.max

/**
 * Normalized signature stroke measurements. Captured signatures are rasterized at 4x the placed
 * box dimensions, so the proportional stroke width remains legible after placement and PDF export.
 */
internal object SignatureStrokeMetrics {
    const val DEFAULT_TARGET_WIDTH_PX = 160f
    const val DEFAULT_TARGET_HEIGHT_PX = 80f
    const val RASTER_SCALE = 4f
    const val MINIMUM_VISIBLE_STROKE_PX = 6f

    fun relativeStrokeWidth(strokeWidth: Float, signatureCanvasWidth: Float): Float {
        if (!strokeWidth.isFinite() || strokeWidth <= 0f ||
            !signatureCanvasWidth.isFinite() || signatureCanvasWidth <= 0f
        ) return 0f
        return strokeWidth / signatureCanvasWidth
    }

    fun scaledStrokeWidth(
        relativeStrokeWidth: Float,
        targetBoxWidth: Float,
        minimumVisible: Float = MINIMUM_VISIBLE_STROKE_PX
    ): Float {
        val proportionalWidth = relativeStrokeWidth.coerceAtLeast(0f) * targetBoxWidth.coerceAtLeast(0f)
        return max(minimumVisible, proportionalWidth)
    }
}
