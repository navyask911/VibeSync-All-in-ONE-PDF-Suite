package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.data.pdf.PageRangeParser
import org.junit.Assert.assertEquals
import org.junit.Test

class PageRangeParserTest {
    @Test
    fun blankAndAllSelectEveryPage() {
        val allPages = listOf(1..12)
        assertEquals(allPages, PageRangeParser.parse("", 12))
        assertEquals(allPages, PageRangeParser.parse("   ", 12))
        assertEquals(allPages, PageRangeParser.parse("All", 12))
        assertEquals(allPages, PageRangeParser.parse("all", 12))
    }

    @Test
    fun explicitRangesRemainFiltered() {
        assertEquals(listOf(1..3, 7..7), PageRangeParser.parse("1-3, 7", 12))
    }
}
