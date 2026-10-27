package com.vibesync.toolkit.pdf.data.pdf.filters

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min

/**
 * Estimates the paper/shadow field with a separable box blur whose radius is min(width,height)/40,
 * divides luminance by that field, then normalizes the ratio into 0..255 and restores chroma.
 * The horizontal buffer is reused for ratios after the vertical blur is complete to bound memory.
 */
class NoShadowFilter : ScanFilter {
    override val id: FilterId = FilterId.NO_SHADOW

    override suspend fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        onProgress: (Float) -> Unit
    ): IntArray {
        FilterMath.validate(pixels, width, height)
        val radius = min(width, height) / 40
        if (radius <= 0) {
            FilterMath.report(1f, onProgress)
            return pixels.copyOf()
        }

        val context = currentCoroutineContext()
        val horizontal = FloatArray(pixels.size)
        val vertical = FloatArray(pixels.size)
        val rowStride = maxOf(1, height / 100)
        for (y in 0 until height) {
            context.ensureActive()
            val row = y * width
            var sum = 0f
            var count = 0
            for (x in 0..min(width - 1, radius)) {
                sum += FilterMath.luminance(pixels[row + x])
                count++
            }
            for (x in 0 until width) {
                horizontal[row + x] = sum / count
                val removeX = x - radius
                val addX = x + radius + 1
                if (removeX >= 0) {
                    sum -= FilterMath.luminance(pixels[row + removeX])
                    count--
                }
                if (addX < width) {
                    sum += FilterMath.luminance(pixels[row + addX])
                    count++
                }
            }
            if (y % rowStride == 0 || y == height - 1) {
                FilterMath.report(0.35f * (y + 1).toFloat() / height, onProgress)
            }
        }

        val columnStride = maxOf(1, width / 100)
        for (x in 0 until width) {
            context.ensureActive()
            var sum = 0f
            var count = 0
            for (y in 0..min(height - 1, radius)) {
                sum += horizontal[y * width + x]
                count++
            }
            for (y in 0 until height) {
                vertical[y * width + x] = sum / count
                val removeY = y - radius
                val addY = y + radius + 1
                if (removeY >= 0) {
                    sum -= horizontal[removeY * width + x]
                    count--
                }
                if (addY < height) {
                    sum += horizontal[addY * width + x]
                    count++
                }
            }
            if (x % columnStride == 0 || x == width - 1) {
                FilterMath.report(0.35f + 0.30f * (x + 1).toFloat() / width, onProgress)
            }
        }

        // Reuse the no-longer-needed horizontal buffer to hold each pixel's Y / boxBlur(Y) ratio.
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        for (index in horizontal.indices) {
            if (index % width == 0) context.ensureActive()
            val ratio = FilterMath.luminance(pixels[index]) / max(1f, vertical[index])
            horizontal[index] = ratio
            minimum = min(minimum, ratio)
            maximum = max(maximum, ratio)
        }

        val output = IntArray(pixels.size)
        val range = maximum - minimum
        for (y in 0 until height) {
            context.ensureActive()
            val start = y * width
            for (x in 0 until width) {
                val index = start + x
                val normalized = if (range < 0.0001f) {
                    FilterMath.luminance(pixels[index])
                } else {
                    (horizontal[index] - minimum) * 255f / range
                }
                output[index] = FilterMath.replaceLuminance(pixels[index], normalized.coerceIn(0f, 255f))
            }
            if (y % rowStride == 0 || y == height - 1) {
                FilterMath.report(0.65f + 0.35f * (y + 1).toFloat() / height, onProgress)
            }
        }
        FilterMath.report(1f, onProgress)
        return output
    }
}
