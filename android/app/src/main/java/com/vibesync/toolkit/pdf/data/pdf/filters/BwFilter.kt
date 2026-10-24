package com.vibesync.toolkit.pdf.data.pdf.filters

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min

/** Adaptive black-and-white conversion using a 15×15 integral-image local mean. */
class BwFilter : ScanFilter {
    override val id: FilterId = FilterId.BW

    override suspend fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        onProgress: (Float) -> Unit
    ): IntArray {
        FilterMath.validate(pixels, width, height)
        val context = currentCoroutineContext()
        val stride = width + 1
        val integral = LongArray((width + 1) * (height + 1))
        val rowStride = maxOf(1, height / 100)

        for (y in 0 until height) {
            context.ensureActive()
            var rowSum = 0L
            val pixelStart = y * width
            val integralRow = (y + 1) * stride
            val previousRow = y * stride
            for (x in 0 until width) {
                rowSum += FilterMath.luminance(pixels[pixelStart + x]).toInt().toLong()
                integral[integralRow + x + 1] = integral[previousRow + x + 1] + rowSum
            }
            if (y % rowStride == 0 || y == height - 1) {
                FilterMath.report(0.25f * (y + 1).toFloat() / height, onProgress)
            }
        }

        val output = IntArray(pixels.size)
        val radius = 7
        for (y in 0 until height) {
            context.ensureActive()
            val y0 = max(0, y - radius)
            val y1 = min(height - 1, y + radius)
            val outputStart = y * width
            for (x in 0 until width) {
                val x0 = max(0, x - radius)
                val x1 = min(width - 1, x + radius)
                val sum = integral[(y1 + 1) * stride + x1 + 1] -
                    integral[y0 * stride + x1 + 1] -
                    integral[(y1 + 1) * stride + x0] +
                    integral[y0 * stride + x0]
                val area = (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
                val mean = sum.toFloat() / area.toFloat()
                val pixel = pixels[outputStart + x]
                output[outputStart + x] = if (FilterMath.luminance(pixel) < mean * 0.90f) {
                    FilterMath.black(pixel)
                } else {
                    FilterMath.white(pixel)
                }
            }
            if (y % rowStride == 0 || y == height - 1) {
                FilterMath.report(0.25f + 0.75f * (y + 1).toFloat() / height, onProgress)
            }
        }
        FilterMath.report(1f, onProgress)
        return output
    }
}
