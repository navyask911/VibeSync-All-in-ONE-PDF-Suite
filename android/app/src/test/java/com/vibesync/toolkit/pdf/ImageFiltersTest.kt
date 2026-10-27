package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.data.pdf.filters.FilterEngine
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageFiltersTest {
    @Test
    fun engineRegistersAllSixPureKotlinFiltersAndReturnsNewArrays() = runBlocking {
        val width = 17
        val height = 13
        val source = IntArray(width * height) { index ->
            val value = (index * 37) and 0xff
            0xff000000.toInt() or (value shl 16) or ((value / 2) shl 8) or (255 - value)
        }
        val original = source.copyOf()
        val engine = FilterEngine()
        assertEquals(FilterId.values().toSet(), engine.availableFilters().toSet())

        for (filter in engine.availableFilters()) {
            val progress = mutableListOf<Float>()
            val result = engine.apply(filter, source, width, height) { progress += it }
            assertEquals(source.size, result.size, "$filter must preserve dimensions")
            assertNotSame(source, result, "$filter must allocate an output array")
            assertEquals(original.toList(), source.toList(), "$filter must not mutate the input")
            assertTrue(progress.isNotEmpty(), "$filter must report progress")
            assertEquals(0f, progress.first())
            assertEquals(1f, progress.last())
            assertTrue(progress.all { it in 0f..1f })
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a }, "$filter progress must be monotonic")
        }
    }

    @Test
    fun adaptiveBwUsesTheFifteenByFifteenLocalMeanWithPointNineThreshold() = runBlocking {
        val width = 15
        val height = 15
        val source = IntArray(width * height) { gray(240) }
        source[7 * width + 7] = gray(80)
        val result = FilterEngine().apply(FilterId.BW, source, width, height)
        assertEquals(0, result[7 * width + 7] and 0x00ffffff)
        assertEquals(0x00ffffff, result[0] and 0x00ffffff)
    }

    @Test
    fun lightenAppliesGammaPointSevenFiveButPreservesYBelowTwenty() = runBlocking {
        val source = intArrayOf(gray(64), gray(10))
        val result = FilterEngine().apply(FilterId.LIGHTEN, source, 2, 1)
        assertTrue(luma(result[0]) > luma(source[0]))
        assertEquals(source[1], result[1])
    }

    @Test
    fun magicColorClampsLuminanceAndMapsTheUpperAndLowerBands() = runBlocking {
        val values = listOf(0, 10, 20, 30, 50, 80, 110, 140, 170, 200, 230, 250, 255)
        val source = values.map(::gray).toIntArray()
        val result = FilterEngine().apply(FilterId.MAGIC_COLOR, source, source.size, 1)
        assertEquals(0, result.first() and 0x00ffffff)
        assertEquals(0x00ffffff, result.last() and 0x00ffffff)
    }

    @Test
    fun magicColorRetainsChromaForSaturatedMidTonePixels() = runBlocking {
        val source = intArrayOf(
            0xff000000.toInt(), 0xff303030.toInt(), 0xffe0e0e0.toInt(), 0xffffffff.toInt(),
            0xffd02020.toInt(), 0xff303030.toInt(), 0xffa0a0a0.toInt(), 0xffb0b0b0.toInt()
        )
        val result = FilterEngine().apply(FilterId.MAGIC_COLOR, source, 4, 2)
        val colored = result[4]
        assertTrue(((colored shr 16) and 0xff) > ((colored shr 8) and 0xff))
        assertTrue(((colored shr 16) and 0xff) > (colored and 0xff))
    }

    @Test
    fun noShadowHandlesUniformImagesWithoutDividingByZero() = runBlocking {
        val source = IntArray(45 * 45) { gray(190) }
        val result = FilterEngine().apply(FilterId.NO_SHADOW, source, 45, 45)
        assertEquals(source.size, result.size)
        assertTrue(result.all { luma(it) in 188f..192f })
    }

    @Test
    fun facadePropagatesCancellationCooperatively() = runBlocking {
        val source = IntArray(160 * 160) { index -> gray(index % 256) }
        var worker: Job? = null
        val launched = launch(start = CoroutineStart.LAZY) {
            FilterEngine().apply(FilterId.SCREEN, source, 160, 160) { progress ->
                if (progress > 0f) worker?.cancel()
            }
        }
        worker = launched
        launched.start()
        launched.join()
        assertTrue(launched.isCancelled)
    }

    private fun gray(value: Int): Int =
        0xff000000.toInt() or (value shl 16) or (value shl 8) or value

    private fun luma(pixel: Int): Float =
        0.299f * ((pixel shr 16) and 0xff) + 0.587f * ((pixel shr 8) and 0xff) + 0.114f * (pixel and 0xff)
}
