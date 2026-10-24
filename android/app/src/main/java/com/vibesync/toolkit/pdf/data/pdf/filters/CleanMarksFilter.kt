package com.vibesync.toolkit.pdf.data.pdf.filters

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min

/**
 * Suppresses saturated colored marks and low-variance gray pencil/background marks.
 * Dark luminance (<60) is deliberately preserved as printed text.
 */
class CleanMarksFilter : ScanFilter {
    override val id: FilterId = FilterId.CLEAN_MARKS

    override suspend fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        onProgress: (Float) -> Unit
    ): IntArray {
        FilterMath.validate(pixels, width, height)
        val context = currentCoroutineContext()
        val luminance = FloatArray(pixels.size) { index -> FilterMath.luminance(pixels[index]) }
        val radius = 2
        val output = IntArray(pixels.size)
        val rowStride = maxOf(1, height / 100)

        for (y in 0 until height) {
            context.ensureActive()
            val start = y * width
            for (x in 0 until width) {
                val index = start + x
                val pixel = pixels[index]
                val yValue = luminance[index]
                if (yValue < 60f) {
                    output[index] = pixel
                    continue
                }

                val localVariance = localVariance(luminance, width, height, x, y, radius)
                output[index] = when {
                    FilterMath.saturation(pixel) > 0.35f && yValue in 60f..200f ->
                        FilterMath.white(pixel)
                    yValue in 140f..220f && localVariance < LOW_LOCAL_VARIANCE_THRESHOLD ->
                        FilterMath.white(pixel)
                    else -> pixel
                }
            }
            if (y % rowStride == 0 || y == height - 1) {
                FilterMath.report((y + 1).toFloat() / height, onProgress)
            }
        }
        FilterMath.report(1f, onProgress)
        return output
    }

    private fun localVariance(
        luminance: FloatArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        radius: Int
    ): Float {
        var sum = 0f
        var sumSquared = 0f
        var count = 0
        for (sampleY in max(0, y - radius)..min(height - 1, y + radius)) {
            val row = sampleY * width
            for (sampleX in max(0, x - radius)..min(width - 1, x + radius)) {
                val value = luminance[row + sampleX]
                sum += value
                sumSquared += value * value
                count++
            }
        }
        val mean = sum / count
        return (sumSquared / count - mean * mean).coerceAtLeast(0f)
    }

    companion object {
        /** Variance in Y units squared; equivalent to a local standard deviation below 15. */
        const val LOW_LOCAL_VARIANCE_THRESHOLD = 225f
    }
}
