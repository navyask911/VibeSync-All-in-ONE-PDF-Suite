package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.data.pdf.filters.FilterEngine
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CleanMarksFilterTest {
    @Test
    fun cleanMarksRemovesSaturatedRedBlueRangeAndKeepsDarkPrintedText() = runBlocking {
        val width = 9
        val height = 9
        val source = IntArray(width * height) { gray(185) }
        source[4 * width + 3] = 0xffd02020.toInt()
        source[4 * width + 4] = 0xff2030d0.toInt()
        source[4 * width + 5] = gray(30)
        val result = FilterEngine().apply(FilterId.CLEAN_MARKS, source, width, height)
        assertEquals(0x00ffffff, result[4 * width + 3] and 0x00ffffff)
        assertEquals(0x00ffffff, result[4 * width + 4] and 0x00ffffff)
        assertEquals(source[4 * width + 5], result[4 * width + 5], "Y<60 printed text must be preserved")
    }

    @Test
    fun cleanMarksRemovesLowVarianceGrayMarksInSpecifiedLuminanceBand() = runBlocking {
        val source = IntArray(25 * 25) { gray(180) }
        val result = FilterEngine().apply(FilterId.CLEAN_MARKS, source, 25, 25)
        assertTrue(result.all { (it and 0x00ffffff) == 0x00ffffff })
    }

    @Test
    fun lowLuminancePixelsRemainUntouchedEvenWhenNeighborhoodIsBright() = runBlocking {
        val source = IntArray(25 * 25) { gray(180) }
        source[12 * 25 + 12] = gray(20)
        val result = FilterEngine().apply(FilterId.CLEAN_MARKS, source, 25, 25)
        assertEquals(source[12 * 25 + 12], result[12 * 25 + 12])
    }

    private fun gray(value: Int) = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
}
