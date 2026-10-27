package com.vibesync.toolkit.pdf.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Allocates an opaque white bitmap for PDF page rasterization and thumbnails. */
internal fun createWhiteBackgroundBitmap(
    width: Int,
    height: Int,
    config: Bitmap.Config = Bitmap.Config.ARGB_8888
): Bitmap {
    require(width > 0 && height > 0) { "Bitmap dimensions must be positive." }
    return Bitmap.createBitmap(width, height, config).apply { eraseColor(Color.WHITE) }
}

/** Returns an independent opaque bitmap suitable for JPEG encoding. */
internal fun flattenBitmapOnWhiteBackground(source: Bitmap): Bitmap {
    check(!source.isRecycled) { "Cannot flatten a recycled bitmap." }
    val flattened = createWhiteBackgroundBitmap(source.width, source.height)
    try {
        Canvas(flattened).drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        // Make the JPEG input explicitly opaque even when the source has an alpha channel.
        flattened.setHasAlpha(false)
        return flattened
    } catch (error: Throwable) {
        if (!flattened.isRecycled) flattened.recycle()
        throw error
    }
}
