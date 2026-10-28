package com.vibesync.toolkit.pdf.data.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.InputStream

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
    /**
     * Returns an opaque bitmap for JPEG encoding, compositing transparency over solid white.
     * RGB_565 cannot carry alpha, so it is already safe to encode without copying.
     */
    @JvmStatic
    public fun ensureWhiteBackgroundForJpeg(source: Bitmap): Bitmap {
        check(!source.isRecycled) { "Cannot flatten a recycled bitmap." }
        if (source.config == Bitmap.Config.RGB_565) return source // already opaque

        val opaqueBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(opaqueBitmap)
            canvas.drawColor(Color.WHITE) // Force solid white backing!
            canvas.drawBitmap(source, 0f, 0f, null)

            // Explicitly composite each row as a fallback for encoders that retain RGB=0
            // under fully transparent pixels. Row buffers bound temporary memory use.
            val sourceRow = IntArray(source.width)
            val opaqueRow = IntArray(source.width)
            for (y in 0 until source.height) {
                source.getPixels(sourceRow, 0, source.width, 0, y, source.width, 1)
                for (x in sourceRow.indices) {
                    val pixel = sourceRow[x]
                    val alpha = Color.alpha(pixel)
                    val inverseAlpha = 255 - alpha
                    val red = (Color.red(pixel) * alpha + 255 * inverseAlpha + 127) / 255
                    val green = (Color.green(pixel) * alpha + 255 * inverseAlpha + 127) / 255
                    val blue = (Color.blue(pixel) * alpha + 255 * inverseAlpha + 127) / 255
                    opaqueRow[x] = Color.rgb(red, green, blue)
                }
                opaqueBitmap.setPixels(opaqueRow, 0, source.width, 0, y, source.width, 1)
            }
            opaqueBitmap.setHasAlpha(false)
            return opaqueBitmap
        } catch (error: Throwable) {
            if (!opaqueBitmap.isRecycled) opaqueBitmap.recycle()
            throw error
        }
    }

    private const val DEFAULT_MAX_BITMAP_DIMENSION = 2048

    /**
     * Decodes a file without allocating its full-size bitmap when its longest side exceeds
     * [maxDimension]. Bounds are read first; sampling uses Android's power-of-two convention.
     */
    @JvmStatic
    public fun decodeFileDownsampled(
        path: String,
        maxDimension: Int = DEFAULT_MAX_BITMAP_DIMENSION
    ): Bitmap? {
        require(maxDimension > 0) { "Maximum bitmap dimension must be positive." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, maxDimension)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    /**
     * Decodes a reopenable stream with a bounds pass and then a sampled pass. The provider is
     * invoked twice because many content streams cannot be rewound after bounds decoding.
     */
    @JvmStatic
    public fun decodeStreamDownsampled(
        openInputStream: () -> InputStream?,
        maxDimension: Int = DEFAULT_MAX_BITMAP_DIMENSION
    ): Bitmap? {
        require(maxDimension > 0) { "Maximum bitmap dimension must be positive." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = openInputStream() ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, maxDimension)
        }
        val imageStream = openInputStream() ?: return null
        return imageStream.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Returns the smallest power-of-two sample that brings the longest side within [maxDimension]. */
    @JvmStatic
    public fun calculateInSampleSize(
        bounds: BitmapFactory.Options,
        maxDimension: Int = DEFAULT_MAX_BITMAP_DIMENSION
    ): Int {
        require(maxDimension > 0) { "Maximum bitmap dimension must be positive." }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 1
        var sampleSize = 1
        while (
            maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxDimension &&
            sampleSize < Int.MAX_VALUE / 2
        ) {
            sampleSize *= 2
        }
        return sampleSize
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
