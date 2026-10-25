package com.vibesync.toolkit.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import com.vibesync.toolkit.pdf.data.ocr.MlKitOcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrEngineTest {
    @Test
    fun fixtureImageWithHelloWorldReturnsRecognizedTextAndBlockBounds() = runBlocking {
        val bitmap = Bitmap.createBitmap(1200, 360, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 116f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            canvas.drawText("Hello World", 72f, 215f, paint)
            val result = MlKitOcrEngine().use { it.recognize(bitmap) }
            val normalized = result.text.lowercase()
            assertTrue("OCR should recognize Hello; got: ${result.text}", normalized.contains("hello"))
            assertTrue("OCR should recognize World; got: ${result.text}", normalized.contains("world"))
            assertTrue("OCR must return at least one block", result.blocks.isNotEmpty())
            assertTrue("Text block bounds should be reported", result.blocks.any { it.boundingBox != null })
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun resultModelCanRepresentAnEmptyImageResultWithoutNetworkDependencies() {
        val result = com.vibesync.toolkit.pdf.data.ocr.OcrResult("", emptyList())
        assertTrue(result.text.isEmpty())
        assertTrue(result.blocks.isEmpty())
        assertTrue(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName.startsWith("com.vibesync")
        )
    }
}
