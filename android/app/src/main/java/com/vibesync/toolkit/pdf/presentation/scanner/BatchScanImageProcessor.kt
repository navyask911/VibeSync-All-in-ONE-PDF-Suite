package com.vibesync.toolkit.pdf.presentation.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

internal data class BatchProcessedImage(val width: Int, val height: Int)

/**
 * Applies EXIF rotation/flips and restrained document enhancement in-place.
 * ML Kit FULL-mode JPEGs are retained at source resolution unless a heap-safe cap is needed.
 */
internal object BatchScanImageProcessor {
    private const val JPEG_QUALITY = 96
    private const val CONTRAST = 1.07f
    private const val BRIGHTNESS = 3f
    private const val SHARPEN_AMOUNT = 0.12f

    fun process(sourceFile: File, destinationFile: File): BatchProcessedImage {
        require(sourceFile.isFile && sourceFile.length() > 0L) { "Captured image is empty." }
        val orientation = ExifInterface(sourceFile.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Unable to decode a captured scan page.")
        }

        val maxPixels = (Runtime.getRuntime().maxMemory() / 16L)
            .coerceAtLeast(1_000_000L)
            .coerceAtMost(20_000_000L)
        var sampleSize = 1
        while (
            (bounds.outWidth.toLong() / sampleSize) *
            (bounds.outHeight.toLong() / sampleSize) > maxPixels &&
            sampleSize < 32
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            ?: throw IOException("Unable to decode a captured scan page.")
        var working = decoded
        try {
            val upright = applyExifOrientation(decoded, orientation)
            if (upright !== decoded) decoded.recycle()
            working = upright
            enhanceInPlace(working)

            val parent = destinationFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("Could not create the batch scan folder.")
            }
            FileOutputStream(destinationFile).use { output ->
                if (!working.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw IOException("Could not save the enhanced scan image.")
                }
                output.flush()
            }
            return BatchProcessedImage(working.width, working.height)
        } catch (error: Throwable) {
            destinationFile.delete()
            throw error
        } finally {
            if (!working.isRecycled) working.recycle()
            if (decoded !== working && !decoded.isRecycled) decoded.recycle()
        }
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Row-by-row unsharp masking avoids a second full-frame pixel array. */
    private fun enhanceInPlace(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) throw IOException("Captured image has invalid dimensions.")

        var upper = IntArray(width)
        var current = IntArray(width)
        var lower = IntArray(width)
        val output = IntArray(width)
        bitmap.getPixels(current, 0, width, 0, 0, width, 1)
        if (height > 1) bitmap.getPixels(lower, 0, width, 0, 1, width, 1)
        else lower = current.copyOf()

        for (y in 0 until height) {
            val top = if (y == 0) current else upper
            val bottom = if (y == height - 1) current else lower
            for (x in 0 until width) {
                val pixel = current[x]
                val left = current[(x - 1).coerceAtLeast(0)]
                val right = current[(x + 1).coerceAtMost(width - 1)]
                val alpha = Color.alpha(pixel)
                val red = enhanceChannel(Color.red(pixel), Color.red(left), Color.red(right), Color.red(top[x]), Color.red(bottom[x]))
                val green = enhanceChannel(Color.green(pixel), Color.green(left), Color.green(right), Color.green(top[x]), Color.green(bottom[x]))
                val blue = enhanceChannel(Color.blue(pixel), Color.blue(left), Color.blue(right), Color.blue(top[x]), Color.blue(bottom[x]))
                output[x] = Color.argb(alpha, red, green, blue)
            }
            bitmap.setPixels(output, 0, width, 0, y, width, 1)

            if (y < height - 1) {
                val recyclableRow = upper
                upper = current
                current = lower
                lower = recyclableRow
                if (y + 2 < height) bitmap.getPixels(lower, 0, width, 0, y + 2, width, 1)
                else lower = current
            }
        }
    }

    private fun enhanceChannel(center: Int, left: Int, right: Int, above: Int, below: Int): Int {
        val edge = center * 4 - left - right - above - below
        val sharpened = (center + edge * SHARPEN_AMOUNT).roundToInt()
        return (((sharpened - 128) * CONTRAST) + 128 + BRIGHTNESS)
            .roundToInt()
            .coerceIn(0, 255)
    }
}
