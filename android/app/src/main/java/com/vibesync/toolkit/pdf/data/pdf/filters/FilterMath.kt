package com.vibesync.toolkit.pdf.data.pdf.filters

import kotlin.math.roundToInt

internal object FilterMath {
    fun validate(pixels: IntArray, width: Int, height: Int) {
        require(width > 0 && height > 0) { "Image dimensions must be positive." }
        require(width.toLong() * height.toLong() == pixels.size.toLong()) {
            "Pixel count must equal width × height."
        }
    }

    fun luminance(argb: Int): Float {
        val red = (argb shr 16) and 0xff
        val green = (argb shr 8) and 0xff
        val blue = argb and 0xff
        return 0.299f * red + 0.587f * green + 0.114f * blue
    }

    /** HSV-style saturation normalized to 0..1. */
    fun saturation(argb: Int): Float {
        val red = (argb shr 16) and 0xff
        val green = (argb shr 8) and 0xff
        val blue = argb and 0xff
        val maximum = maxOf(red, green, blue)
        if (maximum == 0) return 0f
        val minimum = minOf(red, green, blue)
        return (maximum - minimum).toFloat() / maximum.toFloat()
    }

    fun gray(argb: Int, luminance: Float): Int {
        val value = luminance.roundToInt().coerceIn(0, 255)
        return withRgb(argb, value, value, value)
    }

    /** Replace Y in YCbCr while retaining the input chroma and alpha. */
    fun replaceLuminance(argb: Int, luminance: Float): Int {
        val red = ((argb shr 16) and 0xff).toDouble()
        val green = ((argb shr 8) and 0xff).toDouble()
        val blue = (argb and 0xff).toDouble()
        val cb = -0.168736 * red - 0.331264 * green + 0.5 * blue
        val cr = 0.5 * red - 0.418688 * green - 0.081312 * blue
        val targetY = luminance.toDouble()
        val outRed = (targetY + 1.402 * cr).roundToInt().coerceIn(0, 255)
        val outGreen = (targetY - 0.344136 * cb - 0.714136 * cr).roundToInt().coerceIn(0, 255)
        val outBlue = (targetY + 1.772 * cb).roundToInt().coerceIn(0, 255)
        return withRgb(argb, outRed, outGreen, outBlue)
    }

    fun withRgb(source: Int, red: Int, green: Int, blue: Int): Int =
        (source and -0x1000000) or
            ((red.coerceIn(0, 255) and 0xff) shl 16) or
            ((green.coerceIn(0, 255) and 0xff) shl 8) or
            (blue.coerceIn(0, 255) and 0xff)

    fun black(argb: Int): Int = withRgb(argb, 0, 0, 0)
    fun white(argb: Int): Int = withRgb(argb, 255, 255, 255)

    fun report(progress: Float, onProgress: (Float) -> Unit) {
        onProgress(progress.coerceIn(0f, 1f))
    }
}
