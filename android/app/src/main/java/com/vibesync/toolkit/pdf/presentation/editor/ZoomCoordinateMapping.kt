package com.vibesync.toolkit.pdf.presentation.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/**
 * Coordinate helpers for the PDF editor.
 *
 * Stroke input is attached to a child of the page graphicsLayer (CASE A), so Compose has
 * already mapped pointer positions to the child's unscaled local coordinates. CASE B is
 * kept as a pure helper for a pointerInput node attached outside a center-origin layer.
 */
internal object ZoomCoordinateMapping {
    /** Inverse transform for pointerInput OUTSIDE a center-origin graphicsLayer (CASE B). */
    fun mapOutsideGraphicsLayer(
        touch: Offset,
        containerSize: Size,
        zoomScale: Float,
        panOffset: Offset
    ): Offset {
        val safeScale = zoomScale.coerceAtLeast(0.001f)
        val centerX = containerSize.width / 2f
        val centerY = containerSize.height / 2f
        val localX = (touch.x - centerX - panOffset.x) / safeScale + centerX
        val localY = (touch.y - centerY - panOffset.y) / safeScale + centerY
        return Offset(localX, localY)
    }

    /** The inner rectangle drawn by ContentScale.Fit, centered inside [containerSize]. */
    fun calculatePageRect(containerSize: Size, bitmapSize: IntSize): Rect {
        val containerWidth = containerSize.width.coerceAtLeast(0f)
        val containerHeight = containerSize.height.coerceAtLeast(0f)
        if (
            containerWidth <= 0f || containerHeight <= 0f ||
            bitmapSize.width <= 0 || bitmapSize.height <= 0
        ) {
            return Rect(0f, 0f, containerWidth, containerHeight)
        }
        val fitScale = min(
            containerWidth / bitmapSize.width.toFloat(),
            containerHeight / bitmapSize.height.toFloat()
        )
        val pageWidth = bitmapSize.width * fitScale
        val pageHeight = bitmapSize.height * fitScale
        val left = (containerWidth - pageWidth) / 2f
        val top = (containerHeight - pageHeight) / 2f
        return Rect(left, top, left + pageWidth, top + pageHeight)
    }

    /** Normalizes an already-local CASE A point against the actual fitted page, not its margins. */
    fun toNormalizedPagePoint(
        touch: Offset,
        containerSize: Size,
        bitmapSize: IntSize
    ): Offset {
        val pageRect = calculatePageRect(containerSize, bitmapSize)
        val normX = ((touch.x - pageRect.left) / pageRect.width.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        val normY = ((touch.y - pageRect.top) / pageRect.height.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
        return Offset(normX, normY)
    }
}
