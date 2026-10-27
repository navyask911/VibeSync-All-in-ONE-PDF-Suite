package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibesync.toolkit.pdf.data.pdf.PerspectiveTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerspectiveTransformTest {
    @Test
    fun knownFourCornerInputProducesExpectedBoundingDimensions() {
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val result = PerspectiveTransform.crop(
            source,
            listOf(PointF(10f, 10f), PointF(90f, 10f), PointF(80f, 90f), PointF(20f, 90f))
        )
        assertEquals(80, result.width)
        assertEquals(81, result.height)
        result.recycle()
        source.recycle()
    }

    @Test
    fun rectangleIdentityCropKeepsDimensionsAndPixels() {
        val source = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val result = PerspectiveTransform.crop(source, PerspectiveTransform.rectangleCorners(source))
        assertEquals(20, result.width)
        assertEquals(10, result.height)
        assertEquals(Color.BLUE, result.getPixel(10, 5))
        result.recycle()
        source.recycle()
    }

    @Test
    fun committedCropReturnsIndependentBitmapWithSelectedDimensions() {
        val source = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val result = PerspectiveTransform.crop(
            source,
            listOf(PointF(5f, 2f), PointF(25f, 2f), PointF(25f, 18f), PointF(5f, 18f))
        )
        assertNotSame(source, result)
        assertEquals(20, result.width)
        assertEquals(16, result.height)
        assertTrue(!result.isRecycled)
        assertEquals(Color.RED, result.getPixel(10, 8))
        result.recycle()
        source.recycle()
    }
}
