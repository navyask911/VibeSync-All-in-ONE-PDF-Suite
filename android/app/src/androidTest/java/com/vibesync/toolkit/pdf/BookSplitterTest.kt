package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.vibesync.toolkit.pdf.data.pdf.BookSplitter
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterEngine
import com.vibesync.toolkit.pdf.data.pdf.filters.FilterId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSplitterTest {
    @Test
    fun detectsCenterGutterSplitsLeftThenRightAndKeepsPortraitAsOnePage() = runBlocking {
        val spread = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        spread.eraseColor(Color.rgb(236, 236, 236))
        for (y in 0 until spread.height) {
            for (x in 0 until spread.width) {
                when {
                    x < 200 -> spread.setPixel(x, y, Color.rgb(210, 45, 40))
                    x > 200 -> spread.setPixel(x, y, Color.rgb(40, 60, 215))
                    else -> spread.setPixel(x, y, Color.BLACK)
                }
            }
        }
        try {
            val gutter = BookSplitter.detectGutter(spread)
            assertTrue("Gutter " + gutter + " was more than 5 px from center.", kotlin.math.abs(gutter - 200) <= 5)
            val split = BookSplitter.split(spread, gutter)
            assertTrue(split.isLandscape)
            assertEquals(2, split.pages.size)
            assertTrue(Color.red(split.pages[0].getPixel(split.pages[0].width / 2, 100)) >
                Color.blue(split.pages[0].getPixel(split.pages[0].width / 2, 100)))
            assertTrue(Color.blue(split.pages[1].getPixel(split.pages[1].width / 2, 100)) >
                Color.red(split.pages[1].getPixel(split.pages[1].width / 2, 100)))

            // The active B&W filter can process each cropped half independently.
            val filterEngine = FilterEngine()
            split.pages.forEach { half ->
                val pixels = IntArray(half.width * half.height)
                half.getPixels(pixels, 0, half.width, 0, 0, half.width, half.height)
                val filtered = filterEngine.apply(FilterId.BW, pixels, half.width, half.height)
                assertTrue(filtered.all { Color.red(it) == Color.green(it) && Color.green(it) == Color.blue(it) })
                half.recycle()
            }
        } finally {
            spread.recycle()
        }

        val portrait = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        try {
            val result = BookSplitter.split(portrait)
            assertFalse(result.isLandscape)
            assertEquals(1, result.pages.size)
            result.pages.forEach { it.recycle() }
        } finally {
            portrait.recycle()
        }
    }
}
