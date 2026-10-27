package com.vibesync.toolkit.pdf.data.pdf.filters

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.pow

/** Applies a gamma-0.75 lookup table to Y while preserving very dark pixels and chroma. */
class LightenFilter : ScanFilter {
    override val id: FilterId = FilterId.LIGHTEN

    private val gammaLut = IntArray(256) { value ->
        (255.0 * (value / 255.0).pow(0.75)).toInt().coerceIn(0, 255)
    }

    override suspend fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        onProgress: (Float) -> Unit
    ): IntArray {
        FilterMath.validate(pixels, width, height)
        val context = currentCoroutineContext()
        val output = IntArray(pixels.size)
        val rowStride = maxOf(1, height / 100)

        for (y in 0 until height) {
            context.ensureActive()
            val start = y * width
            for (x in 0 until width) {
                val index = start + x
                val pixel = pixels[index]
                val sourceY = FilterMath.luminance(pixel)
                output[index] = if (sourceY < 20f) {
                    pixel
                } else {
                    FilterMath.replaceLuminance(pixel, gammaLut[sourceY.toInt().coerceIn(0, 255)].toFloat())
                }
            }
            if (y % rowStride == 0 || y == height - 1) {
                FilterMath.report((y + 1).toFloat() / height, onProgress)
            }
        }
        FilterMath.report(1f, onProgress)
        return output
    }
}
