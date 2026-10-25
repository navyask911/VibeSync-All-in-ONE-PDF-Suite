package com.vibesync.toolkit.pdf

import com.vibesync.toolkit.pdf.presentation.tools.formatBytesForDisplay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class FormatterTest {
    @Test
    fun formatBytesForDisplay_producesExpectedMbString() {
        val bytes = 3_188_000L
        val expected = formatBytesForDisplay(bytes, Locale.US)
        assertEquals("3.04 MB", expected)
    }
}
