package com.vibesync.toolkit.pdf.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Shared bitmap helpers for PDF rasterization, thumbnails, and JPEG export. */
public object PdfBitmapUtils {
    /** Allocates an opaque white bitmap for PDF page rasterization and thumbnails. */
    @JvmStatic
    public fun createWhiteBackgroundBitmap(
        width: Int,
        height: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888
    ): Bitmap {
        require(width > 0 && height > 0) { "Bitmap dimensions must be positive." }
        return Bitmap.createBitmap(width, height, config).apply { eraseColor(Color.WHITE) }
    }

    /** Returns an independent bitmap composited over an opaque white background. */
    @JvmStatic
    public fun flattenBitmapOnWhiteBackground(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot flatten a recycled bitmap." }
        val flattened = createWhiteBackgroundBitmap(source.width, source.height)
        try {
            Canvas(flattened).drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            return flattened
        } catch (error: Throwable) {
            if (!flattened.isRecycled) flattened.recycle()
            throw error
        }
    }
}

/*
 * Public compatibility wrappers keep existing production call sites source-compatible,
 * while tests and new code can address the stable PdfBitmapUtils API directly.
 */
public fun createWhiteBackgroundBitmap(
    width: Int,
    height: Int,
    config: Bitmap.Config = Bitmap.Config.ARGB_8888
): Bitmap = PdfBitmapUtils.createWhiteBackgroundBitmap(width, height, config)

public fun flattenBitmapOnWhiteBackground(source: Bitmap): Bitmap =
    PdfBitmapUtils.flattenBitmapOnWhiteBackground(source)
