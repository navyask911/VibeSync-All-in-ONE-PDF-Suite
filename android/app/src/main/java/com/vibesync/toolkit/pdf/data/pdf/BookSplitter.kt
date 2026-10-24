package com.vibesync.toolkit.pdf.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/** Deterministic landscape-spread gutter detection and non-destructive bitmap splitting. */
object BookSplitter {
    data class SplitResult(val pages: List<Bitmap>, val gutterPx: Int, val isLandscape: Boolean)

    fun detectGutter(source: Bitmap): Int {
        require(source.width > 0 && source.height > 0)
        if (source.width <= source.height) return source.width / 2
        val from = (source.width * 0.40f).toInt().coerceAtLeast(1)
        val until = (source.width * 0.60f).toInt().coerceAtMost(source.width - 1)
        val top = (source.height * 0.08f).toInt()
        val bottom = (source.height * 0.92f).toInt().coerceAtLeast(top + 1)
        val sums = LongArray(until - from + 1)
        for (x in from..until) {
            var sum = 0L
            var y = top
            while (y < bottom) {
                val color = source.getPixel(x, y)
                sum += (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toLong()
                y++
            }
            sums[x - from] = sum
        }
        var bestIndex = 0
        for (index in 1 until sums.size) {
            if (sums[index] < sums[bestIndex]) bestIndex = index
        }
        return (from + bestIndex).coerceIn(1, source.width - 1)
    }

    fun split(source: Bitmap, gutterPx: Int = detectGutter(source)): SplitResult {
        if (source.width <= source.height) {
            return SplitResult(listOf(source.copy(Bitmap.Config.ARGB_8888, false)), source.width / 2, false)
        }
        val gutter = gutterPx.coerceIn(1, source.width - 1)
        val left = Bitmap.createBitmap(source, 0, 0, gutter, source.height)
        val right = Bitmap.createBitmap(source, gutter, 0, source.width - gutter, source.height)
        return SplitResult(listOf(left, right), gutter, true)
    }

    /** Distance from a detected gutter to a known synthetic/ground-truth center. */
    fun gutterErrorPx(detected: Int, expected: Int): Int = abs(detected - expected)
}
