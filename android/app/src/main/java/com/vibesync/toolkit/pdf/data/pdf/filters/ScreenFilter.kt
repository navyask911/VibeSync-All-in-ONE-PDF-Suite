package com.vibesync.toolkit.pdf.data.pdf.filters

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 3×3 luminance median filter for suppressing fine periodic screen/halftone interference.
 *
 * A median rejects isolated high-frequency light/dark screen dots without creating the
 * halos that an unsharp mask adds. Replacing luminance rather than RGB independently
 * preserves the source chroma and alpha, while the rank operation keeps solid text strokes
 * crisp when they are wider than the kernel.
 */
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
        // Reuse one small work buffer for every pixel to avoid per-pixel allocations.
        val samples = FloatArray(KERNEL_AREA)
        val rowStride = maxOf(1, height / 100)

        for (y in 0 until height) {
            context.ensureActive()
            val topRow = maxOf(0, y - 1) * width
            val middleRow = y * width
            val bottomRow = minOf(height - 1, y + 1) * width

            for (x in 0 until width) {
                val left = maxOf(0, x - 1)
                val middle = x
                val right = minOf(width - 1, x + 1)

                samples[0] = luminance[topRow + left]
                samples[1] = luminance[topRow + middle]
                samples[2] = luminance[topRow + right]
                samples[3] = luminance[middleRow + left]
                samples[4] = luminance[middleRow + middle]
                samples[5] = luminance[middleRow + right]
                samples[6] = luminance[bottomRow + left]
                samples[7] = luminance[bottomRow + middle]
                samples[8] = luminance[bottomRow + right]

                val median = medianOfNine(samples)
                val index = middleRow + x
                output[index] = FilterMath.replaceLuminance(pixels[index], median)
            }

            if (y % rowStride == 0 || y == height - 1) {
                FilterMath.report((y + 1).toFloat() / height, onProgress)
            }
        }
        FilterMath.report(1f, onProgress)
        return output
    }

    /**
     * In-place insertion sort is efficient for this fixed nine-value window and avoids
     * allocating a collection or invoking a general-purpose sort for every image pixel.
     */
    private fun medianOfNine(values: FloatArray): Float {
        for (index in 1 until KERNEL_AREA) {
            val value = values[index]
            var insertionPoint = index - 1
            while (insertionPoint >= 0 && values[insertionPoint] > value) {
                values[insertionPoint + 1] = values[insertionPoint]
                insertionPoint--
            }
            values[insertionPoint + 1] = value
        }
        return values[KERNEL_RADIUS]
    }

    private companion object {
        const val KERNEL_AREA = 9
        const val KERNEL_RADIUS = 4
    }
}
