package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.data.pdf.filters.FilterEngine
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MoireReductionTest {
    @Test
    fun screenFilterSuppressesPeriodicGridAndKeepsTextEdgesSharp() = runBlocking {
        val width = 96
        val height = 64
        val source = syntheticScreenImage(width, height)

        // Measure an interference-only patch, well clear of the synthetic text glyphs.
        val varianceBefore = luminanceVariance(
            source,
            width,
            left = 4,
            top = 4,
            regionWidth = 24,
            regionHeight = 24
        )
        val result = FilterEngine().apply(FilterId.SCREEN, source, width, height)
        val varianceAfter = luminanceVariance(
            result,
            width,
            left = 4,
            top = 4,
            regionWidth = 24,
            regionHeight = 24
        )

        assertTrue(
            varianceAfter < varianceBefore * 0.3,
            "The 3×3 median should substantially suppress the periodic grid: " +
                "before=$varianceBefore, after=$varianceAfter"
        )

        // The H has four-pixel-wide strokes, leaving a solid 3×3 black core after filtering.
        val textCore = (21 * width) + 33
        assertTrue(
            luminance(result[textCore]) <= 8f,
            "Solid black text cores must remain near-black after de-moiré filtering."
        )

        // One pixel outside the left edge has only a minority of black samples in its window.
        val outsideText = (22 * width) + 31
        assertTrue(
            luminance(result[outsideText]) >= 230f,
            "The text boundary should remain crisp rather than bleeding into the background."
        )
    }

    @Test
    fun screenFilterLeavesFlatMidGrayPixelsUnchanged() = runBlocking {
        val source = IntArray(25) { gray(128) }
        val result = FilterEngine().apply(FilterId.SCREEN, source, 5, 5)
        assertTrue(result.all { kotlin.math.abs(luminance(it) - 128f) <= 1.1f })
    }

    /**
     * Builds a white page, lays down a high-frequency alternating screen grid, then draws
     * bold black H/I glyphs on top so the test checks both interference suppression and text.
     */
    private fun syntheticScreenImage(width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            if (x % GRID_PERIOD == 0 || y % GRID_PERIOD == 0) {
                val darkCell = ((x / GRID_PERIOD) + (y / GRID_PERIOD)) % 2 == 0
                gray(if (darkCell) GRID_DARK else GRID_LIGHT)
            } else {
                gray(BACKGROUND)
            }
        }

        drawGlyph(
            pixels = pixels,
            width = width,
            left = TEXT_LEFT,
            top = TEXT_TOP,
            scale = TEXT_SCALE,
            rows = listOf(
                "10001",
                "10001",
                "10001",
                "11111",
                "10001",
                "10001",
                "10001"
            )
        )
        drawGlyph(
            pixels = pixels,
            width = width,
            left = TEXT_LEFT + (6 * TEXT_SCALE),
            top = TEXT_TOP,
            scale = TEXT_SCALE,
            rows = listOf(
                "11111",
                "00100",
                "00100",
                "00100",
                "00100",
                "00100",
                "11111"
            )
        )
        return pixels
    }

    private fun drawGlyph(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        scale: Int,
        rows: List<String>
    ) {
        rows.forEachIndexed { row, pattern ->
            pattern.forEachIndexed { column, filled ->
                if (filled == '1') {
                    for (dy in 0 until scale) {
                        for (dx in 0 until scale) {
                            val x = left + (column * scale) + dx
                            val y = top + (row * scale) + dy
                            pixels[y * width + x] = gray(0)
                        }
                    }
                }
            }
        }
    }

    private fun luminanceVariance(
        pixels: IntArray,
        width: Int,
        left: Int,
        top: Int,
        regionWidth: Int,
        regionHeight: Int
    ): Double {
        var sum = 0.0
        var sumSquared = 0.0
        val count = regionWidth * regionHeight
        for (y in top until top + regionHeight) {
            for (x in left until left + regionWidth) {
                val value = luminance(pixels[y * width + x]).toDouble()
                sum += value
                sumSquared += value * value
            }
        }
        val mean = sum / count
        return (sumSquared / count) - (mean * mean)
    }

    private fun gray(value: Int): Int =
        0xff000000.toInt() or (value shl 16) or (value shl 8) or value

    private fun luminance(pixel: Int): Float =
        0.299f * ((pixel shr 16) and 0xff) +
            0.587f * ((pixel shr 8) and 0xff) +
            0.114f * (pixel and 0xff)

    private companion object {
        const val GRID_PERIOD = 4
        const val GRID_DARK = 48
        const val GRID_LIGHT = 208
        const val BACKGROUND = 255
        const val TEXT_LEFT = 32
        const val TEXT_TOP = 20
        const val TEXT_SCALE = 4
    }
}
