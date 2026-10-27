package com.vibesync.toolkit.pdf.data.pdf.filters

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.floor

/**
 * Contrast-aware document color: clamps luminance to its 2nd–98th percentiles,
 * maps the bottom/top 20% to black/white, and retains chroma for saturated pixels.
 */
class MagicColorFilter : ScanFilter {
    override val id: FilterId = FilterId.MAGIC_COLOR

    override suspend fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        onProgress: (Float) -> Unit
    ): IntArray {
        FilterMath.validate(pixels, width, height)
        val context = currentCoroutineContext()
        val histogram = IntArray(256)
        val rowStride = maxOf(1, height / 100)
        for (y in 0 until height) {
            context.ensureActive()
            val start = y * width
            for (x in 0 until width) {
                histogram[FilterMath.luminance(pixels[start + x]).toInt().coerceIn(0, 255)]++
            }
            if (y % rowStride == 0 || y == height - 1) {
                FilterMath.report(0.3f * (y + 1).toFloat() / height, onProgress)
            }
        }

        val pixelCount = pixels.size
        val low = percentile(histogram, floor((pixelCount - 1) * 0.02).toInt())
        val high = percentile(histogram, floor((pixelCount - 1) * 0.98).toInt())
        val output = IntArray(pixels.size)
        if (high <= low) {
            pixels.copyInto(output)
            FilterMath.report(1f, onProgress)
            return output
        }

        for (y in 0 until height) {
            context.ensureActive()
            val start = y * width
            for (x in 0 until width) {
                val index = start + x
                val pixel = pixels[index]
                val sourceY = FilterMath.luminance(pixel)
                val normalizedY = ((sourceY - low) * 255f / (high - low)).coerceIn(0f, 255f)
                output[index] = when {
                    normalizedY <= 51f -> FilterMath.black(pixel)
                    normalizedY >= 204f -> FilterMath.white(pixel)
                    FilterMath.saturation(pixel) > 0.3f ->
                        FilterMath.replaceLuminance(pixel, normalizedY)
                    else -> FilterMath.gray(pixel, normalizedY)
                }
            }
            if (y % rowStride == 0 || y == height - 1) {
                FilterMath.report(0.3f + 0.7f * (y + 1).toFloat() / height, onProgress)
            }
        }
        FilterMath.report(1f, onProgress)
        return output
    }

    private fun percentile(histogram: IntArray, rank: Int): Int {
        var cumulative = 0
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative > rank) return value
        }
        return 255
    }
}
