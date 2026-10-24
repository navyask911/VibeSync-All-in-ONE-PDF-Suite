package com.vibesync.toolkit.pdf.data.pdf.filters

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 3×3 Gaussian unsharp mask for reducing screen moiré and recovering text-edge contrast. */
class ScreenFilter : ScanFilter {
    override val id: FilterId = FilterId.SCREEN

    override suspend fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        onProgress: (Float) -> Unit
    ): IntArray {
        FilterMath.validate(pixels, width, height)
        val context = currentCoroutineContext()
        val luminance = FloatArray(pixels.size) { index -> FilterMath.luminance(pixels[index]) }
        val output = IntArray(pixels.size)
        val rowStride = maxOf(1, height / 100)

        for (y in 0 until height) {
            context.ensureActive()
            for (x in 0 until width) {
                var weighted = 0f
                var totalWeight = 0f
                for (dy in -1..1) {
                    val sampleY = (y + dy).coerceIn(0, height - 1)
                    val wy = if (dy == 0) 2f else 1f
                    for (dx in -1..1) {
                        val sampleX = (x + dx).coerceIn(0, width - 1)
                        val wx = if (dx == 0) 2f else 1f
                        val weight = wx * wy
                        weighted += luminance[sampleY * width + sampleX] * weight
                        totalWeight += weight
                    }
                }
                val blurred = weighted / totalWeight
                val index = y * width + x
                val sourceY = luminance[index]
                val sharpenedY = (sourceY + 0.6f * (sourceY - blurred)).coerceIn(0f, 255f)
                output[index] = FilterMath.replaceLuminance(pixels[index], sharpenedY)
            }
            if (y % rowStride == 0 || y == height - 1) {
                FilterMath.report((y + 1).toFloat() / height, onProgress)
            }
        }
        FilterMath.report(1f, onProgress)
        return output
    }
}
