package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.data.pdf.filters.FilterEngine
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MoireReductionTest {
    @Test
    fun screenFilterUsesGaussianThreeByThreeUnsharpSignal() = runBlocking {
        val width =  nine()
        val height = nine()
        val source = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            gray(if ((x + y) % 2 == 0) 220 else 30)
        }
        val result = FilterEngine().apply(FilterId.SCREEN, source, width, height)
        val center = 4 * width + 4
        val dark = 4 * width + 3
        assertTrue(luma(result[center]) > luma(source[center]), "Bright edges should be sharpened")
        assertTrue(luma(result[dark]) < luma(source[dark]), "Dark edges should be sharpened")
        assertEquals(source.size, result.size)
    }

    @Test
    fun screenFilterDoesNotChangeFlatMidGrayPixels() = runBlocking {
        val source = IntArray(25) { gray(128) }
        val result = FilterEngine().apply(FilterId.SCREEN, source, 5, 5)
        assertTrue(result.all { kotlin.math.abs(luma(it) - 128f) <= 1.1f })
    }

    private fun nine() = 9
    private fun gray(value: Int) = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
    private fun luma(pixel: Int) = 0.299f * ((pixel shr 16) and 0xff) +
        0.587f * ((pixel shr 8) and 0xff) + 0.114f * (pixel and 0xff)
}
